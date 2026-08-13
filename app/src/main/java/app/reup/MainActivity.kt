package app.reup

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.reup.core.horizon
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/**
 * Phase 2b. The queue is real now - these alarms fire with the app closed.
 *
 * The screen has three jobs: ask for the one runtime permission, show what is
 * actually scheduled, and say plainly when something in the system settings is
 * going to stop the alarms arriving. That third one is the important one. The
 * commonest failure on this hardware is not a bug in the app; it is the phone
 * deciding on its own that a rarely-used app should stop waking up, with no
 * notice to anyone.
 *
 * A note on style: the string building below is deliberately plain. An earlier
 * version nested string literals and lambdas inside templates - all legal
 * Kotlin, all fine until one character got mangled in transit, at which point
 * an unterminated string swallowed the rest of the file and the compiler
 * reported the error two hundred lines away from the actual mistake. Values are
 * computed into locals first now. Slightly longer, far easier to debug.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Notifications.ensureChannel(this)
        requestNotificationPermission()

        status = TextView(this)
        status.setTextColor(Color.parseColor("#E8E8EA"))
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.typeface = Typeface.MONOSPACE

        val testButton = Button(this)
        testButton.text = "ทดสอบ: แจ้งเตือนในอีก 60 วินาที"
        testButton.setOnClickListener {
            Scheduler.fireTestIn(this, 60)
            Toast.makeText(this, "ตั้งแล้ว ปิดแอปแล้วล็อกจอรอได้เลย", Toast.LENGTH_LONG).show()
        }

        val batteryButton = Button(this)
        batteryButton.text = "เปิดหน้าตั้งค่าแบตเตอรี่ของแอป"
        batteryButton.setOnClickListener {
            openBatterySettings()
        }

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(48, 64, 48, 64)
        column.addView(status, rowParams())
        column.addView(testButton, rowParams())
        column.addView(batteryButton, rowParams())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F0F12"))
        scroll.addView(column)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is one of the four moments the queue is rebuilt, and
        // the only one a person can trigger deliberately. It is also what
        // recovers from a timezone change, since those are unreliable to
        // observe and someone who has just landed opens their phone anyway.
        Scheduler.reschedule(this)
        tick()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacksAndMessages(null)
    }

    private fun tick() {
        status.text = render()
        ticker.postDelayed({ tick() }, 1000L)
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = 24
        return p
    }

    private fun render(): String {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val alarms = horizon(Samples.tasks, now, zone, 8, Samples.quiet)

        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(AlarmManager::class.java)

        val notificationsOn = nm.areNotificationsEnabled()
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_RESETS)
        val channelOn = channel != null &&
                channel.importance != NotificationManager.IMPORTANCE_NONE
        val systemAlarmSet = am.nextAlarmClock != null

        val sb = StringBuilder()
        sb.append("game-scheduler / phase 2b\n\n")
        sb.append("zone     ").append(zone.toString()).append("\n")
        sb.append("now      ").append(stamp(now, zone)).append("\n")
        sb.append("รอบเงียบ  ").append(Samples.quiet.start)
            .append(" ถึง ").append(Samples.quiet.end).append("\n\n")

        sb.append("- สถานะที่มีผลกับการเตือน -\n")
        sb.append(mark(notificationsOn)).append(" อนุญาตแจ้งเตือน\n")
        sb.append(mark(channelOn)).append(" ช่องรอบรีเซ็ตเปิดอยู่\n")
        sb.append(mark(batteryExempt)).append(" ยกเว้นการประหยัดแบต\n")
        if (!batteryExempt) {
            // Not a warning for its own sake. On this vendor's software an app
            // left alone for a few days is put to sleep and its alarms stop;
            // the person has no way to know that happened.
            sb.append("   ถ้าไม่ยกเว้น ซัมซุงจะพักแอปเองหลังไม่ได้เปิดไม่กี่วัน\n")
            sb.append("   แล้วการเตือนจะเงียบไปโดยไม่มีอะไรบอก\n")
        }
        sb.append("\n")

        sb.append("- คิวที่ส่งให้ระบบแล้ว (").append(alarms.size).append(") -\n")
        if (alarms.isEmpty()) {
            sb.append("(ว่าง)\n")
        } else {
            for (alarm in alarms) {
                val label = Samples.labelOf(alarm.taskId)
                val left = remaining(alarm.fireAt - now)
                sb.append(stamp(alarm.fireAt, zone)).append("  ").append(label).append("\n")
                sb.append("   อีก ").append(left)
                if (alarm.shiftedOutOfQuiet) {
                    sb.append("  (เลื่อนจากรอบเงียบ)")
                }
                sb.append("\n")
            }
        }
        sb.append("\n")

        sb.append("โหมด inexact - ระบบอาจเลื่อนได้ไม่กี่นาทีเพื่อประหยัดแบต\n")
        sb.append("system alarm clock: ").append(if (systemAlarmSet) "set" else "none").append("\n")

        return sb.toString()
    }

    private fun mark(ok: Boolean): String {
        return if (ok) "[ผ่าน]" else "[ยังไม่ผ่าน]"
    }

    private fun stamp(at: Instant, zone: TimeZone): String {
        val t = at.toLocalDateTime(zone)
        return pad2(t.dayOfMonth) + "/" + pad2(t.monthNumber) + " " +
                pad2(t.hour) + ":" + pad2(t.minute) + ":" + pad2(t.second)
    }

    private fun remaining(d: Duration): String {
        if (d.isNegative()) return "ถึงแล้ว"
        val total = d.inWholeSeconds
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        val clock = pad2(hours.toInt()) + ":" + pad2(minutes.toInt()) + ":" + pad2(seconds.toInt())
        if (days > 0) {
            return days.toString() + " วัน " + clock
        }
        return clock
    }

    private fun pad2(n: Int): String {
        if (n < 10) return "0" + n
        return n.toString()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * Opens this app's page in the system battery settings.
     *
     * Deliberately NOT ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which pops
     * a one-tap dialog: Google Play forbids that intent outside a short list of
     * app categories, and this app is not on it. Sending someone to the settings
     * page and telling them which switch to look for is slower and allowed.
     *
     * On Samsung the switch that matters is in a second place as well:
     * Settings, Battery, Background usage limits, Never sleeping apps - which
     * no intent can open directly.
     */
    private fun openBatterySettings() {
        val uri = Uri.fromParts("package", packageName, null)
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}