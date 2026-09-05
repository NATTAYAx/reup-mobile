package app.reup

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.reup.core.Alarm
import app.reup.core.HomeRow
import app.reup.core.HomeSection
import app.reup.core.QuietHours
import app.reup.core.ScheduledTask
import app.reup.core.SetupWarning
import app.reup.core.THEMES
import app.reup.core.clockOf
import app.reup.core.headerDate
import app.reup.core.homeOrder
import app.reup.core.homeSections
import app.reup.core.horizon
import app.reup.core.remaining
import app.reup.core.rowClock
import app.reup.core.rowNote
import app.reup.core.setupWarnings
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

/**
 * The screen this app is opened for.
 *
 * ─── WHAT THE PHONE SHOWED, AND WHAT IT SAID ────────────────────────────────
 *
 * The first version of this screen was a monospace readout. The second was one
 * list, which was right, and it still looked wrong on the phone for three
 * reasons that only a photograph of a real screen could show.
 *
 * The title sat inside the status bar and the buttons sat inside the navigation
 * bar, because the app had never asked where those were. That is fixed one
 * level down, in Ui.sticky.
 *
 * Nine rows were nine separate floating panels. Nine panels stacked with air
 * between them is not a list; it is nine things that happen to be in a column.
 *
 * And it repeated itself. Four daily tasks that reset at midnight all get
 * pushed to 08:00 by quiet hours, so four rows read exactly
 * "พรุ่งนี้ 08:00 · อีก 23 ชม. 10 นาที · เลื่อนจากรอบเงียบ" — the same sentence,
 * four times, at full width. Every word true, almost none of it information.
 *
 * ─── THE SHAPE NOW ──────────────────────────────────────────────────────────
 *
 *   วันนี้                                       ← the day, said once
 *   ┌───────────────────────────────────────┐
 *   │ ▍○  Blood pressure meds        09:00  │  ← the lead: violet rule
 *   │     อีก 10 นาที                        │
 *   │ ──────────────────────────────────────│
 *   │   ○  My Hero Ultra Rumble      10:00  │
 *   └───────────────────────────────────────┘
 *
 *   พรุ่งนี้                    เลื่อนจากรอบเงียบ  ← hoisted, because all of them
 *   ┌───────────────────────────────────────┐
 *   │   ○  Honkai Star Rail Daily    08:00  │
 *   │   ○  FGO (JP server)           08:00  │
 *   └───────────────────────────────────────┘
 *
 * The clock in a column of its own is what did most of the work: four
 * identical times stop being four sentences and become one column the eye
 * reads once. The rules for what a section says are in Face.kt, with tests,
 * because "when every row shares a reason, the reason moves to the heading" is
 * a decision and not a layout.
 *
 * ─── WHAT IS SAID ONLY WHILE IT IS TRUE ─────────────────────────────────────
 *
 * Nothing about permissions appears while nothing is wrong. A checklist that
 * reads all-clear every day is a checklist nobody reads on the day it changes.
 * That rule is from the wellbeing document rather than from any style guide.
 *
 * ─── AND THE DIAGNOSTICS ARE STILL HERE ─────────────────────────────────────
 *
 * Behind one line at the bottom. They are what the eight o'clock reminder that
 * never arrived was chased down with, and deleting them to make the screen
 * prettier would be trading a real tool for a nice photograph.
 */
class MainActivity : Activity() {

    private val ticker = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── the screen ──────────────────────────────────────────────────────────
    private lateinit var dateLine: TextView
    private lateinit var warnBox: LinearLayout
    private lateinit var emptyCard: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var emptyNote: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var syncLine: TextView
    private lateinit var themeBox: LinearLayout
    private lateinit var detailsToggle: Button
    private lateinit var detailsCard: LinearLayout
    private lateinit var status: TextView

    /** Folded away by default. Opened deliberately, on the day it is needed. */
    private var detailsOpen = false

    // ── what is being drawn ─────────────────────────────────────────────────
    private var tasks: List<ScheduledTask> = emptyList()
    private var labels: Map<String, String> = emptyMap()

    // Nullable because "the person turned quiet hours off" is a different answer
    // from "this phone has not been told yet", and only Repo resolves the two.
    private var quiet: QuietHours? = Repo.DEFAULT_QUIET
    private var completions: Map<String, String?> = emptyMap()
    private var loaded = false
    private var loadError: String? = null

    /** One per task id, in the order they are drawn. */
    private val rows = LinkedHashMap<String, Ui.Row>()

