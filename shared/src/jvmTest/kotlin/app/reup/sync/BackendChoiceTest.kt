// shared/src/jvmTest/kotlin/app/reup/sync/BackendChoiceTest.kt
package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// ─── BackendChoiceTest.kt ────────────────────────────────────────────────────
//
// The debt from the round that added the backend chooser.
//
// The bug it is holding shut was this: the phone could sign in to Google, store
// a refresh token and show a connected button, and no line of code on the
// device could write the word `drive` into the settings row. Every sync after
// the sign-in still went to the WebDAV folder and reported success. Nothing
// errored, which is why it cost an evening rather than a minute.
//
// WHAT IS WORTH NOTICING ABOUT THE FIRST TEST BELOW
//
// It is not new. `saving without touching anything changes nothing` has been in
// SyncSetupTest since the screen existed, and it is exactly the property that
// fails for a Drive config: fieldsOf gives three empty boxes, and the old apply
// read three empty boxes as "sync off". So the test that would have caught this
// was already written and already passing — it had simply never been handed a
// Drive config to try.
//
// That is the more useful lesson than the fix. A property test is only as wide
// as the list of cases it is given, and the case that was missing was the one
// backend nobody had a screen for yet.
//
// A separate file rather than more methods in SyncSetupTest, because that file
// is about one thing said four ways — a pairing code is never lost by pressing
// a button — and this is about a different thing. The two private vals here are
// deliberately not named CODE and WEBDAV: private top-level properties are
// file-scoped and would not actually clash, but the last time two test files in
// this package shared a name it cost a build, and the name was free.

private val CHOICE_CODE = PairingCode.encode(
    Pairing(
        bucketId = "b7f3a1c2d4e5f60718293a4b5c6d7e8f",
        key = ByteArray(SealedBlob.KEY_BYTES) { it.toByte() },
    ),
)

private val CHOICE_WEBDAV = SyncConfig(
    SyncBackend.WebDav("https://box.example/dav/reup/", "me", "hunter2"),
    CHOICE_CODE,
)

private val CHOICE_DRIVE = SyncConfig(SyncBackend.Drive, CHOICE_CODE)

private fun ok(r: SetupResult): SyncConfig {
    assertTrue(r is SetupResult.Accepted, "expected accepted, got $r")
    return r.config
}

class BackendChoiceTest {

    @Test
    fun `opening and saving a drive config leaves it a drive config`() {
        // The one that was failing. Under the old rules this returned Off,
        // because fieldsOf handed back three empty boxes and apply read empty
        // boxes as "no folder". Simply visiting the settings screen and
        // pressing save would have moved a working Drive setup to off.
        assertEquals(CHOICE_DRIVE, ok(SyncSetup.apply(CHOICE_DRIVE, SyncSetup.fieldsOf(CHOICE_DRIVE))))
    }

    @Test
    fun `a drive config opens with drive selected and no address`() {
        val f = SyncSetup.fieldsOf(CHOICE_DRIVE)
        assertEquals(BackendChoice.DRIVE, f.backend)
        assertEquals("", f.baseUrl)
        assertEquals("", f.username)
        assertEquals("", f.password)
        // The one field that is not about how the data travels.
        assertEquals(CHOICE_CODE, f.pairing)
    }

    @Test
    fun `a webdav config opens with webdav selected`() {
        assertEquals(BackendChoice.WEBDAV, SyncSetup.fieldsOf(CHOICE_WEBDAV).backend)
        // Off shows the WebDAV side too: off is what an empty address box means,
        // and that is the box being shown.
        assertEquals(BackendChoice.WEBDAV, SyncSetup.fieldsOf(SYNC_OFF).backend)
    }

    @Test
    fun `picking drive wins over whatever is still in the address box`() {
        // The realistic sequence: someone set up the home server months ago,
        // opens this screen and taps Google Drive. The address is still on the
        // screen at the moment they press save.
        val typed = SyncSetup.fieldsOf(CHOICE_WEBDAV).copy(backend = BackendChoice.DRIVE)
        val saved = ok(SyncSetup.apply(CHOICE_WEBDAV, typed))
        assertEquals(SyncBackend.Drive, saved.backend)
        assertEquals(CHOICE_CODE, saved.pairing)
    }

    @Test
    fun `and that is a different folder, so the cursor has to go`() {
        // Not this file's job to reset it — Config.save does that — but it is
        // this file's job to say that the two answers disagree, because if they
        // ever agreed the phone would carry its record of one pile of files
        // over to another one and skip everything in it.
        assertTrue(!SyncConfigs.sameTarget(CHOICE_WEBDAV, CHOICE_DRIVE))
        assertNotEquals(CHOICE_WEBDAV.backend, CHOICE_DRIVE.backend)
    }

    @Test
    fun `choosing drive does not depend on anyone being signed in`() {
        // Deliberate. Where the files should go is a decision; whether a token
        // is currently valid is a condition that comes and goes. If the setting
        // refused to save without a live sign-in, it would revert itself every
        // time a refresh token expired, and the sync would go somewhere else
        // without anyone choosing that.
        val typed = SyncFields("", "", "", CHOICE_CODE, BackendChoice.DRIVE)
        assertEquals(SyncBackend.Drive, ok(SyncSetup.apply(SYNC_OFF, typed)).backend)
    }

    @Test
    fun `an unreadable code is refused even when drive is chosen`() {
        // The refusal has to come before the backend branch, or picking Drive
        // becomes a way past the one rule this whole area exists to enforce.
        val typed = SyncFields("", "", "", "not-a-pairing-code", BackendChoice.DRIVE)
        val r = SyncSetup.apply(CHOICE_WEBDAV, typed)
        assertTrue(r is SetupResult.Refused, "expected refused, got $r")
        assertEquals(SetupProblem.UNREADABLE_CODE, r.problem)
    }

    @Test
    fun `an empty code box still means unchanged, on either backend`() {
        val typed = SyncFields("", "", "", "", BackendChoice.DRIVE)
        assertEquals(CHOICE_CODE, ok(SyncSetup.apply(CHOICE_WEBDAV, typed)).pairing)
    }

    @Test
    fun `every backend survives the round trip through the screen`() {
        // The wider version of the first test, and the one to add a case to the
        // day a fourth backend exists. That is the whole failure this file is
        // about: the property was right, the list was short.
        for (c in listOf(CHOICE_WEBDAV, CHOICE_DRIVE, SyncConfig(SyncBackend.Off, CHOICE_CODE), SYNC_OFF)) {
            assertEquals(c, ok(SyncSetup.apply(c, SyncSetup.fieldsOf(c))), "$c")
        }
    }
}