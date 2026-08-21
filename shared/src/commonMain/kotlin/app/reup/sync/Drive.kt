package app.reup.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ─── Drive.kt — Google Drive, the backend most people will actually use ──────
//
// Mirror of src/lib/sync/drive.ts. Same interface as WebDAV, same transport,
// same tests-with-a-fake. The only difference is that Drive is a REST API
// instead of a filesystem, and that difference is entirely inside this file.
//
// WHY appDataFolder AND NOT AN ORDINARY FOLDER
//
// appDataFolder is a hidden per-application area inside the user's own Drive.
// Only the app that wrote there can read it, and it never appears in the normal
// Drive interface, so nobody deletes a folder full of unreadable files six
// months from now while tidying up.
//
// The scope matters more than the hiding. `drive.appdata` is classified
// non-sensitive by Google, which means basic app verification rather than the
// sensitive-scope review with its demo video and multi-week wait. The
// alternative — asking for someone's entire Drive in order to store our own
// files in it — is a restricted scope, needs a third-party security
// assessment, and deserves to.
//
// And the storage is the user's. Their quota, their account, their relationship
// with Google. No server here to run, pay for, or be responsible for.
//
// WHAT MAKES DRIVE AWKWARD, AND HOW THAT IS CONTAINED
//
// Files are addressed by id, not by name, and names are not unique. SyncStorage
// is name-based because a filesystem is, so this keeps a name-to-id map filled
// in by list() and falls back to a targeted query for anything it has not seen.
//
// Caching an id is safe here specifically because the log is append-only and
// blobs are immutable. A name is written once and never rewritten, so a cached
// id cannot point at stale contents. The worst case is that it points at a file
// since deleted, which arrives as a 404 and is handled.
//
// PAGINATION IS NOT OPTIONAL
//
// Drive returns one page at a time. Ignoring nextPageToken works perfectly in
// testing, with four files, and then quietly stops seeing older batches once a
// real log builds up — which reads as "sync forgot last month" and carries no
// error at all.

/** The one scope this app asks for. Non-sensitive; see the header. */
const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

private const val API = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

@Serializable
data class DriveFile(val id: String, val name: String)

@Serializable
data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

data class OAuthTokens(
    val accessToken: String,
    /** Absent on a refresh response, which reuses the one already stored. */
    val refreshToken: String?,
    /** Seconds since the epoch. Absolute, not a duration — see [refreshDue]. */
    val expiresAtSec: Long,
)

/**
 * Where tokens live between runs.
 *
 * An interface because the honest answer differs per platform: the Windows
 * Credential Manager on the desktop, EncryptedSharedPreferences backed by the
 * Android Keystore on the phone. Neither belongs in a file that also knows
 * about multipart bodies.
 */
interface TokenStore {
    suspend fun load(): OAuthTokens?
    suspend fun save(tokens: OAuthTokens)
    suspend fun clear()
}

interface AccessTokenSource {
    /** A token good for at least the next minute, refreshing if needed. */
    suspend fun token(): String

    /** Force a refresh. Called once after a 401 before giving up. */
    suspend fun refresh(): String
}

object DriveApi {

    val json = Json { ignoreUnknownKeys = true }

    fun scope(): String = DRIVE_SCOPE

    /**
     * Refresh a minute early rather than on expiry.
     *
     * A token still valid when the request is built can be expired by the time
     * it arrives, and the resulting 401 is indistinguishable from a revoked
     * grant. The margin turns a race into arithmetic.
     */
    fun refreshDue(expiresAtSec: Long, nowSec: Long): Boolean = expiresAtSec - nowSec < 60

    fun listUrl(pageToken: String? = null): String {
        // Without an explicit fields list Drive returns a large default
        // projection for every file. Two fields are all that is used.
        var u = "$API?spaces=appDataFolder" +
                "&fields=" + encode("nextPageToken,files(id,name)") +
                "&pageSize=1000"
        if (pageToken != null) u += "&pageToken=" + encode(pageToken)
        return u
    }

    /**
     * Built by hand rather than with a form encoder.
     *
     * A form encoder writes a space as `+`, and whether `+` means a space or a
     * literal plus in a query string depends on who is parsing it. If Drive
     * reads it literally the clause becomes `name+=+'x'`, which matches
     * nothing — and that failure is silent, because a file that matches nothing
     * is simply reported as missing and the sync skips a batch.
     *
     * No spaces at all, and percent-encoding for the rest, so there is nothing
     * left to disagree about. The desktop found this the same way.
     */
    fun findByNameUrl(name: String): String {
        val clause = encode("name='" + name.replace("'", "\\'") + "'")
        return "$API?spaces=appDataFolder&q=$clause&fields=" + encode("files(id,name)") + "&pageSize=10"
    }

    fun mediaUrl(id: String): String = "$API/${encode(id)}?alt=media"

    fun deleteUrl(id: String): String = "$API/${encode(id)}"

    fun uploadUrl(): String = "$UPLOAD?uploadType=multipart&fields=" + encode("id,name")

    fun tokenUrl(): String = TOKEN_URL

