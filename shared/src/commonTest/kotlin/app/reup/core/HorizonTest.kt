package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── HorizonTest ─────────────────────────────────────────────────────────────
//
// Unlike ScheduleVectorsTest, there is no desktop implementation to compare
// against — this logic is new. So these assert properties and worked examples
// instead: ordering, bounds, and a handful of scenarios where the right answer
// can be worked out by hand.
//
// In commonTest rather than jvmTest because it reads no files, which means iOS
// runs it too once there is a machine to run it on.

class HorizonTest {

    private val bkk = TimeZone.of("Asia/Bangkok")

    /** 11 Aug 2026, 10:00 Bangkok. A Tuesday, mid-morning, nothing special. */
    private val now = Instant.parse("2026-08-11T03:00:00Z")

    private val daily04 = ScheduledTask("daily04", ResetSpec(ResetType.DAILY, resetTime = "04:00"))
    private val weeklyMon = ScheduledTask(
        "weeklyMon",
        ResetSpec(ResetType.WEEKLY, resetDay = 1, resetTime = "05:00"),
    )
    private val every3d = ScheduledTask(
        "every3d",
        ResetSpec(
            ResetType.CUSTOM_DAYS,
            anchorDate = "2026-08-01",
            resetIntervalDays = 3,
            resetTime = "09:00",
        ),
    )
    private val tokyo04 = ScheduledTask(
        "tokyo04",
        ResetSpec(ResetType.DAILY, resetTime = "04:00", timeZone = "Asia/Tokyo"),
    )

    private val all = listOf(daily04, weeklyMon, every3d, tokyo04)

    @Test
    fun `returns nothing when there is nothing to return`() {
        assertTrue(horizon(emptyList(), now, bkk).isEmpty())
        assertTrue(horizon(all, now, bkk, limit = 0).isEmpty())
    }

    @Test
    fun `alarms come back in order, in the future, and within the limit`() {
        val result = horizon(all, now, bkk, limit = 50)

        assertEquals(50, result.size)
        assertEquals(result.map { it.fireAt }.sorted(), result.map { it.fireAt })
        assertTrue(result.all { it.fireAt > now })
    }

    @Test
    fun `a pinned zone stays pinned`() {
        // 04:00 Tokyo is 02:00 Bangkok, so the Tokyo task fires two hours
        // before the local one every day. If this ever reads 04:00, the task's
        // own zone is being ignored and every cross-region game is wrong.
        val first = horizon(listOf(tokyo04), now, bkk, limit = 1).single()
        assertEquals(Instant.parse("2026-08-11T19:00:00Z"), first.fireAt)
    }

    @Test
    fun `lead time moves the alarm earlier but not the reset`() {
        val warned = daily04.copy(notifyBeforeMin = 90)
        val first = horizon(listOf(warned), now, bkk, limit = 1).single()

        assertEquals(Instant.parse("2026-08-11T19:30:00Z"), first.fireAt) // 02:30 local
        assertEquals(Instant.parse("2026-08-11T21:00:00Z"), first.resetAt) // 04:00 local
        assertTrue(first.fireAt < first.resetAt)
    }

    @Test
    fun `quiet hours push alarms to the end of the window`() {
        val result = horizon(all, now, bkk, limit = 8, quiet = QuietHours("23:00", "08:00"))

        // Everything that would have rung between 23:00 and 08:00 now rings at
        // 08:00 — and, importantly, still rings. An earlier version dropped
        // these entirely, which meant a daily 04:00 task notified never and
        // said nothing about why.
        val shifted = result.filter { it.shiftedOutOfQuiet }
        assertTrue(shifted.isNotEmpty())
        assertTrue(shifted.all { it.fireAt > it.resetAt })

        for (alarm in shifted) {
            val wall = wallClock(alarm.fireAt.toEpochMilliseconds(), bkk)
            assertEquals(8, wall.h)
            assertEquals(0, wall.mi)
        }

        // The 09:00 task is outside the window and must be left alone.
        assertTrue(result.any { it.taskId == "every3d" && !it.shiftedOutOfQuiet })
    }

    @Test
    fun `quiet hours produce ties, and ties are ordered deterministically`() {
        val a = horizon(all, now, bkk, limit = 20, quiet = QuietHours("23:00", "08:00"))
        val b = horizon(all.reversed(), now, bkk, limit = 20, quiet = QuietHours("23:00", "08:00"))

        // Same set of tasks in a different order must give an identical queue,
        // or "has the schedule changed since last time" becomes unanswerable
        // and every recompute looks like a change.
        assertEquals(a, b)

        val collisions = a.groupBy { it.fireAt }.filterValues { it.size > 1 }
        assertTrue(collisions.isNotEmpty(), "expected several tasks to land on the same instant")
    }

    @Test
    fun `equal quiet bounds mean no quiet hours at all`() {
        val withQuiet = horizon(all, now, bkk, limit = 10, quiet = QuietHours("08:00", "08:00"))
        val without = horizon(all, now, bkk, limit = 10)
        assertEquals(without, withQuiet)
    }

    @Test
    fun `a paused task contributes nothing before it wakes`() {
        val until = Instant.parse("2026-08-15T17:00:00Z") // 16 Aug 00:00 local
        val paused = daily04.copy(pausedUntil = until)

        val result = horizon(listOf(paused), now, bkk, limit = 5)
        assertTrue(result.all { it.fireAt >= until })
    }

    @Test
    fun `a date that has already passed yields nothing and terminates`() {
        val old = ScheduledTask(
            "old",
            ResetSpec(ResetType.SPECIFIC_DATE, specificDate = "2020-01-01", resetTime = "10:00"),
        )
        // The real assertion is that this returns at all. The first version of
        // horizon() walked the calendar forever here.
        assertTrue(horizon(listOf(old), now, bkk, limit = 5).isEmpty())
    }

    @Test
    fun `a task whose every occurrence is skipped still terminates`() {
        // A lead time longer than the gap between occurrences means every
        // candidate lands before `now` for a while. Nothing should hang.
        val absurd = daily04.copy(notifyBeforeMin = 60 * 24 * 400)
        val result = horizon(listOf(absurd), now, bkk, limit = 5)
        assertTrue(result.all { it.fireAt > now })
    }

    @Test
    fun `a frequent task cannot crowd out a rare one`() {
        val rare = ScheduledTask(
            "rare",
            ResetSpec(
                ResetType.CUSTOM_DAYS,
                anchorDate = "2026-08-01",
                resetIntervalDays = 30,
                resetTime = "12:00",
            ),
        )
        val result = horizon(listOf(daily04, rare), now, bkk, limit = 40)

        // 40 slots covers well past the rare task's next occurrence, so taking
        // the globally soonest must include it. If a per-task quota were ever
        // added, this is what would break.
        assertTrue(result.any { it.taskId == "rare" })
    }
}