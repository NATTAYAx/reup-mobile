package app.reup

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.reup.sync.Db
import app.reup.sync.DriveApi
import app.reup.sync.GoogleOAuth
import app.reup.sync.HttpRequest
import app.reup.sync.HttpTransport
import app.reup.sync.Pkce
import app.reup.sync.RedirectResult
import app.reup.sync.SettingsTokenStore
import app.reup.sync.Sha256
import app.reup.sync.SyncValue

// ─── GoogleSignIn.kt — the phone's half of the handshake ─────────────────────
//
// WHAT IS HERE AND WHAT IS NOT
//
// The decisions are in :shared — OAuth.kt builds the URL, computes the PKCE
// challenge and reads the redirect, and sixteen tests hold it; Drive.kt has
// carried the refreshing and the 401 dance since layer four. What is left is
// the two things only Android can do: send someone to a browser, and be woken
// up when they come back.
//
// WHY THE SYSTEM BROWSER AND NOT A CUSTOM TAB
//
// A Custom Tab keeps the person inside the app and looks better. It also means
// androidx.browser, which this module does not have and which would be a
// dependency added for appearance. RFC 8252 asks for an external user-agent and
// names the system browser as one; a tab is a nicer version of the same answer,
// not a safer one. If the dependency arrives later for another reason, swapping
// the launch is four lines and nothing else here changes.
//
// WHY THE VERIFIER IS WRITTEN TO THE DATABASE
//
// This is the part that is easy to get wrong and impossible to notice in
// testing. Between the browser opening and the redirect arriving, this app is
// in the background, and Android is free to kill it — that is not an edge case,
// it is the normal fate of a backgrounded app on a phone whose battery manager
// is already known to be aggressive. A verifier held in a field is a verifier
// that is gone, and the failure arrives as "Google refused the exchange" with
// nothing to explain it.
//
// So the pending attempt is a row, like everything else that has to survive.

private const val PENDING_KEY = "sync_google_pending"

/** The path after the scheme. Any path works; this one says what it is. */
private const val REDIRECT_PATH = "/oauth2redirect"

class AndroidSignIn(
    private val db: Db,
    private val http: HttpTransport,
    private val sha: Sha256 = AndroidSha256(),
) {

    /** Built in :shared, where a test can check it. See GoogleOAuth. */
    fun redirectUri(clientId: String): String =
        GoogleOAuth.androidRedirectUri(clientId, REDIRECT_PATH)

    /** Send the person to Google. Returns false only if no browser answered. */
    suspend fun start(ctx: Context, clientId: String): Boolean {
        val cipher = AndroidAeadCipher()
        // Parenthesised, not a trailing lambda: `length` comes after `random`
        // in the signature, so the lambda is not the last parameter.
        val verifier = Pkce.newVerifier({ n -> cipher.randomBytes(n) })
        val state = hex(cipher.randomBytes(16))

        // Written before the browser opens, not after. The opposite order leaves
        // a window in which the redirect can arrive first — on a fast device
        // with the browser already warm, that window is real.
        db.execute(
            "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(
                SyncValue.Text(PENDING_KEY),
                // Two values, one row, split on a character neither can contain:
                // a verifier is unreserved-only and the state is hex.
                SyncValue.Text("$verifier|$state"),
            ),
        )

        val url = GoogleOAuth.authUrl(
            clientId = clientId,
            redirectUri = redirectUri(clientId),
            challenge = Pkce.challenge(verifier, sha),
            state = state,
        )

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            // The browser must be its own task, or coming back lands on a
            // screen stacked on top of this one instead of on this one.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    sealed interface Result {
        data object Connected : Result
        /** They pressed cancel. Not a fault, and must not be shown as one. */
        data class Refused(val why: String) : Result
        data class Failed(val why: String) : Result
    }

    /**
     * Handle the redirect. Safe to call with anything, including a stray intent
     * from another app: everything is decided by OAuth.parseRedirect and the
     * stored state, neither of which trusts the caller.
     */
    suspend fun finish(uri: Uri?, clientId: String): Result? {
        val url = uri?.toString() ?: return null
        if (!url.contains(REDIRECT_PATH)) return null

        val rows = db.select(
            "SELECT value FROM app_settings WHERE key = ?",
            listOf(SyncValue.Text(PENDING_KEY)),
        )
        val pending = (rows.firstOrNull()?.get("value") as? SyncValue.Text)?.value
            ?: return Result.Failed("this sign-in did not start here")
        val verifier = pending.substringBefore('|')
        val state = pending.substringAfter('|', "")

        // Cleared before the exchange, not after. A code can only be spent once,
        // so a row left behind is only useful to whoever sends the next intent.
        db.execute("DELETE FROM app_settings WHERE key = ?", listOf(SyncValue.Text(PENDING_KEY)))

        when (val r = GoogleOAuth.parseRedirect(url, state)) {
            is RedirectResult.Refused ->
                return if (r.error == "access_denied") {
                    Result.Refused("การล็อกอินถูกยกเลิก ไม่มีอะไรเปลี่ยน")
                } else {
                    Result.Refused(r.description ?: r.error)
                }

            is RedirectResult.Rejected -> return Result.Failed(r.why)

            is RedirectResult.Code -> {
                val res = http.send(
                    HttpRequest(
                        method = "POST",
                        url = "https://oauth2.googleapis.com/token",
                        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                        // No client secret. An Android client has none at all,
                        // and the verifier is what is doing the work.
                        body = GoogleOAuth.exchangeBody(
                            clientId = clientId,
                            code = r.code,
                            verifier = verifier,
                            redirectUri = redirectUri(clientId),
                        ),
                    ),
                )
                if (res.status !in 200..299) {
                    // Google's own message is usually the only thing that says
                    // which of the four possible mistakes this was.
                    return Result.Failed("Google ไม่รับ: " + res.body.decodeToString())
                }

                val tokens = try {
                    DriveApi.tokensFrom(res.body.decodeToString(), nowSec(), null)
                } catch (e: Exception) {
                    return Result.Failed(e.message ?: "อ่านคำตอบของ Google ไม่ได้")
                }
                if (tokens.refreshToken == null) {
                    // Without one this works for an hour and then asks to sign
                    // in again for ever, which means access_type or prompt did
                    // not survive the URL — a bug here, not something to retry.
                    return Result.Failed("Google ไม่ได้ส่ง refresh token มา")
                }

                SettingsTokenStore(db).save(tokens)
                return Result.Connected
            }
        }
    }

    /** Forget the sign-in on this phone. The computer keeps working. */
    suspend fun disconnect() {
        SettingsTokenStore(db).clear()
    }

    suspend fun connected(): Boolean =
        SettingsTokenStore(db).load()?.refreshToken != null

    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    /** Hex, so the state can never contain the separator the pending row uses. */
    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    companion object {
        /**
         * The client id this build was given, or null.
         *
         * Null is the normal state for anyone who cloned the repository, since
         * local.properties is not in it. The screen keeps its WebDAV boxes and
         * simply does not offer Drive, which is the same thing the desktop does.
         */
        fun clientId(): String? =
            BuildConfig.GOOGLE_CLIENT_ID?.takeIf { it.isNotEmpty() }
    }
}