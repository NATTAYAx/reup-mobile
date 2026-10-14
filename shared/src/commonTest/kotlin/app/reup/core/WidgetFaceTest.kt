package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ─── WidgetFaceTest ──────────────────────────────────────────────────────────
//
// In commonTest for the reason HorizonTest is: nothing here reads a file or the
// clock. Every case is on one Wednesday in Bangkok, 14 October 2026, at ten in
// the morning, unless it says otherwise.

class WidgetFaceTest {

    private val bkk = TimeZone.of("Asia/Bangkok")

    /** "2026-10-16", "04:00" on the wall in Bangkok. */
    private fun at(date: String, clock: String): Instant = Instant.parse("${date}T$clock:00+07:00")

    private val now = at("2026-10-14", "10:00")

    private fun daily(id: String, fireAt: Instant?, done: Boolean = false, doneToday: Boolean = false, paused: Boolean = false) =
        HomeRow(id = id, name = id, fireAt = fireAt, silent = false, done = done, resetAt = fireAt, doneToday = doneToday, paused = paused)

    private fun weekly(id: String, fireAt: Instant, resetAt: Instant = fireAt) =
        HomeRow(id = id, name = id, fireAt = fireAt, silent = false, done = false, resetAt = resetAt, longCycle = true)

    private fun once(id: String, fireAt: Instant, resetAt: Instant = fireAt) =
        HomeRow(id = id, name = id, fireAt = fireAt, silent = false, done = false, resetAt = resetAt, recurring = false)

    // ── what is next, and what comes after it ───────────────────────────────

