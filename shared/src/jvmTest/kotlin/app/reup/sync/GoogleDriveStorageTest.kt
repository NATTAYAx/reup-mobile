package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── GoogleDriveStorageTest ──────────────────────────────────────────────────
//
// Same arrangement as the WebDAV suite: a fake transport, real response bodies,
// no network. Everything that can be wrong about a Drive client is decided
// before a socket opens — which URL, which header, how a 401 is handled, what a
// 403 actually means — and all of it is ordinary logic.
//
// Two of these tests exist because of failures that are invisible in normal
// use and only appear months later:
//
//   pagination     — ignoring nextPageToken works perfectly with four files and
//                    then silently stops seeing older batches
//   403 handling   — Drive uses 403 for both "not allowed" and "slow down", and
//                    conflating them tells someone to reconnect their account
//                    when the real answer was to wait a minute

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
    check(finished) { "the block actually suspended; the fake transport never does" }
    thrown?.let { throw it }
}

private class Fake(val reply: (HttpRequest, Int) -> HttpResponse) : HttpTransport {
    val sent = mutableListOf<HttpRequest>()
    override suspend fun send(req: HttpRequest): HttpResponse {
        sent += req
        return reply(req, sent.size)
    }
}

private fun res(status: Int, body: String = "") = HttpResponse(status, body.encodeToByteArray())
private fun bin(status: Int, body: ByteArray) = HttpResponse(status, body)

private class FakeTokens(val value: String = "AT1") : AccessTokenSource {
    var refreshes = 0
    override suspend fun token() = value
    override suspend fun refresh(): String {
        refreshes++
        return "$value-new"
    }
}

private class FakeStore(var tokens: OAuthTokens?) : TokenStore {
    override suspend fun load() = tokens
    override suspend fun save(tokens: OAuthTokens) {
        this.tokens = tokens
    }
    override suspend fun clear() {
        tokens = null
    }
}

private const val PAGE1 =
    """{"nextPageToken":"P2","files":[{"id":"i1","name":"phone-1.reup"},{"id":"i2","name":"laptop-3.reup"}]}"""
private const val PAGE2 =
    """{"files":[{"id":"i3","name":"phone-2.reup"},{"id":"i4","name":"phone-1.reup"}]}"""

class GoogleDriveStorageTest {

    @Test
    fun `urls scope to appDataFolder and ask only for the fields used`() {
        val u = DriveApi.listUrl()
        assertTrue(u.contains("spaces=appDataFolder"), u)
        assertTrue(u.contains("pageSize=1000"), u)
        assertTrue(DriveApi.listUrl("PT2").contains("pageToken=PT2"))
        assertTrue(DriveApi.mediaUrl("i3").contains("alt=media"))
    }

    @Test
    fun `the name query contains no plus-encoded spaces`() {
        val u = DriveApi.findByNameUrl("phone-4.reup")
        // A form encoder would write the spaces in `name = 'x'` as `+`, and if
        // Drive reads those literally the clause matches nothing — silently,
        // because a file that matches nothing is just reported missing.
        assertTrue(!u.contains("+"), "a plus appeared in the query: $u")
        assertTrue(u.contains(DriveApi.encode("name='phone-4.reup'")), u)
    }

    @Test
    fun `percent-encoding matches what the desktop produces`() {
        assertEquals("nextPageToken%2Cfiles%28id%2Cname%29", DriveApi.encode("nextPageToken,files(id,name)"))
        assertEquals("a-b_c.d~e", DriveApi.encode("a-b_c.d~e"), "unreserved characters must pass through")
        assertEquals("%20", DriveApi.encode(" "))
        assertEquals("%E0%B8%87", DriveApi.encode("ง"), "multi-byte characters encode per byte")
    }

