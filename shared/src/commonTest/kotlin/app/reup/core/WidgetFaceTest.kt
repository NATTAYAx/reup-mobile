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
    fun `the card is the soonest thing due, and the rest of the day follows it`() {
        val rows = listOf(
            daily("a", at("2026-10-14", "13:00")),
            daily("b", at("2026-10-14", "18:00")),
            daily("c", at("2026-10-15", "04:00")),
            weekly("d", at("2026-10-19", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(WidgetLine("a", "a", "13:00"), face.next)
        assertEquals(null, face.calm)
        // A daily resetting at four tomorrow is tonight's work: within a day.
        assertEquals(listOf(WidgetLine("b", "b", "18:00"), WidgetLine("c", "c", "พรุ่งนี้ 04:00")), face.soon)
        assertEquals(listOf(WidgetLine("d", "d", "หมด จ. 05:00")), face.later)
        assertEquals(0, face.done)
        assertEquals(3, face.load)
    }

    @Test
    fun `at night the first round after midnight is next, not a calm day`() {
        // Twenty past eight: everything left resets after midnight. The card
        // used to say nothing resets today while the bar counted these same
        // rounds as today's.
        val evening = at("2026-10-14", "20:20")
        val rows = listOf(
            daily("star", at("2026-10-15", "03:00")),
            daily("fgo", at("2026-10-15", "04:00")),
            daily("rumble", at("2026-10-15", "10:00")),
            weekly("raid", at("2026-10-19", "05:00")),
        )
        val face = widgetFace(rows, evening, bkk, null)

        assertEquals(WidgetLine("star", "star", "พรุ่งนี้ 03:00"), face.next)
        assertEquals(null, face.calm)
        assertEquals(listOf("fgo", "rumble"), face.soon.map { it.id })
        assertEquals(listOf("raid"), face.later.map { it.id })
        assertEquals(0, face.done)
        assertEquals(3, face.load)
    }

    @Test
    fun `a calm day names the round after it once, not twice`() {
        val rows = listOf(
            daily("x", at("2026-10-15", "04:00"), done = true, doneToday = true),
            weekly("z", at("2026-10-17", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)

        assertEquals(null, face.next)
        assertEquals("วันนี้ทำครบแล้ว", face.calm)
        assertEquals(WidgetLine("z", "z", "ส. 05:00", warn = false), face.calmNext)
        assertEquals(emptyList(), face.soon)
        assertEquals(emptyList(), face.later, "the round the card names is not listed under it")
        assertEquals(1, face.done)
        assertEquals(1, face.load)
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
        // Four tomorrow morning is within the day: the card, not a calm day.
        assertEquals("t", face.next?.id)
        assertEquals(null, face.calmNext)
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

    // ── the bar's words ─────────────────────────────────────────────────────

    @Test
    fun `the bar says what the day holds until something is ticked`() {
        assertEquals(emptyList(), widgetTally(0, 0))
        assertEquals(listOf("7 อย่าง", "7 อย่างใน 24 ชม.", "7 อย่างภายใน 24 ชั่วโมง"), widgetTally(0, 7))
        assertEquals(listOf("2/7", "ทำแล้ว 2/7", "ทำแล้ว 2 จาก 7"), widgetTally(2, 7))
    }

    // ── a line for each moment ──────────────────────────────────────────────

    @Test
    fun `rounds due at one moment are one line, and the day is said once down the list`() {
        val evening = at("2026-10-14", "20:00")
        val rows = listOf(
            daily("Star Rail", at("2026-10-15", "03:00")),
            daily("FGO", at("2026-10-15", "04:00")),
            daily("a", at("2026-10-15", "04:00")),
            daily("b", at("2026-10-15", "04:00")),
            daily("c", at("2026-10-15", "05:00")),
            weekly("w", at("2026-10-15", "05:00")),
        )
        val s = widgetSchedule(widgetFace(rows, evening, bkk, null))
        assertEquals(WidgetGroup(listOf("Star Rail"), "พรุ่งนี้ 03:00", warn = false), s.head)
        assertEquals(
            listOf(
                WidgetGroup(listOf("FGO", "a", "b"), "พรุ่งนี้ 04:00", warn = false),
                // Under พรุ่งนี้ 04:00, 05:00 is tomorrow too. The weekly's last
                // two days turn the whole line amber.
                WidgetGroup(listOf("c", "w"), "05:00", warn = true),
            ),
            s.soon,
        )
        assertEquals(emptyList(), s.later)
    }

    @Test
    fun `everything due at the soonest moment is on the card`() {
        val rows = listOf(
            daily("a", at("2026-10-14", "13:00")),
            daily("b", at("2026-10-14", "13:00")),
            daily("c", at("2026-10-14", "18:00")),
        )
        val s = widgetSchedule(widgetFace(rows, now, bkk, null))
        assertEquals(listOf("a", "b"), s.head!!.names)
        assertEquals("13:00", s.head!!.time)
        assertEquals(listOf(WidgetGroup(listOf("c"), "18:00", warn = false)), s.soon)
    }

    @Test
    fun `further out is gathered too, but every line keeps its day`() {
        val rows = listOf(
            daily("d", at("2026-10-14", "18:00")),
            weekly("x", at("2026-10-16", "04:00")),
            weekly("y", at("2026-10-16", "04:00")),
            weekly("z", at("2026-10-18", "04:00")),
            weekly("v", at("2026-10-16", "05:00")),
        )
        val later = widgetSchedule(widgetFace(rows, now, bkk, null)).later
        assertEquals(
            listOf(
                WidgetGroup(listOf("x", "y"), "หมด ศ. 04:00", warn = true),
                WidgetGroup(listOf("v"), "หมด ศ. 05:00", warn = true),
                WidgetGroup(listOf("z"), "หมด อา. 04:00", warn = false),
            ),
            later,
        )
    }

    @Test
    fun `a calm day has no card to gather onto`() {
        val rows = listOf(daily("a", at("2026-10-14", "08:00"), done = true, doneToday = true))
        val s = widgetSchedule(widgetFace(rows, now, bkk, null))
        assertEquals(null, s.head)
        assertEquals(emptyList(), s.soon)
    }

    // ── the list, in the board's order ──────────────────────────────────────

    @Test
    fun `the list is the rest of the day, a heading for tomorrow, the bar, then further out`() {
        val evening = at("2026-10-14", "20:20")
        val rows = listOf(
            daily("fgo", at("2026-10-15", "04:00")),
            daily("test", at("2026-10-15", "04:00")),
            daily("late", at("2026-10-14", "22:00")),
            daily("meds", at("2026-10-15", "09:00")),
            daily("rumble", at("2026-10-15", "10:00")),
            weekly("plants", at("2026-10-18", "18:00")),
            weekly("raid", at("2026-10-19", "05:00")),
        )
        val face = widgetFace(rows, evening, bkk, null)
        val items = widgetItems(face, "ทำแล้ว 0 จาก 5", "ต.ค. จ่าย ฿1,240")

        assertEquals(
            listOf(
                WidgetItem.Heading("พรุ่งนี้"),
                WidgetItem.Row("04:00", listOf("fgo", "test"), warn = false, later = false),
                WidgetItem.Row("09:00", listOf("meds"), warn = false, later = false),
                WidgetItem.Row("10:00", listOf("rumble"), warn = false, later = false),
                WidgetItem.Tally("ทำแล้ว 0 จาก 5", 0, 5),
                WidgetItem.Heading(WIDGET_LATER_HEADING),
                WidgetItem.Row("หมด อา. 18:00", listOf("plants"), warn = false, later = true),
                WidgetItem.Row("หมด จ. 05:00", listOf("raid"), warn = false, later = true),
                WidgetItem.Money("ต.ค. จ่าย ฿1,240"),
            ),
            items,
        )
        // The card took 22:00, tonight's; the list starts after it.
        assertEquals(WidgetLine("late", "late", "22:00"), face.next)
    }

    @Test
    fun `rows today are bare clocks with no heading, and the card's moment is left out`() {
        val rows = listOf(
            daily("a", at("2026-10-14", "13:00")),
            daily("b", at("2026-10-14", "13:00")),
            daily("c", at("2026-10-14", "18:00")),
            daily("d", at("2026-10-15", "04:00")),
        )
        val items = widgetItems(widgetFace(rows, now, bkk, null), null, null)
        assertEquals(
            listOf(
                WidgetItem.Row("18:00", listOf("c"), warn = false, later = false),
                WidgetItem.Heading("พรุ่งนี้"),
                WidgetItem.Row("04:00", listOf("d"), warn = false, later = false),
            ),
            items,
        )
    }

    @Test
    fun `a calm day lists only what is further out`() {
        val rows = listOf(
            daily("x", at("2026-10-14", "08:00"), done = true, doneToday = true),
            weekly("w", at("2026-10-19", "05:00")),
        )
        val face = widgetFace(rows, now, bkk, null)
        val items = widgetItems(face, null, null)
        // On a calm day the card names the first round after it, so it is not listed twice.
        assertEquals(emptyList(), items)
        assertEquals("w", face.calmNext?.id)
    }

    @Test
    fun `rounds due at one moment are named oldest task first, as the board names them`() {
        val four = at("2026-10-15", "04:00")
        val rows = listOf(
            daily("new", four).copy(createdAt = "2026-10-10 09:00:00"),
            daily("old", four).copy(createdAt = "2026-08-01 12:00:00"),
            daily("mid", four).copy(createdAt = "2026-09-15 08:30:00"),
        )
        val s = widgetSchedule(widgetFace(rows, now, bkk, null))
        assertEquals(listOf("old", "mid", "new"), s.head?.names)
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
