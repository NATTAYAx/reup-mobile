package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * What runs when an alarm goes off — with the app closed, the screen locked,
 * and no Activity anywhere.
 *
 * onReceive gets roughly ten seconds on the main thread before the system kills
 * it, so everything here has to be quick: post a notification, rebuild the
 * queue, return. Both are milliseconds. The moment this needs to touch a
 * database or the network it will need a coroutine and a goAsync(), and that is
 * a good reason to keep it doing neither.
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

        val label = Samples.labelOf(taskId)
        val zone = TimeZone.currentSystemDefault()
        val resetAt = Instant.fromEpochMilliseconds(resetAtMs)
        val nowMs = System.currentTimeMillis()

        // Three different situations, three different sentences. Saying "ถึงรอบ
        // แล้ว" for a reset that happened five hours ago, or for one that has
        // not happened yet, is the kind of small dishonesty that teaches people
        // to stop reading notifications.
        val body = when {
            shifted -> "รีเซ็ตไปแล้วตอน ${clock(resetAt, zone)}"
            resetAtMs > nowMs + 60_000 -> "อีก ${minutesUntil(resetAtMs, nowMs)} นาทีจะรีเซ็ต"
            else -> "ถึงรอบแล้ว"
        }

        // notify() replaces any notification with the same id, so one per task
        // means a task can never stack up several of itself in the shade while
        // nobody is looking.
        Notifications.post(context, id = taskId.hashCode(), title = label, body = body)

        Scheduler.reschedule(context)
    }

    private fun clock(at: Instant, zone: TimeZone): String {
        val t = at.toLocalDateTime(zone)
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private fun minutesUntil(thenMs: Long, nowMs: Long): Long =
        ((thenMs - nowMs) + 59_999) / 60_000
}