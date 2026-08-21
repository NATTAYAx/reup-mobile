package app.reup.sync

// ─── OAuth.kt — the half of signing in that is not platform-specific ─────────
//
// WHAT WAS ALREADY HERE, AND WHY THIS FILE IS SMALL
//
// Drive.kt has carried the whole token lifecycle since it was written: refresh
// a minute early, refresh once after a 401 and no more, keep the stored refresh
// token when a refresh response omits it, clear the store when the grant is
// revoked. Seventeen tests hold that. What it never had was the very first
// authorisation — the one time a person has to look at a Google page and say
// yes. AccessTokenSource is the seam that was left for it.
//
// So this file is not "OAuth". It is the four decisions between the button and
// the first refresh token, and they are here rather than in the two screens
// because they are the four places this flow is usually got wrong:
//
//   1. the PKCE challenge, which is a hash and an encoding and no room to be
//      approximately right
//   2. the authorisation URL, whose query string is exactly where this project
//      has already been bitten once — the Drive name query encoded a space as
//      `+`, which Drive read literally and matched nothing, silently
//   3. reading the redirect, which is the only moment an attacker gets to hand
//      this app a value
//   4. the exchange body, which must carry the verifier or the whole point of
//      PKCE is gone
//
// Everything above and below that is per platform: the desktop opens a browser
// and listens on a loopback port, Android opens a Custom Tab and receives an
// Intent. Nothing in those two files is shared, and neither can be tested from
// where this one is tested.

/**
 * SHA-256, supplied by the platform.
 *
 * An interface for the same reason AeadCipher is one: `kotlin.crypto` has no
 * hash in common code, and pulling in a pure-Kotlin implementation to compute
 * one hash of one short string would be adding a dependency to avoid writing
 * five lines twice.
 */
interface Sha256 {
    fun hash(bytes: ByteArray): ByteArray
}

/**
 * Proof Key for Code Exchange, RFC 7636.
 *
 * WHY THIS IS NOT OPTIONAL HERE
 *
 * A desktop app cannot keep a client secret: whatever is compiled into it can
 * be read out of it. Google knows this and calls the desktop credential a
 * "client secret" anyway, which invites the mistake of treating it as one.
 * PKCE is what actually protects the exchange — the code is useless to anyone
 * who does not also hold the verifier, and the verifier never leaves the
 * device. On Android there is no secret at all, only this.
 */
object Pkce {

    /** The character set RFC 7636 allows in a verifier, all of it unreserved. */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /**
     * A fresh verifier. 64 characters, inside the RFC's 43..128 range.
     *
     * Built from random bytes rather than from a random index per character,
     * because the modulo of 256 by 66 is not uniform and a verifier that is
     * slightly predictable is a verifier that does slightly less than nothing.
     * Rejection sampling costs a handful of extra bytes and is exactly uniform.
     */
    fun newVerifier(random: (Int) -> ByteArray, length: Int = 64): String {
        require(length in 43..128) { "a verifier is 43 to 128 characters" }
        val sb = StringBuilder(length)
        while (sb.length < length) {
            for (b in random(length)) {
                val v = b.toInt() and 0xFF
                if (v >= 256 - (256 % ALPHABET.length)) continue
                sb.append(ALPHABET[v % ALPHABET.length])
                if (sb.length == length) break
            }
        }
        return sb.toString()
    }

    /**
     * The S256 challenge: base64url of the SHA-256 of the verifier, unpadded.
     *
     * `plain` is also in the spec and Google accepts it. It is worth nothing:
     * it sends the verifier itself as the challenge, so anyone who sees the
     * authorisation request can complete the exchange.
     */
    fun challenge(verifier: String, sha: Sha256): String =
        Base64Url.encode(sha.hash(verifier.encodeToByteArray()))
}

/** What came back on the redirect, once. */
sealed interface RedirectResult {
    /** The authorisation code, ready to exchange. */
    data class Code(val code: String) : RedirectResult

    /**
     * Google said no, or the user did. `error` is Google's own word for it —
     * `access_denied` when the person pressed cancel, which is not a fault and
     * must not be shown as one.
     */
    data class Refused(val error: String, val description: String?) : RedirectResult

    /** The redirect was not one this app is willing to act on. */
    data class Rejected(val why: String) : RedirectResult
}

object GoogleOAuth {

    const val AUTH_ENDPOINT: String = "https://accounts.google.com/o/oauth2/v2/auth"

