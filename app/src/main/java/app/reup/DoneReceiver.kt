// app/src/main/java/app/reup/DoneReceiver.kt
package app.reup

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * The button in the notification shade.
 *
 * Everything here happens with no Activity anywhere, often with the screen
 * locked, in the ten seconds a broadcast gets before the system stops waiting.
 *
 * THE NOTIFICATION IS TAKEN DOWN FIRST, BEFORE ANY OF THE WORK
 * -----------------------------------------------------------
 * `setAutoCancel(true)` dismisses a notification when its body is tapped. It
 * does nothing for an action button — press one of those and the notification
 * sits there afterwards looking exactly as it did before.
 *
 * A person who presses a button that appears to do nothing presses it again.
 * That is not a problem for the database, since ticking a task that is already
 * ticked writes nothing, but it is a problem for the person: it reads as an app
 * that lost the press. So the shade is cleared synchronously, in the first
 * milliseconds, before the database is opened at all — and it is cleared even
 * when the write turns out to be impossible, because the thing being said is
 * "heard", not "done", and the alternative is a notification that cannot be got
 * rid of by the button that is on it.
 *
 * IT MARKS DONE. IT DOES NOT TOGGLE
 * ---------------------------------
 * The reasoning is written out at Repo.markDoneOnce. The short version: this
 * notification may have been posted hours ago, and the task may have been
 * ticked on the desktop since. A toggle would untick it, silently, and the only
 * evidence would be an alarm going off again later.
 *
 * THE QUEUE IS REBUILT, AND THAT IS NOT OPTIONAL
 * ---------------------------------------------
 * `completed_until` moving is exactly what the horizon reads to decide which
 * eight alarms should exist. Without a rebuild, the alarm for the cycle just
 * ticked stays registered and fires anyway — the receiver would then notice it
 * was already done and go quiet, so nothing visible breaks, but the phone spent
 * a wakeup to say nothing.
 *
 * THE SYNC IS BEST EFFORT AND ON A LEASH
 * --------------------------------------
 * A tick that reaches the desktop within the minute is worth a request; a tick
 * that does not reach it until the app is next opened is still a tick. So the
 * folder gets six seconds and no more, and a failure is a log line rather than
 * anything a person is asked to read. The same shape as the sync in
 * AlarmReceiver, for the same reason: this wakeup is already paid for.
 */
class DoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, taskId.hashCode())

        context.getSystemService(NotificationManager::class.java).cancel(notificationId)

        finishLater(TAG) {
            val marked = Repo.markDoneOnce(
                context,
                taskId,
                Clock.System.now(),
                TimeZone.currentSystemDefault(),
            )

            if (!marked) {
                // Deleted on the desktop while this sat in the shade. There is
                // nothing to tell anybody and nowhere to tell them, but the
                // queue may still be holding alarms for it and others like it.
                Log.i(TAG, "ticked a task that no longer exists: $taskId")
            }

            Scheduler.reschedule(context)

            try {
                withTimeout(6_000) { Repo.syncQuietly(context) }
            } catch (e: Exception) {
                Log.i(TAG, "the tick will go up on the next sync: ${e.message}")
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TAG = "DoneReceiver"
    }
}