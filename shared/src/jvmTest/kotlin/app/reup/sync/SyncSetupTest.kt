package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── SyncSetupTest.kt ────────────────────────────────────────────────────────
//
// There is no vector file behind this one either, for the reason Config.kt
// gives: nothing here crosses between the two machines. What it pins down is
// the behaviour someone would notice, and most of it is one behaviour said four
// ways — a pairing code is never lost by pressing a button.
//
// The reason that is worth this many tests is that the loss is silent and
// total. There is no error, no empty state, no support address. The next sync
// simply starts a new empty bucket, and everything in the old one stays there
// encrypted with a key nobody has.

private val CODE = PairingCode.encode(
    Pairing(
        bucketId = "b7f3a1c2d4e5f60718293a4b5c6d7e8f",
        key = ByteArray(SealedBlob.KEY_BYTES) { it.toByte() },
    ),
)

private val OTHER_CODE = PairingCode.encode(
    Pairing(
        bucketId = "0123456789abcdef0123456789abcdef",
        key = ByteArray(SealedBlob.KEY_BYTES) { (it + 1).toByte() },
    ),
)

private val WEBDAV = SyncConfig(
    SyncBackend.WebDav("https://box.example/dav/reup/", "me", "hunter2"),
    CODE,
)

private fun accepted(r: SetupResult): SyncConfig {
    assertTrue(r is SetupResult.Accepted, "expected accepted, got $r")
    return r.config
}

private fun refused(r: SetupResult): SetupProblem {
    assertTrue(r is SetupResult.Refused, "expected refused, got $r")
    return r.problem
}

class SyncSetupTest {

    @Test
    fun `the boxes open holding what was saved`() {
        val f = SyncSetup.fieldsOf(WEBDAV)
        assertEquals("https://box.example/dav/reup/", f.baseUrl)
        assertEquals("me", f.username)
        assertEquals("hunter2", f.password)
        assertEquals(CODE, f.pairing)
    }

    @Test
    fun `with sync off the folder boxes are empty and the code box is not`() {
        // The code outliving the folder is the whole reason these are separate
        // fields. Turning sync off is not a decision about the key.
        val f = SyncSetup.fieldsOf(SyncConfig(SyncBackend.Off, CODE))
        assertEquals("", f.baseUrl)
        assertEquals("", f.username)
        assertEquals("", f.password)
        assertEquals(CODE, f.pairing)
    }

    @Test
    fun `saving without touching anything changes nothing`() {
        // The commonest thing anyone does on a settings screen, and the one
        // that has to be free. Round-tripping through the boxes must be the
        // identity, or every visit to this screen is a chance to lose something.
        for (c in listOf(WEBDAV, SyncConfig(SyncBackend.Off, CODE), SYNC_OFF)) {
            assertEquals(c, accepted(SyncSetup.apply(c, SyncSetup.fieldsOf(c))), "$c")
        }
    }

    @Test
    fun `an empty code box does not delete the code`() {
        // Reads as "I did not change this", never as "throw the key away". The
        // likeliest way to arrive here is someone clearing the box by accident
        // while fixing the address above it.
        val r = accepted(SyncSetup.apply(WEBDAV, SyncSetup.fieldsOf(WEBDAV).copy(pairing = "")))
        assertEquals(CODE, r.pairing)
    }

    @Test
    fun `a code box holding only spaces does not delete the code either`() {
        val r = accepted(SyncSetup.apply(WEBDAV, SyncSetup.fieldsOf(WEBDAV).copy(pairing = "   ")))
        assertEquals(CODE, r.pairing)
    }

    @Test
    fun `a code that cannot be read is refused, and nothing at all is saved`() {
        // Including the folder settings, which were fine. Saving half a form is
        // worse than saving none of it: the screen would then show a code the
        // person did not enter, with no way to tell which parts went in.
        val typed = SyncFields(
            baseUrl = "https://new.example/dav/",
            username = "someone",
            password = "else",
            pairing = "reup://pair?b=x&k=NOT-A-KEY!!",
        )
        assertEquals(SetupProblem.UNREADABLE_CODE, refused(SyncSetup.apply(WEBDAV, typed)))
    }

    @Test
    fun `re-saving a code that was already unreadable keeps it`() {
        // Config.kt refuses to throw a broken code away on the way in, so this
        // screen must not become the thing that throws it away on the way out.
        // It may be a code that was written down correctly and typed wrong once.
        val broken = SyncConfig(SyncBackend.Off, "reup://pair?b=x&k=NOT-A-KEY!!")
        val r = accepted(SyncSetup.apply(broken, SyncSetup.fieldsOf(broken)))
        assertEquals("reup://pair?b=x&k=NOT-A-KEY!!", r.pairing)
        assertNull(SyncConfigs.pairingOf(r))
    }