    @Test
    fun `the card is the Today tab's card, and the rest of the day follows it`() {
        val rows = listOf(
            daily("a", at("2026-10-14", "13:00")),
            daily("b", at("2026-10-14", "18:00")),
            daily("c", at("2026-10-15", "04:00")),
            weekly("d", at("2026-10-19", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(todayFace(rows, now, bkk).next?.id, face.next?.id)
        assertEquals(WidgetLine("a", "a", "13:00"), face.next)
        assertEquals(null, face.calm)
        // A daily resetting at four tomorrow is tonight's work: within a day.
        assertEquals(listOf(WidgetLine("b", "b", "18:00"), WidgetLine("c", "c", "พรุ่งนี้ 04:00")), face.soon)
        assertEquals(listOf(WidgetLine("d", "d", "หมด จ. 05:00")), face.later)
        assertEquals(0, face.done)
        assertEquals(3, face.load)
    }

    @Test
    fun `a calm day names tomorrow's first round once, not twice`() {
        val rows = listOf(
            daily("x", at("2026-10-15", "04:00"), done = true, doneToday = true),
            daily("y", at("2026-10-15", "04:00")),
            weekly("z", at("2026-10-17", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(null, face.next)
        assertEquals("วันนี้ทำครบแล้ว", face.calm)
        assertEquals(WidgetLine("y", "y", "พรุ่งนี้ 04:00"), face.calmNext)
        assertEquals(emptyList(), face.soon, "the round the card names is not listed under it")
        assertEquals(listOf("z"), face.later.map { it.id })
        // What was ticked, and the round still due within the day.
        assertEquals(1, face.done)
        assertEquals(2, face.load)
    }

    @Test
    fun `a day with nothing queued says so and draws no bar`() {
        val face = widgetFace(emptyList(), now, bkk, null)
        assertEquals("วันนี้ไม่มีอะไรรีเซ็ต", face.calm)
        assertEquals(null, face.calmNext)
        assertEquals(0, face.load)
        assertTrue(face.soon.isEmpty() && face.later.isEmpty())
    }

    // ── further out ─────────────────────────────────────────────────────────

    @Test
    fun `further out is in the order things run out, not the order they ring`() {
        val rows = listOf(
            daily("t", at("2026-10-15", "04:00")),
            // Rings Friday at five, a day ahead of a Saturday deadline.
            weekly("p", at("2026-10-16", "05:00"), resetAt = at("2026-10-17", "05:00")),
            // Rings later on Friday, and runs out first: 46 hours from now.
            once("q", at("2026-10-16", "07:00"), resetAt = at("2026-10-16", "08:00")),
            weekly("r", at("2026-10-22", "05:00")),
            // On pause: it asked not to be reminded.
            daily("s", at("2026-10-20", "04:00"), paused = true),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(
            listOf(
                WidgetLine("q", "q", "หมด ศ. 08:00", warn = true),
                WidgetLine("p", "p", "หมด ส. 05:00", warn = false),
                WidgetLine("r", "r", "หมด 22 ต.ค.", warn = false),
            ),
            face.later,
        )
        assertEquals("t", face.calmNext?.id)
        assertEquals(emptyList(), face.soon)
    }

    @Test
    fun `a daily is never amber, and what is not a daily is, near its end`() {
        val rows = listOf(
            daily("n", at("2026-10-14", "11:00")),
            daily("d", at("2026-10-14", "12:00")),
            once("o", at("2026-10-14", "15:00")),
            weekly("w", at("2026-10-15", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(WidgetLine("n", "n", "11:00", warn = false), face.next)
        assertEquals(
            listOf(
                WidgetLine("d", "d", "12:00", warn = false),
                WidgetLine("o", "o", "15:00", warn = true),
                WidgetLine("w", "w", "พรุ่งนี้ 05:00", warn = true),
            ),
            face.soon,
        )
    }

    @Test
    fun `what is long is a week, two, or every few days`() {
        assertTrue(longCycle(ResetSpec(ResetType.WEEKLY, resetDay = 1)))
        assertTrue(longCycle(ResetSpec(ResetType.BIWEEKLY, anchorDate = "2026-10-01")))
        assertTrue(longCycle(ResetSpec(ResetType.CUSTOM_DAYS, resetIntervalDays = 3)))
        assertFalse(longCycle(ResetSpec(ResetType.CUSTOM_DAYS, resetIntervalDays = 1)), "every 1 day is a daily")
        assertFalse(longCycle(ResetSpec(ResetType.CUSTOM_DAYS)))
        assertFalse(longCycle(ResetSpec(ResetType.DAILY)))
        assertFalse(longCycle(ResetSpec(ResetType.EVENT_WINDOW, eventEnd = "2026-10-30")))
        assertFalse(longCycle(ResetSpec(ResetType.SPECIFIC_DATE, specificDate = "2026-10-30")))
    }

    // ── how a time is said ──────────────────────────────────────────────────

    @Test
    fun `a time is a clock today, a weekday this week, a date past it`() {
        assertEquals("18:00", shortWhen(at("2026-10-14", "18:00"), now, bkk))
        assertEquals("พรุ่งนี้ 04:00", shortWhen(at("2026-10-15", "04:00"), now, bkk))
        assertEquals("ศ. 04:00", shortWhen(at("2026-10-16", "04:00"), now, bkk))
        assertEquals("อ. 04:00", shortWhen(at("2026-10-20", "04:00"), now, bkk))
        assertEquals("21 ต.ค.", shortWhen(at("2026-10-21", "04:00"), now, bkk))
        assertEquals("พุธ 14 ต.ค.", shortDate(now, bkk))
    }

    @Test
    fun `half past midnight is tomorrow, though UTC still says today`() {
        val late = at("2026-10-14", "23:00")
        assertEquals("พรุ่งนี้ 00:30", shortWhen(at("2026-10-15", "00:30"), late, bkk))
    }

    // ── when it is drawn again ──────────────────────────────────────────────

    @Test
    fun `with nothing on it, the widget changes at midnight, or when quiet hours start`() {
        assertEquals(at("2026-10-15", "00:00"), widgetNextChange(emptyList(), now, bkk, null))
        assertEquals(at("2026-10-15", "00:00"), nextMidnight(now, bkk))
        assertEquals(at("2026-10-14", "23:00"), widgetNextChange(emptyList(), now, bkk, QuietHours("23:00", "08:00")))
    }

    @Test
    fun `a round passing, or a tick running out, is a change`() {
        assertEquals(at("2026-10-14", "13:00"), widgetNextChange(listOf(daily("a", at("2026-10-14", "13:00"))), now, bkk, null))
        val ticked = daily("b", at("2026-10-14", "15:00"), done = true, doneToday = true)
        assertEquals(at("2026-10-14", "15:00"), widgetNextChange(listOf(ticked), now, bkk, null))
    }

    @Test
    fun `a weekly turning amber is a change, two days before it runs out`() {
        // Runs out on Friday at noon: amber from Wednesday at noon, joins the
        // day's list on Thursday at noon.
        val w = weekly("w", at("2026-10-16", "12:00"))
        assertEquals(at("2026-10-14", "12:00"), widgetNextChange(listOf(w), now, bkk, null))
        val later = at("2026-10-14", "12:30")
        assertEquals(at("2026-10-15", "00:00"), widgetNextChange(listOf(w), later, bkk, null))
        val thursday = at("2026-10-15", "09:00")
        assertEquals(at("2026-10-15", "12:00"), widgetNextChange(listOf(w), thursday, bkk, null))
    }

    @Test
    fun `nothing wakes the widget twice inside a minute`() {
        val edge = daily("e", Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 10_000))
        assertEquals(
            Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + 60_000),
            widgetNextChange(listOf(edge), now, bkk, null),
        )
    }

    // ── money, when it has been asked for ───────────────────────────────────

    @Test
    fun `the month's money in one line`() {
        assertEquals("ต.ค. จ่าย ฿1,240 · รับ ฿30,000", widgetMoneyLine(1240.0, 30000.0, "THB", 10))
        assertEquals("ต.ค. จ่าย ฿1,240", widgetMoneyLine(1240.0, 0.0, "THB", 10))
        assertEquals("ม.ค. รับ $12.50", widgetMoneyLine(0.0, 12.5, "USD", 1))
        assertEquals("ต.ค. ยังไม่มีรายการ", widgetMoneyLine(0.0, 0.0, "THB", 10))
    }
}
