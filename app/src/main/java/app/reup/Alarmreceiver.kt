package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.reup.sync.isDoneNow
import app.reup.sync.isoMillis

/**
 * What runs when an alarm goes off — with the app closed, the screen locked,
 * and no Activity anywhere.
 *
 * onReceive gets roughly ten seconds before the system kills the process, and
 * this now reads a database, which is exactly the case the old header said
 * would need a coroutine and a goAsync(). Both are here. The read is
 * milliseconds; the ten seconds is not the constraint, forgetting to say
 * "finished" is, and that lives in one place in Repo.kt.
 *
 * THE TEST NOTIFICATION DELIBERATELY TOUCHES NOTHING
 * --------------------------------------------------
 * It returns before any of this. That keeps the one tool for answering "does
 * this phone deliver alarms at all" working on a device with an empty database,
 * which is the state this app is in until the first sync lands. Two unknowns at
 * once is how a quiet evening turns into a long one.
 *
 * AN ALARM CAN OUTLIVE ITS TASK
 * -----------------------------
 * Up to eight alarms sit registered with the OS at a time, and a task can be
 * deleted on the desktop and synced away here in between one being set and it
 * firing. The old code took the name from a hardcoded list and fell back to
 * printing the id, so that case would have posted a notification titled with a
 * uuid. Now a name that is not in the database means the task is gone, and the
 * right answer is silence and a rebuilt queue rather than a notification about
 * something that no longer exists.
 *
 * Rescheduling here is what makes the chain self-healing. Eight alarms are
 * registered at a time; each fire puts another eight in place, so the queue
 * never runs down as long as one link lands.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Notifications.ensureChannel(context)

        if (intent.getBooleanExtra(Scheduler.EXTRA_TEST, false)) {
            Notifications.post(
                context,
                id = 1,
                title = "ทดสอบการแจ้งเตือน",
                body = "ถ้าเห็นข้อความนี้ แปลว่าระบบตั้งเวลาทำงานได้ตอนแอปปิดอยู่",
            )
            return
        }

        val taskId = intent.getStringExtra(Scheduler.EXTRA_TASK_ID) ?: return
        val resetAtMs = intent.getLongExtra(Scheduler.EXTRA_RESET_AT, 0L)
        val shifted = intent.getBooleanExtra(Scheduler.EXTRA_SHIFTED, false)

        finishLater(TAG) {
            // ── ask the folder first, but only for a moment ───────────────────
            //
            // This is the one wakeup this app gets for free. The process is
            // already up, the radio is already up — an inexact alarm fires
            // inside a maintenance window, which is exactly when the system
            // hands background apps their network back — and the thing about to
            // happen is the thing most worth being right about. A task ticked
            // off on the desktop an hour ago should not buzz a phone in a
            // pocket, and until now nothing here could know that it had been.
            //
            // WHY IT IS TIMEBOXED AND WHY FAILING IS FINE
            //
            // goAsync buys about ten seconds before the system stops waiting.
            // Six is a generous ceiling for one small blob over a working
            // connection and a hard stop on a bad one, and everything below
            // this line works from the local database whether it succeeded or
            // not. A sync that does not finish costs a notification that is as
            // correct as the last time this phone was opened, which is what it
            // would have been anyway.
            //
            // Nothing is retried. The next alarm is the next attempt, and a
            // receiver that keeps trying is a receiver that is still running
            // when the system decides it has had long enough.
            // TWICE, INSIDE THE SAME BUDGET, AND WHY
            //
            // The case this is for: the task is ticked off on the desktop a few
            // seconds before this alarm is due. The desktop notices it has
            // something to send within a couple of seconds and uploads; this
            // side asks once at the moment it wakes. Whether the buzz happens
            // comes down to which of those two landed first, which is a coin
            // toss decided by the network.
            //
            // A second look a few seconds later costs nothing that is not
            // already spent — the process is up, the radio is up, and goAsync
            // has been holding this open the whole time — and it turns the toss
            // into "the desktop had five seconds", which it almost always did.
            //
            // It stops as soon as something arrives, so the normal case is one
            // request, exactly as before.
            try {
                withTimeout(7_000) {
                    if (!Repo.syncQuietly(context)) {
                        delay(3_000)
                        Repo.syncQuietly(context)
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "no sync before this alarm: ${e.message}")
            }

            val repo = Repo.open(context)
            val label = repo.labels()[taskId]

            // ── DONE IS CHECKED HERE, NOT WHEN THE ALARM WAS SET ──────────────
            //
            // The desktop has had this line since before sync existed: its
            // notifier skips a task whose cycle is already complete, and it asks
            // at the moment the notification would go out. This side never asked
            // at all, because until this week nothing on this phone could mark
            // anything done.
            //
            // Asking now rather than when the queue was built is the whole
            // point. An alarm is registered up to eight ahead, and everything
            // that would silence it happens afterwards: ticking it here, ticking
            // it on the desktop and syncing it down, or a sync landing while the
            // screen is off. A queue built before any of that cannot know.
            //
            // Silence, and then rebuild. Rebuilding matters more than the
            // silence: each fire is what puts the next eight alarms in place, so
            // returning early without it would make a completed task the thing
            // that quietly ends the chain.
            if (label != null && isDoneNow(repo.completions()[taskId], isoMillis(System.currentTimeMillis()))) {
                Log.i(TAG, "alarm for a task already ticked done: $taskId")
                Scheduler.reschedule(context)
                return@finishLater
            }

            if (label == null) {
                // Gone since this alarm was set. Rebuild anyway: the queue may
                // still be holding other alarms for tasks that are also gone.
                Log.i(TAG, "alarm for a task that no longer exists: $taskId")
                Scheduler.reschedule(context)
                return@finishLater
            }

            val zone = TimeZone.currentSystemDefault()
            val resetAt = Instant.fromEpochMilliseconds(resetAtMs)
            val nowMs = System.currentTimeMillis()

            // Three different situations, three different sentences. Saying "ถึง
            // รอบแล้ว" for a reset that happened five hours ago, or for one that
            // has not happened yet, is the kind of small dishonesty that teaches
            // people to stop reading notifications.
            val body = when {
                shifted -> "รีเซ็ตไปแล้วตอน ${clock(resetAt, zone)}"
                resetAtMs > nowMs + 60_000 -> "อีก ${minutesUntil(resetAtMs, nowMs)} นาทีจะรีเซ็ต"
                else -> "ถึงรอบแล้ว"
            }

            // notify() replaces any notification with the same id, so one per
            // task means a task can never stack up several of itself in the
            // shade while nobody is looking. Keyed on the uid's hash rather than
            // the row id, for the reason TaskRepo gives: a row id means a
            // different task on each device.
            // The uid goes with it so the notification can carry a button that
            // ticks this row without opening anything. The test notification
            // above passes none, and gets none.
            Notifications.post(
                context,
                id = taskId.hashCode(),
                title = label,
                body = body,
                taskId = taskId,
            )

            Scheduler.reschedule(context)
        }
    }

    private fun clock(at: Instant, zone: TimeZone): String {
        val t = at.toLocalDateTime(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private fun minutesUntil(thenMs: Long, nowMs: Long): Long =
        ((thenMs - nowMs) + 59_999) / 60_000

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}