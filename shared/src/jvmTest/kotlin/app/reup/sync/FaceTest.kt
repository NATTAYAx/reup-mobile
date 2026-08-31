package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

// ─── FaceTest ────────────────────────────────────────────────────────────────
//
// In commonTest for the same reason HorizonTest is: nothing here reads a file
// or asks the clock what time it is, so iOS can run it too the day there is a
// machine to run it on.
//
// The two that matter most are the midnight cases. Every one of these functions
// takes an Instant and a TimeZone and has to answer in the zone, and the way
// that goes wrong is always the same: doing the subtraction in UTC and getting
// a day boundary seven hours late. Bangkok is +7, so 23:00 local is 16:00 UTC
// and the following local midnight is still today in UTC. That is the same
// shape as the bug the 3,440 schedule vectors caught, which is why it gets its
// own test here rather than being trusted.

class FaceTest {

    private val bkk = TimeZone.of("Asia/Bangkok")

    /** Sunday 30 August 2026, 10:00 in Bangkok. */
    private val now = Instant.parse("2026-08-30T03:00:00Z")

    private fun plus(seconds: Long): Instant =
        Instant.fromEpochSeconds(now.epochSeconds + seconds)

    // ── the date under the app's name ────────────────────────────────────────

    @Test
    fun `the header names the day in Thai`() {
        assertEquals("วันอาทิตย์ 30 สิงหาคม", headerDate(now, bkk))
    }

    @Test
    fun `the clock is the one on the wall, not the one in UTC`() {
        // 16:50 UTC is ten to midnight in Bangkok, which is when a task
        // resetting at midnight actually rings.
        assertEquals("23:50", clockOf(Instant.parse("2026-08-30T16:50:00Z"), bkk))
    }

    // ── which day ────────────────────────────────────────────────────────────

    @Test
    fun `half past midnight is tomorrow, even though UTC says otherwise`() {
        val lateSunday = Instant.parse("2026-08-30T16:00:00Z")   // 23:00 local
        val earlyMonday = Instant.parse("2026-08-30T17:30:00Z")  // 00:30 local, next day
        assertEquals("พรุ่งนี้", dayPhrase(earlyMonday, lateSunday, bkk))
    }

    @Test
    fun `today, tomorrow and yesterday are named rather than dated`() {
        assertEquals("วันนี้", dayPhrase(plus(3600), now, bkk))
        assertEquals("พรุ่งนี้", dayPhrase(plus(86_400), now, bkk))
        assertEquals("เมื่อวาน", dayPhrase(plus(-86_400), now, bkk))
    }

    @Test
    fun `inside the week it is a weekday, past it a date`() {
        // Sunday plus five days is Friday.
        assertEquals("ศ.", dayPhrase(plus(5 * 86_400), now, bkk))
        // Eight days out, where a weekday name would be ambiguous.
        assertEquals("7 ก.ย.", dayPhrase(plus(8 * 86_400), now, bkk))
    }

    // ── how long ─────────────────────────────────────────────────────────────

    @Test
    fun `a countdown that has run out says so instead of going negative`() {
        assertEquals("ถึงแล้ว", untilPhrase(now, now))
        assertEquals("ถึงแล้ว", untilPhrase(now, plus(-120)))
    }

    @Test
    fun `the unit follows the distance`() {
        assertEquals("อีกไม่ถึงนาที", untilPhrase(now, plus(30)))
        assertEquals("อีก 1 นาที", untilPhrase(now, plus(90)))
        assertEquals("อีก 45 นาที", untilPhrase(now, plus(45 * 60)))
        assertEquals("อีก 1 ชม.", untilPhrase(now, plus(3600)))
        assertEquals("อีก 3 ชม. 12 นาที", untilPhrase(now, plus(3 * 3600 + 12 * 60)))
        assertEquals("อีก 1 วัน", untilPhrase(now, plus(86_400)))
        assertEquals("อีก 2 วัน 2 ชม.", untilPhrase(now, plus(50 * 3600)))
    }

