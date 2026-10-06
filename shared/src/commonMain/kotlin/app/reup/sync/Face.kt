package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

// ─── Face.kt — what the screens say, as functions of what they were given ────
//
// WHY THIS IS NOT IN THE ACTIVITY
//
// Everything here used to live inside MainActivity, mixed in with the views it
// was setting text on. That is the one place in this project where a decision
// cannot be tested, because reaching it means starting Android. And these are
// decisions, not decoration:
//
//   • whether "อีก 3 ชม." or "อีก 3 ชม. 12 นาที" is the honest phrasing
//   • whether a phone with notifications switched off is also told its
//     notification channel is off
//   • which task the screen is allowed to call the next one
//
// Each has a right answer that can be written down, and each was previously
// only checkable by looking at a phone.
//
// WHY THE PHRASING IS COARSE
//
// The old home screen counted down in seconds, on every row, forever. A number
// that changes every second is a number that has to be watched, and this is an
// app for someone who should be able to glance at it and put it down. Minutes
// below an hour, hours below a day, days above that — the same resolution a
// person would use out loud. The exact clock is still available, in the panel
// at the bottom of the screen that exists for debugging.
//
// The one exception is [remaining], which keeps seconds because it is what that
// panel prints.

/** Sunday first, matching [Wall.dow] so there is one convention in this module. */
private val THAI_DAYS = listOf(
    "อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์",
)

private val THAI_DAYS_SHORT = listOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")

private val THAI_MONTHS = listOf(
    "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
    "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
)

/**
 * Shared with the slip reader, which parses these rather than printing them.
 *
 * Two lists of the same twelve strings is the shape of every bug this project
 * has spent a month removing, and this one had a direction: SlipText matched
 * what a bank printed against its copy, and this file printed dates from its
 * own. Nothing would have gone red the day they stopped agreeing.
 */
internal val THAI_MONTHS_SHORT = listOf(
    "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
    "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.",
)

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

/**
 * The line under the app's name: which day it is, in words.
 *
 * No year. A year is only useful when there is doubt about which one it is, and
 * a phone that is awake and showing this is not in any doubt.
 */
fun headerDate(now: Instant, zone: TimeZone): String {
    val t = now.toLocalDateTime(zone)
    val dow = t.dayOfWeek.isoDayNumber % 7
    return "วัน" + THAI_DAYS[dow] + " " + t.dayOfMonth + " " + THAI_MONTHS[t.monthNumber - 1]
}

/** "23:50", in the zone the person is standing in. */
fun clockOf(at: Instant, zone: TimeZone): String {
    val t = at.toLocalDateTime(zone)
    return pad2(t.hour) + ":" + pad2(t.minute)
}

/**
 * Which day something falls on, said the way a person would say it.
 *
 * Names the weekday only inside the coming week. Past the seventh day "ศ."
 * stops meaning anything — there are two of them in view — so it becomes a
 * date.
 */
fun dayPhrase(at: Instant, now: Instant, zone: TimeZone): String {
    val today = now.toLocalDateTime(zone).date
    val that = at.toLocalDateTime(zone)
    val diff = today.daysUntil(that.date)
    return when {
        diff == 0 -> "วันนี้"
        diff == 1 -> "พรุ่งนี้"
        diff == -1 -> "เมื่อวาน"
        diff in 2..6 -> THAI_DAYS_SHORT[that.dayOfWeek.isoDayNumber % 7]
        // Anything further out than a week, in either direction, is a date.
        else -> that.dayOfMonth.toString() + " " + THAI_MONTHS_SHORT[that.monthNumber - 1]
    }
}

/**
 * How long until something, rounded to the unit a person would use.
 *
 * "ถึงแล้ว" rather than a negative number: a row whose reset has passed is not
 * late by two minutes, it is simply due, and the countdown has stopped being
 * the interesting thing about it.
 */
fun untilPhrase(from: Instant, to: Instant): String {
    val seconds = (to - from).inWholeSeconds
    if (seconds <= 0) return "ถึงแล้ว"
    if (seconds < 60) return "อีกไม่ถึงนาที"

    val minutes = seconds / 60
    if (minutes < 60) return "อีก $minutes นาที"

    val hours = minutes / 60
    val restMinutes = minutes % 60
    if (hours < 24) {
        return if (restMinutes == 0L) "อีก $hours ชม." else "อีก $hours ชม. $restMinutes นาที"
    }

    val days = hours / 24
    val restHours = hours % 24
    return if (restHours == 0L) "อีก $days วัน" else "อีก $days วัน $restHours ชม."
}