    /**
     * What the sections looked like last time views were built.
     *
     * Rebuilding nine rows sixty times a minute to change two words in them
     * would be silly, and never rebuilding them means the list is wrong the
     * moment midnight turns พรุ่งนี้ into วันนี้. So the shape gets a signature,
     * and views are rebuilt when it changes rather than on a timer.
     */
    private var shape = NOTHING_DRAWN

    private var alarms: List<Alarm> = emptyList()
    private var alarmsAt: Instant = Instant.fromEpochMilliseconds(0)

    private var ticksSinceSync = 0
    private var syncing = false

    /** Seconds since anything actually moved. See [syncEveryTicks]. */
    private var quietTicks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First, before a single view exists. Everything below reads colours
        // out of Ui, and a screen that starts in one theme and repaints into
        // another a moment later is worse than one with no choice at all.
        Ui.load(this)

        Notifications.ensureChannel(this)
        requestNotificationPermission()

        val screen = Ui.sticky(this)
        val column = screen.column

        // ── who and when ────────────────────────────────────────────────────
        val head = Ui.header(this, "Reup", "")
        dateLine = head.getChildAt(1) as TextView
        column.addView(head, Ui.row(this, 0f))

        // ── anything the phone is doing to itself ───────────────────────────
        warnBox = Ui.column(this)
        column.addView(warnBox, Ui.row(this, 0f))

        // ── the list, or the reason there is no list ────────────────────────
        emptyCard = Ui.card(this)
        emptyText = Ui.body(this, "")
        emptyNote = Ui.note(this, "")
        emptyCard.addView(emptyText)
        emptyCard.addView(emptyNote, Ui.row(this, 6f))
        column.addView(emptyCard, Ui.row(this, 20f))

        listBox = Ui.column(this)
        column.addView(listBox, Ui.row(this, 20f))

        // ── everything below here is opened once, or never ──────────────────
        column.addView(Ui.divider(this), Ui.dividerRow(this))
        column.addView(Ui.label(this, "ตั้งค่า"), Ui.row(this, 16f))

        val settings = Ui.listCard(this)
        settings.addView(
            Ui.navRow(this, "ซิงก์กับคอม", "โฟลเดอร์ รหัสจับคู่ และปุ่มซิงก์") {
                startActivity(Intent(this, SyncActivity::class.java))
            },
        )
        settings.addView(Ui.hairline(this), Ui.hairlineRow(this))
        settings.addView(
            Ui.navRow(this, "อ่านสลิป", "ดูว่าตัวอ่านเห็นอะไรบนสลิปจริง") {
                startActivity(Intent(this, ScanSlipActivity::class.java))
            },
        )
        column.addView(settings, Ui.row(this, 10f))

        // The one line of the automatic sync worth having on this screen.
        // Success is the absence of news, which on its own looks identical to
        // "this has not run in ten minutes" and is a different problem.
        syncLine = Ui.note(this, Repo.lastQuiet)
        syncLine.setTextColor(Ui.FAINT)
        column.addView(syncLine, Ui.row(this, 10f))

        // ── the colours ─────────────────────────────────────────────────────
        column.addView(Ui.label(this, "หน้าตา"), Ui.row(this, 24f))
        themeBox = Ui.column(this)
        column.addView(themeBox, Ui.row(this, 10f))
        drawThemes()

        // ── the panel this screen used to be ────────────────────────────────
        detailsToggle = Ui.quiet(this, DETAILS_SHUT) { toggleDetails() }
        detailsToggle.gravity =
            android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
        detailsToggle.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
        column.addView(detailsToggle, Ui.row(this, 20f))

        status = Ui.mono(this)
        status.setTextIsSelectable(true)
        detailsCard = Ui.card(this)
        detailsCard.addView(status)
        detailsCard.addView(
            Ui.secondary(this, "ทดสอบ: แจ้งเตือนในอีก 60 วินาที") {
                Scheduler.fireTestIn(this, 60)
                Toast.makeText(this, "ตั้งแล้ว ปิดแอปแล้วล็อกจอรอได้เลย", Toast.LENGTH_LONG).show()
            },
            Ui.row(this, 16f),
        )
        detailsCard.addView(
            Ui.secondary(this, "เปิดหน้าตั้งค่าแอปในระบบ") { openAppSettings() },
            Ui.row(this, 8f),
        )
        Ui.show(detailsCard, false)
        column.addView(detailsCard, Ui.row(this, 8f))

