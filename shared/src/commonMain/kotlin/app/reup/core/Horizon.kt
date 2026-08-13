package app.reup.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes

// ─── Horizon.kt — deciding what the OS gets told about ───────────────────────
//
// The app cannot wake itself up. It hands a list of future instants to the
// operating system and then dies; the OS is what rings. Everything in this file
// exists to build that list.
//
// Two platform facts shape the whole design.
//
// iOS refuses to hold more than 64 pending notification requests per app. Past
// that it keeps the 64 soonest and silently discards the rest — no error, no
// log, just alarms that never arrive. So the app cannot register everything it
// knows about; it registers a window and refills it.
//
// Android clears every alarm on reboot. So this has to be cheap enough to rerun
// from scratch whenever anything happens — a boot, an edit, a timezone change,
// a sync — because "recompute everything" is the only strategy with no state to
// get out of step.
//
// It is a pure function for the same reason nextReset() is: the interesting
// cases are all about time, and a function that reads the clock can only be
// tested at the moment the test runs.

/** A task as far as scheduling is concerned. */
data class ScheduledTask(
    val id: String,
    val spec: ResetSpec,

    /** Ring this many minutes *before* the reset. Null means ring at it. */
    val notifyBeforeMin: Int? = null,

    /** Snoozed until this instant; nothing is scheduled before it. */
    val pausedUntil: Instant? = null,
)

/** One entry in the queue handed to the OS. */
data class Alarm(
    val taskId: String,

    /** When the OS should ring. */
    val fireAt: Instant,

    /** The reset this refers to. Differs from [fireAt] when a lead time or a
     *  quiet-hours shift applies, and it is what the text should mention —
     *  people care when the thing happens, not when the phone buzzed. */
    val resetAt: Instant,

    /**
     * True when quiet hours moved this later. The notification layer needs to
     * know: "resets in 10 minutes" and "reset at 04:00, five hours ago" are
     * different sentences, and [fireAt] alone cannot tell them apart.
     */
    val shiftedOutOfQuiet: Boolean = false,
)

/**
 * A window of the day during which nothing should ring, as "HH:MM" wall-clock
 * times in the app's zone. May wrap midnight ("23:00" to "08:00").
 */
data class QuietHours(val start: String, val end: String)

/**
 * The next [limit] alarms across all [tasks], soonest first.
 *
 * @param now the instant to compute from; only alarms strictly after it are returned
 * @param appZone the zone used by tasks that pin none, and by [quiet]
 * @param limit how many to return — 50 by default, leaving headroom under iOS's 64
 */
fun horizon(
    tasks: List<ScheduledTask>,
    now: Instant,
    appZone: TimeZone,
    limit: Int = 50,
    quiet: QuietHours? = null,
): List<Alarm> {
    if (limit <= 0 || tasks.isEmpty()) return emptyList()

    val quietWindow = quiet?.let(::parseQuiet)
    val out = ArrayList<Alarm>()

    // Each task contributes at most `limit` occurrences. Taking the globally
    // soonest at the end means a daily task cannot crowd out a monthly one:
    // the monthly one's occurrence in thirty days is simply earlier than the
    // daily one's in forty, and sorting handles it. No per-task quota needed,
    // and any quota would have been wrong.
    //
    // maxWalks is a ceiling on walks per task, not on results. An earlier
    // version bounded only the results, and a daily task whose every
    // occurrence got skipped walked the calendar until the year overflowed.
    // Any loop shaped "keep going until we have enough" needs a second bound
    // for the case where enough never arrives.
    val maxWalks = limit * 4 + 32

    for (task in tasks) {
        var cursor = now
        var produced = 0
        var walks = 0

        while (produced < limit && walks < maxWalks) {
            walks++

            val reset = nextReset(task.spec, cursor, appZone) ?: break

            // A reset type that cannot move forward — a fixed date already
            // passed, say — would otherwise spin here forever.
            if (reset <= cursor) break
            cursor = reset

            val lead = task.notifyBeforeMin?.takeIf { it > 0 }
            val wanted = if (lead != null) reset - lead.minutes else reset

            // Lead time can pull an alarm into the past even though its reset
            // is ahead: an hour's warning about something forty minutes away
            // already missed its moment. Skip it and keep walking rather than
            // ringing late about it.
            if (wanted <= now) continue

            val shifted = quietWindow?.let { shiftOutOfQuiet(wanted, it, appZone) }
            val fireAt = shifted ?: wanted

            if (task.pausedUntil != null && fireAt < task.pausedUntil) continue

            out += Alarm(
                taskId = task.id,
                fireAt = fireAt,
                resetAt = reset,
                shiftedOutOfQuiet = shifted != null,
            )
            produced++
        }
    }

    // compareBy on fireAt alone would leave ties in whatever order the tasks
    // happened to be listed in, which makes "did the queue change" impossible
    // to answer. Quiet hours guarantee ties: every alarm inside one window
    // lands on the same instant, so several tasks can share a fireAt exactly.
    // Collapsing those into one notification is the notification layer's job,
    // not this one's — here they stay separate and merely ordered.
    return out
        .sortedWith(compareBy({ it.fireAt }, { it.resetAt }, { it.taskId }))
        .take(limit)
}

// ── quiet hours ──────────────────────────────────────────────────────────────

private data class QuietWindow(val startMin: Int, val endMin: Int, val wraps: Boolean)

private fun parseQuiet(q: QuietHours): QuietWindow? {
    val s = minutesOfDay(q.start) ?: return null
    val e = minutesOfDay(q.end) ?: return null
    // Equal bounds are ambiguous — zero-length or the entire day, depending on
    // who you ask. Treated as "off", because the alternative is an app that
    // silently never notifies and gives no clue why.
    if (s == e) return null
    return QuietWindow(s, e, wraps = s > e)
}

private val HHMM_OF_DAY = Regex("""^(\d{1,2}):(\d{2})$""")

private fun minutesOfDay(hhmm: String): Int? {
    val m = HHMM_OF_DAY.matchEntire(hhmm.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    if (h > 23 || mi > 59) return null
    return h * 60 + mi
}

/**
 * If [at] falls inside the quiet window, the moment it ends. Null if it does
 * not fall inside.
 *
 * Quiet hours are applied here, while the queue is being built, rather than
 * when an alarm fires. On the desktop the app draws its own notifications and
 * can decline to; here the OS draws them and cannot be called back. An alarm
 * that should not ring at 03:00 must never be registered for 03:00 in the
 * first place.
 *
 * Note this deliberately lets an alarm land *after* the reset it describes. A
 * game resetting at 04:00 with quiet hours until 08:00 should say so at 08:00 —
 * "it reset while you were asleep and is waiting" is the useful sentence, and
 * the alternative is a task that never notifies at all and never says why.
 */
private fun shiftOutOfQuiet(at: Instant, w: QuietWindow, zone: TimeZone): Instant? {
    val atMs = at.toEpochMilliseconds()
    val wall = wallClock(atMs, zone)
    val minute = wall.h * 60 + wall.mi

    val inside = if (w.wraps) minute >= w.startMin || minute < w.endMin
    else minute >= w.startMin && minute < w.endMin
    if (!inside) return null

    val endH = w.endMin / 60
    val endMi = w.endMin % 60

    var endMs = wallToEpochMs(atTime(wall, endH, endMi, 0), zone)
    if (endMs <= atMs) endMs = wallToEpochMs(atTime(addDays(wall, 1), endH, endMi, 0), zone)

    return Instant.fromEpochMilliseconds(endMs)
}