package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

// ─── WidgetFace.kt — what the home-screen widget says ────────────────────────
//
// ONE WINDOW FOR ALL OF IT
//
// The widget looks one day ahead, everywhere on it: the card, the list under
// it and the bar all count the same twenty-four hours. The first version took
// the card from the Today tab, which goes by the calendar, and the rest from
// the day ahead, and at eight in the evening the two argued on the home screen
// - "nothing resets today" over "done today 0 of 7", about the same seven
// rounds resetting after midnight. For someone up past midnight those rounds
// are tonight's, so the card now names the first of them. The Today tab keeps
// the calendar; it has the room to show tomorrow as tomorrow.
//
// The desktop's board reads the day the same way (src/lib/widgetFace.ts), so
// the desk and the phone still say one thing.
//
// WHY THE TIMES ARE CLOCKS AND NOT COUNTDOWNS
//
// The tab is redrawn every second while it is open. The widget is drawn once
// and then sits on the home screen for hours, and "อีก 3 ชม." would be wrong
// long before anybody read it. "18:00" stays true until 18:00. The clock at the
// top is the system's own and keeps itself.
//
// WHEN IT IS DRAWN
//
// Never on a timer. When something changes - a tick, a sync, an alarm, a slip -
// and at [widgetNextChange], the first moment one of these lines would go
// stale on its own: a round passing, midnight turning พรุ่งนี้ into วันนี้, a
// weekly crossing into its last two days, the moon rising.

/** One line on the widget: what, and when. */
data class WidgetLine(
    val id: String,
    val name: String,
    /**
     * "18:00", "พรุ่งนี้ 04:00", "ศ. 04:00", "16 ต.ค." - or, for a round further
     * out than a day, when it runs out: "หมด ศ. 04:00".
     */
    val time: String,
    /** Not a daily, and less than two days left. Said in amber. */
    val warn: Boolean = false,
)

/** Everything the widget draws, at every size; each size takes what it has room for. */
data class WidgetFace(
    /** "พุธ 14 ตุลาคม". */
    val date: String,
    /** "พุธ 14 ต.ค.", for the smallest size. */
    val dateShort: String,
    /** The soonest thing not done that is due within a day. Null on a calm day. */
    val next: WidgetLine?,
    /** "วันนี้ทำครบแล้ว" or "วันนี้ไม่มีอะไรรีเซ็ต", exactly when [next] is null: nothing due within a day. */
    val calm: String?,
    /** On a calm day, the first round after it, if any is queued. */
    val calmNext: WidgetLine?,
    /** Not done and due within a day, soonest first, after the one above. */
    val soon: List<WidgetLine>,
    /** Not done and further out than a day, by when they run out. */
    val later: List<WidgetLine>,
    /** Ticked today. */
    val done: Int,
    /** [done], and everything not done that is due within a day: the bar's length. */
    val load: Int,
    /** Quiet hours, right now. */
    val quiet: Boolean,
)

/**
 * The widget, from the same rows the Today tab is drawn from.
 *
 * THE THREE PLACES A ROW CAN BE
 *
 * The card at the top, the rounds within the next day, or the rounds further
 * out - and only one of them. The card's row is left out of both lists, on a
 * calm day too, where the card names the first round after it in words.
 *
 * "Within a day" rather than "today", because a daily that resets at four in
 * the morning is tonight's work at ten in the evening, though its alarm is
 * tomorrow's. The card and the bar count the same day: what was ticked today,
 * and what is still due within one.
 *
 * WHAT IS FURTHER OUT
 *
 * Weeklies, events, anything not due for more than a day, in the order they
 * run out - which is not always the order they ring, since a lead time can
 * ring a Saturday deadline on Friday. Something on pause is left out: it has
 * asked not to be reminded, and a list of what is undone is a reminder.
 */
fun widgetFace(rows: List<HomeRow>, now: Instant, zone: TimeZone, quiet: QuietHours?): WidgetFace {
    val day = 24.hours

    val waiting = ArrayList<Pair<HomeRow, Instant>>()
    for (r in rows) {
        val at = r.fireAt
        if (!r.done && at != null) waiting.add(r to at)
    }
    waiting.sortBy { it.second }

    val within = waiting.filter { it.second - now <= day }
    val nextPair = within.firstOrNull()
    val upcoming = if (nextPair == null) waiting.firstOrNull() else null
    val head = nextPair?.first?.id ?: upcoming?.first?.id

    val soon = within.filter { it.first.id != head }.map { lineOf(it.first, it.second, now, zone, ending = false) }
    val later = waiting
        .filter { (r, at) -> r.id != head && !r.paused && at - now > day }
        .map { (r, at) -> r to (r.resetAt ?: at) }
        .sortedBy { it.second }
        .map { lineOf(it.first, it.second, now, zone, ending = true) }

    val done = rows.count { it.done && it.doneToday }
    return WidgetFace(
        date = headerDate(now, zone),
        dateShort = shortDate(now, zone),
        next = nextPair?.let { lineOf(it.first, it.second, now, zone, ending = false) },
        calm = when {
            nextPair != null -> null
            done > 0 -> "วันนี้ทำครบแล้ว"
            else -> "วันนี้ไม่มีอะไรรีเซ็ต"
        },
        calmNext = upcoming?.let { lineOf(it.first, it.second, now, zone, ending = false) },
        soon = soon,
        later = later,
        done = done,
        load = done + within.size,
        quiet = inQuiet(now, quiet, zone),
    )
}

