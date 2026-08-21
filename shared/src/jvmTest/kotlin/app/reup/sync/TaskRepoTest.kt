// shared/src/jvmTest/kotlin/app/reup/sync/TaskRepoTest.kt
package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── TaskRepoTest.kt — the column names, checked against the real schema ─────
//
// WHY THIS FILE EXISTS
// --------------------
// TaskRepo was the only file in the sync package with no test, and it was the
// one that broke on the phone. That is not a coincidence.
//
// Everything else in this package is held by either the compiler or a vector
// file. TaskRepo is held by neither. Its column names live inside string
// literals, and a string literal agrees with a database schema only for as long
// as somebody remembers that it should. On this build nobody did: the query
// asked for `notify_before_min`, a column that exists in no schema on either
// side, and SQLite refused to compile the statement. The result was the task
// list reading as "cannot open" on a build where :shared:jvmTest was green and
// :app:assembleDebug succeeded — which is exactly the shape of failure that
// green tests are supposed to rule out.
//
// So this file does the one thing the compiler cannot: it reads SCHEMA_SQL, the
// same generated constant the phone actually runs at startup, and checks that
// every column name TaskRepo depends on is in it.
//
// No SQLite, no Android, no network, no new dependency. String comparison,
// which is all this particular failure ever was.

class TaskRepoTest {

    /**
     * Every column the query and the mapper name, other than the pending ones
     * below. A missing name here does not degrade the task list, it stops the
     * list loading at all, so this is the list that matters.
     */
    private val required: List<String> = listOf(
        "uid",
        "name",
        "reset_type",
        "reset_time",
        "reset_day",
        "anchor_date",
        "reset_interval_days",
        "event_end",
        "specific_date",
        "time_zone",
        "paused_until",
        "is_active",
        "deleted",
    )

    /**
     * Columns the engine supports but the desktop has never shipped.
     *
     * `notifyBeforeMin` is a real field on ScheduledTask with real tests in
     * HorizonTest, and the mapper reads it, so the day the column is added to
     * schema.sql it starts working with no code change here. Until then it
     * reads as absent, which the engine treats as "ring at the reset" — the
     * behaviour the desktop has always had anyway.
     *
     * Naming it here rather than deleting the read is the difference between a
     * gap that is written down and a gap somebody has to rediscover from a
     * screenshot. When the column ships, move it up into [required].
     */
    private val pending: List<String> = listOf(
        "notify_before_min",
    )

    @Test
    fun `every column the query depends on exists in the schema`() {
        val missing = required.filter { !SCHEMA_SQL.contains(it) }
        assertTrue(
            missing.isEmpty(),
            "not in schema.sql: $missing — the task list will fail to load, not " +
                    "degrade. Either add the column on the desktop and rerun " +
                    "`pnpm gen:store-vectors`, or stop reading it here.",
        )
    }

    @Test
    fun `pending columns really are still absent`() {
        // If one of these quietly appears, the note above has gone stale, and a
        // column that works should not be sitting in a list of columns that
        // supposedly do not exist.
        val arrived = pending.filter { SCHEMA_SQL.contains(it) }
        assertEquals(
            emptyList<String>(), arrived,
            "these shipped on the desktop — move them into `required`",
        )
    }

    @Test
    fun `the query selects everything rather than naming columns`() {
        // The named-column version is what broke. An edit that tidies this back
        // into a column list reintroduces one exact failure mode: a single
        // wrong name takes down the whole read, while SQLite is still compiling
        // the statement, before the defensive mapper can absorb anything.
        assertTrue(
            ACTIVE_TASKS_SQL.contains("SELECT * FROM tasks"),
            "ACTIVE_TASKS_SQL should select *: $ACTIVE_TASKS_SQL",
        )
    }

    @Test
    fun `the query still excludes the bin and tombstones`() {
        // `*` widened which columns come back. It must not have widened which
        // rows do. A tombstone reaching this list becomes an alarm for a task
        // that was deleted on the other device.
        assertTrue(ACTIVE_TASKS_SQL.contains("is_active = 1"), ACTIVE_TASKS_SQL)
        assertTrue(ACTIVE_TASKS_SQL.contains("deleted = 0"), ACTIVE_TASKS_SQL)
        assertTrue(ACTIVE_TASKS_SQL.contains("uid IS NOT NULL"), ACTIVE_TASKS_SQL)
    }

    @Test
    fun `a row missing most columns maps rather than throwing`() {
        // The whole argument for `*` is that the mapper survives gaps. Prove it
        // on the narrowest row that should still be a task, rather than assume.
        val bare: DbRow = mapOf(
            "uid" to SyncValue.Text("u-1"),
            "reset_type" to SyncValue.Text("daily"),
        )

        val t = scheduledTaskFrom(bare) ?: error("uid + reset_type should be enough")
        assertEquals("u-1", t.id)
        assertEquals("daily", t.spec.resetType)
        assertEquals(null, t.notifyBeforeMin)
        assertEquals(null, t.pausedUntil)
        assertEquals("งาน", labelFrom(bare))
    }

    @Test
    fun `a row with no uid or no reset type is dropped, not guessed`() {
        assertEquals(null, scheduledTaskFrom(mapOf("reset_type" to SyncValue.Text("daily"))))
        assertEquals(null, scheduledTaskFrom(mapOf("uid" to SyncValue.Text("u-1"))))
    }

    @Test
    fun `an unreadable paused_until costs the pause, not the task`() {
        // A newer desktop could write a format this build does not know. The
        // task should still be scheduled; it simply is not treated as snoozed.
        val row: DbRow = mapOf(
            "uid" to SyncValue.Text("u-2"),
            "reset_type" to SyncValue.Text("daily"),
            "paused_until" to SyncValue.Text("next tuesday-ish"),
        )
        val t = scheduledTaskFrom(row) ?: error("an unreadable pause should not drop the task")
        assertEquals(null, t.pausedUntil)
    }

