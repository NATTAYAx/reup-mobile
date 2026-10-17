package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
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
internal val THAI_DAYS = listOf(
    "อาทิตย์", "จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์",
)

/** Shared with the widget, which says "ศ. 04:00" in the same two letters. */
internal val THAI_DAYS_SHORT = listOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")

internal val THAI_MONTHS = listOf(
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
 * The small line above the screen's title: which day it is, in words.
 *
 * No year. A year is only useful when there is doubt about which one it is, and
 * a phone that is awake and showing this is not in any doubt. No "วัน" in front
 * either, since the title under it is วันนี้ and two of them stacked is a stutter.
 */
fun headerDate(now: Instant, zone: TimeZone): String {
    val t = now.toLocalDateTime(zone)
    val dow = t.dayOfWeek.isoDayNumber % 7
    return THAI_DAYS[dow] + " " + t.dayOfMonth + " " + THAI_MONTHS[t.monthNumber - 1]
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
    /** The person turned reminders off on this phone, on purpose. */
    DEVICE_OFF,

    /** The person said no to notifications, or turned them off later. */
    NOTIFICATIONS_OFF,

    /** Notifications are allowed but this app's channel was muted. */
    CHANNEL_OFF,

    /** The vendor's battery manager is free to stop waking the app. */
    BATTERY_MANAGED,

    /** Android's own pause-if-unused may stop the app after a few months. */
    HIBERNATION,

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
    hibernationExempt: Boolean = true,
    deviceOff: Boolean = false,
): List<SetupWarning> {
    // Said alone, by the same rule as the first one below: while the person has
    // turned this phone off, every other line is a fix for something that is
    // not happening anyway.
    if (deviceOff) return listOf(SetupWarning.DEVICE_OFF)
    val out = ArrayList<SetupWarning>()
    if (!notificationsOn) {
        out.add(SetupWarning.NOTIFICATIONS_OFF)
    } else if (!channelOn) {
        out.add(SetupWarning.CHANNEL_OFF)
    }
    if (!batteryExempt) out.add(SetupWarning.BATTERY_MANAGED)
    if (!hibernationExempt) out.add(SetupWarning.HIBERNATION)
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
 * One task, reduced to what the list draws it from.
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
    /** The reset [fireAt] is about. Later than it when a lead time applies. */
    val resetAt: Instant? = null,
    /** Ticked today, where the person is standing. Only read when [done]. */
    val doneToday: Boolean = false,
    /** Snoozed on the desktop, so nothing rings before the pause ends. */
    val paused: Boolean = false,
    /** How often it comes back, from [cadencePhrase]. */
    val cadence: String? = null,
    /** The desktop's category key: game, school, work or personal. */
    val category: String? = null,
    /** Comes back on its own. A one-off that is ticked is simply done. */
    val recurring: Boolean = true,
    /** Comes back less often than every day. See [longCycle]. */
    val longCycle: Boolean = false,
    /**
     * When the task was made, as the row says it ("YYYY-MM-DD HH:MM:SS"), or
     * null. Only ever compared: rounds due at one moment are named oldest
     * first, which is the order the desktop's board names them in.
     */
    val createdAt: String? = null,
)

/** A group of rows under one heading, with anything they all share said once. */
data class HomeSection(
    val title: String,
    /** Hoisted out of the rows when every row in the section says it. */
    val note: String?,
    val rows: List<HomeRow>,
)

/**
 * What the Today tab draws.
 *
 * Exactly one of [next] and [calm] is set. Either something is left to do
 * today, and the soonest of it gets the big card with the button on it, or
 * nothing is, and the screen says that in words instead of drawing an empty
 * card that looks like something failed to load.
 */
data class TodayFace(
    val next: HomeRow?,
    val calm: Calm?,
    val sections: List<HomeSection>,
)

/** The day with nothing left in it. */
data class Calm(
    val title: String,
    /** "รอบถัดไปคือพรุ่งนี้ 04:00", or null when nothing is queued at all. */
    val nextAt: String?,
    /** Whose round that is. */
    val nextName: String?,
    /** And which row, for a screen that names it once and must not list it again. */
    val nextId: String? = null,
)

/** Said once per section when it is true of every row in it. See [todayFace]. */
const val QUIET_NOTE = "ไม่มีเสียง"

/**
 * The Today tab, cut into what is next, what else is today, and the days after.
 *
 * WHY THE FIRST ROW IS TAKEN OUT OF THE LIST
 *
 * The list answered "what is coming" and left "what do I do now" to the reader,
 * who had to find the top row and then find its button. The soonest thing due
 * today is the one question someone opening this at a bus stop has, so it gets
 * a card of its own with a button big enough for a thumb, and it is not drawn a
 * second time underneath.
 *
 * Only something due TODAY. A daily that resets at four tomorrow morning is not
 * "next" at eight in the evening in any sense a person means; it is tomorrow's.
 * When nothing is left today the card becomes [Calm], which names the next
 * round in words, because an empty slot where a card should be reads as a bug.
 *
 * WHERE A TICKED ROW GOES
 *
 * A row ticked today stays in today's group, at the bottom, so the day reads as
 * done rather than as empty, and a mis-tap is undone where it happened. A row
 * ticked on an earlier day and still inside its cycle - a weekly done on
 * Monday - is not today's work, so it sits in a group of its own at the end,
 * still on screen, because hiding a finished thing is how a mis-tap becomes
 * permanent.
 *
 * WHEN A REASON IS SAID ONCE
 *
 * The flat list once read "พรุ่งนี้ 08:00 · เลื่อนจากรอบเงียบ" four times at full
 * width. When every row still waiting in a group arrives silently, the word
 * moves to the heading; one row on its own keeps it, since "all of them" moves
 * a word up one line for nothing there.
 *
 * ORDER
 *
 * Preserved exactly as given. The caller has already sorted by [homeOrder], and
 * a second opinion about order in here is how two rules end up disagreeing.
 */
fun todayFace(rows: List<HomeRow>, now: Instant, zone: TimeZone): TodayFace {
    val today = now.toLocalDateTime(zone).date

    val dueToday = ArrayList<HomeRow>()
    val undated = ArrayList<HomeRow>()
    val doneToday = ArrayList<HomeRow>()
    val doneBefore = ArrayList<HomeRow>()

    // Insertion-ordered, so the days come out in the order the queue reached
    // them, which is the order they happen in.
    val later = LinkedHashMap<LocalDate, MutableList<HomeRow>>()

    for (r in rows) {
        val at = r.fireAt
        when {
            r.done && r.doneToday -> doneToday.add(r)
            r.done -> doneBefore.add(r)
            at == null -> undated.add(r)
            else -> {
                val day = at.toLocalDateTime(zone).date
                // The queue only holds the future, so "not after today" is
                // today. Said this way so a clock that moved backwards puts a
                // row on today rather than on a day that has already gone.
                if (today.daysUntil(day) <= 0) dueToday.add(r)
                else later.getOrPut(day) { ArrayList() }.add(r)
            }
        }
    }

    val next = dueToday.firstOrNull()
    val rest = dueToday.drop(1)

    val sections = ArrayList<HomeSection>()
    if (rest.isNotEmpty() || doneToday.isNotEmpty()) {
        sections.add(
            HomeSection(
                title = if (rest.isNotEmpty()) "ที่เหลือวันนี้" else "ทำแล้ววันนี้",
                note = hoisted(rest),
                rows = rest + doneToday,
            ),
        )
    }
    for (group in later.values) {
        sections.add(HomeSection(dayTitle(group[0].fireAt!!, now, zone), hoisted(group), group))
    }
    if (undated.isNotEmpty()) sections.add(HomeSection("ไม่มีกำหนด", null, undated))
    if (doneBefore.isNotEmpty()) sections.add(HomeSection("ทำแล้ว รอรอบใหม่", null, doneBefore))

    val calm = if (next != null) null else {
        val upcoming = later.values.firstOrNull()?.firstOrNull()
        Calm(
            title = if (doneToday.isNotEmpty()) "วันนี้ทำครบแล้ว" else "วันนี้ไม่มีอะไรรีเซ็ต",
            nextAt = upcoming?.fireAt?.let { "รอบถัดไปคือ" + whenWords(it, now, zone) },
            nextName = upcoming?.name,
            nextId = upcoming?.id,
        )
    }
    return TodayFace(next, calm, sections)
}

/** The quiet note, when every row still waiting says it and there is more than one. */
private fun hoisted(waiting: List<HomeRow>): String? =
    if (waiting.size > 1 && waiting.all { it.silent }) QUIET_NOTE else null

/**
 * A day as a heading: "พรุ่งนี้ · ศุกร์ 9 ต.ค.", or the weekday and date further
 * out.
 *
 * The date rides along with พรุ่งนี้ because a heading is read once, carefully,
 * and "tomorrow" alone makes the reader work out which day that is.
 */
fun dayTitle(at: Instant, now: Instant, zone: TimeZone): String {
    val today = now.toLocalDateTime(zone).date
    val t = at.toLocalDateTime(zone)
    val date = THAI_DAYS[t.dayOfWeek.isoDayNumber % 7] + " " + t.dayOfMonth + " " +
            THAI_MONTHS_SHORT[t.monthNumber - 1]
    return when (today.daysUntil(t.date)) {
        0 -> "วันนี้"
        1 -> "พรุ่งนี้ · $date"
        -1 -> "เมื่อวาน · $date"
        else -> date
    }
}

/** "พรุ่งนี้ 04:00", "วันศุกร์ 04:00", "วันที่ 20 ต.ค. 04:00" - for a sentence. */
private fun whenWords(at: Instant, now: Instant, zone: TimeZone): String {
    val today = now.toLocalDateTime(zone).date
    val t = at.toLocalDateTime(zone)
    val clock = clockOf(at, zone)
    return when (val diff = today.daysUntil(t.date)) {
        0 -> "วันนี้ $clock"
        1 -> "พรุ่งนี้ $clock"
        in 2..6 -> "วัน" + THAI_DAYS[t.dayOfWeek.isoDayNumber % 7] + " " + clock
        else -> if (diff < 0) clock else
            "วันที่ " + t.dayOfMonth + " " + THAI_MONTHS_SHORT[t.monthNumber - 1] + " " + clock
    }
}

/**
 * The line under the name on the big card: "13:00 · อีก 33 นาที".
 *
 * The countdown is here and not on the rows, because this is the one row whose
 * "how long" is the question being asked.
 */
fun heroLine(row: HomeRow, now: Instant, zone: TimeZone): String {
    val at = row.fireAt ?: return ""
    val sameDay = now.toLocalDateTime(zone).date == at.toLocalDateTime(zone).date
    val time = if (sameDay) clockOf(at, zone) else dayPhrase(at, now, zone) + " " + clockOf(at, zone)
    val parts = arrayListOf(time, untilPhrase(now, at))
    if (row.silent) parts.add(QUIET_NOTE)
    return parts.joinToString(" · ")
}

/**
 * The clock in the right-hand column, or nothing when there is no next round.
 */
fun rowClock(row: HomeRow, zone: TimeZone): String =
    if (row.fireAt == null) "" else clockOf(row.fireAt, zone)

/**
 * The second line of a row: how often, what kind, and anything unusual.
 *
 * A ticked row says when it comes back instead, because that is the only thing
 * still worth knowing about it. Null when there is nothing to add, so the row is
 * one line rather than one line and an empty one.
 *
 * @param hoisted the heading already says the quiet note for this group
 */
fun rowLine(row: HomeRow, now: Instant, zone: TimeZone, hoisted: Boolean): String? {
    if (row.done) {
        // A one-off ticked before its deadline still has that deadline queued,
        // and "รอบใหม่ 18:00" would promise a round that is never coming.
        if (!row.recurring) return "ทำแล้ว"
        val back = row.resetAt ?: row.fireAt ?: return "ทำแล้ว"
        return "ทำแล้ว · รอบใหม่ " + comeBack(back, now, zone)
    }
    val parts = ArrayList<String>()
    row.cadence?.let { parts.add(it) }
    categoryName(row.category)?.let { parts.add(it) }
    if (row.paused) parts.add("พักอยู่")
    if (row.silent && !hoisted) parts.add(QUIET_NOTE)
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * When a ticked row comes back. A clock inside a day, where "04:00" can only
 * mean the next one; a weekday and clock inside a week; a date past that.
 */
private fun comeBack(at: Instant, now: Instant, zone: TimeZone): String {
    val hours = (at - now).inWholeHours
    val t = at.toLocalDateTime(zone)
    return when {
        hours < 24 -> clockOf(at, zone)
        hours < 24 * 7 -> THAI_DAYS_SHORT[t.dayOfWeek.isoDayNumber % 7] + " " + clockOf(at, zone)
        else -> t.dayOfMonth.toString() + " " + THAI_MONTHS_SHORT[t.monthNumber - 1]
    }
}

/**
 * How often a task comes back, in the words a person would use.
 *
 * Weekly names its day, because "ทุกสัปดาห์" leaves the one useful fact out.
 * Null for a type this build has never heard of: a newer desktop can invent
 * one, and saying nothing is better than guessing a rhythm.
 */
fun cadencePhrase(spec: ResetSpec): String? = when (spec.resetType) {
    ResetType.DAILY -> "ทุกวัน"
    ResetType.WEEKLY -> spec.resetDay?.takeIf { it in 0..6 }?.let { "ทุกวัน" + THAI_DAYS[it] } ?: "ทุกสัปดาห์"
    ResetType.BIWEEKLY -> "ทุก 2 สัปดาห์"
    ResetType.CUSTOM_DAYS -> {
        val n = spec.resetIntervalDays
        when {
            n == null || n < 1 -> "รอบกำหนดเอง"
            n == 1 -> "ทุกวัน"
            else -> "ทุก $n วัน"
        }
    }
    ResetType.EVENT_WINDOW -> "กิจกรรมจำกัดเวลา"
    ResetType.ONE_TIME, ResetType.SPECIFIC_DATE -> "ครั้งเดียว"
    else -> null
}

/**
 * Whether a task comes back on its own. Mirror of the desktop's isRecurring,
 * and the same set TaskRepo's doneUntil decides a tick's length by.
 */
fun recurs(spec: ResetSpec): Boolean = spec.resetType in setOf(
    ResetType.DAILY, ResetType.WEEKLY, ResetType.BIWEEKLY, ResetType.CUSTOM_DAYS,
)

/**
 * Whether a task comes back less often than every day: weekly, every two
 * weeks, every few days.
 *
 * What the widget lists under รอบยาวที่ยังไม่ทำ. A daily is never there - its
 * round always ends within a day, so it is either today's or done - and
 * "every 1 day" is a daily however it was typed in.
 */
fun longCycle(spec: ResetSpec): Boolean = when (spec.resetType) {
    ResetType.WEEKLY, ResetType.BIWEEKLY -> true
    ResetType.CUSTOM_DAYS -> (spec.resetIntervalDays ?: 0) > 1
    else -> false
}

/** The desktop's four task categories, in its Thai. Null for anything else. */
fun categoryName(key: String?): String? = when (key) {
    "game" -> "เกม"
    "school" -> "การเรียน"
    "work" -> "งาน"
    "personal" -> "ส่วนตัว"
    else -> null
}

/**
 * Whether a tick happened today where the person is.
 *
 * `completed_at` is written by the database clock on this side and by
 * toISOString on the desktop, both UTC with milliseconds. Anything that does not
 * parse is "not today", which puts the row in the group at the bottom rather
 * than losing it.
 */
fun tickedToday(completedAt: String?, now: Instant, zone: TimeZone): Boolean {
    val raw = completedAt?.takeIf { it.isNotBlank() } ?: return false
    val at = runCatching { Instant.parse(raw) }.getOrNull() ?: return false
    return at.toLocalDateTime(zone).date == now.toLocalDateTime(zone).date
}

// ─── the forms ───────────────────────────────────────────────────────────────

/**
 * The day a form is set to, on its third date button: "วันนี้", "เมื่อวาน",
 * "พรุ่งนี้", or "ศ. 9 ต.ค.".
 *
 * Short, because it shares a line with the two buttons that are always there:
 * วันนี้ and เมื่อวาน on the money form, where most money is written down the day
 * it goes or the morning after, and วันนี้ and พรุ่งนี้ on the task form, which
 * looks the other way. Another year adds the year, as the headings do.
 *
 * @param date `YYYY-MM-DD` as stored; anything else is printed as it is
 */
fun dayChip(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return date
    return when (today.daysUntil(d)) {
        0 -> "วันนี้"
        -1 -> "เมื่อวาน"
        1 -> "พรุ่งนี้"
        else -> THAI_DAYS_SHORT[d.dayOfWeek.isoDayNumber % 7] + " " + d.dayOfMonth + " " +
            THAI_MONTHS_SHORT[d.monthNumber - 1] + if (d.year != today.year) " " + d.year else ""
    }
}

/**
 * A clock's hour and minute as a task's reset_time: "04:00", the one spelling
 * the desktop writes and taskProblems accepts.
 *
 * Here rather than beside the time picker. The picker answers in two numbers
 * whatever the phone's language, and the step from those to the string is the
 * part that could disagree with the desktop - which is why the old form had a
 * text box instead of a clock. Out of range is pulled back in rather than
 * thrown: a picker never sends one, and a form is no place to crash.
 */
fun resetTimeText(hour: Int, minute: Int): String =
    pad2(hour.coerceIn(0, 23)) + ":" + pad2(minute.coerceIn(0, 59))

/**
 * A stored reset_time as the hour and minute to open the picker on, or null
 * for no time at all - the end of the day - or for something that is not a
 * time of day.
 *
 * Lenient about "9:00", as everything that reads a stored time is: a row an
 * older version wrote still opens where it is set, and is saved back as
 * "09:00".
 */
fun resetTimeParts(text: String?): Pair<Int, Int>? {
    val m = RESET_TIME_READ.matchEntire(text?.trim() ?: return null) ?: return null
    val hour = m.groupValues[1].toInt()
    val minute = m.groupValues[2].toInt()
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

private val RESET_TIME_READ = Regex("""^(\d{1,2}):(\d{2})$""")
