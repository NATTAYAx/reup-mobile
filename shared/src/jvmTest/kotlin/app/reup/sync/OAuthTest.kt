// shared/src/jvmTest/kotlin/app/reup/sync/OAuthTest.kt
package app.reup.sync

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OAuthTest {

    private val sha = object : Sha256 {
        override fun hash(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    private val CLIENT = "123-abc.apps.googleusercontent.com"
    private val REDIRECT = "http://127.0.0.1:47123/callback"

    @Test
    fun `the challenge matches the one published in RFC 7636`() {
        // Appendix B of the RFC, verbatim. An external authority rather than a
        // number this project produced and then agreed with itself about — the
        // failure mode being guarded against is base64 with padding, or
        // standard base64 instead of base64url, and a self-generated vector
        // would happily encode either mistake as the expected answer.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge(verifier, sha),
        )
    }

    @Test
    fun `a challenge carries no padding and nothing needing escape`() {
        val c = Pkce.challenge("any verifier will do here", sha)
        assertTrue('=' !in c, c)
        assertTrue('+' !in c && '/' !in c, c)
        assertEquals(c, DriveApi.encode(c), "a challenge should survive a URL untouched")
    }

    @Test
    fun `a verifier is the right length and uses only unreserved characters`() {
        var n = 0
        val v = Pkce.newVerifier({ len -> ByteArray(len) { (n++ % 251).toByte() } })
        assertEquals(64, v.length)
        assertEquals(v, DriveApi.encode(v), "a verifier should survive a URL untouched")
    }

    @Test
    fun `a verifier does not repeat itself when the random source does not`() {
        val src = java.security.SecureRandom()
        val a = Pkce.newVerifier({ len -> ByteArray(len).also(src::nextBytes) })
        val b = Pkce.newVerifier({ len -> ByteArray(len).also(src::nextBytes) })
        assertTrue(a != b)
    }

    @Test
    fun `the authorisation url asks for offline access and a fresh consent`() {
        // Both, or Google returns no refresh token — and an app with no refresh
        // token works for an hour and then asks for a login forever after.
        val u = GoogleOAuth.authUrl(CLIENT, REDIRECT, "chal", "st")
        assertTrue("access_type=offline" in u, u)
        assertTrue("prompt=consent" in u, u)
        assertTrue("code_challenge_method=S256" in u, u)
        assertTrue("response_type=code" in u, u)
    }

    @Test
    fun `the query string escapes with percent-twenty and never with plus`() {
        // This is the mistake that already cost a day on the Drive name query:
        // the standard form encoder writes a space as `+`, Google read it
        // literally, and the request matched nothing without erroring.
        val u = GoogleOAuth.authUrl(CLIENT, REDIRECT, "chal", "st", scope = "a b")
        assertTrue("scope=a%20b" in u, u)
        assertTrue('+' !in u.substringAfter('?'), u)
        assertTrue("redirect_uri=http%3A%2F%2F127.0.0.1%3A47123%2Fcallback" in u, u)
    }

    @Test
    fun `the exchange body carries the verifier, which is the whole point`() {
        val b = GoogleOAuth.exchangeBody(CLIENT, "code-1", "ver-1", REDIRECT).decodeToString()
        assertTrue("code_verifier=ver-1" in b, b)
        assertTrue("grant_type=authorization_code" in b, b)
        assertTrue("client_secret" !in b, "android has no secret and must not send an empty one")
    }

    @Test
    fun `the desktop's non-secret is sent only when there is one`() {
        val b = GoogleOAuth.exchangeBody(CLIENT, "c", "v", REDIRECT, "GOCSPX-xyz").decodeToString()
        assertTrue("client_secret=GOCSPX-xyz" in b, b)
    }

    @Test
    fun `the android redirect is the client id backwards`() {
        // Registered by Google when the Android client is made, so it is never
        // typed anywhere and nothing else checks it. One character wrong and the
        // browser lands on a page no app on the phone answers, with no error to
        // read at either end.
        assertEquals(
            "com.googleusercontent.apps.250966479972-6o0u00rso1aq0kko2rjr10kv8cdu1bhl:/oauth2redirect",
            GoogleOAuth.androidRedirectUri(
                "250966479972-6o0u00rso1aq0kko2rjr10kv8cdu1bhl.apps.googleusercontent.com",
            ),
        )
    }

    @Test
    fun `the android redirect survives a url and comes back readable`() {
        // It goes out percent-encoded in the authorisation request and comes
        // back as the thing the phone is woken with, so the two have to agree.
        val id = "1-a.apps.googleusercontent.com"
        val r = GoogleOAuth.androidRedirectUri(id)
        val u = GoogleOAuth.authUrl("c", r, "chal", "st")
        assertTrue(("redirect_uri=" + DriveApi.encode(r)) in u, u)
        assertEquals(RedirectResult.Code("x"), GoogleOAuth.parseRedirect("$r?code=x&state=st", "st"))
    }

    @Test
    fun `a redirect with the right state yields the code`() {
        val r = GoogleOAuth.parseRedirect("$REDIRECT?code=4%2F0AX&state=st", "st")
        assertEquals(RedirectResult.Code("4/0AX"), r)
    }

    @Test
    fun `a redirect with someone else's state is refused`() {
        // A loopback port is reachable by anything on the machine and an Android
        // scheme is reachable by any app. This is the one input here that does
        // not come from Google.
        val r = GoogleOAuth.parseRedirect("$REDIRECT?code=stolen&state=theirs", "mine")
        assertTrue(r is RedirectResult.Rejected, r.toString())
    }

    @Test
    fun `a redirect with no state at all is refused too`() {
        // Not "an older client being friendly". Missing is not a match.
        val r = GoogleOAuth.parseRedirect("$REDIRECT?code=abc", "mine")
        assertTrue(r is RedirectResult.Rejected, r.toString())
    }

    @Test
    fun `pressing cancel reads as a refusal, not as a fault`() {
        val r = GoogleOAuth.parseRedirect(
            "$REDIRECT?error=access_denied&error_description=The%20user%20denied&state=st", "st",
        )
        assertEquals(RedirectResult.Refused("access_denied", "The user denied"), r)
    }

    @Test
    fun `a second code appended to the url does not become the one used`() {
        val r = GoogleOAuth.parseRedirect("$REDIRECT?code=real&state=st&code=injected", "st")
        assertEquals(RedirectResult.Code("real"), r)
    }

    @Test
    fun `a redirect with no query at all is refused rather than crashing`() {
        assertTrue(GoogleOAuth.parseRedirect(REDIRECT, "st") is RedirectResult.Rejected)
        assertTrue(GoogleOAuth.parseRedirect("", "st") is RedirectResult.Rejected)
    }

    @Test
    fun `a fragment after the query is not read as part of it`() {
        val r = GoogleOAuth.parseRedirect("$REDIRECT?code=abc&state=st#anything", "st")
        assertEquals(RedirectResult.Code("abc"), r)
    }

    @Test
    fun `decoding a query value treats plus as a space and percent as bytes`() {
        assertEquals("a b", GoogleOAuth.percentDecode("a+b"))
        assertEquals("a b", GoogleOAuth.percentDecode("a%20b"))
        assertEquals("ยา", GoogleOAuth.percentDecode("%E0%B8%A2%E0%B8%B2"))
        // A stray percent is left alone rather than throwing: the useful
        // failure is the state check, not an exception from a decoder.
        assertEquals("100%", GoogleOAuth.percentDecode("100%"))
    }
}