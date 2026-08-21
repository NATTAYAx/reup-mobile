package app.reup.sync

// ─── Storage.kt — the four things a place to put bytes must do ───────────────
//
// Mirror of src/lib/sync/storage.ts. The long reasoning lives there.
//
// The whole reason there is no server to run is that this interface is small
// enough for somebody else's storage to already satisfy it. Google Drive, a NAS
// over WebDAV, a folder inside Syncthing — none of them is trusted with
// anything, because the bytes arriving here have already been through
// SealedBlob, so "who stores it" is a setting rather than an architecture.
//
// Four methods. No query, no compare-and-swap, no server-side merge, no
// transactions. Every one of those would have been useful and every one would
// have narrowed the set of possible backends to roughly one.
//
// WHY THE WHOLE ADAPTER IS IN commonMain
//
// Only [HttpTransport] touches the platform. URL joining, the PROPFIND body,
// href parsing, the 409-then-MKCOL retry, and every status-code decision are
// ordinary Kotlin, so they are tested with a fake transport and no network at
// all. The parts that can be wrong in interesting ways are the parts that never
// need a phone to check.

enum class StorageErrorKind { CONFIG, AUTH, NOT_FOUND, NETWORK, SERVER }

class StorageException(
    val kind: StorageErrorKind,
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

interface SyncStorage {
    /** Every file name in the folder, including ones we did not write. */
    suspend fun list(): List<String>
    suspend fun get(name: String): ByteArray

    /** Must create the folder if it is missing. Callers never think about that. */
    suspend fun put(name: String, bytes: ByteArray)
    suspend fun delete(name: String)
}

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpRequest && method == other.method && url == other.url &&
                headers == other.headers &&
                (body?.contentEquals(other.body ?: ByteArray(0)) ?: (other.body == null))

    override fun hashCode(): Int =
        (31 * method.hashCode() + url.hashCode()) * 31 + headers.hashCode()
}

data class HttpResponse(val status: Int, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is HttpResponse && status == other.status && body.contentEquals(other.body)

    override fun hashCode(): Int = 31 * status + body.contentHashCode()
}

interface HttpTransport {
    suspend fun send(req: HttpRequest): HttpResponse
}

// ─── WebDAV ──────────────────────────────────────────────────────────────────
//
// WHY WEBDAV IS BUILT FIRST WHEN ALMOST NOBODY WILL USE IT
//
// Google Drive is the one real people will pick: the account is already on the
// phone, it is two taps, and nobody has to know what a URL is. WebDAV asks
// someone to type a hostname, which most will not do.
//
// It is written first anyway, because it can be stood up locally in one command
// with no OAuth in front of it. When Drive later fails — and it will, because
// OAuth has more ways to fail than the rest of this app put together — the
// question is always "is it my sync logic or my Google setup", and with nothing
// to compare against that question costs a day.
//
// Same mistake as the wallpaper plugin, avoided in advance: a day went into
// debugging on the assumption that a library handled a hard Windows behaviour,
// and it did not. The fix was not better debugging, it was checking the
// assumption first. A boring backend that provably works is how the interesting
// one gets checked.

data class WebDavConfig(
    /** Folder URL. A trailing slash is optional; it is normalised either way. */
    val baseUrl: String,
    val username: String,
    val password: String,
)

object WebDav {

    private const val PROPFIND_BODY =
        """<?xml version="1.0" encoding="utf-8"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""

    private val HREF = Regex(
        """<(?:[A-Za-z0-9_.-]+:)?href\s*>([^<]*)</(?:[A-Za-z0-9_.-]+:)?href\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val SAFE_NAME = Regex("""^[A-Za-z0-9_.\-]+$""")
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /**
     * Plain http is refused for public hosts and allowed for private ones.
     *
     * The data is already encrypted, so http would not expose a single task
     * name. What it would expose is the WebDAV password, on every request, to
     * anything on the path — and people reuse passwords, so that is a bigger
     * loss than the data would have been.
     *
     * On a home network the threat differs in kind rather than degree, and
     * plenty of NAS boxes only ever speak http on the LAN. Refusing those
     * outright pushes someone toward turning the check off entirely, which is
     * worse than having no check.
     */
    fun assertUsableUrl(raw: String) {
        val scheme = raw.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) throw StorageException(StorageErrorKind.CONFIG, "not a URL: $raw")
        if (scheme == "https") return
        if (scheme != "http") {
            throw StorageException(StorageErrorKind.CONFIG, "$scheme is not supported, use https")
        }
        val host = raw.substringAfter("://").substringBefore("/").substringBefore(":")
        if (isPrivateHost(host)) return
        throw StorageException(
            StorageErrorKind.CONFIG,
            "plain http would send the password in the clear over the internet; use https",
        )
    }

    fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        val m = IPV4.find(host) ?: return false
        val parts = m.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
        if (parts.any { it > 255 }) return false
        val (a, b) = parts
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** Base URL with exactly one trailing slash, so joining is never ambiguous. */
    fun normaliseBase(raw: String): String {
        assertUsableUrl(raw)
        return raw.trimEnd('/') + "/"
    }

    /**
     * Blob names are `[A-Za-z0-9_-]+-\d+.reup`, so none of them needs escaping.
     * Anything else is refused rather than escaped: a name containing `..` or a
     * slash is not a name this app produced, and building a URL from it would
     * let a crafted filename in the listing reach outside the folder.
     */
    fun childUrl(base: String, name: String): String {
        if (!SAFE_NAME.matches(name)) {
            throw StorageException(StorageErrorKind.CONFIG, """refusing to build a URL for the name "$name"""")
        }
        return normaliseBase(base) + name
    }

    /**
     * Pull the file names out of a 207 Multi-Status body.
     *
     * A regex rather than an XML parser, which needs justifying because it is
     * usually the wrong answer. Here: the only element read is href, the
     * response is machine-generated by a server implementing a spec, and adding
     * an XML dependency to two platforms to read one element is a poor trade.
     *
     * The namespace prefix is left open deliberately. Nextcloud sends
     * `<d:href>`, Apache mod_dav sends `<D:href>`, some send `<href>` under a
     * default xmlns, and a parser hardcoding any one of them works against
     * exactly one server.
     *
     * Names that are not valid blob names are dropped later by
     * [Protocol.filesToFetch], so collections and strangers' files need no
     * special handling here. That is what lets this stay simple.
     */
    fun parseMultiStatus(xml: String): List<String> = HREF.findAll(xml).mapNotNull { m ->
        val href = m.groupValues[1].trim()
        if (href.isEmpty() || href.endsWith("/")) return@mapNotNull null
        val last = href.split("/").lastOrNull { it.isNotEmpty() } ?: return@mapNotNull null
        percentDecode(last)
    }.toList()

    /** Folder names can be Thai, so the href comes back percent-encoded. */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    out.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            // A lone % is left as-is. A badly encoded name is still a name, and
            // throwing here would make one odd file in the folder break listing
            // for everything else in it.
            for (b in c.toString().encodeToByteArray()) out.add(b)
            i++
        }
        return out.toByteArray().decodeToString()
    }

    fun propfindBody(): ByteArray = PROPFIND_BODY.encodeToByteArray()
}

