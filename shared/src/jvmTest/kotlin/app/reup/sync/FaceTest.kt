package app.reup.core

import app.reup.sync.TaskDraft
import app.reup.sync.taskProblems
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
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
        // No วัน in front: the title under it is วันนี้.
        assertEquals("อาทิตย์ 30 สิงหาคม", headerDate(now, bkk))
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

    /** 04:00 Bangkok on the day [days] after Sunday the 30th. */
    private fun fourAm(days: Int): Instant =
        Instant.fromEpochSeconds(Instant.parse("2026-08-29T21:00:00Z").epochSeconds + days * 86_400L)

    private fun row(
        id: String,
        at: Instant?,
        silent: Boolean = false,
        done: Boolean = false,
        doneToday: Boolean = false,
        resetAt: Instant? = null,
        paused: Boolean = false,
        cadence: String? = null,
        category: String? = null,
        recurring: Boolean = true,
        // True unless a test says otherwise: most of the cases below are about
        // which day a deadline falls on, and an open daily is today's whatever
        // its date (see the daily tests further down).
        longCycle: Boolean = true,
    ) = HomeRow(id, id, at, silent, done, resetAt, doneToday, paused, cadence, category, recurring, longCycle)

    private fun daily(id: String, at: Instant, paused: Boolean = false) =
        row(id, at, cadence = "ทุกวัน", longCycle = false, paused = paused)

    @Test
    fun `the soonest thing due today gets the card and is not drawn twice`() {
        val face = todayFace(
            listOf(row("a", plus(3600)), row("b", plus(7200)), row("c", fourAm(1))),
            now, bkk,
        )
        assertEquals("a", face.next?.id)
        assertEquals(null, face.calm)
        assertEquals(listOf("ที่เหลือวันนี้", "พรุ่งนี้ · จันทร์ 31 ส.ค."), face.sections.map { it.title })
        assertEquals(listOf("b"), face.sections[0].rows.map { it.id })
        assertEquals(listOf("c"), face.sections[1].rows.map { it.id })
    }

    @Test
    fun `tomorrow's first round is not next tonight`() {
        // Nothing left today: the card turns into words, and the round it
        // names is the one the list below starts with.
        val face = todayFace(listOf(row("Genshin", fourAm(1)), row("b", fourAm(2))), now, bkk)
        assertEquals(null, face.next)
        assertEquals(
            Calm("วันนี้ไม่มีอะไรรีเซ็ต", "รอบถัดไปคือพรุ่งนี้ 04:00", "Genshin", nextId = "Genshin"),
            face.calm,
        )
        assertEquals(listOf("พรุ่งนี้ · จันทร์ 31 ส.ค.", "อังคาร 1 ก.ย."), face.sections.map { it.title })
    }

    @Test
    fun `the next round further out is named by its weekday, then by its date`() {
        assertEquals("รอบถัดไปคือวันพุธ 04:00", todayFace(listOf(row("a", fourAm(3))), now, bkk).calm?.nextAt)
        assertEquals("รอบถัดไปคือวันที่ 8 ก.ย. 04:00", todayFace(listOf(row("a", fourAm(9))), now, bkk).calm?.nextAt)
    }

    @Test
    fun `a day with everything ticked says so rather than that nothing was due`() {
        val face = todayFace(
            listOf(row("b", fourAm(1)), row("a", fourAm(1), done = true, doneToday = true)),
            now, bkk,
        )
        assertEquals(null, face.next)
        assertEquals("วันนี้ทำครบแล้ว", face.calm?.title)
        assertEquals(listOf("ทำแล้ววันนี้", "พรุ่งนี้ · จันทร์ 31 ส.ค."), face.sections.map { it.title })
    }

    @Test
    fun `a row ticked today stays with today, below what is left`() {
        val face = todayFace(
            listOf(row("a", plus(3600)), row("b", plus(7200)), row("d", fourAm(1), done = true, doneToday = true)),
            now, bkk,
        )
        assertEquals("ที่เหลือวันนี้", face.sections[0].title)
        assertEquals(listOf("b", "d"), face.sections[0].rows.map { it.id })
    }

    @Test
    fun `a weekly ticked days ago waits at the end, still on screen`() {
        // Hiding a finished thing is how a mis-tap becomes permanent.
        val face = todayFace(
            listOf(row("a", plus(3600)), row("u", null), row("w", fourAm(4), done = true)),
            now, bkk,
        )
        assertEquals(listOf("ไม่มีกำหนด", "ทำแล้ว รอรอบใหม่"), face.sections.map { it.title })
        assertEquals(listOf("w"), face.sections[1].rows.map { it.id })
    }

    @Test
    fun `a reason every waiting row shares is said once, at the top`() {
        // The four dailies in the screenshot: same reset, same quiet, same
        // sentence four times at full width.
        val face = todayFace(
            listOf(
                row("a", fourAm(1), silent = true, cadence = "ทุกวัน"),
                row("b", fourAm(1), silent = true, cadence = "ทุกวัน"),
                row("c", fourAm(1), silent = true, cadence = "ทุกวัน"),
            ),
            now, bkk,
        )
        assertEquals(QUIET_NOTE, face.sections[0].note)
        for (r in face.sections[0].rows) assertEquals("ทุกวัน", rowLine(r, now, bkk, hoisted = true))
    }

    @Test
    fun `a reason only one row has stays on that row`() {
        val face = todayFace(
            listOf(row("a", fourAm(1), silent = true), row("b", fourAm(1))),
            now, bkk,
        )
        assertEquals(null, face.sections[0].note)
        assertEquals(QUIET_NOTE, rowLine(face.sections[0].rows[0], now, bkk, hoisted = false))
        assertEquals(null, rowLine(face.sections[0].rows[1], now, bkk, hoisted = false))
    }

    @Test
    fun `one row on its own keeps its own reason`() {
        // "all of them" is true of a single row too, and hoisting there moves a
        // word up one line for nothing.
        val face = todayFace(listOf(row("a", fourAm(1), silent = true)), now, bkk)
        assertEquals(null, face.sections[0].note)
    }

    @Test
    fun `an empty list is calm with nothing to point at`() {
        assertEquals(
            TodayFace(null, Calm("วันนี้ไม่มีอะไรรีเซ็ต", null, null), emptyList()),
            todayFace(emptyList(), now, bkk),
        )
    }

    @Test
    fun `a ticked row says when it comes back, at the precision that is still clear`() {
        assertEquals("ทำแล้ว · รอบใหม่ 04:00", rowLine(row("a", fourAm(1), done = true), now, bkk, false))
        assertEquals("ทำแล้ว · รอบใหม่ พ. 04:00", rowLine(row("a", fourAm(3), done = true), now, bkk, false))
        assertEquals("ทำแล้ว · รอบใหม่ 9 ก.ย.", rowLine(row("a", fourAm(10), done = true), now, bkk, false))
        // A one-off has nothing to come back to - even when its deadline is
        // still queued for later today.
        assertEquals("ทำแล้ว", rowLine(row("a", null, done = true), now, bkk, false))
        assertEquals("ทำแล้ว", rowLine(row("a", plus(8 * 3600), done = true, recurring = false), now, bkk, false))
    }

    @Test
    fun `the kinds that come back are the four the desktop calls recurring`() {
        for (t in listOf(ResetType.DAILY, ResetType.WEEKLY, ResetType.BIWEEKLY, ResetType.CUSTOM_DAYS)) {
            assertEquals(true, recurs(ResetSpec(t)), t)
        }
        for (t in listOf(ResetType.ONE_TIME, ResetType.EVENT_WINDOW, ResetType.SPECIFIC_DATE, "lunar")) {
            assertEquals(false, recurs(ResetSpec(t)), t)
        }
    }

    @Test
    fun `the reset is what comes back, not the warning before it`() {
        val warned = row("a", Instant.parse("2026-08-30T20:30:00Z"), done = true, resetAt = fourAm(1))
        assertEquals("ทำแล้ว · รอบใหม่ 04:00", rowLine(warned, now, bkk, false))
    }

    @Test
    fun `a waiting row says how often, what kind, and anything unusual`() {
        val r = row("a", fourAm(1), cadence = "ทุกวัน", category = "game")
        assertEquals("ทุกวัน · เกม", rowLine(r, now, bkk, false))
        assertEquals(
            "ทุกวัน · เกม · พักอยู่ · ไม่มีเสียง",
            rowLine(r.copy(paused = true, silent = true), now, bkk, false),
        )
        assertEquals(null, rowLine(row("b", fourAm(1)), now, bkk, false))
    }

    @Test
    fun `the big card counts down, which the rows do not`() {
        assertEquals("10:33 · อีก 33 นาที", heroLine(row("a", plus(33 * 60), recurring = false), now, bkk))
        assertEquals(
            "10:33 · อีก 33 นาที · ไม่มีเสียง",
            heroLine(row("a", plus(33 * 60), silent = true, recurring = false), now, bkk),
        )
    }

    @Test
    fun `the big card gives a round that comes back as a deadline`() {
        // Open until it resets, so "by", not "at".
        assertEquals("ถึง 10:33 · อีก 33 นาที", heroLine(row("a", plus(33 * 60)), now, bkk))
        assertEquals("ถึงพรุ่งนี้ 04:00 · อีก 18 ชม.", heroLine(daily("a", fourAm(1)), now, bkk))
    }

    // ── which day an open round belongs to ───────────────────────────────────
    //
    // The bug these are here for: at four in the afternoon, with seven dailies
    // reset that morning and none of them ticked, the tab said
    // "วันนี้ไม่มีอะไรรีเซ็ต", because every one of them next reset tomorrow.

    /** Four in the morning, as the desktop's วันใหม่เริ่ม stores it. */
    private val four = 240

    @Test
    fun `an open daily that resets tomorrow morning is today's, not tomorrow's`() {
        val face = todayFace(listOf(daily("hsr", fourAm(1)), daily("fgo", fourAm(1))), now, bkk)
        assertEquals("hsr", face.next?.id)
        assertEquals(null, face.calm)
        assertEquals(listOf("ที่เหลือวันนี้"), face.sections.map { it.title })
        assertEquals(true, face.sections[0].today)
    }

    @Test
    fun `medicine due this morning and not ticked is still today's in the afternoon`() {
        // Its round began at nine today and is open until nine tomorrow. By
        // deadline alone it would be filed under tomorrow.
        val nineTomorrow = Instant.parse("2026-08-31T02:00:00Z")
        val face = todayFace(listOf(daily("meds", nineTomorrow)), now, bkk, four)
        assertEquals("meds", face.next?.id)
    }

    @Test
    fun `a game weekly running out at three on Monday is Sunday's for someone whose day starts at four`() {
        val threeMonday = Instant.parse("2026-08-30T20:00:00Z")  // 03:00 Mon 31 local
        val face = todayFace(listOf(row("weekly", threeMonday, cadence = "ทุกวันจันทร์")), now, bkk, four)
        assertEquals("weekly", face.next?.id)
        // With the day starting at midnight, the same round is tomorrow's.
        assertEquals(null, todayFace(listOf(row("weekly", threeMonday)), now, bkk).next)
    }

    @Test
    fun `a chore due at eight on Monday is Monday's whenever the day starts`() {
        val eightMonday = Instant.parse("2026-08-31T01:00:00Z")
        val face = todayFace(listOf(row("trash", eightMonday)), now, bkk, four)
        assertEquals(null, face.next)
        assertEquals(listOf("พรุ่งนี้ · จันทร์ 31 ส.ค."), face.sections.map { it.title })
    }

    @Test
    fun `a round that runs out exactly as the day begins belongs to the day that is ending`() {
        val midnightMonday = Instant.parse("2026-08-30T17:00:00Z")  // 00:00 Mon 31 local
        assertEquals("w", todayFace(listOf(row("w", midnightMonday)), now, bkk).next?.id)
        val fourMonday = fourAm(1)
        assertEquals("w", todayFace(listOf(row("w", fourMonday)), now, bkk, four).next?.id)
    }

    @Test
    fun `a weekly with days left waits under its own day`() {
        val face = todayFace(listOf(row("w", fourAm(4))), now, bkk, four)
        assertEquals(null, face.next)
        assertEquals("วันนี้ไม่มีอะไรรีเซ็ต", face.calm?.title)
        assertEquals(listOf("พุธ 2 ก.ย."), face.sections.map { it.title })
    }

    @Test
    fun `a paused daily is not today's`() {
        // It asked not to be reminded, and today's list is a reminder.
        val face = todayFace(listOf(daily("p", fourAm(3), paused = true)), now, bkk)
        assertEquals(null, face.next)
    }

    @Test
    fun `the personal day turns at the hour it is set to`() {
        val threeAm = Instant.parse("2026-08-30T20:00:00Z")  // 03:00 Mon local
        assertEquals(LocalDate(2026, 8, 30), personalDate(threeAm, bkk, four))
        assertEquals(LocalDate(2026, 8, 31), personalDate(threeAm, bkk))
    }

    @Test
    fun `a tick at two in the morning still counts for the day before it`() {
        val twoAm = Instant.parse("2026-08-30T19:00:00Z")    // 02:00 Mon local
        val tickedAt = "2026-08-30T10:00:00Z"                // 17:00 Sun local
        assertEquals(true, tickedToday(tickedAt, twoAm, bkk, four))
        assertEquals(false, tickedToday(tickedAt, twoAm, bkk))
    }

    @Test
    fun `in today's group a round that comes back says until when`() {
        val r = daily("a", fourAm(1))
        assertEquals("ถึง 04:00", rowClock(r, bkk, today = true))
        assertEquals("04:00", rowClock(r, bkk))
        assertEquals("09:00", rowClock(row("x", Instant.parse("2026-08-30T02:00:00Z"), recurring = false), bkk, today = true))
    }

    @Test
    fun `a heading carries the date the day word stands for`() {
        assertEquals("วันนี้", dayTitle(plus(3600), now, bkk))
        assertEquals("พรุ่งนี้ · จันทร์ 31 ส.ค.", dayTitle(fourAm(1), now, bkk))
        assertEquals("ศุกร์ 4 ก.ย.", dayTitle(fourAm(5), now, bkk))
    }

    @Test
    fun `half past midnight is headed tomorrow, even though UTC says otherwise`() {
        val lateSunday = Instant.parse("2026-08-30T16:00:00Z")   // 23:00 local
        val earlyMonday = Instant.parse("2026-08-30T17:30:00Z")  // 00:30 local, next day
        assertEquals("พรุ่งนี้ · จันทร์ 31 ส.ค.", dayTitle(earlyMonday, lateSunday, bkk))
    }

    @Test
    fun `the clock column is the wall clock of the row`() {
        assertEquals("09:00", rowClock(row("a", Instant.parse("2026-08-31T02:00:00Z")), bkk))
        assertEquals("", rowClock(row("a", null), bkk))
    }

    @Test
    fun `how often, in words, with the one useful fact a week has`() {
        assertEquals("ทุกวัน", cadencePhrase(ResetSpec(ResetType.DAILY)))
        assertEquals("ทุกวันจันทร์", cadencePhrase(ResetSpec(ResetType.WEEKLY, resetDay = 1)))
        assertEquals("ทุกวันอาทิตย์", cadencePhrase(ResetSpec(ResetType.WEEKLY, resetDay = 0)))
        assertEquals("ทุกสัปดาห์", cadencePhrase(ResetSpec(ResetType.WEEKLY)))
        assertEquals("ทุก 2 สัปดาห์", cadencePhrase(ResetSpec(ResetType.BIWEEKLY)))
        assertEquals("ทุก 3 วัน", cadencePhrase(ResetSpec(ResetType.CUSTOM_DAYS, resetIntervalDays = 3)))
        assertEquals("ทุกวัน", cadencePhrase(ResetSpec(ResetType.CUSTOM_DAYS, resetIntervalDays = 1)))
        assertEquals("รอบกำหนดเอง", cadencePhrase(ResetSpec(ResetType.CUSTOM_DAYS)))
        assertEquals("กิจกรรมจำกัดเวลา", cadencePhrase(ResetSpec(ResetType.EVENT_WINDOW)))
        assertEquals("ครั้งเดียว", cadencePhrase(ResetSpec(ResetType.SPECIFIC_DATE)))
        assertEquals("ครั้งเดียว", cadencePhrase(ResetSpec(ResetType.ONE_TIME)))
        // A newer desktop can invent a type. Saying nothing beats guessing.
        assertEquals(null, cadencePhrase(ResetSpec("lunar")))
    }

    @Test
    fun `the four categories are named as the desktop names them`() {
        assertEquals("เกม", categoryName("game"))
        assertEquals("การเรียน", categoryName("school"))
        assertEquals("งาน", categoryName("work"))
        assertEquals("ส่วนตัว", categoryName("personal"))
        assertEquals(null, categoryName("hobby"))
        assertEquals(null, categoryName(null))
    }

    @Test
    fun `ticked today is decided on the wall clock`() {
        // Bangkok midnight is 17:00 UTC the day before.
        assertEquals(true, tickedToday("2026-08-30T01:00:00.000Z", now, bkk))
        assertEquals(true, tickedToday("2026-08-29T17:00:00.000Z", now, bkk))
        assertEquals(false, tickedToday("2026-08-29T16:59:59.999Z", now, bkk))
        assertEquals(false, tickedToday(null, now, bkk))
        assertEquals(false, tickedToday("", now, bkk))
        assertEquals(false, tickedToday("yesterday-ish", now, bkk))
    }

    // ── the phone it is actually running on ─────────────────────────────────

    @Test
    fun `an iQOO is called iQOO and gets vivo's steps`() {
        val p = brandPowerFor("vivo", "iQOO")
        assertEquals("iQOO", p?.brand)
        assertEquals("dontkillmyapp.com/vivo", p?.guide)
        assertEquals(true, p?.steps?.any { it.startsWith("Autostart") })
    }

    @Test
    fun `nobody is told they own a Samsung unless they do`() {
        assertEquals("Samsung", brandPowerFor("samsung", "samsung")?.brand)
        assertEquals(null, brandPowerFor("Google", "google"))
        assertEquals(null, brandPowerFor("", ""))
    }

    @Test
    fun `a brand is matched however the phone spells it`() {
        val p = brandPowerFor(" Xiaomi ", "POCO")
        assertEquals("POCO", p?.brand)
        assertEquals("dontkillmyapp.com/xiaomi", p?.guide)
    }

    @Test
    fun `turning this phone off is said alone`() {
        assertEquals(
            listOf(SetupWarning.DEVICE_OFF),
            setupWarnings(false, false, false, false, hibernationExempt = false, deviceOff = true),
        )
    }

    @Test
    fun `android's own pause-if-unused stacks with the rest`() {
        assertEquals(
            listOf(SetupWarning.BATTERY_MANAGED, SetupWarning.HIBERNATION, SetupWarning.INEXACT_ALARMS),
            setupWarnings(true, true, false, false, hibernationExempt = false),
        )
    }

    // ── the forms ──

    @Test
    fun `the day on a form's third date button`() {
        val today = LocalDate(2026, 10, 13)
        assertEquals("วันนี้", dayChip("2026-10-13", today))
        assertEquals("เมื่อวาน", dayChip("2026-10-12", today))
        assertEquals("พรุ่งนี้", dayChip("2026-10-14", today))
        assertEquals("ศ. 9 ต.ค.", dayChip("2026-10-09", today))
        assertEquals("พฤ. 15 ต.ค.", dayChip("2026-10-15", today))
        assertEquals("ศ. 26 ธ.ค. 2025", dayChip("2025-12-26", today))
        assertEquals("ไม่ใช่วันที่", dayChip("ไม่ใช่วันที่", today))
    }

    @Test
    fun `a picked time is written the way the desktop writes it`() {
        assertEquals("04:00", resetTimeText(4, 0))
        assertEquals("00:05", resetTimeText(0, 5))
        assertEquals("23:59", resetTimeText(23, 59))
        assertEquals("23:59", resetTimeText(24, 75), "pulled back in, not thrown")
        // Every minute of the day comes back as itself, and passes the check
        // the desktop and the save both make.
        for (h in 0..23) for (m in 0..59) {
            val text = resetTimeText(h, m)
            assertEquals(h to m, resetTimeParts(text), text)
            assertEquals(emptyList(), taskProblems(TaskDraft(name = "x", resetType = "daily", resetTime = text)), text)
        }
    }

    @Test
    fun `a stored time opens the picker where it is set`() {
        assertEquals(4 to 0, resetTimeParts("04:00"))
        assertEquals(9 to 30, resetTimeParts("9:30"), "an older row's spelling still opens")
        assertEquals(9 to 30, resetTimeParts(" 09:30 "))
        assertEquals(null, resetTimeParts(null), "no time is the end of the day")
        assertEquals(null, resetTimeParts(""))
        assertEquals(null, resetTimeParts("25:00"))
        assertEquals(null, resetTimeParts("04:60"))
        assertEquals(null, resetTimeParts("04:00:00"))
        assertEquals(null, resetTimeParts("สิ้นวัน"))
    }
}