/** The whole line under a task: when, and how far away that is. */
fun duePhrase(at: Instant, now: Instant, zone: TimeZone): String =
    dayPhrase(at, now, zone) + " " + clockOf(at, zone) + " · " + untilPhrase(now, at)

/**
 * The exact countdown, seconds and all, for the diagnostics panel.
 *
 * Kept separate from [untilPhrase] on purpose. Two callers wanted two different
 * things out of the same subtraction, and giving them one function with a flag
 * would have meant every future change had to be right for both.
 */
fun remaining(from: Instant, to: Instant): String {
    val total = (to - from).inWholeSeconds
    if (total <= 0) return "ถึงแล้ว"
    val days = total / 86400
    val clock = pad2(((total % 86400) / 3600).toInt()) + ":" +
            pad2(((total % 3600) / 60).toInt()) + ":" + pad2((total % 60).toInt())
    return if (days > 0) "$days วัน $clock" else clock
}

/**
 * Something in the phone's own settings that will stop a reminder arriving.
 *
 * All four are silent failures. Nothing on this device says a word when the
 * battery manager puts the app to sleep, and the app cannot find out that it
 * happened either — it simply stops being woken. So the screen has to say the
 * conditions out loud while they are true, and say nothing at all when they are
 * not.
 */
enum class SetupWarning {
    /** The person said no to notifications, or turned them off later. */
    NOTIFICATIONS_OFF,

    /** Notifications are allowed but this app's channel was muted. */
    CHANNEL_OFF,

    /** The vendor's battery manager is free to stop waking the app. */
    BATTERY_MANAGED,

    /** Alarms are batched, so a reminder can be late by up to half an hour. */
    INEXACT_ALARMS,
}

/**
 * Which of those to say, in the order they should be said.
 *
 * The interesting rule is the first one. A phone with notifications switched
 * off is NOT also told that its channel is muted, because unmuting a channel
 * changes nothing at all while the app is not allowed to post — it is a second
 * task that looks like progress and produces none. One cause, one line, one
 * button. When the first is fixed the second appears if it is still true.
 *
 * The other three stack, because they are independent and each one on its own
 * is enough to make a reminder not arrive.
 */
fun setupWarnings(
    notificationsOn: Boolean,
    channelOn: Boolean,
    batteryExempt: Boolean,
    exactAllowed: Boolean,
): List<SetupWarning> {
    val out = ArrayList<SetupWarning>()
    if (!notificationsOn) {
        out.add(SetupWarning.NOTIFICATIONS_OFF)
    } else if (!channelOn) {
        out.add(SetupWarning.CHANNEL_OFF)
    }
    if (!batteryExempt) out.add(SetupWarning.BATTERY_MANAGED)
    if (!exactAllowed) out.add(SetupWarning.INEXACT_ALARMS)
    return out
}

/**
 * The order the home list is drawn in.
 *
 * Sorted by when each task next rings, because that is the question the app
 * exists to answer and the list is the answer to it.
 *
 * Two rules on top of that. Anything already ticked sinks below everything that
 * is not — a finished thing is still worth seeing, since a mis-tap has to be
 * undoable, but it is not what the list is for. And a task the queue said
 * nothing about goes last rather than being dropped: it has no alarm to sort
 * by, and hiding it is how a paused or broken task becomes invisible instead of
 * quiet.
 *
 * @param ids every task, in whatever order the database returned them
 * @param queued task ids in the order the queue rings them, soonest first
 * @param done the ids that are ticked off right now
 */
fun homeOrder(ids: List<String>, queued: List<String>, done: Set<String>): List<String> {
    val place = HashMap<String, Int>()
    for ((i, id) in queued.withIndex()) if (!place.containsKey(id)) place[id] = i
    // Stable sort, so two tasks with nothing queued keep the order they arrived
    // in rather than swapping places between one redraw and the next.
    return ids.sortedWith(
        compareBy(
            { if (done.contains(it)) 1 else 0 },
            { place[it] ?: Int.MAX_VALUE },
        ),
    )
}