    @Test
    fun `a round number does not print the empty part of itself`() {
        // "อีก 3 ชม. 0 นาที" is a machine reading a subtraction out loud.
        assertEquals("อีก 3 ชม.", untilPhrase(now, plus(3 * 3600)))
        assertEquals("อีก 2 วัน", untilPhrase(now, plus(48 * 3600)))
    }

    @Test
    fun `the panel keeps its seconds`() {
        assertEquals("03:12:07", remaining(now, plus(3 * 3600 + 12 * 60 + 7)))
        assertEquals("2 วัน 02:00:00", remaining(now, plus(50 * 3600)))
    }

    @Test
    fun `the two lines a row shows are the two questions it is asked`() {
        assertEquals(
            "พรุ่งนี้ 09:00 · อีก 23 ชม.",
            duePhrase(Instant.parse("2026-08-31T02:00:00Z"), now, bkk),
        )
    }

    // ── what the phone is doing to itself ────────────────────────────────────

    @Test
    fun `a phone with nothing wrong is told nothing`() {
        assertEquals(
            emptyList(),
            setupWarnings(
                notificationsOn = true,
                channelOn = true,
                batteryExempt = true,
                exactAllowed = true,
            ),
        )
    }

    @Test
    fun `a muted channel is not mentioned while nothing can be posted at all`() {
        val warnings = setupWarnings(
            notificationsOn = false,
            channelOn = false,
            batteryExempt = true,
            exactAllowed = true,
        )
        assertEquals(listOf(SetupWarning.NOTIFICATIONS_OFF), warnings)
    }

    @Test
    fun `the channel speaks up once the app is allowed to post`() {
        val warnings = setupWarnings(
            notificationsOn = true,
            channelOn = false,
            batteryExempt = true,
            exactAllowed = true,
        )
        assertEquals(listOf(SetupWarning.CHANNEL_OFF), warnings)
    }

    @Test
    fun `the independent ones stack, in the order they are said`() {
        val warnings = setupWarnings(
            notificationsOn = false,
            channelOn = false,
            batteryExempt = false,
            exactAllowed = false,
        )
        assertEquals(
            listOf(
                SetupWarning.NOTIFICATIONS_OFF,
                SetupWarning.BATTERY_MANAGED,
                SetupWarning.INEXACT_ALARMS,
            ),
            warnings,
        )
    }

    // ── the order of the list ────────────────────────────────────────────────

