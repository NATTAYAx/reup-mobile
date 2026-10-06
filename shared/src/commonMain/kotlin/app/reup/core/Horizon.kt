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

    /**
     * May ring through quiet hours. The person sets it on the task, in
     * advance, the way an alarm is set. On this side every alarm is its task's
     * only one, which makes each of them the last call - the one the desktop
     * also lets through.
     */
    val ringInQuiet: Boolean = false,
)

/** One entry in the queue handed to the OS. */
data class Alarm(
    val taskId: String,

    /** When the OS should ring. */
    val fireAt: Instant,

    /** The reset this refers to. Differs from [fireAt] when a lead time
     *  applies, and it is what the text should mention - people care when the
     *  thing happens, not when the phone buzzed. */
    val resetAt: Instant,

    /**
     * Post it without sound or vibration: it falls inside quiet hours and its
     * task did not ask to be let through.
     *
     * This replaced shiftedOutOfQuiet, which moved the alarm to the end of the
     * window instead. That buzzed at 08:00 about a deadline at 02:00 - six hours
     * late, which is not a delayed reminder but a report - and it meant this
     * phone and the desktop read one synced setting two ways. Quiet hours now
     * mean what they mean on the desktop and in every phone platform's own
     * do-not-disturb: on time, in the list, silently.
     */
    val silent: Boolean = false,
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

    val quietWindow = quiet?.let(::quietWindow)
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

            val fireAt = wanted
            val silent = !task.ringInQuiet &&
                    quietWindow?.let { insideQuiet(wanted, it, appZone) } == true

            if (task.pausedUntil != null && fireAt < task.pausedUntil) continue

            out += Alarm(
                taskId = task.id,
                fireAt = fireAt,
                resetAt = reset,
                silent = silent,
            )
            produced++
        }
    }

    // compareBy on fireAt alone would leave ties in whatever order the tasks
    // happened to be listed in, which makes "did the queue change" impossible
    // to answer. Ties are ordinary: four daily tasks that all reset at
    // midnight share a fireAt exactly. (Quiet hours used to manufacture them by
    // piling every alarm in the window onto its end; they no longer move
    // anything, but the tie-break stays because the ties never needed them.)
    // Collapsing those into one notification is the notification layer's job,
    // not this one's — here they stay separate and merely ordered.
    return out
        .sortedWith(compareBy({ it.fireAt }, { it.resetAt }, { it.taskId }))
        .take(limit)
}

// ── quiet hours ──────────────────────────────────────────────────────────────

private data class QuietWindow(val startMin: Int, val endMin: Int, val wraps: Boolean)

/**
 * Named for what it returns rather than what it does, because there is a
 * different parseQuiet in the sync module that turns stored JSON into a
 * setting. Two functions with one name across two packages is not a clash the
 * compiler minds and is one a person does.
 */
private fun quietWindow(q: QuietHours): QuietWindow? {
    val s = minutesOfDay(q.start) ?: return null
    val e = minutesOfDay(q.end) ?: return null
    // Equal bounds are ambiguous — zero-length or the entire day, depending on
    // who you ask. Treated as "off", because the alternative is an app that
    // silently never notifies and gives no clue why.
    if (s == e) return null
    return QuietWindow(s, e, wraps = s > e)
}

/**
 * Minutes since midnight, from the same parse the scheduler uses.
 *
 * This file used to carry its own regex and its own parser, identical to the
 * pair in ResetSchedule down to the character. Two copies of one rule about
 * what a time looks like, in one package, in a project that has spent a month
 * removing exactly that.
 */
private fun minutesOfDay(hhmm: String): Int? {
    val hm = parseHHMM(hhmm) ?: return null
    return hm.h * 60 + hm.mi
}

/**
 * Whether [at] falls inside the quiet window.
 *
 * Applied here, while the queue is being built, and not when an alarm fires -
 * the reasoning that used to justify moving alarms still holds for deciding
 * about them: the OS draws these notifications and cannot be called back, so
 * whether one makes a sound has to be settled before it is registered. What
 * changed is the answer. An alarm inside the window is registered at its real
 * time and posted on the silent channel, so a game that reset at 04:00 is
 * sitting in the list when the person wakes, and a deadline at 02:00 is on
 * time rather than six hours late.
 */
private fun insideQuiet(at: Instant, w: QuietWindow, zone: TimeZone): Boolean {
    val wall = wallClock(at.toEpochMilliseconds(), zone)
    val minute = wall.h * 60 + wall.mi
    return if (w.wraps) minute >= w.startMin || minute < w.endMin
    else minute >= w.startMin && minute < w.endMin
}
// ── this device ──────────────────────────────────────────────────────────────

/**
 * Which reminders this phone gives.
 *
 * Stays on the phone and never syncs, because it answers a question about the
 * device in somebody's hand rather than about the person: "not on the phone
 * tonight, the computer is enough" must not reach over and quiet the computer.
 * The desktop's sound switch stays on the desktop for the same reason. What is
 * about the person - quiet hours, which task may wake them - does sync.
 */
enum class DeviceNotify(val id: String) {
    /** Vibrates as usual; quiet hours and ring_in_quiet apply. */
    ON("on"),
    /** Everything goes in the shade, nothing vibrates. */
    SILENT("silent"),
    /** Nothing is registered with the system at all. Tasks still sync. */
    OFF("off");

    companion object {
        /** Anything unrecognised is ON: the failure that silences reminders
         *  nobody asked to silence is the worse one to pick. */
        fun parse(s: String?): DeviceNotify = entries.firstOrNull { it.id == s } ?: ON
    }
}

/** The queue as this device will actually register it. */
fun applyDeviceNotify(alarms: List<Alarm>, mode: DeviceNotify): List<Alarm> = when (mode) {
    DeviceNotify.ON -> alarms
    DeviceNotify.SILENT -> alarms.map { if (it.silent) it else it.copy(silent = true) }
    DeviceNotify.OFF -> emptyList()
}