// ─── the shape of the home list ─────────────────────────────────────────────

/**
 * One task, reduced to the four things the list draws it from.
 *
 * Not ScheduledTask: this is deliberately a smaller thing, so that the grouping
 * below can be tested without building a schedule, a timezone and a queue to
 * get one row on a screen.
 */
data class HomeRow(
    val id: String,
    val name: String,
    /** When it next rings, or null when nothing is queued for it. */
    val fireAt: Instant?,
    /** It falls inside quiet hours and will arrive without a sound. */
    val silent: Boolean,
    val done: Boolean,
)

/** A day's worth of rows, with anything they all have in common said once. */
data class HomeSection(
    val title: String,
    /** Hoisted out of the rows when every row in the section says it. */
    val note: String?,
    val rows: List<HomeRow>,
)

private const val SILENT = "ช่วงเงียบ ไม่มีเสียง"

/**
 * The list, cut into days.
 *
 * WHY THIS EXISTS
 *
 * The flat version repeated itself. Four daily tasks that all reset at midnight
 * all get pushed to the same time by quiet hours, so four rows in a row read
 * exactly "พรุ่งนี้ 08:00 · อีก 23 ชม. 10 นาที · เลื่อนจากรอบเงียบ" — the same
 * sentence, four times, at full width. Every word of it was true and almost
 * none of it was information.
 *
 * A day heading says the day once. The clock goes in its own column on the
 * right, where four identical times line up and stop being four sentences. And
 * when every row under a heading is silenced by quiet hours, that
 * moves up to the heading, which is the case in the screenshot that started
 * this.
 *
 * ORDER
 *
 * Preserved exactly as given. The caller has already sorted by [homeOrder], and
 * a second opinion about order in here is how two rules end up disagreeing.
 */
fun homeSections(rows: List<HomeRow>, now: Instant, zone: TimeZone): List<HomeSection> {
    val out = ArrayList<HomeSection>()
    val undated = ArrayList<HomeRow>()
    val ticked = ArrayList<HomeRow>()

    // Insertion-ordered, so the days come out in the order the queue reached
    // them rather than in alphabetical order of the words for them.
    val days = LinkedHashMap<String, MutableList<HomeRow>>()

    for (r in rows) {
        when {
            // Finished things sink, whatever time they would otherwise sort to.
            // Still on screen, because a mis-tap has to be undoable, but they
            // are not what the list is for.
            r.done -> ticked.add(r)
            r.fireAt == null -> undated.add(r)
            else -> days.getOrPut(dayPhrase(r.fireAt, now, zone)) { ArrayList() }.add(r)
        }
    }

    for ((title, group) in days) {
        // One row saying it is not a repetition, so it stays on the row where
        // the clock it explains is.
        val hoist = group.size > 1 && group.all { it.silent }
        out.add(HomeSection(title, if (hoist) SILENT else null, group))
    }
    if (undated.isNotEmpty()) out.add(HomeSection("ไม่มีกำหนด", null, undated))
    if (ticked.isNotEmpty()) out.add(HomeSection("ติ๊กแล้ว", null, ticked))
    return out
}

/**
 * The clock in the right-hand column, or nothing when there is no next round.
 */
fun rowClock(row: HomeRow, zone: TimeZone): String =
    if (row.fireAt == null) "" else clockOf(row.fireAt, zone)

/**
 * The second line of a row, which exists only when it has something to add.
 *
 * The countdown is dropped past a day. Under a heading that already says
 * พรุ่งนี้, "อีก 23 ชม. 10 นาที" is the same fact spelled out longer, and it is
 * the line that made four rows look like a wall of text.
 *
 * @param hoisted the section heading is already carrying the quiet note
 */
fun rowNote(row: HomeRow, now: Instant, hoisted: Boolean): String {
    if (row.done) return ""
    if (row.fireAt == null) return "ไม่มีรอบถัดไป"
    val parts = ArrayList<String>()
    if ((row.fireAt - now).inWholeHours < 24) parts.add(untilPhrase(now, row.fireAt))
    if (row.silent && !hoisted) parts.add(SILENT)
    return parts.joinToString(" · ")
}