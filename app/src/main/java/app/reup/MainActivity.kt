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
import app.reup.core.QuietHours
import app.reup.core.ScheduledTask
import app.reup.core.horizon
import app.reup.sync.isDoneNow
import app.reup.sync.isoMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
 * WHAT CHANGED IN THIS ROUND. The list is read from the database rather than
 * from Samples.kt, which is deleted. That makes an empty list possible for the
 * first time, and an empty list looks exactly like a broken one from here — so
 * the screen says which it is. "ยังไม่มีงานในฐานข้อมูล" is an answer; a queue
 * of zero with no explanation is a bug report waiting to be filed against
 * nothing.
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

    // Counted in ticks rather than kept as a clock, so that a phone asleep for
    // six hours does not come back and fire immediately: the ticker is stopped
    // while paused, so the count is time spent with this screen actually in
    // front of somebody.
    private var ticksSinceSync = 0
    private var syncing = false

    /**
     * Seconds since anything actually moved.
     *
     * A fixed interval has to pick one number for two different situations. Two
     * devices being poked at right now want the next question asked in a couple
     * of seconds; a screen left open on a desk while nothing happens for twenty
     * minutes wants to be left alone. Fifteen seconds was the compromise, and a
     * compromise is what makes a change that lands on the second after a check
     * wait out the whole gap.
     *
     * So the gap follows what is happening instead. Something moved in the last
     * minute means somebody is doing something, and the next question is worth
     * asking almost immediately.
     */
    private var quietTicks = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Read once per resume and redrawn every second from here. The clock moves
    // far more often than the rows do, and re-reading the database sixty times
    // a minute to find that out would be a strange way to save nothing.
    private var tasks: List<ScheduledTask> = emptyList()
    private var labels: Map<String, String> = emptyMap()
    // Nullable because "the person turned quiet hours off" is a different answer
    // from "this phone has not been told yet", and only Repo resolves the two.
    private var quiet: QuietHours? = Repo.DEFAULT_QUIET
    private var completions: Map<String, String?> = emptyMap()
    private var loaded = false
    private var loadError: String? = null

    // Rebuilt when the rows are read, never on the tick. A button that is
    // replaced once a second is a button that cannot be pressed.
    private lateinit var ticks: LinearLayout

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

        // Only built when it is needed. A permanent button for a permission
        // that is already granted is a button that teaches people to ignore
        // buttons, and this screen has two of those already.
        val exactButton = Button(this)
        exactButton.text = "อนุญาตให้เตือนตรงเวลา"
        exactButton.setOnClickListener { openExactAlarmSettings() }

        // The only way into the sync screen. Deliberately a second screen and
        // not a section of this one: this screen is a live readout that redraws
        // every second, and text boxes that lose what is being typed into them
        // once a second are not text boxes.
        val syncButton = Button(this)
        syncButton.text = "ตั้งค่าซิงก์กับคอม"
        syncButton.setOnClickListener {
            startActivity(Intent(this, SyncActivity::class.java))
        }

        // The money going out is spent standing up, and the machine it was
        // being recorded on is at a desk. That gap is why the numbers in the
        // app have never quite been the numbers.
        val spendButton = Button(this)
        spendButton.text = "\u0e1a\u0e31\u0e19\u0e17\u0e36\u0e01\u0e40\u0e07\u0e34\u0e19"
        spendButton.setOnClickListener {
            startActivity(Intent(this, AddMoneyActivity::class.java))
        }
        // The screen has a switch at the top, so this is a shortcut rather
        // than the only way in. Spending is the common case by a long way
        // and gets the tap; a payment arriving is rare enough to be worth a
        // press and hold.
        spendButton.setOnLongClickListener {
            startActivity(
                Intent(this, AddMoneyActivity::class.java)
                    .putExtra(AddMoneyActivity.EXTRA_INCOMING, true),
            )
            true
        }

        val addButton = Button(this)
        addButton.text = "\u0e40\u0e1e\u0e34\u0e48\u0e21\u0e07\u0e32\u0e19"
        addButton.setOnClickListener {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }

        ticks = LinearLayout(this)
        ticks.orientation = LinearLayout.VERTICAL

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(48, 64, 48, 64)
        column.addView(status, rowParams())
        column.addView(ticks, rowParams())
        column.addView(testButton, rowParams())
        column.addView(batteryButton, rowParams())
        if (!Scheduler.exactAllowed(this)) column.addView(exactButton, rowParams())
        column.addView(addButton, rowParams())
        column.addView(spendButton, rowParams())
        column.addView(syncButton, rowParams())

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
        //
        // Reading for the screen comes after rescheduling rather than before,
        // so that what is drawn is what was just handed to the OS and not the
        // state a moment earlier.
        scope.launch {
            try {
                Scheduler.reschedule(this@MainActivity)
                val repo = Repo.open(this@MainActivity)
                tasks = repo.tasks()
                labels = repo.labels()
                completions = repo.completions()
                quiet = Repo.quietHours(this@MainActivity)
                loadError = null
            } catch (e: Exception) {
                // Shown rather than swallowed. A screen that silently keeps the
                // last good list is a screen that lies for as long as the fault
                // lasts.
                loadError = e.message ?: e.toString()
            }
            loaded = true
            quietTicks = 0
            drawTicks()

            // Then, and only then, ask the folder. Drawn first because the
            // local answer is instant and correct as of the last sync, and a
            // screen that waits for the network before showing anything is a
            // screen that is blank on a train.
            if (Repo.syncQuietly(this@MainActivity)) {
                Scheduler.reschedule(this@MainActivity)
                val repo = Repo.open(this@MainActivity)
                tasks = repo.tasks()
                labels = repo.labels()
                completions = repo.completions()
                drawTicks()
            }
        }
        tick()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun tick() {
        // The automatic line is appended rather than woven in, so that render()
        // stays a description of the data and this stays a description of the
        // machinery. Two different questions, and only one of them is about
        // tasks.
        // Said once, plainly, and only while it is true. A reminder that may be
        // twenty minutes late is still worth having; not knowing that it may be
        // is what turns a late buzz into a broken app.
        val late = if (Scheduler.exactAllowed(this)) "" else
            "\n⚠ การเตือนอาจสายได้ถึงครึ่งชั่วโมง กดปุ่มล่างสุดเพื่ออนุญาต"
        status.text = render() + late + "\n" + Repo.lastQuiet
        ticker.postDelayed({ tick() }, 1000L)

        // ── asking the folder while somebody is looking ───────────────────────
        //
        // Syncing only on resume left one gap that reads exactly like a bug: the
        // app is already open, something changes on the desktop, and this screen
        // sits there being wrong until it is backgrounded and brought forward
        // again. Nobody thinks to do that, and they should not have to.
        //
        // This is not background work and does not break the rule against it.
        // The loop it rides on only exists while this Activity is resumed, is
        // torn down in onPause with everything else on the ticker, and the
        // screen is on for all of it. The battery cost is one request a minute
        // while a person is actually reading the list.
        //
        // A minute rather than every tick, because the clock moves far more
        // often than the rows do — the same reason the rows are read once per
        // resume rather than sixty times a minute.
        quietTicks++
        if (++ticksSinceSync < syncEveryTicks()) return
        ticksSinceSync = 0
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                if (Repo.syncQuietly(this@MainActivity)) {
                    // Something moved, so stay quick: a change rarely arrives
                    // alone, and the person watching this screen is the reason
                    // it did.
                    quietTicks = 0
                    Scheduler.reschedule(this@MainActivity)
                    val repo = Repo.open(this@MainActivity)
                    tasks = repo.tasks()
                    labels = repo.labels()
                    completions = repo.completions()
                    drawTicks()
                }
            } finally {
                syncing = false
            }
        }
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = 24
        return p
    }

    /**
     * One button per task, in the order the queue rings them.
     *
     * WHY THE ORDER COMES FROM THE QUEUE AND NOT FROM THE TABLE
     *
     * The list above is what is about to happen, sorted by when. A person who
     * has just read it and reaches for a button is reaching for the thing at
     * the top of it. Sorting these by name or by row id would mean the two
     * lists disagree about which task is which, and the only way to notice
     * would be to have ticked the wrong one.
     *
     * Tasks with nothing scheduled come last rather than being dropped. A task
     * that is already ticked has no upcoming alarm to sort it by, and hiding
     * the button that undoes a tick is the one way to make a mis-tap permanent.
     */
    private fun drawTicks() {
        ticks.removeAllViews()
        if (tasks.isEmpty()) return

        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val queued = horizon(tasks, now, zone, 8, quiet).map { it.taskId }
        val nowIso = isoMillis(now.toEpochMilliseconds())

        val ordered = tasks.sortedBy { t ->
            val at = queued.indexOf(t.id)
            if (at < 0) Int.MAX_VALUE else at
        }

        for (task in ordered) {
            val done = isDoneNow(completions[task.id], nowIso)
            val button = Button(this)
            button.text = (if (done) "✓ " else "☐ ") + (labels[task.id] ?: task.id)
            button.setOnClickListener { onTick(task) }
            // Long press to edit. A tap is the thing this list is for and has
            // to stay a tap; opening a form by accident when reaching to tick
            // something off is worse than editing being slightly hidden.
            button.setOnLongClickListener {
                startActivity(
                    Intent(this, AddTaskActivity::class.java)
                        .putExtra(AddTaskActivity.EXTRA_UID, task.id),
                )
                true
            }
            ticks.addView(button, rowParams())
        }
    }

    /**
     * Ticking is a database write, so the button is disabled until it lands.
     *
     * Not for looks. Two taps land as two writes, and the second one reads a
     * completion the first has just made and undoes it — a double tap that
     * silently means nothing happened.
     */
    private fun onTick(task: ScheduledTask) {
        scope.launch {
            for (i in 0 until ticks.childCount) ticks.getChildAt(i).isEnabled = false
            try {
                val until = Repo.toggleDone(
                    this@MainActivity, task, Clock.System.now(), TimeZone.currentSystemDefault(),
                )
                // The queue is rebuilt because a tick can change what is next:
                // the alarm for a finished cycle is the one at its reset, and
                // the reset is exactly when the tick expires.
                Scheduler.reschedule(this@MainActivity)
                val repo = Repo.open(this@MainActivity)
                tasks = repo.tasks()
                labels = repo.labels()
                completions = repo.completions()
                val name = labels[task.id] ?: task.id
                // Sent straight away rather than waiting for the next time this
                // screen is opened. Ticking is the one moment this device has
                // news the other one wants, and the gap between having it and
                // sending it is the gap where the desktop shows the wrong thing.
                quietTicks = 0
                Repo.syncQuietly(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    if (until == null) "เอาเครื่องหมายออกแล้ว $name" else "ติ๊กแล้ว $name",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                // Said out loud. A tick that quietly does nothing is worse than
                // one that fails, because the next thing that happens is the
                // notification arriving again for something already done.
                loadError = e.message ?: e.toString()
            }
            drawTicks()
        }
    }

    /** Three seconds while things are happening, a minute when they are not. */
    private fun syncEveryTicks(): Int = when {
        quietTicks < 60 -> 3
        quietTicks < 300 -> 15
        else -> 60
    }

    private fun render(): String {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        val alarms = horizon(tasks, now, zone, 8, quiet)

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
        sb.append("reup\n\n")
        sb.append("zone     ").append(zone.toString()).append("\n")
        sb.append("now      ").append(stamp(now, zone)).append("\n")
        // Printed from the same value the scheduler used, so the queue below and
        // the line above it can never disagree. "ปิดอยู่" is a real state now:
        // it means the desktop says off, not that this phone has not heard.
        sb.append("รอบเงียบ  ")
            .append(quiet?.let { "${it.start} ถึง ${it.end}" } ?: "ปิดอยู่")
            .append("\n\n")

        // The line that makes silence readable. Nothing scheduled has two
        // causes and they need different things done about them: no rows yet,
        // or rows that nothing can be scheduled from.
        sb.append("- ฐานข้อมูลบนเครื่องนี้ -\n")
        val failure = loadError
        when {
            failure != null -> sb.append("อ่านไม่ได้ ").append(failure).append("\n")
            !loaded -> sb.append("กำลังอ่าน\n")
            tasks.isEmpty() -> {
                sb.append("ยังไม่มีงาน ตั้งค่าซิงก์แล้วดึงจากคอมลงมาก่อน\n")
                sb.append("การแจ้งเตือนจะเงียบจนกว่าจะมีงาน ซึ่งถูกแล้ว\n")
            }
            else -> {
                val nowIso = isoMillis(now.toEpochMilliseconds())
                val ticked = tasks.count { isDoneNow(completions[it.id], nowIso) }
                sb.append("มี ").append(tasks.size).append(" งาน")
                if (ticked > 0) sb.append(" · ติ๊กแล้ว ").append(ticked)
                sb.append("\n")
                // The alarm for a ticked task still stands, and that is right:
                // it rings at the reset, which is the moment the tick expires.
                // Said here because a queue entry for something just ticked off
                // otherwise reads as the app having missed the tick.
            }
        }
        sb.append("\n")

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
                val label = labels[alarm.taskId] ?: alarm.taskId
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
    /**
     * The system page where exact alarms are granted.
     *
     * Wrapped because a phone that does not have this page is a phone that does
     * not need it, and an app that crashes trying to open a settings screen has
     * turned a late reminder into no app at all.
     */
    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            Toast.makeText(this, "เปิดหน้าตั้งค่าไม่ได้: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

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