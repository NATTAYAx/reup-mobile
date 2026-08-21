// shared/src/jvmTest/kotlin/app/reup/sync/ConfigTargetTest.kt
package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Same driver as ConfigTest, EngineTest and StoreTest: nothing below ever really
// suspends, so a coroutines-test dependency would be pulled in to schedule work
// that never needs scheduling. The driver asserts that rather than assuming it.
// Named apart from the other files' `drive` because a private function called
// `drive` in a file about Google Drive reads as something it is not.
private fun runHere(block: suspend () -> Unit) {
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
    check(finished) { "the block actually suspended; this driver only runs code backed by the fake" }
    thrown?.let { throw it }
}

// ─── the two rules the desktop learned the hard way ──────────────────────────
//
// Both of these were bugs there before they were tests here, and both had the
// same symptom: the screen said the sync had worked and nothing had moved.
//
//   1. A backend with no fields still has a name. Drive was saved as `drive` and
//      read back as `off`, so the settings screen showed Drive selected out of
//      memory while every sync loaded `off` from the database and decided there
//      was nothing to do.
//
//   2. Sync state is a record of what was said to one folder locked with one
//      key. Switching to Drive kept a watermark that meant "already uploaded" —
//      to a different folder, hours earlier — so the new one stayed empty while
//      both devices reported success.
//
// The second is worse on this side than it was there. A phone that switches
// folder and keeps its cursor pulls nothing and shows no tasks, which reads as
// the app being broken rather than as a setting being wrong.

class ConfigTargetTest {

    private val code = "reup://pair?b=" + "A".repeat(22) + "&k=" + "B".repeat(43)
    private val other = "reup://pair?b=" + "C".repeat(22) + "&k=" + "D".repeat(43)
    private val dav = SyncBackend.WebDav("https://one/dav", "u", "p")

    private fun roundTrip(c: SyncConfig): SyncConfig =
        SyncConfigs.parse(SyncConfigs.serialise(c))

    @Test
    fun `every backend survives being written down and read back`() {
        for (b in listOf(SyncBackend.Off, dav, SyncBackend.Drive)) {
            val before = SyncConfig(b, code)
            val after = roundTrip(before)
            assertEquals(b, after.backend, "backend changed on the way through storage")
            assertEquals(code, after.pairing, "the pairing code did not survive")
        }
    }

    @Test
    fun `the drive backend serialises exactly as the desktop writes it`() {
        // Neither side reads the other's row, so this costs nothing and buys one
        // thing: two databases opened side by side show one format, not two that
        // mean the same.
        assertEquals(
            """{"backend":{"kind":"drive"},"pairing":"$code"}""",
            SyncConfigs.serialise(SyncConfig(SyncBackend.Drive, code)),
        )
    }

    @Test
    fun `a backend from a newer version reads as off and keeps the code`() {
        // Off is survivable — the person turns sync back on. Losing the pairing
        // code is not, and it is the one value in the row nobody can reissue.
        val c = SyncConfigs.parse("""{"backend":{"kind":"sftp"},"pairing":"$code"}""")
        assertTrue(c.backend is SyncBackend.Off, c.backend.toString())
        assertEquals(code, c.pairing)
    }

    @Test
    fun `correcting a password is the same folder`() {
        // The rule has to cut both ways, or every saved keystroke re-uploads the
        // whole database over a phone connection.
        assertTrue(
            SyncConfigs.sameTarget(
                SyncConfig(SyncBackend.WebDav("https://one/dav", "u", "wrng"), code),
                SyncConfig(SyncBackend.WebDav("https://one/dav", "u", "right"), code),
            ),
        )
    }

    @Test
    fun `a different address is a different folder`() {
        assertTrue(
            !SyncConfigs.sameTarget(
                SyncConfig(dav, code),
                SyncConfig(SyncBackend.WebDav("https://two/dav", "u", "p"), code),
            ),
        )
    }

    @Test
    fun `webdav to drive is a different folder`() {
        assertTrue(!SyncConfigs.sameTarget(SyncConfig(dav, code), SyncConfig(SyncBackend.Drive, code)))
    }

