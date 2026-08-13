package app.reup

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()

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
            tasks = Samples.tasks,
            now = now,
            appZone = zone,
            limit = SLOTS,
            quiet = Samples.quiet,
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

            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarm.fireAt.toEpochMilliseconds(),
                pending,
            )
        }

        Log.i(TAG, "scheduled ${alarms.size} alarms; next=${alarms.firstOrNull()?.fireAt}")
    }

    /**
     * One notification a minute from now.
     *
     * Not debug scaffolding to delete later — this is the only practical way to
     * find out whether the pipeline survives the phone being locked, idle, or
     * asleep, and later whether Samsung's battery manager has quietly stopped
     * it. Waiting until 04:00 to learn that is a bad way to learn it.
     */
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

        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + seconds * 1_000,
            pending,
        )
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