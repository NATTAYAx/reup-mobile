package app.reup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.reup.core.horizon
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * Handing the queue to the operating system.
 *
 * INEXACT ON PURPOSE. `setAndAllowWhileIdle` lets Android batch this app's
 * wake-up with every other app's, so the CPU comes up once and serves them all.
 * The exact variants each force their own wake and, on a phone whose battery is
 * already worn, that difference is the whole reason the app is welcome to stay
 * installed. The cost is that an alarm can land minutes late — which for "the
 * game reset" or "pay the bill" is not a cost at all. Exact timing becomes a
 * per-task opt-in later, for the handful of tasks that genuinely need it, and
 * that opt-in is what will ask for SCHEDULE_EXACT_ALARM. Until then the app
 * needs no special permission at all.
 *
 * RECOMPUTE, NEVER PATCH. Every entry point rebuilds the entire queue from
 * scratch: opening the app, an alarm firing, a reboot, an app update. There is
 * no stored state about what was scheduled, so there is no stored state to
 * drift out of sync with reality. It costs a few milliseconds and removes an
 * entire category of bug.
 *
 * THE LIST COMES FROM THE DATABASE NOW. That is the whole of this change, and
 * it is why reschedule() suspends: reading rows is I/O, and a function that
 * reads I/O cannot pretend otherwise for the convenience of its callers. The
 * three callers each had to say how they wanted to wait, which is the point —
 * an Activity has a scope, a receiver has goAsync, and neither should have been
 * guessing.
 *
 * An empty database is a correct answer and produces no alarms. It is also the
 * state this app is in until the first sync brings rows down, so the screen
 * says which of the two it is rather than leaving silence to be interpreted.
 */
object Scheduler {

    private const val TAG = "Scheduler"

    /** How many alarms sit registered at once. */
    private const val SLOTS = 8

    private const val REQ_BASE = 7_000
    private const val REQ_TEST = 6_999

    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_RESET_AT = "reset_at"
    const val EXTRA_SHIFTED = "shifted"
    const val EXTRA_TEST = "test"

    /**
     * Cancel everything and re-register the next [SLOTS] alarms.
     *
     * Eight rather than fifty because Android has no per-app cap and each
     * registered alarm is a real object the system holds. Every fire triggers
     * another recompute, so the chain refills itself; eight is enough slack
     * that missing one or two links does not break it, and a reboot rebuilds it
     * regardless.
     */
    suspend fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()

        val repo = Repo.open(context)
        val tasks = repo.tasks()
        // Through Repo so that the queue and the window printed above it on the
        // home screen are resolved by one function. Null is a real answer here
        // — quiet hours turned off on the desktop — and Horizon already treats a
        // null as none.
        val quiet = Repo.quietHours(context)

        // Cancel first, always, and cancel every slot rather than the ones we
        // think are in use. A slot left over from a previous list is an alarm
        // for a task that may no longer exist.
        for (slot in 0 until SLOTS) {
            existing(context, REQ_BASE + slot)?.let {
                am.cancel(it)
                it.cancel()
            }
        }

        val alarms = horizon(
            tasks = tasks,
            now = now,
            appZone = zone,
            limit = SLOTS,
            quiet = quiet,
        )

        alarms.forEachIndexed { slot, alarm ->
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_TASK_ID, alarm.taskId)
                putExtra(EXTRA_RESET_AT, alarm.resetAt.toEpochMilliseconds())
                putExtra(EXTRA_SHIFTED, alarm.shiftedOutOfQuiet)
            }

            val pending = PendingIntent.getBroadcast(
                context,
                REQ_BASE + slot,
                intent,
                // IMMUTABLE is required from API 31 up. UPDATE_CURRENT is what
                // refreshes the extras: PendingIntent equality ignores extras
                // entirely, so without it slot 3 would keep whatever payload it
                // was first created with, forever.
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            wakeAt(am, alarm.fireAt.toEpochMilliseconds(), pending)
        }

        // The task count is in the line on purpose. "scheduled 0 alarms" has two
        // causes that look identical in a log — no tasks, or tasks that all
        // resolve to nothing schedulable — and this tells them apart without
        // anyone having to reproduce it.
        Log.i(
            TAG,
            "scheduled ${alarms.size} alarms from ${tasks.size} tasks; " +
                    "next=${alarms.firstOrNull()?.fireAt}",
        )
    }

    /**
     * One notification a minute from now.
     *
     * Not debug scaffolding to delete later — this is the only practical way to
     * find out whether the pipeline survives the phone being locked, idle, or
     * asleep, and later whether Samsung's battery manager has quietly stopped
     * it. Waiting until 04:00 to learn that is a bad way to learn it.
     */
    /** For the home screen, which has to say when reminders will be late. */
    fun exactAllowed(context: Context): Boolean =
        canBeExact(context.getSystemService(AlarmManager::class.java))

    fun fireTestIn(context: Context, seconds: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(EXTRA_TEST, true)

        val pending = PendingIntent.getBroadcast(
            context,
            REQ_TEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The test button uses the same path as a real alarm on purpose: a test
        // that takes a different route answers a question nobody asked.
        wakeAt(am, System.currentTimeMillis() + seconds * 1_000, pending)
    }

    /**
     * Whether this build may wake the phone at the minute it was told.
     *
     * WHY IT IS ASKED EVERY TIME AND NOT CACHED
     *
     * The person can take it away in system settings while the app is not
     * running, and Android kills the process when they do. Caching would only
     * ever be right until the moment it mattered.
     */
    private fun canBeExact(am: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    /**
     * On the minute when allowed, batched when not.
     *
     * WHY THIS IS NOW THE DEFAULT FOR EVERYTHING
     *
     * It used to be batched for every task, to save battery on a phone with a
     * worn one. That was decided while the task list was four hardcoded lines
     * and nothing real could be missed by it, so the saving had no visible
     * price. The price turned out to be a reminder arriving twenty-five minutes
     * late, and this list has medication in it beside the game dailies.
     *
     * The obvious repair — a per-task "must be on time" tick — was worse. It
     * asks a person to predict, months ahead, how much they will care about the
     * timing of a task they are typing in right now, which is the same shape as
     * every other field this project has had to move or delete: the answer it
     * collects is the one given while concentrating, not the true one.
     *
     * And the arithmetic never favoured batching much. There are eight alarms.
     * Waking, reading SQLite and posting a notification is a fraction of a
     * second of CPU. What actually costs power on this path is the sync that
     * now runs inside the alarm — a radio held open for seconds, not a timer
     * fired on the minute. If battery ever has to be bought back, that is the
     * lever, and cutting eight alarms to fewer is the next one. Being twenty
     * minutes late for medication was never a sensible way to pay for it.
     */
    private fun wakeAt(am: AlarmManager, atMillis: Long, pending: PendingIntent) {
        if (canBeExact(am)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            // Not an error and not worth interrupting anyone over. The home
            // screen says so, once, with a way to fix it.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    /** The PendingIntent for a slot if one is registered, or null. */
    private fun existing(context: Context, requestCode: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
}