    @Test
    fun `a new pairing code is a different box, even at the same address`() {
        // The bucket id lives inside the code, so the folder may be the same
        // while the pile of files in it is one this key cannot open.
        assertTrue(
            !SyncConfigs.sameTarget(
                SyncConfig(SyncBackend.Drive, code),
                SyncConfig(SyncBackend.Drive, other),
            ),
        )
    }

    @Test
    fun `the settings boxes have an answer for every backend`() {
        // `when` without an else is what asked this question the moment Drive
        // was added, instead of leaving a screen that opens on a backend it
        // cannot describe. Drive has no address, username or password, so the
        // boxes are the same three empty ones as when sync is off — but the
        // pairing code stays, because it belongs to the data rather than to the
        // way the data travels.
        for (b in listOf(SyncBackend.Off, dav, SyncBackend.Drive)) {
            val f = SyncSetup.fieldsOf(SyncConfig(b, code))
            assertEquals(code, f.pairing, "the code should survive on $b")
        }
        val drive = SyncSetup.fieldsOf(SyncConfig(SyncBackend.Drive, code))
        assertEquals("", drive.baseUrl)
        assertEquals("", drive.username)
        assertEquals("", drive.password)
    }

    @Test
    fun `moving folder clears the cursor and refills the queue, keeping the name`() {
        val db = FakeDb()
        db.rows[SYNC_STATE_KEY] =
            """{"device":"d-abc","seq":9,"cursor":{"d-xyz":4},"pushedThrough":"2026-08-17T00:00:00.000Z"}"""

        runHere { SyncConfigs.forgetRemoteProgress(db) }

        val after = db.rows[SYNC_STATE_KEY]!!
        assertTrue("\"device\":\"d-abc\"" in after, after)
        // seq is not "how far through this folder", it is "the highest number I
        // have ever put on a file". Resetting it would write d-abc-1 a second
        // time, and if the old folder is ever used again there would be two
        // different files with one name.
        assertTrue("\"seq\":9" in after, after)
        assertTrue("\"cursor\":{}" in after, after)
        // The field that used to decide what got sent is gone from the blob
        // entirely, so a state written here must not carry it forward either.
        assertTrue("pushedThrough" !in after, after)

        // WHY THE QUEUE IS REFILLED HERE
        //
        // Clearing the cursor makes this device read the new folder from the
        // start. Nothing makes it WRITE to it: the outbox was emptied against
        // the old folder, one row at a time, as each was settled. Without the
        // reseed the first sync after a move reports success and sends nothing,
        // which is the desktop's `sent 0 out` against an empty Drive — on the
        // device with no screen to notice it on.
        val lowered = db.executed.indexOfFirst { "sync_outbox_state SET seeded = 0" in it }
        val seeded = db.executed.indexOfFirst { "INSERT INTO sync_outbox" in it }
        assertTrue(lowered >= 0, "the seed guard was never lowered, so seeding does nothing")
        assertTrue(seeded > lowered, "seeding ran before the guard was lowered")
    }

    @Test
    fun `unreadable state is left alone rather than half rewritten`() {
        val db = FakeDb()
        db.rows[SYNC_STATE_KEY] = "not json at all"
        runHere { SyncConfigs.forgetRemoteProgress(db) }
        // The next run replaces it wholesale anyway. Writing a partial state
        // over it would be inventing a device id.
        assertEquals("not json at all", db.rows[SYNC_STATE_KEY])
    }

    /** The two statements SyncConfigs uses against app_settings, and nothing else. */
    private class FakeDb : Db {
        val rows = mutableMapOf<String, String>()

        /** Every statement, in order. The settings writes are two-parameter and
         *  land in [rows]; the outbox reseed carries no parameters at all, and
         *  the earlier version of this fake read params[0] unconditionally and
         *  would now throw rather than fail. */
        val executed = mutableListOf<String>()

        override suspend fun execute(sql: String, params: List<SyncValue>) {
            executed += sql
            if (params.size < 2) return
            val key = (params[0] as SyncValue.Text).value
            rows[key] = (params[1] as SyncValue.Text).value
        }

        override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
            val key = (params.firstOrNull() as? SyncValue.Text)?.value ?: return emptyList()
            val v = rows[key] ?: return emptyList()
            return listOf(mapOf("value" to SyncValue.Text(v)))
        }
    }
}