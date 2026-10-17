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
    // Rounds due at one moment, oldest task first: the order the desktop's
    // board names them in, so the two screens lead with the same name. Rows
    // without a date keep the order they came in, since the sort is stable.
    waiting.sortWith(compareBy({ it.second }, { it.first.createdAt ?: "" }))

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

/** Rounds that come due at the same moment, said as one line. */
data class WidgetGroup(
    /** In the order they came, which for a moment shared is the Today tab's. */
    val names: List<String>,
    /** When, said as short as the line above allows: "05:00" under "พรุ่งนี้ 04:00". */
    val time: String,
    /** Any one of them in amber. */
    val warn: Boolean,
)

/** The widget's rows, gathered by the moment they come due. */
data class WidgetSchedule(
    /** On the card: every round due at the soonest moment within a day. Null on a calm day. */
    val head: WidgetGroup?,
    /** The rest of the day, a line for each moment. */
    val soon: List<WidgetGroup>,
    /** What is further out, a line for each moment one runs out. */
    val later: List<WidgetGroup>,
)

/**
 * [face] gathered into lines, a line for each moment rather than for each round.
 *
 * WHY
 *
 * Games of one region reset together, and a phone's column is narrow. "พรุ่งนี้
 * 04:00" three times over, under each of three names, said one thing and took
 * the room of three; now it is one line that names all three, and the rows it
 * frees show the rest of the day. The card does the same: when several rounds
 * come due at the soonest moment, all of them are what is next.
 *
 * Down the list of the day, the day is said once: under "พรุ่งนี้ 04:00" the
 * next line is "05:00". It is not dropped from the first line, which sits
 * under a card of its own, nor from what is further out, which is a list of
 * different days.
 */
fun widgetSchedule(face: WidgetFace): WidgetSchedule {
    val next = face.next
    var soon = face.soon
    val head = next?.let { first ->
        val same = soon.takeWhile { it.time == first.time }
        soon = soon.drop(same.size)
        WidgetGroup(listOf(first.name) + same.map { it.name }, first.time, first.warn || same.any { it.warn })
    }
    return WidgetSchedule(head, gather(soon, dayOnce = true), gather(face.later, dayOnce = false))
}

/** One line of the widget's list, laid out the way the desktop's board lays its list out. */
sealed interface WidgetItem {
    /** The day the rows under it share ("พรุ่งนี้"), or the longer rounds' heading. */
    data class Heading(val text: String) : WidgetItem

    /**
     * When, then what. [time] is a bare clock under a day's heading; a longer
     * round keeps its whole phrase ("หมด อา. 18:00"), since each is a different
     * day. [later] says which of the two lists it is in, for the width of the
     * time column, which each list measures for itself.
     */
    data class Row(val time: String, val names: List<String>, val warn: Boolean, val later: Boolean) : WidgetItem

    /** The day's bar: [words] beside it, filled [done] of [load], and only words until something is ticked. */
    data class Tally(val words: String, val done: Int, val load: Int) : WidgetItem

    /** The month's money, when the widget has been asked to show it. */
    data class Money(val text: String) : WidgetItem
}

/** The heading over the rounds further out than a day. The desktop's widget.later says the same. */
const val WIDGET_LATER_HEADING: String = "รอบยาวที่ยังไม่ทำ"

/**
 * The list under the card, in the desktop board's order: the rest of the day,
 * with a heading for each day the rows fall on after today and bare clocks
 * under it; then the day's bar; then the longer rounds under their heading;
 * then the month's money.
 *
 * The card's own moment is left out, as it is in [widgetSchedule]. Rounds that
 * share a moment are one row naming all of them.
 */
fun widgetItems(face: WidgetFace, tally: String?, money: String?): List<WidgetItem> {
    val out = ArrayList<WidgetItem>()
    val head = face.next
    val soon = if (head == null) face.soon else face.soon.dropWhile { it.time == head.time }
    var lastDay: String? = null
    for (g in gather(soon, dayOnce = false)) {
        val (day, clock) = splitWhen(g.time)
        if (day != null && clock.isNotEmpty()) {
            if (day != lastDay) out.add(WidgetItem.Heading(day))
            lastDay = day
            out.add(WidgetItem.Row(clock, g.names, g.warn, later = false))
        } else {
            out.add(WidgetItem.Row(g.time, g.names, g.warn, later = false))
        }
    }
    if (tally != null) out.add(WidgetItem.Tally(tally, face.done, face.load))
    val later = gather(face.later, dayOnce = false)
    if (later.isNotEmpty()) {
        out.add(WidgetItem.Heading(WIDGET_LATER_HEADING))
        for (g in later) out.add(WidgetItem.Row(g.time, g.names, g.warn, later = true))
    }
    if (money != null) out.add(WidgetItem.Money(money))
    return out
}

/** Lines in a row with the same time, as one. */
private fun gather(lines: List<WidgetLine>, dayOnce: Boolean): List<WidgetGroup> {
    val out = ArrayList<WidgetGroup>()
    var lastDay: String? = null
    var i = 0
    while (i < lines.size) {
        val time = lines[i].time
        var j = i
        while (j < lines.size && lines[j].time == time) j++
        val run = lines.subList(i, j)
        val (day, clock) = splitWhen(time)
        val said = if (dayOnce && out.isNotEmpty() && day != null && day == lastDay && clock.isNotEmpty()) clock else time
        out.add(WidgetGroup(run.map { it.name }, said, run.any { it.warn }))
        lastDay = day
        i = j
    }
    return out
}

/**
 * "พรุ่งนี้ 04:00" as the day and the clock; "18:00" as no day and the clock.
 * Anything not ending in a clock - "22 ต.ค." - is all day and no clock, and
 * is never shortened.
 */
private fun splitWhen(time: String): Pair<String?, String> {
    val space = time.lastIndexOf(' ')
    val last = time.substring(space + 1)
    val isClock = last.length in 4..5 && last[last.length - 3] == ':' && last.all { it.isDigit() || it == ':' }
    if (!isClock) return time to ""
    return (if (space > 0) time.substring(0, space) else null) to last
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