    @Test
    fun `the list follows the queue`() {
        val order = homeOrder(
            ids = listOf("c", "a", "b"),
            queued = listOf("a", "b", "c"),
            done = emptySet(),
        )
        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun `a ticked task sinks without disappearing`() {
        val order = homeOrder(
            ids = listOf("a", "b", "c"),
            queued = listOf("a", "b", "c"),
            done = setOf("a"),
        )
        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun `a task the queue said nothing about goes last, not away`() {
        val order = homeOrder(
            ids = listOf("paused", "a", "b"),
            queued = listOf("a", "b"),
            done = emptySet(),
        )
        assertEquals(listOf("a", "b", "paused"), order)
    }

    @Test
    fun `two tasks with nothing queued keep the order they arrived in`() {
        // Otherwise they swap places between redraws, which on a screen that
        // redraws by itself reads as the list flickering for no reason.
        val order = homeOrder(
            ids = listOf("x", "y"),
            queued = emptyList(),
            done = emptySet(),
        )
        assertEquals(listOf("x", "y"), order)
    }

    @Test
    fun `everything ticked keeps the queue order among themselves`() {
        val order = homeOrder(
            ids = listOf("b", "a"),
            queued = listOf("a", "b"),
            done = setOf("a", "b"),
        )
        assertEquals(listOf("a", "b"), order)
    }

    // ── the shape of the list ────────────────────────────────────────────────

    private fun row(
        id: String,
        at: Instant?,
        shifted: Boolean = false,
        done: Boolean = false,
    ) = HomeRow(id, id, at, shifted, done)

    @Test
    fun `rows are cut into days, in the order they arrived`() {
        val sections = homeSections(
            listOf(
                row("a", plus(3600)),
                row("b", plus(7200)),
                row("c", plus(86_400)),
            ),
            now, bkk,
        )
        assertEquals(listOf("วันนี้", "พรุ่งนี้"), sections.map { it.title })
        assertEquals(listOf("a", "b"), sections[0].rows.map { it.id })
        assertEquals(listOf("c"), sections[1].rows.map { it.id })
    }

    @Test
    fun `a reason every row shares is said once, at the top`() {
        // The four dailies in the screenshot: same reset, same shift, same
        // sentence four times at full width.
        val sections = homeSections(
            listOf(
                row("a", plus(86_400), shifted = true),
                row("b", plus(86_400), shifted = true),
                row("c", plus(86_400), shifted = true),
            ),
            now, bkk,
        )
        assertEquals("เลื่อนจากรอบเงียบ", sections[0].note)
        for (r in sections[0].rows) assertEquals("", rowNote(r, now, hoisted = true))
    }

    @Test
    fun `a reason only one row has stays on that row`() {
        val sections = homeSections(
            listOf(
                row("a", plus(86_400), shifted = true),
                row("b", plus(86_400)),
            ),
            now, bkk,
        )
        assertEquals(null, sections[0].note)
        assertEquals("เลื่อนจากรอบเงียบ", rowNote(sections[0].rows[0], now, hoisted = false))
        assertEquals("", rowNote(sections[0].rows[1], now, hoisted = false))
    }

    @Test
    fun `one row on its own keeps its own reason`() {
        // "all of them" is true of a single row too, and hoisting there moves a
        // word up one line for nothing.
        val sections = homeSections(listOf(row("a", plus(86_400), shifted = true)), now, bkk)
        assertEquals(null, sections[0].note)
    }

    @Test
    fun `the countdown is dropped once the heading already says the day`() {
        // Under พรุ่งนี้, "อีก 23 ชม. 10 นาที" is the same fact spelled longer.
        assertEquals("อีก 3 ชม.", rowNote(row("a", plus(3 * 3600)), now, hoisted = false))
        assertEquals("", rowNote(row("a", plus(30 * 3600)), now, hoisted = false))
    }

    @Test
    fun `a task with nothing queued says so instead of showing a blank`() {
        val sections = homeSections(listOf(row("a", null)), now, bkk)
        assertEquals(listOf("ไม่มีกำหนด"), sections.map { it.title })
        assertEquals("ไม่มีรอบถัดไป", rowNote(sections[0].rows[0], now, hoisted = false))
        assertEquals("", rowClock(sections[0].rows[0], bkk))
    }

    @Test
    fun `finished rows sink into their own section whatever time they hold`() {
        val sections = homeSections(
            listOf(
                row("done", plus(60), done = true),
                row("later", plus(7200)),
            ),
            now, bkk,
        )
        assertEquals(listOf("วันนี้", "ติ๊กแล้ว"), sections.map { it.title })
        assertEquals(listOf("later"), sections[0].rows.map { it.id })
        // The tick is the whole message; a countdown under it is noise about
        // something already dealt with.
        assertEquals("", rowNote(sections[1].rows[0], now, hoisted = false))
    }

    @Test
    fun `undated rows sit above finished ones`() {
        val sections = homeSections(
            listOf(
                row("d", plus(60), done = true),
                row("u", null),
                row("t", plus(3600)),
            ),
            now, bkk,
        )
        assertEquals(listOf("วันนี้", "ไม่มีกำหนด", "ติ๊กแล้ว"), sections.map { it.title })
    }

    @Test
    fun `an empty list is an empty list, not a section of nothing`() {
        assertEquals(emptyList(), homeSections(emptyList(), now, bkk))
    }

    @Test
    fun `the clock column is the wall clock of the row`() {
        assertEquals("09:00", rowClock(row("a", Instant.parse("2026-08-31T02:00:00Z")), bkk))
    }
}