        // ── the two things done most days ───────────────────────────────────
        //
        // Ticking is the third, and it is not here: it is the list itself.
        val addButton = Ui.secondary(this, "เพิ่มงาน") {
            startActivity(Intent(this, AddTaskActivity::class.java))
        }
        // Money going out is spent standing up, and the machine it was being
        // recorded on is at a desk. That gap is why the numbers in the app have
        // never quite been the numbers.
        val spendButton = Ui.primary(this, "บันทึกเงิน") {
            startActivity(Intent(this, AddMoneyActivity::class.java))
        }
        spendButton.setOnLongClickListener {
            startActivity(
                Intent(this, AddMoneyActivity::class.java)
                    .putExtra(AddMoneyActivity.EXTRA_INCOMING, true),
            )
            true
        }
        screen.bar.addView(addButton, Ui.cell(this, true))
        screen.bar.addView(spendButton, Ui.cell(this))

        setContentView(screen.root)
    }

    override fun onResume() {
        super.onResume()
        scope.launch {
            try {
                Scheduler.reschedule(this@MainActivity)
                readEverything()
                loadError = null
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            }
            loaded = true
            quietTicks = 0
            redraw()

            if (Repo.syncQuietly(this@MainActivity)) {
                Scheduler.reschedule(this@MainActivity)
                readEverything()
                redraw()
            }
        }
        // Settings can only have changed while this screen was away, which is
        // exactly what coming back means.
        drawWarnings()
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

    private suspend fun readEverything() {
        val repo = Repo.open(this@MainActivity)
        tasks = repo.tasks()
        labels = repo.labels()
        completions = repo.completions()
        quiet = Repo.quietHours(this@MainActivity)
        alarmsAt = Instant.fromEpochMilliseconds(0)
    }

    private fun tick() {
        refresh()
        ticker.postDelayed({ tick() }, 1000L)

        quietTicks++
        if (++ticksSinceSync < syncEveryTicks()) return
        ticksSinceSync = 0
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                if (Repo.syncQuietly(this@MainActivity)) {
                    quietTicks = 0
                    Scheduler.reschedule(this@MainActivity)
                    readEverything()
                    redraw()
                }
            } finally {
                syncing = false
            }
        }
    }

    /** Three seconds while things are happening, a minute when they are not. */
    private fun syncEveryTicks(): Int = when {
        quietTicks < 60 -> 3
        quietTicks < 300 -> 15
        else -> 60
    }

    /** Force the queue and the views to be worked out again from scratch. */
    private fun redraw() {
        alarmsAt = Instant.fromEpochMilliseconds(0)
        shape = NOTHING_DRAWN
        refresh()
    }

    // ─── the list ───────────────────────────────────────────────────────────

    /**
     * Everything that changes as the clock moves, and nothing that does not.
     *
     * Runs once a second. Text is compared before it is set, because setting a
     * TextView to the string it already holds still asks for a layout pass.
     */
    private fun refresh() {
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()

        say(dateLine, headerDate(now, zone))
        say(syncLine, Repo.lastQuiet)

        // The queue is not recomputed every second. The list says "อีก 3 ชม."
        // and that answer holds for the next three thousand seconds. It is
        // redone every half minute, and immediately when the soonest alarm has
        // passed — the only moment the answer can change on its own.
        val stale = (now - alarmsAt).inWholeSeconds >= 30 ||
                alarms.firstOrNull()?.let { now >= it.fireAt } == true
        if (stale) {
            alarms = horizon(tasks, now, zone, 8, quiet)
            alarmsAt = now
        }

        val sections = sectionsNow(now, zone)
        val signature = sections.joinToString("|") { s ->
            s.title + "/" + s.note + "/" + s.rows.joinToString(",") { it.id }
        }
        if (signature != shape) {
            build(sections)
            shape = signature
        }

        var lead: String? = null
        for (s in sections) {
            for (r in s.rows) {
                if (!r.done) { lead = r.id; break }
            }
            if (lead != null) break
        }

        for (s in sections) {
            val hoisted = s.note != null
            for (r in s.rows) {
                val view = rows[r.id] ?: continue
                val note = if (paused(r.id, now)) "พักอยู่" else rowNote(r, now, hoisted)
                Ui.dressRow(this, view, r.done, r.id == lead, rowClock(r, zone), note)
            }
        }

        if (detailsOpen) status.text = render(now, zone)
    }

    /**
     * The list as data, before anything is drawn.
     *
     * The order comes from [homeOrder] and the grouping from [homeSections],
     * both of which live in Face.kt with their tests. Nothing here decides
     * anything; it reads three maps and hands over four fields per task.
     */
    private fun sectionsNow(now: Instant, zone: TimeZone): List<HomeSection> {
        val nowIso = isoMillis(now.toEpochMilliseconds())
        val done = tasks.map { it.id }.filter { isDoneNow(completions[it], nowIso) }.toSet()
        val order = homeOrder(tasks.map { it.id }, alarms.map { it.taskId }, done)

        val soonest = HashMap<String, Alarm>()
        for (a in alarms) if (!soonest.containsKey(a.taskId)) soonest[a.taskId] = a

        val out = ArrayList<HomeRow>()
        for (id in order) {
            val alarm = soonest[id]
            out.add(
                HomeRow(
                    id = id,
                    name = labels[id] ?: id,
                    fireAt = alarm?.fireAt,
                    shifted = alarm?.shiftedOutOfQuiet ?: false,
                    done = done.contains(id),
                ),
            )
        }
        return homeSections(out, now, zone)
    }

    /**
     * One heading and one panel per section, one row per task.
     *
     * Rebuilt only when the sections themselves changed — a name edited, a task
     * ticked, or midnight moving a group from พรุ่งนี้ into วันนี้.
     */
    private fun build(sections: List<HomeSection>) {
        listBox.removeAllViews()
        rows.clear()

        for ((index, s) in sections.withIndex()) {
            listBox.addView(
                Ui.sectionHead(this, s.title, s.note),
                Ui.row(this, if (index == 0) 0f else 20f),
            )
            val card = Ui.listCard(this)
            for ((position, r) in s.rows.withIndex()) {
                if (position > 0) card.addView(Ui.hairline(this), Ui.hairlineRow(this))
                val task = tasks.firstOrNull { it.id == r.id } ?: continue
                val view = Ui.taskRow(
                    this,
                    onClick = { onTick(task) },
                    // A tap is what this list is for and has to stay a tap.
                    // Opening a form by accident while reaching to tick
                    // something off is worse than editing being slightly hidden.
                    onLong = {
                        startActivity(
                            Intent(this, AddTaskActivity::class.java)
                                .putExtra(AddTaskActivity.EXTRA_UID, r.id),
                        )
                    },
                )
                view.name.text = r.name
                rows[r.id] = view
                card.addView(view.view)
            }
            listBox.addView(card)
        }

        drawEmpty()
    }

    /**
     * The line that makes silence readable.
     *
     * An empty list has three causes that look identical from here and need
     * different things done about them: still reading, could not read, or read
     * fine and there is nothing in it. Saying which is the whole job.
     */
    private fun drawEmpty() {
        val failure = loadError
        val empty = tasks.isEmpty()
        Ui.show(emptyCard, empty || failure != null)
        Ui.show(listBox, !empty)
        if (!empty && failure == null) return

        when {
            failure != null -> {
                emptyText.text = "อ่านฐานข้อมูลบนเครื่องนี้ไม่ได้"
                emptyText.setTextColor(Ui.WARN)
                emptyNote.text = failure
            }
            !loaded -> {
                emptyText.text = "กำลังอ่าน"
                emptyText.setTextColor(Ui.TEXT)
                emptyNote.text = ""
            }
            else -> {
                emptyText.text = "ยังไม่มีงานในเครื่องนี้"
                emptyText.setTextColor(Ui.TEXT)
                emptyNote.text = "ตั้งค่าซิงก์กับคอมแล้วดึงลงมาก่อน " +
                        "จนกว่าจะมีงาน การแจ้งเตือนจะเงียบ ซึ่งถูกแล้ว"
            }
        }
        Ui.show(emptyNote, emptyNote.text.isNotEmpty())
    }

    private fun paused(id: String, now: Instant): Boolean {
        val until = tasks.firstOrNull { it.id == id }?.pausedUntil ?: return false
        return until > now
    }

    private fun say(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    /**
     * Ticking is a database write, so every row is disabled until it lands.
     *
     * Not for looks. Two taps land as two writes, and the second reads a
     * completion the first has just made and undoes it — a double tap that
     * silently means nothing happened.
     */
    private fun onTick(task: ScheduledTask) {
        scope.launch {
            for (r in rows.values) r.view.isEnabled = false
            try {
                val until = Repo.toggleDone(
                    this@MainActivity, task, Clock.System.now(), TimeZone.currentSystemDefault(),
                )
                // The queue is rebuilt because a tick can change what is next:
                // the alarm for a finished cycle is the one at its reset, and
                // the reset is exactly when the tick expires.
                Scheduler.reschedule(this@MainActivity)
                readEverything()
                val name = labels[task.id] ?: task.id
                // Sent straight away rather than at the next opening. Ticking is
                // the one moment this device has news the other one wants.
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
            for (r in rows.values) r.view.isEnabled = true
            redraw()
        }
    }

    // ─── the colours ────────────────────────────────────────────────────────

    /**
     * Six themes, each drawn in its own palette.
     *
     * WHY IT IS HERE AND NOT ON A SCREEN OF ITS OWN
     *
     * It is six buttons pressed roughly twice in a lifetime. A whole activity
     * for that is a manifest entry, a back stack and a title bar for something
     * that fits under a heading.
     *
     * WHY THE SCREEN IS REBUILT RATHER THAN REPAINTED
     *
     * Colours are read once, when a view is made. Repainting would mean walking
     * every view on every screen and knowing which of the sixteen colours each
     * one used, which is a second copy of this file's job. recreate() throws the
     * views away and builds them from the new palette, which is what a theme
     * change is.
     */
    private fun drawThemes() {
        themeBox.removeAllViews()
        val current = Ui.themeId(this)
        var strip: LinearLayout? = null
        for ((index, p) in THEMES.withIndex()) {
            if (index % 3 == 0) {
                strip = Ui.strip(this)
                themeBox.addView(strip, Ui.row(this, if (index == 0) 0f else 8f))
            }
            strip?.addView(
                Ui.swatch(this, p, p.id == current) {
                    if (p.id != current) {
                        Ui.setTheme(this, p.id)
                        recreate()
                    }
                },
                Ui.cell(this, index % 3 == 0, 8f),
            )
        }
    }

    // ─── what the phone is doing to itself ──────────────────────────────────

    /**
     * A line for each thing that will stop a reminder arriving, and the button
     * that fixes it. Nothing at all when there is nothing to say.
     */
    private fun drawWarnings() {
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_RESETS)

        val warnings = setupWarnings(
            notificationsOn = nm.areNotificationsEnabled(),
            channelOn = channel != null &&
                    channel.importance != NotificationManager.IMPORTANCE_NONE,
            batteryExempt = pm.isIgnoringBatteryOptimizations(packageName),
            exactAllowed = Scheduler.exactAllowed(this),
        )

        warnBox.removeAllViews()
        Ui.show(warnBox, warnings.isNotEmpty())
        for (w in warnings) {
            val view = when (w) {
                SetupWarning.NOTIFICATIONS_OFF -> Ui.banner(
                    this,
                    "แอปโพสต์แจ้งเตือนไม่ได้ ปิดอยู่ในตั้งค่าระบบ",
                    Ui.DANGER,
                    "เปิด",
                ) { openNotificationSettings() }

                SetupWarning.CHANNEL_OFF -> Ui.banner(
                    this,
                    "ช่องรอบรีเซ็ตถูกปิดเสียงไว้ การเตือนจะไม่ขึ้น",
                    Ui.DANGER,
                    "เปิด",
                ) { openNotificationSettings() }

                // Not a warning for its own sake. On this vendor's software an
                // app left alone for a few days is put to sleep and its alarms
                // stop, and nothing tells the person that happened.
                SetupWarning.BATTERY_MANAGED -> Ui.banner(
                    this,
                    "ซัมซุงอาจพักแอปเองหลังไม่ได้เปิดไม่กี่วัน แล้วการเตือนจะเงียบโดยไม่มีอะไรบอก",
                    Ui.WARN,
                    "แก้",
                ) { openBatterySettings() }

                SetupWarning.INEXACT_ALARMS -> Ui.banner(
                    this,
                    "การเตือนอาจสายได้ถึงครึ่งชั่วโมง",
                    Ui.WARN,
                    "อนุญาต",
                ) { openExactAlarmSettings() }
            }
            warnBox.addView(view, Ui.row(this, 12f))
        }
    }

    // ─── the panel ──────────────────────────────────────────────────────────

    private fun toggleDetails() {
        detailsOpen = !detailsOpen
        Ui.show(detailsCard, detailsOpen)
        detailsToggle.text = if (detailsOpen) DETAILS_OPEN else DETAILS_SHUT
        if (detailsOpen) status.text = render(Clock.System.now(), TimeZone.currentSystemDefault())
    }

    /**
     * Everything the old screen said, kept whole.
     *
     * This is the readout that found the alarm firing at ten to midnight, and
     * it stays exact — seconds in the countdown, the timezone spelled out, all
     * four permissions listed whether they pass or not. The list upstairs
     * rounds to the nearest minute because that is what a person reads; this
     * does not, because it is what a bug is chased with.
     */
    private fun render(now: Instant, zone: TimeZone): String {
        val am = getSystemService(AlarmManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val pm = getSystemService(PowerManager::class.java)
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_RESETS)

        val notificationsOn = nm.areNotificationsEnabled()
        val channelOn = channel != null &&
                channel.importance != NotificationManager.IMPORTANCE_NONE
        val batteryExempt = pm.isIgnoringBatteryOptimizations(packageName)
        val systemAlarmSet = am.nextAlarmClock != null

        val sb = StringBuilder()
        sb.append("theme    ").append(Ui.palette.id).append("\n")
        sb.append("zone     ").append(zone.toString()).append("\n")
        sb.append("now      ").append(stamp(now, zone)).append("\n")
        // Printed from the same value the scheduler used, so the queue below and
        // the line above it cannot disagree. "ปิดอยู่" is a real state: it means
        // the desktop says off, not that this phone has not heard.
        sb.append("รอบเงียบ  ")
            .append(quiet?.let { it.start + " ถึง " + it.end } ?: "ปิดอยู่")
            .append("\n\n")

        sb.append("- สถานะที่มีผลกับการเตือน -\n")
        sb.append(mark(notificationsOn)).append(" อนุญาตแจ้งเตือน\n")
        sb.append(mark(channelOn)).append(" ช่องรอบรีเซ็ตเปิดอยู่\n")
        sb.append(mark(batteryExempt)).append(" ยกเว้นการประหยัดแบต\n")
        sb.append(mark(Scheduler.exactAllowed(this))).append(" เตือนตรงเวลา\n\n")

        sb.append("- คิวที่ส่งให้ระบบแล้ว (").append(alarms.size).append(") -\n")
        if (alarms.isEmpty()) {
            sb.append("(ว่าง)\n")
        } else {
            for (alarm in alarms) {
                val label = labels[alarm.taskId] ?: alarm.taskId
                sb.append(stamp(alarm.fireAt, zone)).append("  ").append(label).append("\n")
                sb.append("   อีก ").append(remaining(now, alarm.fireAt))
                if (alarm.shiftedOutOfQuiet) sb.append("  (เลื่อนจากรอบเงียบ)")
                sb.append("\n")
            }
        }
        sb.append("\n")

        sb.append(if (Scheduler.exactAllowed(this)) "โหมด exact\n" else "โหมด inexact\n")
        sb.append("system alarm clock: ").append(if (systemAlarmSet) "set" else "none").append("\n")
        sb.append(Repo.lastQuiet).append("\n")
        return sb.toString()
    }

    private fun mark(ok: Boolean): String = if (ok) "[ผ่าน]" else "[ยังไม่ผ่าน]"

    private fun stamp(at: Instant, zone: TimeZone): String {
        val t = at.toLocalDateTime(zone)
        return pad2(t.dayOfMonth) + "/" + pad2(t.monthNumber) + " " +
                clockOf(at, zone) + ":" + pad2(t.second)
    }

    private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()

    // ─── system pages ───────────────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    /**
     * Opens this app's page in the system settings.
     *
     * Deliberately NOT ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which pops
     * a one-tap dialog: Google Play forbids that intent outside a short list of
     * app categories, and this app is not on it. Sending someone to the settings
     * page and saying which switch to look for is slower and allowed.
     *
     * On Samsung the switch that matters is in a second place as well —
     * Settings, Battery, Background usage limits, Never sleeping apps — which
     * no intent can open directly.
     */
    private fun openBatterySettings() = openAppSettings()

    private fun openAppSettings() {
        val uri = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            Toast.makeText(this, "เปิดหน้าตั้งค่าไม่ได้: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        /**
         * Not the empty string.
         *
         * A screen with nothing on it produces an empty signature, and the
         * empty string was also what `shape` started as — so on a phone whose
         * database could not be read, the two matched, the views were never
         * built, and the card that exists to say why the list is empty was
         * itself empty. A blank panel under a warning, which is the least
         * useful thing this screen has ever drawn.
         *
         * A value no signature can ever have.
         */
        const val NOTHING_DRAWN = "\u0000"

        const val DETAILS_SHUT = "รายละเอียดระบบ  ▾"
        const val DETAILS_OPEN = "รายละเอียดระบบ  ▴"
    }
}