package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── WebDavStorageTest ───────────────────────────────────────────────────────
//
// No network, no server, no phone. Everything interesting about a WebDAV client
// is decided before a socket is opened: which verb, which headers, how the
// reply is read, what a 409 means. All of that is ordinary logic and belongs in
// a test that runs in a second.
//
// The response bodies below are shaped like what real servers send, and the
// three differ in exactly the way that breaks naive parsers: Nextcloud uses the
// `d:` prefix, Apache mod_dav uses `D:`, and some use a default xmlns with no
// prefix at all. A parser that hardcodes one of them works against one server
// and looks correct until somebody else tries it.
//
// WHY THERE IS A HAND-ROLLED COROUTINE DRIVER BELOW
//
// The obvious way to test suspend functions is runTest from
// kotlinx-coroutines-test, which means a new dependency and edits to
// libs.versions.toml and build.gradle.kts. That is build configuration, and
// build configuration is the one kind of change a test cannot catch: a broken
// Gradle file does not fail a test, it stops every test from running.
//
// Ten lines of stdlib do the job instead, because the fake transport never
// actually suspends. The check() makes that limitation loud rather than
// leaving it as an assumption — the day a test needs real concurrency it fails
// with a sentence explaining why, instead of hanging.

private const val NEXTCLOUD = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/nat/reup/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/nat/reup/phone-4.reup</d:href>
  <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/nat/reup/laptop-12.reup</d:href>
  <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