    fun refreshBody(clientId: String, refreshToken: String): ByteArray =
        ("client_id=" + encode(clientId) +
                "&refresh_token=" + encode(refreshToken) +
                "&grant_type=refresh_token").encodeToByteArray()

    /** Google returns a duration; everything downstream wants a deadline. */
    fun tokensFrom(bodyJson: String, nowSec: Long, previousRefresh: String?): OAuthTokens {
        val r = try {
            json.decodeFromString(TokenResponse.serializer(), bodyJson)
        } catch (e: Exception) {
            throw StorageException(StorageErrorKind.AUTH, "the token response was not readable", null, e)
        }
        val access = r.accessToken
            ?: throw StorageException(StorageErrorKind.AUTH, "the token response carried no access token")
        return OAuthTokens(
            accessToken = access,
            // A refresh response omits refresh_token. Overwriting the stored
            // one with null here is how an app silently loses the ability to
            // refresh and starts demanding a re-login every hour.
            refreshToken = r.refreshToken ?: previousRefresh,
            expiresAtSec = nowSec + (r.expiresIn ?: 3600L),
        )
    }

    fun parseFileList(body: ByteArray): DriveFileList = try {
        json.decodeFromString(DriveFileList.serializer(), body.decodeToString())
    } catch (e: Exception) {
        throw StorageException(StorageErrorKind.SERVER, "Drive returned something that was not JSON", null, e)
    }