    @Test
    fun `a real code replaces another real code`() {
        val typed = SyncSetup.fieldsOf(WEBDAV).copy(pairing = OTHER_CODE)
        assertEquals(OTHER_CODE, accepted(SyncSetup.apply(WEBDAV, typed)).pairing)
    }

    @Test
    fun `a pasted code is stored without the whitespace that came with it`() {
        val typed = SyncSetup.fieldsOf(SYNC_OFF).copy(pairing = "  $CODE\n")
        val r = accepted(SyncSetup.apply(SYNC_OFF, typed))
        assertEquals(CODE, r.pairing)
        assertNotNull(SyncConfigs.pairingOf(r))
    }

    @Test
    fun `clearing the address turns sync off and keeps the code`() {
        val r = accepted(SyncSetup.apply(WEBDAV, SyncSetup.fieldsOf(WEBDAV).copy(baseUrl = "")))
        assertEquals(SyncBackend.Off, r.backend)
        assertEquals(CODE, r.pairing)
    }

    @Test
    fun `an address that cannot work is refused rather than stored`() {
        // Stored, it would fail on every sync from then on with an error that
        // arrives long after the screen where it could be fixed.
        for (bad in listOf("box.example/dav/", "ftp://box.example/dav/", "http://box.example/dav/")) {
            val typed = SyncSetup.fieldsOf(WEBDAV).copy(baseUrl = bad)
            assertEquals(SetupProblem.UNUSABLE_URL, refused(SyncSetup.apply(WEBDAV, typed)), bad)
        }
    }

    @Test
    fun `the refusal says which problem it was, for a screen that has to explain it`() {
        val typed = SyncSetup.fieldsOf(WEBDAV).copy(baseUrl = "http://box.example/dav/")
        val r = SyncSetup.apply(WEBDAV, typed)
        assertTrue(r is SetupResult.Refused)
        assertTrue(r.detail != null && r.detail!!.contains("https"), "detail was ${r.detail}")
    }

    @Test
    fun `plain http is allowed to a machine on the home network`() {
        // The case that matters in practice: a NAS that only speaks http on the
        // LAN, or rclone running on the desktop while this is being tested.
        for (ok in listOf("http://192.168.1.20:8080/dav/", "http://127.0.0.1:8080/", "http://nas.local/dav/")) {
            val typed = SyncSetup.fieldsOf(SYNC_OFF).copy(baseUrl = ok, pairing = CODE)
            val b = accepted(SyncSetup.apply(SYNC_OFF, typed)).backend
            assertTrue(b is SyncBackend.WebDav, ok)
            assertEquals(ok, b.baseUrl)
        }
    }

    @Test
    fun `the address is trimmed and the password is not`() {
        // A space pasted along with a URL is not a decision anyone made. A space
        // at either end of a password is a character in the password.
        val typed = SyncFields(
            baseUrl = "  https://box.example/dav/  ",
            username = "me",
            password = " hunter2 ",
            pairing = CODE,
        )
        val b = accepted(SyncSetup.apply(SYNC_OFF, typed)).backend as SyncBackend.WebDav
        assertEquals("https://box.example/dav/", b.baseUrl)
        assertEquals(" hunter2 ", b.password)
    }

    // ─── what a run is reported as ──────────────────────────────────────────

    @Test
    fun `a run that moved nothing is quiet, however many files it read`() {
        // Pressing the button twice is the normal case, and the second press
        // reads every file again and finds it already has all of it. A row of
        // zeros reads like a failure; "both sides already agree" does not.
        val s = SyncSetup.summarise(SyncReport(read = 3, applied = 0, pushed = 0, skipped = emptyList(), wrote = null))
        assertTrue(s.quiet)
        assertEquals(3, s.read)
    }

    @Test
    fun `anything moving in either direction is not quiet`() {
        assertTrue(!SyncSetup.summarise(SyncReport(0, 1, 0, emptyList(), null)).quiet)
        assertTrue(!SyncSetup.summarise(SyncReport(0, 0, 1, emptyList(), "d-1-1.reup")).quiet)
    }

    @Test
    fun `skipped files are counted on their own`() {
        // Deliberately not folded into quiet. A file that could not be read is
        // the one thing in a report worth coming back to, and it can happen in
        // a run where nothing else did.
        val s = SyncSetup.summarise(
            SyncReport(1, 0, 0, listOf(SkippedFile("d-2-4.reup", "truncated")), null),
        )
        assertEquals(1, s.skipped)
        assertTrue(s.quiet)
    }
}