    /**
     * The authorisation URL.
     *
     * `access_type=offline` with `prompt=consent` is what makes Google return a
     * refresh token. Without offline there is no refresh token and the app asks
     * for a login every hour; without consent Google skips the screen on a
     * second grant and returns no refresh token *that time*, which produces an
     * app that works when first installed and stops working after a reinstall.
     * The pair is deliberate, not cargo.
     *
     * Every value goes through the same percent-encoder Drive.kt uses, which
     * writes `%20` and never `+`. The scope string contains slashes and colons
     * and the redirect contains a colon and slashes of its own; an encoder that
     * leaves them alone produces a URL that Google reads as a different
     * redirect than the one registered, and the error it returns names none of
     * this.
     */
    fun authUrl(
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
        scope: String = DRIVE_SCOPE,
    ): String =
        AUTH_ENDPOINT +
                "?client_id=" + DriveApi.encode(clientId) +
                "&redirect_uri=" + DriveApi.encode(redirectUri) +
                "&response_type=code" +
                "&scope=" + DriveApi.encode(scope) +
                "&code_challenge=" + DriveApi.encode(challenge) +
                "&code_challenge_method=S256" +
                "&state=" + DriveApi.encode(state) +
                "&access_type=offline" +
                "&prompt=consent"

    /**
     * The redirect an Android client is registered for: the client id backwards.
     *
     * Google reserves this scheme when the Android client is created, which is
     * why nothing has to be typed into the console for it — and also why an
     * off-by-one-character version produces a redirect that no app on the phone
     * answers, so the browser sits on a page that cannot load while the screen
     * waits for a result that is never coming. There is no error to read at
     * either end, which is why this one line lives here rather than in the
     * Android file where nothing could check it.
     *
     * The single slash after the colon is deliberate and is what Google's own
     * examples use. `scheme://path` would read the first segment as a host,
     * which is a different URI even though both look like a typo.
     */
    fun androidRedirectUri(clientId: String, path: String = "/oauth2redirect"): String =
        clientId.split(".").reversed().joinToString(".") + ":" + path

    /**
     * The body that trades the code for tokens.
     *
     * [clientSecret] is null on Android, where the credential has none, and is
     * the desktop client's non-secret on the desktop, which Google requires in
     * the request even though it is compiled into a binary anyone can read. The
     * verifier is what is actually doing the work in both cases.
     */
    fun exchangeBody(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String,
        clientSecret: String? = null,
    ): ByteArray =
        buildString {
            append("client_id=").append(DriveApi.encode(clientId))
            if (clientSecret != null) append("&client_secret=").append(DriveApi.encode(clientSecret))
            append("&code=").append(DriveApi.encode(code))
            append("&code_verifier=").append(DriveApi.encode(verifier))
            append("&redirect_uri=").append(DriveApi.encode(redirectUri))
            append("&grant_type=authorization_code")
        }.encodeToByteArray()

    /**
     * Read the redirect the browser came back with.
     *
     * THE STATE CHECK IS THE POINT
     *
     * On the desktop this app listens on a loopback port, and anything running
     * on the machine can reach a loopback port. On Android any app can send an
     * Intent with the registered scheme. So the redirect is the one input here
     * that does not come from Google — it comes from whoever got there first,
     * and a code from somewhere else exchanged by this app attaches this
     * person's Drive to an account that is not theirs.
     *
     * [expected] is compared whole rather than by prefix, and a redirect with
     * no state at all is refused rather than treated as an old client being
     * friendly.
     */
    fun parseRedirect(url: String, expected: String): RedirectResult {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return RedirectResult.Rejected("the redirect carried no query string")

        val params = mutableMapOf<String, String>()
        for (pair in q.split("&")) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i < 0) pair else pair.substring(0, i)
            val v = if (i < 0) "" else pair.substring(i + 1)
            // First wins. A duplicated parameter is somebody appending a second
            // code to a URL that already had one; the reading that lets that
            // work is the reading where the last one is used.
            if (!params.containsKey(k)) params[k] = percentDecode(v)
        }

        val state = params["state"]
        if (state == null || state != expected) {
            return RedirectResult.Rejected("this redirect did not come from the request this app made")
        }

        params["error"]?.let {
            return RedirectResult.Refused(it, params["error_description"])
        }

        val code = params["code"]
        if (code.isNullOrEmpty()) return RedirectResult.Rejected("the redirect carried no code")
        return RedirectResult.Code(code)
    }

    /**
     * Query-string decoding, with `+` meaning a space.
     *
     * That rule is the opposite of the encoder above, and both are right: the
     * encoder is building a URL, where a space is `%20`, while this is reading
     * form-encoded data, where a space may be either. Browsers send both.
     */
    fun percentDecode(s: String): String {
        if ('%' !in s && '+' !in s) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.add(' '.code.toByte()); i++ }
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) { out.add(c.code.toByte()); i++ }
                    else { out.add(hex.toByte()); i += 3 }
                }
                else -> { out.addAll(c.toString().encodeToByteArray().toList()); i++ }
            }
        }
        return out.toByteArray().decodeToString()
    }
}