    /**
     * A multipart/related body: JSON metadata, then the bytes.
     *
     * Drive's simple upload takes content but cannot set a name, and the
     * resumable flow is two extra round trips for files measured in kilobytes.
     * Multipart does both in one request.
     *
     * The boundary is passed in rather than generated so tests are
     * deterministic. It has to be a string that does not appear in the payload;
     * a random one from a 128-bit source will not, and a fixed one in
     * production eventually would.
     */
    fun multipartBody(name: String, bytes: ByteArray, boundary: String): ByteArray {
        val meta = """{"name":"$name","parents":["appDataFolder"]}"""
        val head = (
                "--$boundary\r\n" +
                        "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                        meta + "\r\n" +
                        "--$boundary\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                ).encodeToByteArray()
        val tail = "\r\n--$boundary--\r\n".encodeToByteArray()
        val out = ByteArray(head.size + bytes.size + tail.size)
        head.copyInto(out, 0)
        bytes.copyInto(out, head.size)
        tail.copyInto(out, head.size + bytes.size)
        return out
    }

    /** Percent-encoding, written out because java.net.URLEncoder is JVM-only. */
    fun encode(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (b in s.encodeToByteArray()) {
            val c = b.toInt().toChar()
            if (c in unreserved) {
                sb.append(c)
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                sb.append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }
}

class GoogleTokenSource(
    private val http: HttpTransport,
    private val clientId: String,
    private val store: TokenStore,
    private val nowSec: () -> Long,
) : AccessTokenSource {

    private var cached: OAuthTokens? = null

    private suspend fun current(): OAuthTokens? {
        if (cached == null) cached = store.load()
        return cached
    }

    override suspend fun token(): String {
        val t = current() ?: throw StorageException(StorageErrorKind.AUTH, "Google Drive is not connected yet")
        if (!DriveApi.refreshDue(t.expiresAtSec, nowSec())) return t.accessToken
        return refresh()
    }

    override suspend fun refresh(): String {
        val t = current()
        val rt = t?.refreshToken
            ?: throw StorageException(StorageErrorKind.AUTH, "Google Drive needs to be connected again")

        val res = http.send(
            HttpRequest(
                "POST",
                DriveApi.tokenUrl(),
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                DriveApi.refreshBody(clientId, rt),
            ),
        )
        if (res.status != 200) {
            // ── WHY THIS NO LONGER THROWS THE GRANT AWAY ON ANY FAILURE ───────
            //
            // A refresh token IS revoked when the app is removed, the password
            // changes, or it goes unused for six months — none worth retrying,
            // so the first version cleared the grant on any non-200.
            //
            // That was survivable while a sync only happened when somebody
            // pressed a button. It stopped being survivable the moment sync
            // became automatic: one timeout, one 500 from Google, one moment in
            // a tunnel, and the app signs itself out. The frequency did not
            // create the bug, it stopped hiding it.
            //
            // Google says which of the two it is. `invalid_grant` means asking
            // again is the only way forward; a 429 or a 5xx means try later, and
            // clearing a working grant because a server was busy costs a person
            // a sign-in for nothing. Same distinction the request path already
            // draws between "you may not" and "you are going too fast".
            val text = res.body.decodeToString()
            val revoked = (res.status == 400 || res.status == 401) &&
                    Regex("invalid_grant|invalid_client|unauthorized_client").containsMatchIn(text)

            if (revoked) {
                store.clear()
                cached = null
                throw StorageException(
                    StorageErrorKind.AUTH,
                    "Google Drive needs to be connected again",
                    res.status,
                )
            }

            // Kept. The next attempt reuses the same refresh token, which is
            // what a temporary failure deserves.
            throw StorageException(
                if (res.status == 429 || res.status >= 500) StorageErrorKind.NETWORK
                else StorageErrorKind.SERVER,
                "Google could not refresh the token right now",
                res.status,
            )
        }
        val next = DriveApi.tokensFrom(res.body.decodeToString(), nowSec(), rt)
        store.save(next)
        cached = next
        return next.accessToken
    }
}

class GoogleDriveStorage(
    private val http: HttpTransport,
    private val tokens: AccessTokenSource,
    private val newBoundary: () -> String,
) : SyncStorage {

    /** Filled by [list]. Safe to cache only because blobs are immutable. */
    private val ids = LinkedHashMap<String, String>()

    /**
     * Every Drive call goes through here, so the 401 dance exists once.
     *
     * Exactly one retry. A loop would turn a permanently revoked grant into an
     * infinite one, and Google answers a revoked refresh token fast enough that
     * the loop would be tight.
     */
    private suspend fun call(
        method: String,
        url: String,
        body: ByteArray? = null,
        contentType: String? = null,
    ): HttpResponse {
        suspend fun attempt(token: String): HttpResponse {
            val headers = buildMap {
                put("Authorization", "Bearer $token")
                if (contentType != null) put("Content-Type", contentType)
            }
            return http.send(HttpRequest(method, url, headers, body))
        }

        var res = attempt(tokens.token())
        if (res.status == 401) res = attempt(tokens.refresh())

        when {
            res.status == 401 || res.status == 403 -> {
                val text = res.body.decodeToString()
                // 403 is overloaded: it is both "you may not" and "you are going
                // too fast". Only the first is an auth problem, and telling
                // someone to reconnect their account when the real answer is to
                // wait a minute is a small betrayal that costs them an evening.
                if (Regex("rateLimitExceeded|userRateLimitExceeded|quotaExceeded").containsMatchIn(text)) {
                    throw StorageException(
                        StorageErrorKind.NETWORK,
                        "Google is rate-limiting; try again shortly",
                        403,
                    )
                }
                throw StorageException(StorageErrorKind.AUTH, "Google Drive refused the request", res.status)
            }
            res.status == 404 -> throw StorageException(StorageErrorKind.NOT_FOUND, "not found in Drive", 404)
            res.status >= 500 -> throw StorageException(StorageErrorKind.SERVER, "Drive failed (${res.status})", res.status)
            res.status >= 400 -> throw StorageException(StorageErrorKind.SERVER, "Drive rejected the request (${res.status})", res.status)
        }
        return res
    }

    override suspend fun list(): List<String> {
        val names = mutableListOf<String>()
        ids.clear()
        var pageToken: String? = null
        var guard = 0
        do {
            val page = DriveApi.parseFileList(call("GET", DriveApi.listUrl(pageToken)).body)
            for (f in page.files) {
                // Drive allows two files with the same name. The log never
                // writes a name twice, but a PUT that timed out after the
                // server had committed it, and was then retried, would leave a
                // duplicate. Both copies hold identical bytes because blobs are
                // immutable, so keeping the first is correct rather than merely
                // convenient.
                if (!ids.containsKey(f.name)) {
                    ids[f.name] = f.id
                    names += f.name
                }
            }
            pageToken = page.nextPageToken
        } while (pageToken != null && ++guard < 100)
        return names
    }

    private suspend fun idFor(name: String): String {
        ids[name]?.let { return it }
        val found = DriveApi.parseFileList(call("GET", DriveApi.findByNameUrl(name)).body)
            .files.firstOrNull { it.name == name }
            ?: throw StorageException(StorageErrorKind.NOT_FOUND, "$name is not in Drive", 404)
        ids[name] = found.id
        return found.id
    }

    override suspend fun get(name: String): ByteArray {
        // alt=media is what makes this return the bytes instead of the
        // metadata. Without it the response is a small JSON object that
        // decrypts to nothing and is reported as a corrupted blob.
        return call("GET", DriveApi.mediaUrl(idFor(name))).body
    }

    override suspend fun put(name: String, bytes: ByteArray) {
        // Always a create. appDataFolder needs no MKCOL — it exists as soon as
        // the scope is granted — and the log never rewrites a name, so there is
        // no update path to get wrong.
        val boundary = newBoundary()
        val res = call(
            "POST",
            DriveApi.uploadUrl(),
            DriveApi.multipartBody(name, bytes, boundary),
            "multipart/related; boundary=$boundary",
        )
        runCatching { DriveApi.json.decodeFromString(DriveFile.serializer(), res.body.decodeToString()) }
            .getOrNull()?.let { ids[name] = it.id }
    }

    override suspend fun delete(name: String) {
        val id = try {
            idFor(name)
        } catch (e: StorageException) {
            // Already gone is the outcome that was wanted. Two devices
            // compacting the same old batch at once must not produce a failure
            // anybody sees.
            if (e.kind == StorageErrorKind.NOT_FOUND) return else throw e
        }
        try {
            call("DELETE", DriveApi.deleteUrl(id))
        } catch (e: StorageException) {
            if (e.kind != StorageErrorKind.NOT_FOUND) throw e
        }
        ids.remove(name)
    }
}