/**
 * What the bar says, at the three widths: two cells, four, and four by four.
 *
 * Until something is ticked it says how much the day holds, and the bar itself
 * is not drawn. "0 of 7" beside an empty bar is a score for an evening that
 * has not happened yet, which the desktop's task list stopped printing long
 * ago; this is the same rule.
 */
fun widgetTally(done: Int, load: Int): List<String> = when {
    load <= 0 -> emptyList()
    done <= 0 -> listOf("$load อย่าง", "$load อย่างใน 24 ชม.", "$load อย่างภายใน 24 ชั่วโมง")
    else -> listOf("$done/$load", "ทำแล้ว $done/$load", "ทำแล้ว $done จาก $load")
}

private fun lineOf(r: HomeRow, at: Instant, now: Instant, zone: TimeZone, ending: Boolean): WidgetLine {
    val text = shortWhen(at, now, zone)
    return WidgetLine(r.id, r.name, if (ending) "หมด $text" else text, runsOut(r, now))
}

/**
 * Less than two days left on something that is not a daily.
 *
 * A daily's round is always less than a day long, so for it this would be
 * true all day every day, and amber that is always on is a colour, not a
 * warning.
 */
private fun runsOut(r: HomeRow, now: Instant): Boolean {
    if (r.recurring && !r.longCycle) return false
    val end = r.resetAt ?: r.fireAt ?: return false
    return end - now < 48.hours
}

/**
 * When, as short as it can be said: "18:00" today, "พรุ่งนี้ 04:00", "ศ. 04:00"
 * inside the week, "22 ต.ค." past it.
 *
 * Past the week the clock is dropped: on a line that narrow, the day is what
 * matters a week out, and the clock is one tap away.
 */
fun shortWhen(at: Instant, now: Instant, zone: TimeZone): String {
    val today = now.toLocalDateTime(zone).date
    val t = at.toLocalDateTime(zone)
    val clock = clockOf(at, zone)
    val diff = today.daysUntil(t.date)
    return when {
        diff <= 0 -> clock
        diff == 1 -> "พรุ่งนี้ $clock"
        diff <= 6 -> THAI_DAYS_SHORT[t.dayOfWeek.isoDayNumber % 7] + " " + clock
        else -> t.dayOfMonth.toString() + " " + THAI_MONTHS_SHORT[t.monthNumber - 1]
    }
}

/** "พุธ 14 ต.ค.": [headerDate] for a widget two cells wide. */
fun shortDate(now: Instant, zone: TimeZone): String {
    val t = now.toLocalDateTime(zone)
    return THAI_DAYS[t.dayOfWeek.isoDayNumber % 7] + " " + t.dayOfMonth + " " + THAI_MONTHS_SHORT[t.monthNumber - 1]
}

/**
 * The first moment something on the widget stops being true by itself, with
 * nothing in the app having changed.
 *
 * Each row offers the moments it could change at: its ring and its reset; a
 * day before its ring, when it joins the bar and the list of what is due within
 * a day; and, for what is not a daily, two days before it runs out, when it
 * turns amber. Then midnight, when every พรุ่งนี้ becomes วันนี้, and the next
 * edge of quiet hours. The soonest of them, and never sooner than a minute
 * away, so a row at the very edge of a threshold cannot wake the widget over
 * and over.
 */
fun widgetNextChange(rows: List<HomeRow>, now: Instant, zone: TimeZone, quiet: QuietHours?): Instant {
    var soonest = nextMidnight(now, zone)
    fun offer(at: Instant?) {
        if (at != null && at > now && at < soonest) soonest = at
    }
    offer(nextQuietEdge(now, quiet, zone))
    for (r in rows) {
        offer(r.fireAt)
        offer(r.resetAt)
        val at = r.fireAt ?: continue
        if (r.done) continue
        offer(at - 24.hours)
        if (!r.recurring || r.longCycle) offer((r.resetAt ?: at) - 48.hours)
    }
    val floor = now + 1.minutes
    return if (soonest < floor) floor else soonest
}

/** Midnight at the start of tomorrow, on the wall where the person is. */
fun nextMidnight(now: Instant, zone: TimeZone): Instant {
    val tomorrow = addDays(wallClock(now.toEpochMilliseconds(), zone), 1)
    return Instant.fromEpochMilliseconds(wallToEpochMs(atTime(tomorrow, 0, 0), zone))
}

/**
 * The month's money in one line, for a widget that has been asked to show it:
 * "ต.ค. จ่าย ฿1,240 · รับ ฿30,000".
 *
 * Off unless switched on. A home screen is looked at by whoever is next to the
 * phone, and what the month cost is nobody's business but the person's.
 *
 * @param month 1 to 12
 */
fun widgetMoneyLine(spent: Double, received: Double, currency: String, month: Int): String {
    val name = THAI_MONTHS_SHORT.getOrElse(month - 1) { "เดือนนี้" }
    val parts = ArrayList<String>()
    if (spent > 0) parts.add("จ่าย " + moneyText(spent, currency))
    if (received > 0) parts.add("รับ " + moneyText(received, currency))
    return if (parts.isEmpty()) "$name ยังไม่มีรายการ" else name + " " + parts.joinToString(" · ")
}