private const val APACHE = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">
<D:href>/dav/%E0%B8%87%E0%B8%B2%E0%B8%99/</D:href></D:response>
<D:response><D:href>/dav/%E0%B8%87%E0%B8%B2%E0%B8%99/phone-9.reup</D:href></D:response>
<D:response><D:href>/dav/%E0%B8%87%E0%B8%B2%E0%B8%99/notes.txt</D:href></D:response></D:multistatus>"""

private const val NO_PREFIX = """<multistatus xmlns="DAV:"><response><href>/dav/x/</href></response>
<response><href>/dav/x/phone-1.reup</href></response></multistatus>"""

/** Runs a suspend block that never really suspends. See the note above. */
private fun drive(block: suspend () -> Unit) {
    var thrown: Throwable? = null
    var finished = false
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                thrown = result.exceptionOrNull()
                finished = true
            }
        },
    )
    check(finished) {
        "the block actually suspended; this driver only runs code backed by the fake transport"
    }
    thrown?.let { throw it }
}

private class FakeTransport(val reply: (HttpRequest, Int) -> HttpResponse) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(req: HttpRequest): HttpResponse {
        sent += req
        return reply(req, sent.count { it.method == req.method })
    }
}

private fun res(status: Int, body: String = "") = HttpResponse(status, body.encodeToByteArray())

private fun storage(t: HttpTransport) =
    WebDavStorage(t, WebDavConfig("https://a.com/dav", "nat", "pw"))

class WebDavStorageTest {

    @Test
    fun `parses every namespace prefix servers actually send`() {
        assertEquals(listOf("phone-4.reup", "laptop-12.reup"), WebDav.parseMultiStatus(NEXTCLOUD))
        assertEquals(listOf("phone-9.reup", "notes.txt"), WebDav.parseMultiStatus(APACHE))
        assertEquals(listOf("phone-1.reup"), WebDav.parseMultiStatus(NO_PREFIX))
        assertEquals(emptyList<String>(), WebDav.parseMultiStatus(""))
    }

    @Test
    fun `a percent-encoded Thai folder decodes to itself`() {
        assertEquals("งาน", WebDav.percentDecode("%E0%B8%87%E0%B8%B2%E0%B8%99"))
        assertEquals("plain", WebDav.percentDecode("plain"))
        // A lone % must not throw. One odd file in the folder should not stop
        // the rest of the folder from being listed.
        assertEquals("100%", WebDav.percentDecode("100%"))
    }

    @Test
    fun `strangers files are dropped by the protocol, not by the parser`() {
        val names = WebDav.parseMultiStatus(APACHE)
        assertTrue("notes.txt" in names, "the parser should report everything it sees")
        assertEquals(
            listOf("phone-9.reup"),
            Protocol.filesToFetch(names, emptyMap()).map { it.name },
            "only well-formed blob names should survive filtering",
        )
    }

    @Test
    fun `urls join exactly once and refuse names that could escape the folder`() {
        assertEquals("https://a.com/dav/", WebDav.normaliseBase("https://a.com/dav///"))
        assertEquals("https://a.com/", WebDav.normaliseBase("https://a.com"))
        assertEquals("https://a.com/dav/phone-4.reup", WebDav.childUrl("https://a.com/dav", "phone-4.reup"))

        for (bad in listOf("../../etc/passwd", "a/b", "a b.reup", "phone-4.reup?x=1", "")) {
            assertFailsWith<StorageException>("accepted the name '$bad'") {
                WebDav.childUrl("https://a.com/dav", bad)
            }
        }
    }

    @Test
    fun `plain http is allowed on a home network and refused on the internet`() {
        for (h in listOf("localhost", "127.0.0.1", "192.168.1.50", "10.0.0.2", "172.16.5.1", "nas.local")) {
            assertTrue(WebDav.isPrivateHost(h), "$h should count as private")
        }
        for (h in listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "example.com")) {
            assertTrue(!WebDav.isPrivateHost(h), "$h should not count as private")
        }
        WebDav.assertUsableUrl("http://192.168.1.50:5005/dav")
        WebDav.assertUsableUrl("https://example.com/dav")
        assertFailsWith<StorageException> { WebDav.assertUsableUrl("http://example.com/dav") }
        assertFailsWith<StorageException> { WebDav.assertUsableUrl("ftp://example.com/dav") }
    }

    @Test
    fun `list sends PROPFIND with Depth 1 and basic auth`() = drive {
        val t = FakeTransport { _, _ -> res(207, NEXTCLOUD) }
        assertEquals(listOf("phone-4.reup", "laptop-12.reup"), storage(t).list())

        val req = t.sent.single()
        assertEquals("PROPFIND", req.method)
        assertEquals("1", req.headers["Depth"], "Depth infinity is what gets an account rate-limited")
        assertEquals(
            "Basic " + Base64Std.encode("nat:pw".encodeToByteArray()),
            req.headers["Authorization"],
        )
        assertTrue(req.body!!.decodeToString().contains("propfind"))
    }

    @Test
    fun `a folder that does not exist yet lists as empty`() = drive {
        assertEquals(emptyList<String>(), storage(FakeTransport { _, _ -> res(404) }).list())
    }

    @Test
    fun `a 409 on PUT creates the folder and retries exactly once`() = drive {
        val t = FakeTransport { r, n ->
            if (r.method == "PUT") (if (n == 1) res(409) else res(201)) else res(201)
        }
        storage(t).put("phone-1.reup", "x".encodeToByteArray())
        assertEquals(listOf("PUT", "MKCOL", "PUT"), t.sent.map { it.method })
    }

    @Test
    fun `MKCOL returning 405 means the folder already exists`() = drive {
        val t = FakeTransport { r, n ->
            if (r.method == "PUT") (if (n == 1) res(409) else res(201)) else res(405)
        }
        storage(t).put("phone-1.reup", "x".encodeToByteArray())
        assertEquals(3, t.sent.size, "a 405 from MKCOL should not abort the retry")
    }

    @Test
    fun `deleting something already gone is a success`() = drive {
        storage(FakeTransport { _, _ -> res(404) }).delete("gone.reup")
    }

    @Test
    fun `status codes map to reasons a person could act on`() = drive {
        suspend fun kindOf(status: Int): StorageErrorKind =
            try {
                storage(FakeTransport { _, _ -> res(status) }).get("phone-1.reup")
                fail("status $status was accepted")
            } catch (e: StorageException) {
                e.kind
            }

        assertEquals(StorageErrorKind.AUTH, kindOf(401))
        assertEquals(StorageErrorKind.AUTH, kindOf(403))
        assertEquals(StorageErrorKind.NOT_FOUND, kindOf(404))
        assertEquals(StorageErrorKind.SERVER, kindOf(500))
        assertEquals(StorageErrorKind.SERVER, kindOf(418))
    }

    @Test
    fun `bytes pass through untouched`() = drive {
        // A sealed blob is arbitrary binary, so anything treating the body as
        // text corrupts it. Every byte value appears here, including nulls.
        val big = ByteArray(5000) { (it and 0xff).toByte() }
        val t = FakeTransport { _, _ -> res(201) }
        storage(t).put("phone-2.reup", big)
        assertTrue(t.sent.single().body!!.contentEquals(big))
    }

    @Test
    fun `base64 for the auth header uses the standard alphabet, not base64url`() {
        assertEquals("", Base64Std.encode(ByteArray(0)))
        assertEquals("YQ==", Base64Std.encode("a".encodeToByteArray()))
        assertEquals("YWI=", Base64Std.encode("ab".encodeToByteArray()))
        assertEquals("YWJj", Base64Std.encode("abc".encodeToByteArray()))
        assertEquals("bmF0OnB3", Base64Std.encode("nat:pw".encodeToByteArray()))
        // + and / must appear here. If this table were copied from the base64url
        // one next door, a fraction of passwords would encode wrongly and the
        // symptom would be an intermittent 401 that looks like a server problem.
        assertEquals("//79", Base64Std.encode(byteArrayOf(-1, -2, -3)))
    }
}