class WebDavStorage(
    private val http: HttpTransport,
    private val cfg: WebDavConfig,
) : SyncStorage {

    private val base = WebDav.normaliseBase(cfg.baseUrl)

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            // Basic is the only scheme every WebDAV server agrees on. Not a
            // weakness here: the URL is already required to be https or
            // private, and the header is assembled outside any browser context.
            "Authorization" to "Basic " + Base64Std.encode("${cfg.username}:${cfg.password}".encodeToByteArray()),
        ) + extra

    private fun check(res: HttpResponse, what: String): HttpResponse {
        when {
            res.status == 401 || res.status == 403 ->
                throw StorageException(StorageErrorKind.AUTH, "$what: the server rejected the username or password", res.status)
            res.status == 404 ->
                throw StorageException(StorageErrorKind.NOT_FOUND, "$what: not found", 404)
            res.status >= 500 ->
                throw StorageException(StorageErrorKind.SERVER, "$what: the server failed (${res.status})", res.status)
            res.status >= 400 ->
                throw StorageException(StorageErrorKind.SERVER, "$what: rejected (${res.status})", res.status)
        }
        return res
    }

    override suspend fun list(): List<String> {
        val res = http.send(
            HttpRequest(
                method = "PROPFIND",
                url = base,
                // Depth 1 is the folder and its direct children. Depth infinity
                // is what gets an account rate-limited, and there are no
                // subfolders anyway.
                headers = headers(mapOf("Depth" to "1", "Content-Type" to """application/xml; charset="utf-8"""")),
                body = WebDav.propfindBody(),
            ),
        )
        // A folder that does not exist yet is not an error. Nothing has been
        // written, so there is nothing to read, and saying so is the honest
        // answer rather than making the caller handle a special case.
        if (res.status == 404) return emptyList()
        check(res, "listing the folder")
        return WebDav.parseMultiStatus(res.body.decodeToString())
    }

    override suspend fun get(name: String): ByteArray {
        val res = http.send(HttpRequest("GET", WebDav.childUrl(base, name), headers()))
        check(res, "reading $name")
        return res.body
    }

    override suspend fun put(name: String, bytes: ByteArray) {
        suspend fun send() = http.send(
            HttpRequest(
                "PUT",
                WebDav.childUrl(base, name),
                headers(mapOf("Content-Type" to "application/octet-stream")),
                bytes,
            ),
        )

        var res = send()

        // 409 from a PUT means the parent collection is missing. Creating it
        // here rather than at setup time means there is no separate "connect"
        // step that can be half-finished, and no state where the app believes
        // it is configured but the folder was never made.
        if (res.status == 409 || res.status == 404) {
            makeDir()
            res = send()
        }
        check(res, "writing $name")
    }

    override suspend fun delete(name: String) {
        val res = http.send(HttpRequest("DELETE", WebDav.childUrl(base, name), headers()))
        // Already gone is the outcome that was wanted. Compaction deleting the
        // same old batch twice must not be an error, or two devices tidying up
        // at once becomes a failure the person sees.
        if (res.status == 404) return
        check(res, "deleting $name")
    }

    private suspend fun makeDir() {
        val res = http.send(HttpRequest("MKCOL", base, headers()))
        // 405 means it already exists, which is success dressed as failure.
        if (res.status == 405 || res.status in 200..299) return
        check(res, "creating the folder")
    }
}

/** Standard base64 with padding, for the Authorization header. Not base64url. */
object Base64Std {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                    .append(ALPHABET[(n shr 6) and 63]).append('=')
            }
        }
        return sb.toString()
    }
}