    @Test
    fun `a numeric column arriving as text still reads`() {
        // SQLite has affinity, drivers differ, and a synced row is decoded from
        // JSON. reset_day can legitimately arrive either way.
        val asNum: DbRow = mapOf(
            "uid" to SyncValue.Text("u-3"),
            "reset_type" to SyncValue.Text("weekly"),
            "reset_day" to SyncValue.Num(1.0),
        )
        val asText: DbRow = mapOf(
            "uid" to SyncValue.Text("u-4"),
            "reset_type" to SyncValue.Text("weekly"),
            "reset_day" to SyncValue.Text("1"),
        )
        assertEquals(1, (scheduledTaskFrom(asNum) ?: error("num")).spec.resetDay)
        assertEquals(1, (scheduledTaskFrom(asText) ?: error("text")).spec.resetDay)
    }

    // ── the write half ──────────────────────────────────────────────────────

    @Test
    fun `a tick lasts until the next reset, or the end of the day for a one-off`() {
        val next = "2026-08-19T04:00:00.000Z"
        val endOfDay = "2026-08-18T16:59:59.999Z"

        for (t in listOf("daily", "weekly", "biweekly", "custom_days")) {
            assertEquals(next, doneUntil(t, next, endOfDay), t)
        }
        for (t in listOf("one_time", "event_window", "specific_date")) {
            assertEquals(endOfDay, doneUntil(t, next, endOfDay), t)
        }
    }

    @Test
    fun `a recurring task with no next reset is not ticked at all`() {
        // No next reset means the engine could not schedule it. Falling back to
        // the end of the day would write a boundary no other device can read
        // the same way, and this column is compared as a string everywhere.
        assertEquals(null, doneUntil("daily", null, "2026-08-18T16:59:59.999Z"))
        // A one-off does not depend on it, so it is unaffected.
        assertEquals(
            "2026-08-18T16:59:59.999Z",
            doneUntil("specific_date", null, "2026-08-18T16:59:59.999Z"),
        )
    }

    @Test
    fun `a type this build has never heard of is treated as a one-off`() {
        // A newer desktop can invent a reset type. Treating it as recurring
        // would mean writing null and silently doing nothing when the button is
        // pressed; treating it as a one-off marks it done for the day, which is
        // visible, reversible and wrong in a way a person can see.
        assertEquals("2026-08-18T16:59:59.999Z", doneUntil("lunar", null, "2026-08-18T16:59:59.999Z"))
    }

    @Test
    fun `done means done until later than now, and nothing else`() {
        val now = "2026-08-18T03:00:00.000Z"
        assertTrue(isDoneNow("2026-08-19T04:00:00.000Z", now))
        assertTrue(!isDoneNow("2026-08-18T02:59:59.999Z", now), "an expired tick is not a tick")
        assertTrue(!isDoneNow(null, now))
        assertTrue(!isDoneNow("", now))
        // Anything not written by this app reads as not done, so the task stays
        // on the list rather than vanishing on a value nobody can interpret.
        assertTrue(!isDoneNow("18/08/2026", now))
    }

    @Test
    fun `the tick clears the easing in the same statement`() {
        // completed_until and missed_streak are two of the three fields merge
        // moves as a unit. Writing one without the other produces a row that
        // existed nowhere: completed, yet still carrying the missed streak from
        // before it was completed.
        assertTrue("completed_until = ?" in MARK_DONE_SQL)
        assertTrue("missed_streak = 0" in MARK_DONE_SQL)
        assertTrue("WHERE uid = ?" in MARK_DONE_SQL, "id is local; uid is the only shared name")
        assertTrue("updated_at" !in MARK_DONE_SQL, "the trigger stamps it, from the shared clock")

        assertTrue("completed_until = NULL" in CLEAR_DONE_SQL)
        assertTrue("missed_streak" !in CLEAR_DONE_SQL, "the streak was cleared by a real completion")
    }

    @Test
    fun `a timestamp is spelled exactly the way the desktop spells it`() {
        // The expected strings are what `new Date(ms).toISOString()` printed in
        // node, not what looked right here. Milliseconds are always three
        // digits, including when they are zero, which is the whole point: the
        // shortest-form spelling sorts AFTER the padded one because Z beats the
        // dot, so a tick from this device would read as later than the same
        // instant from the desktop.
        assertEquals("2026-08-19T04:00:00.000Z", isoMillis(1787112000000L))
        assertEquals("2026-08-18T16:59:59.999Z", isoMillis(1787072399999L))
        assertEquals("1970-01-01T00:00:00.000Z", isoMillis(0L))
        assertEquals("2000-02-29T12:34:56.007Z", isoMillis(951827696007L), "a leap day")
        assertEquals("2026-12-31T23:59:59.999Z", isoMillis(1798761599999L), "the last millisecond")
        assertEquals("2024-03-01T00:00:00.000Z", isoMillis(1709251200000L), "the day after one")
    }

    @Test
    fun `the padded spelling is what makes the comparison honest`() {
        // Stated as a test rather than only as a comment, because this is the
        // property, not the formatting: two devices writing the same instant
        // must produce strings that compare equal, since comparing is all the
        // merge rule for completion does.
        val instant = 1787112000000L
        assertEquals("2026-08-19T04:00:00.000Z", isoMillis(instant))
        assertTrue(
            "2026-08-19T04:00:00Z" > isoMillis(instant),
            "if this ever stops being true the short spelling has become harmless",
        )
    }
}