    @Test
    fun `the multipart body names the file and keeps bytes verbatim`() {
        val bytes = byteArrayOf(0, -1, 10, 13)
        val body = DriveApi.multipartBody("phone-1.reup", bytes, "BOUND")
        val text = body.decodeToString()
        assertTrue(text.contains(""""name":"phone-1.reup""""), text)
        assertTrue(text.contains(""""parents":["appDataFolder"]"""), text)
        assertTrue(text.startsWith("--BOUND\r\n"))
        assertTrue(text.endsWith("\r\n--BOUND--\r\n"))

        // The payload has to survive byte for byte. A sealed blob is arbitrary
        // binary, so anything that round-trips it through text corrupts it.
        val headLen = body.size - bytes.size - "\r\n--BOUND--\r\n".length
        assertTrue(body.copyOfRange(headLen, headLen + bytes.size).contentEquals(bytes))
    }

    @Test
    fun `list follows pagination and keeps a duplicate name once`() = drive {
        val f = Fake { _, n -> res(200, if (n == 1) PAGE1 else PAGE2) }
        val names = GoogleDriveStorage(f, FakeTokens()) { "B" }.list()

        assertEquals(2, f.sent.size, "nextPageToken was ignored; older batches would go missing")
        assertTrue(f.sent[1].url.contains("pageToken=P2"))
        assertEquals(listOf("phone-1.reup", "laptop-3.reup", "phone-2.reup"), names)
        assertEquals("Bearer AT1", f.sent[0].headers["Authorization"])
    }

    @Test
    fun `an empty appDataFolder lists as empty`() = drive {
        assertEquals(emptyList<String>(), GoogleDriveStorage(Fake { _, _ -> res(200, "{}") }, FakeTokens()) { "B" }.list())
    }

    @Test
    fun `get uses the id from list and asks for the bytes`() = drive {
        val blob = byteArrayOf(1, 2, 3, -6)
        val f = Fake { _, n -> if (n == 1) res(200, PAGE2) else bin(200, blob) }
        val s = GoogleDriveStorage(f, FakeTokens()) { "B" }
        s.list()
        assertTrue(s.get("phone-2.reup").contentEquals(blob))
        assertTrue(f.sent[1].url.contains("/files/i3"), f.sent[1].url)
        assertTrue(f.sent[1].url.contains("alt=media"))
    }

    @Test
    fun `a name never seen before is looked up by query first`() = drive {
        val f = Fake { _, n ->
            if (n == 1) res(200, """{"files":[{"id":"i9","name":"phone-9.reup"}]}""")
            else bin(200, byteArrayOf(7))
        }
        GoogleDriveStorage(f, FakeTokens()) { "B" }.get("phone-9.reup")
        assertTrue(f.sent[0].url.contains(DriveApi.encode("name='phone-9.reup'")))
        assertTrue(f.sent[1].url.contains("/files/i9"))
    }

    @Test
    fun `put posts multipart to the upload host`() = drive {
        val f = Fake { _, _ -> res(200, """{"id":"iNew","name":"phone-5.reup"}""") }
        GoogleDriveStorage(f, FakeTokens()) { "BOUND" }.put("phone-5.reup", byteArrayOf(9))
        assertEquals("POST", f.sent[0].method)
        assertTrue(f.sent[0].url.startsWith("https://www.googleapis.com/upload/drive/v3/files"))
        assertTrue(f.sent[0].url.contains("uploadType=multipart"))
        assertEquals("multipart/related; boundary=BOUND", f.sent[0].headers["Content-Type"])
    }

    @Test
    fun `a 401 refreshes once and retries, and does not loop`() = drive {
        val t1 = FakeTokens()
        val f1 = Fake { _, n -> if (n == 1) res(401, "{}") else res(200, "{}") }
        GoogleDriveStorage(f1, t1) { "B" }.list()
        assertEquals(1, t1.refreshes)
        assertEquals(2, f1.sent.size)
        assertEquals("Bearer AT1-new", f1.sent[1].headers["Authorization"])

        val t2 = FakeTokens()
        val f2 = Fake { _, _ -> res(401, "{}") }
        val kind = try {
            GoogleDriveStorage(f2, t2) { "B" }.list()
            fail("a permanent 401 was accepted")
        } catch (e: StorageException) {
            e.kind
        }
        assertEquals(StorageErrorKind.AUTH, kind)
        assertEquals(1, t2.refreshes, "it refreshed more than once; a revoked grant would loop")
        assertEquals(2, f2.sent.size)
    }

    @Test
    fun `rate limiting is not mistaken for a permission problem`() = drive {
        suspend fun kindFor(json: String): StorageErrorKind = try {
            GoogleDriveStorage(Fake { _, _ -> res(403, json) }, FakeTokens()) { "B" }.list()
            fail("403 was accepted")
        } catch (e: StorageException) {
            e.kind
        }

        assertEquals(
            StorageErrorKind.NETWORK,
            kindFor("""{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""),
            "telling someone to reconnect their account when they only had to wait is a bad answer",
        )
        assertEquals(
            StorageErrorKind.AUTH,
            kindFor("""{"error":{"errors":[{"reason":"insufficientPermissions"}]}}"""),
        )
    }

    @Test
    fun `deleting something already gone is a success`() = drive {
        val f = Fake { _, _ -> res(200, """{"files":[]}""") }
        GoogleDriveStorage(f, FakeTokens()) { "B" }.delete("gone.reup")
        assertEquals(1, f.sent.size, "it tried to delete a file it could not find")
    }

    @Test
    fun `token expiry is a deadline and is refreshed a minute early`() {
        assertTrue(DriveApi.refreshDue(1000, 950))
        assertTrue(!DriveApi.refreshDue(1000, 900))

        val t = DriveApi.tokensFrom("""{"access_token":"A","refresh_token":"R","expires_in":3599}""", 1000, null)
        assertEquals(4599, t.expiresAtSec)
        assertEquals("R", t.refreshToken)

        // A refresh response omits refresh_token. Dropping the stored one here
        // is how an app silently loses the ability to refresh and starts asking
        // for a login every hour.
        val t2 = DriveApi.tokensFrom("""{"access_token":"A2","expires_in":3600}""", 2000, "R")
        assertEquals("R", t2.refreshToken)
    }

    @Test
    fun `a still-valid token does not touch the network`() = drive {
        val f = Fake { _, _ -> res(200, "{}") }
        val src = GoogleTokenSource(f, "cid", FakeStore(OAuthTokens("AT0", "R", 9999))) { 5000 }
        assertEquals("AT0", src.token())
        assertEquals(0, f.sent.size)
    }

    @Test
    fun `a busy server does not sign the person out`() = drive {
        // The other half of the same decision, and the one that was missing.
        // Clearing the grant on ANY failed refresh turns one 503 into a
        // sign-in prompt, which nobody notices while sync is a button and
        // everybody notices once it runs by itself.
        for (status in listOf(500, 502, 503, 429)) {
            val store = FakeStore(OAuthTokens("old", "R", 0))
            val f = Fake { _, _ -> res(status, "upstream is unhappy") }
            try {
                GoogleTokenSource(f, "cid", store) { 5000 }.token()
                fail("a failed refresh was accepted at $status")
            } catch (e: StorageException) {
                assertTrue(e.kind != StorageErrorKind.AUTH, "$status read as a dead grant")
            }
            assertEquals(
                "R", store.tokens?.refreshToken,
                "the grant was thrown away because a server was busy ($status)",
            )
        }
    }

    @Test
    fun `a 400 that is not invalid_grant is not a dead grant either`() = drive {
        // Google returns 400 for malformed requests as well as for revoked
        // grants. Reading every 400 as revocation means a bug in how the body
        // is built costs the person their sign-in instead of showing up as a
        // bug.
        val store = FakeStore(OAuthTokens("old", "R", 0))
        val f = Fake { _, _ -> res(400, """{"error":"invalid_request"}""") }
        try {
            GoogleTokenSource(f, "cid", store) { 5000 }.token()
            fail("a malformed refresh was accepted")
        } catch (e: StorageException) {
            assertTrue(e.kind != StorageErrorKind.AUTH)
        }
        assertEquals("R", store.tokens?.refreshToken)
    }

    @Test
    fun `a revoked refresh token clears the store instead of retrying forever`() = drive {
        val store = FakeStore(OAuthTokens("old", "R", 0))
        val f = Fake { _, _ -> res(400, """{"error":"invalid_grant"}""") }
        val kind = try {
            GoogleTokenSource(f, "cid", store) { 5000 }.token()
            fail("a revoked grant was accepted")
        } catch (e: StorageException) {
            e.kind
        }
        assertEquals(StorageErrorKind.AUTH, kind)
        assertEquals(null, store.tokens, "the dead grant was kept and will fail every hour forever")
        assertEquals(DriveApi.tokenUrl(), f.sent[0].url)
        assertTrue(f.sent[0].body!!.decodeToString().contains("grant_type=refresh_token"))
    }

    @Test
    fun `a successful refresh is persisted and keeps the refresh token`() = drive {
        val store = FakeStore(OAuthTokens("old", "R", 0))
        val f = Fake { _, _ -> res(200, """{"access_token":"AT9","expires_in":3600}""") }
        assertEquals("AT9", GoogleTokenSource(f, "cid", store) { 5000 }.token())
        assertEquals("AT9", store.tokens!!.accessToken)
        assertEquals("R", store.tokens!!.refreshToken)
        assertEquals(8600, store.tokens!!.expiresAtSec)
    }

    @Test
    fun `an HTML error page reads as a server problem, not a crash`() {
        val kind = try {
            DriveApi.parseFileList("<html>502 Bad Gateway</html>".encodeToByteArray())
            fail("HTML was parsed as JSON")
        } catch (e: StorageException) {
            e.kind
        }
        assertEquals(StorageErrorKind.SERVER, kind)
    }
}