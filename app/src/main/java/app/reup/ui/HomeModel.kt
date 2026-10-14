package app.reup.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.reup.BankNoticeListener
import app.reup.BankNotices
import app.reup.DeviceNotifyPrefs
import app.reup.Notifications
import app.reup.PendingIncome
import app.reup.PendingSlip
import app.reup.PendingSlips
import app.reup.Repo
import app.reup.ReupWidget
import app.reup.Scheduler
import app.reup.SlipWatch
import app.reup.Ui
import app.reup.homeRows
import app.reup.core.Alarm
import app.reup.core.DeviceNotify
import app.reup.core.HomeRow
import app.reup.core.QuietHours
import app.reup.core.ScheduledTask
import app.reup.core.SetupWarning
import app.reup.core.horizon
import app.reup.core.moneyLine
import app.reup.core.moneyText
import app.reup.core.nextPerTask
import app.reup.core.setupWarnings
import app.reup.sync.CURRENCY_FALLBACK
import app.reup.sync.Category
import app.reup.sync.MoneyEntry
import app.reup.sync.MoneySummary
import app.reup.sync.SyncBackend
import app.reup.sync.TaskInfo
import app.reup.sync.arrivalIncome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── HomeModel.kt — what the three tabs are drawn from ───────────────────────
//
// Everything the old MainActivity kept in fields, kept here as state the
// screens read. When a value changes, the parts of a screen that read it are
// drawn again and nothing else is; that is the whole reason the theme no
// longer needs recreate() and the list no longer needs a signature to decide
// when to rebuild itself.
//
// WHAT DID NOT MOVE
//
// The decisions. The order of the list is homeOrder, the groups are
// todayFace, the rule for how long a tick lasts is Repo.toggleDone, the sync
// is Repo.syncQuietly - each with the tests it already had. This file only
// fetches, holds and hands over.

/** Something worth a line at the bottom of the screen, once. */
class Notice(
    val text: String,
    val action: String? = null,
    val onAction: (() -> Unit)? = null,
) {
    /** Two notices with the same words are still two events. */
    val id: Long = next++

    private companion object {
        var next = 0L
    }
}

/** A tab asked for from outside the screens - a tap on the widget. Two asks for one tab are still two. */
class TabAsk(val tab: HomeTab) {
    val id: Long = next++

    private companion object {
        var next = 0L
    }
}

/** The money tab's numbers, read together so the total and the list never disagree. */
data class MoneyView(
    val loaded: Boolean = false,
    val error: String? = null,
    /** The unit the totals are in. */
    val currency: String = CURRENCY_FALLBACK,
    /** False before the first sync, when [currency] is a fallback rather than a fact. */
    val currencyKnown: Boolean = false,
    val summary: MoneySummary? = null,
    val entries: List<MoneyEntry> = emptyList(),
    val categories: Map<String, Category> = emptyMap(),
)

/** Money coming in off a bank's notifications, as the settings screen shows it. */
data class IncomeWatchView(
    val on: Boolean = false,
    /** Whether the phone lets this app read notifications. Changed only in its settings. */
    val access: Boolean = false,
    /** The last money-in notice read and what became of it, after "ล่าสุด". */
    val last: String? = null,
)

/** Slips arriving on their own, as the settings screen shows it. */
data class SlipWatchView(
    val on: Boolean = false,
    val access: SlipWatch.Access = SlipWatch.Access.NONE,
    val albums: List<SlipWatch.Album> = emptyList(),
    /** The album and time of the last slip that arrived. */
    val last: Pair<String, Long>? = null,
)

class HomeModel(private val ctx: Context, private val scope: CoroutineScope) {

    // ── state the screens read ──────────────────────────────────────────────

    var themeId by mutableStateOf(Ui.themeId(ctx))
        private set
    var now by mutableStateOf(Clock.System.now())
        private set
    var zone by mutableStateOf(TimeZone.currentSystemDefault())
        private set

    var loaded by mutableStateOf(false)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set

    var tasks by mutableStateOf<List<ScheduledTask>>(emptyList())
        private set
    var infos by mutableStateOf<Map<String, TaskInfo>>(emptyMap())
        private set

    // Nullable because "the person turned quiet hours off" is a different
    // answer from "this phone has not been told yet", and only Repo resolves
    // the two.
    var quiet by mutableStateOf<QuietHours?>(Repo.DEFAULT_QUIET)
        private set

    /** Each task's own next ring, for the list. See nextPerTask. */
    var next by mutableStateOf<List<Alarm>>(emptyList())
        private set

    /** What the scheduler hands the system, for the diagnostics panel. */
    var queue by mutableStateOf<List<Alarm>>(emptyList())
        private set

    /** A tick is being written; every row waits for it. See [toggle]. */
    var writing by mutableStateOf(false)
        private set

    var deviceNotify by mutableStateOf(DeviceNotifyPrefs.get(ctx))
        private set
    var warnings by mutableStateOf<List<SetupWarning>>(emptyList())
        private set

    var backend by mutableStateOf<SyncBackend>(SyncBackend.Off)
        private set
    var lastSync by mutableStateOf(Repo.lastSync)
        private set
    var lastQuiet by mutableStateOf(Repo.lastQuiet)
        private set

    var money by mutableStateOf(MoneyView())
        private set

    /** Slips that arrived on their own and are waiting for a yes or a no. */
    var slips by mutableStateOf<List<PendingSlip>>(emptyList())
        private set

    var slipWatch by mutableStateOf(SlipWatchView())
        private set

    /** Money that came in, off a bank's notification, waiting for a yes or a no. */
    var incomes by mutableStateOf<List<PendingIncome>>(emptyList())
        private set

    var incomeWatch by mutableStateOf(IncomeWatchView())
        private set

    /** The albums to pick from while that list is open; null when it is not. */
    var albumChoices by mutableStateOf<List<SlipWatch.Album>?>(null)
        private set

    /** Turning slips on is waiting for the gallery permission to be answered. */
    private var wantSlips = false

    var notice by mutableStateOf<Notice?>(null)
        private set

    /** The widget's settings and its frame picker. */
    val widget = WidgetModel(ctx, scope) { say(it) }

    /** The tab the widget asked for, for the screen to go to once. */
    var tabAsk by mutableStateOf<TabAsk?>(null)
        private set

    // ── bookkeeping ─────────────────────────────────────────────────────────

    private var alarmsAt = Instant.fromEpochMilliseconds(0)
    private var ticksSinceSync = 0
    private var ticksSinceSlips = 0
    /** Seconds since anything actually moved. See [syncEveryTicks]. */
    private var quietTicks = 0
    private var syncing = false

    // ── the list ────────────────────────────────────────────────────────────

    /**
     * The tasks as rows, in the order homeOrder gives them. The widget draws
     * from the same function, so the two never disagree about what is done.
     *
     * Read on every frame that needs it rather than cached: it is a few dozen
     * rows, and a cache is one more thing that can disagree with the state it
     * was made from.
     */
    fun rows(): List<HomeRow> = homeRows(tasks, infos, next, now, zone)

    // ── the clock ───────────────────────────────────────────────────────────

    /**
     * Once a second while the app is in front.
     *
     * The queue is not worked out every second: "อีก 3 ชม." holds for the next
     * three thousand. It is redone every half minute, and at once when the
     * soonest ring has passed - the only moment the answer can change on its
     * own.
     */
    fun tick() {
        now = Clock.System.now()
        val stale = (now - alarmsAt).inWholeSeconds >= 30 ||
                next.firstOrNull()?.let { now >= it.fireAt } == true
        if (stale) recompute()

        // A slip can arrive while the app is open — the watcher runs in this
        // same process — and the money tab should not need reopening to show
        // it. The same for money in, which the notification listener hands
        // over in this process too. A read of two small lists from memory,
        // every few seconds.
        if (++ticksSinceSlips >= 3) {
            ticksSinceSlips = 0
            slips = PendingSlips.all(ctx)
            refreshIncome()
        }

        quietTicks++
        if (++ticksSinceSync < syncEveryTicks()) return
        ticksSinceSync = 0
        quietSync()
    }

    /** Three seconds while things are happening, a minute when they are not. */
    private fun syncEveryTicks(): Int = when {
        quietTicks < 60 -> 3
        quietTicks < 300 -> 15
        else -> 60
    }

    private fun recompute() {
        next = nextPerTask(tasks, now, zone, quiet)
        queue = horizon(tasks, now, zone, 8, quiet)
        alarmsAt = now
    }

    // ── coming back to the screen ───────────────────────────────────────────

    fun resume() {
        zone = TimeZone.currentSystemDefault()
        now = Clock.System.now()
        deviceNotify = DeviceNotifyPrefs.get(ctx)
        refreshWarnings()
        refreshSlips()
        widget.resume()
        // The shade first: a notice posted while the listener was held back is
        // still there, and this is the moment that is never held back.
        BankNoticeListener.catchUp(ctx)
        refreshIncome(BankNotices.hasAccess(ctx))
        quietTicks = 0
        scope.launch {
            try {
                Scheduler.reschedule(ctx)
                read()
                loadError = null
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            }
            loaded = true
            // Before syncing, because the row may have arrived on an earlier
            // run and this is the first build that knows to look at it.
            adoptTheme()
            loadMoney()
            quietSync()
            catchUpSlips()
        }
    }

    private suspend fun read() {
        val repo = Repo.open(ctx)
        tasks = repo.tasks()
        infos = repo.infos()
        quiet = Repo.quietHours(ctx)
        backend = try {
            Repo.syncBackend(ctx)
        } catch (e: Exception) {
            SyncBackend.Off
        }
        recompute()
    }

    /** A sync nobody asked for. Rows that moved are read again; nothing else is said. */
    private fun quietSync() {
        if (syncing) return
        syncing = true
        scope.launch {
            try {
                if (Repo.syncQuietly(ctx)) {
                    quietTicks = 0
                    adoptTheme()
                    Scheduler.reschedule(ctx)
                    read()
                    loadMoney()
                }
            } catch (e: Exception) {
                // syncQuietly swallows its own failures; anything here is the
                // read afterwards, which the next resume tries again.
            } finally {
                syncing = false
                lastSync = Repo.lastSync
                lastQuiet = Repo.lastQuiet
            }
        }
    }

    // ── ticking ─────────────────────────────────────────────────────────────

    /**
     * Tick one done, or take the tick back.
     *
     * Every row waits while the write lands. Not for looks: two taps land as
     * two writes, and the second reads the completion the first just made and
     * undoes it - a double tap that silently means nothing happened.
     *
     * The rows come back as soon as the database has the tick; the sync that
     * carries it to the other machine runs after, so a slow network never holds
     * the next tap.
     */
    fun toggle(id: String) {
        if (writing) return
        val task = tasks.firstOrNull { it.id == id } ?: return
        val name = infos[id]?.name ?: "งาน"
        writing = true
        scope.launch {
            var wrote = false
            try {
                val until = Repo.toggleDone(ctx, task, Clock.System.now(), zone)
                // The queue is rebuilt because a tick can change what is next:
                // the alarm for a finished cycle is the one at its reset, and
                // the reset is exactly when the tick expires.
                Scheduler.reschedule(ctx)
                read()
                wrote = true
                notice = Notice(
                    text = if (until == null) "เอาเครื่องหมายออกแล้ว · $name" else "ทำแล้ว · $name",
                    action = "เลิกทำ",
                    onAction = { toggle(id) },
                )
            } catch (e: Exception) {
                // Said out loud. A tick that quietly does nothing is worse than
                // one that fails, because the next thing that happens is the
                // notification arriving again for something already done.
                notice = Notice("ติ๊กไม่ได้ " + (e.message ?: e.toString()))
            } finally {
                writing = false
            }
            // Sent straight away rather than at the next opening. Ticking is the
            // one moment this device has news the other one wants.
            if (wrote) {
                quietTicks = 0
                quietSync()
            }
        }
    }

    // ── this phone ──────────────────────────────────────────────────────────

    /**
     * Which reminders this phone gives. A question about the device, so it
     * never syncs: turning this phone off does not turn the computer off.
     */
    fun chooseDeviceNotify(mode: DeviceNotify) {
        if (mode == deviceNotify) return
        DeviceNotifyPrefs.set(ctx, mode)
        deviceNotify = mode
        refreshWarnings()
        // The queue the system holds has to change now rather than at the next
        // alarm: off means nothing may fire, and an alarm already registered
        // would.
        scope.launch {
            try {
                Scheduler.reschedule(ctx)
            } catch (e: Exception) {
                // The next resume reschedules anyway.
            }
        }
    }

    /**
     * A line for each thing that will stop a reminder arriving. Read from the
     * system every time the screen comes back, because every one of them is
     * changed somewhere else - in the system's own settings.
     */
    fun refreshWarnings() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val channel = nm.getNotificationChannel(Notifications.CHANNEL_RESETS)
        warnings = setupWarnings(
            notificationsOn = nm.areNotificationsEnabled(),
            channelOn = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            batteryExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            exactAllowed = Scheduler.exactAllowed(ctx),
            hibernationExempt = hibernationExempt(ctx),
            deviceOff = deviceNotify == DeviceNotify.OFF,
        )
    }

    // ── the colours ─────────────────────────────────────────────────────────

    /**
     * Locally first, so the screen is right whatever the database does; the
     * row is what the other machine reads. No recreate(): the screens read the
     * colours as state, so changing the state is the whole repaint.
     */
    fun setTheme(id: String) {
        if (id == themeId) return
        Ui.setTheme(ctx, id)
        themeId = id
        // The widget is drawn in the theme too.
        ReupWidget.redraw(ctx)
        scope.launch {
            try {
                Repo.setThemeId(ctx, id)
            } catch (e: Exception) {
                // A colour that did not travel is worth less than a screen
                // that refused to change. The next pick writes the row again.
            }
        }
    }

    /**
     * Follow a theme picked on the other machine. Which ids are real is
     * Ui.adopt's decision; a database that will not open is a colour that
     * stays put, not a crash.
     */
    private suspend fun adoptTheme() {
        try {
            if (Ui.adopt(ctx, Repo.themeId(ctx))) {
                themeId = Ui.themeId(ctx)
                ReupWidget.redraw(ctx)
            }
        } catch (e: Exception) {
            // Stays as it is.
        }
    }

    // ── money ───────────────────────────────────────────────────────────────

    /** The month's totals and the last entries, always read together. */
    fun loadMoney() {
        scope.launch { readMoney() }
    }

    private suspend fun readMoney() {
        money = try {
            val repo = Repo.money(ctx)
            val stored = repo.currency()
            val unit = stored ?: CURRENCY_FALLBACK
            val month = now.toLocalDateTime(zone).date.toString().take(7)
            MoneyView(
                loaded = true,
                error = null,
                currency = unit,
                currencyKnown = stored != null,
                summary = repo.summary(month, unit),
                entries = repo.recent(),
                categories = repo.categories().associateBy { it.key },
            )
        } catch (e: Exception) {
            money.copy(loaded = true, error = e.message ?: e.toString())
        }
    }

    /**
     * A tombstone, which is what the desktop writes too. The list is read again
     * afterwards rather than edited in place: a screen that removes the line
     * itself has decided the write worked before anything said so.
     */
    fun deleteMoney(e: MoneyEntry) {
        scope.launch {
            try {
                Repo.money(ctx).delete(e)
                readMoney()
                // The widget's month line, if it shows one.
                ReupWidget.redraw(ctx)
                notice = Notice("ลบแล้ว")
                quietSync()
            } catch (x: Exception) {
                notice = Notice("ลบไม่ได้ " + (x.message ?: x.toString()))
            }
        }
    }

    fun say(text: String) {
        notice = Notice(text)
    }

    /** Go to [tab], from outside the screens: the widget, through MainActivity. */
    fun openTab(tab: HomeTab) {
        tabAsk = TabAsk(tab)
    }

    // ── slips that arrive on their own ──────────────────────────────────────

    /** Read from the device every time: the permission and the list both change elsewhere. */
    fun refreshSlips() {
        slipWatch = SlipWatchView(
            on = SlipWatch.isOn(ctx),
            access = SlipWatch.access(ctx),
            albums = SlipWatch.chosen(ctx),
            last = SlipWatch.last(ctx),
        )
        slips = PendingSlips.all(ctx)
    }

    /**
     * Whatever arrived while the app was closed, read now.
     *
     * The wake that reads a slip the moment it is saved is the system's to
     * give, and a battery manager can hold it back. Opening the app is the
     * moment that is never held back, so it does the same reading — which
     * finds nothing, cheaply, when the wake already did it.
     */
    private fun catchUpSlips() {
        if (!SlipWatch.ready(ctx)) return
        scope.launch {
            try {
                SlipWatch.arm(ctx)
                SlipWatch.scan(ctx)
            } catch (e: Exception) {
                // The next opening tries again; nothing here is worth a notice.
            }
            refreshSlips()
        }
    }

    /**
     * On or off. On needs the whole gallery, and asking for it is the screen's
     * job — so this says whether it has to ask, and finishes turning on when
     * the answer comes back through [photoAccessAnswered].
     *
     * Turning on with no album chosen opens the album list straight away,
     * because on without an album is on in name only.
     */
    fun setSlipWatch(on: Boolean): Boolean {
        if (on && SlipWatch.access(ctx) != SlipWatch.Access.ALL) {
            wantSlips = true
            return true
        }
        wantSlips = false
        SlipWatch.setOn(ctx, on)
        refreshSlips()
        if (on && SlipWatch.chosen(ctx).isEmpty()) loadAlbums()
        return false
    }

    fun photoAccessAnswered() {
        refreshSlips()
        if (!wantSlips) return
        if (SlipWatch.access(ctx) == SlipWatch.Access.ALL) {
            setSlipWatch(true)
        } else {
            wantSlips = false
            // With a way out: after two refusals Android stops showing the
            // question at all, and the app's page in system settings is the
            // only place left to say yes.
            notice = Notice(
                text = if (SlipWatch.access(ctx) == SlipWatch.Access.SELECTED) {
                    "ต้องเลือก \"อนุญาตทั้งหมด\" สลิปใหม่ถึงจะเข้ามาได้"
                } else {
                    "ยังไม่ได้สิทธิ์ดูรูป สลิปเลยเข้ามาเองไม่ได้"
                },
                action = "ตั้งค่า",
                onAction = { openAppPage() },
            )
        }
    }

    /** This app's page in system settings, from a context that is not an activity. */
    private fun openAppPage() {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            notice = Notice("เปิดหน้าตั้งค่าไม่ได้")
        }
    }

    fun loadAlbums() {
        scope.launch {
            albumChoices = try {
                SlipWatch.albums(ctx)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun closeAlbums() {
        albumChoices = null
    }

    fun chooseAlbums(ids: Set<String>) {
        val list = albumChoices ?: return
        SlipWatch.choose(ctx, list.filter { it.id in ids })
        albumChoices = null
        refreshSlips()
    }

    /** The one press, from the money tab rather than the shade. Same call, same answer. */
    fun saveSlip(slip: PendingSlip) {
        scope.launch {
            val result = try {
                PendingSlips.save(ctx, slip.id)
            } catch (e: Exception) {
                notice = Notice("บันทึกไม่ได้ " + (e.message ?: e.toString()))
                return@launch
            }
            Notifications.cancelSlip(ctx, slip.id)
            notice = when (result) {
                PendingSlips.Saved.Done -> Notice("บันทึกแล้ว")
                PendingSlips.Saved.Already -> Notice("สลิปใบนี้บันทึกไปแล้ว")
                PendingSlips.Saved.Gone -> null
                PendingSlips.Saved.NeedsLook -> Notice("สลิปนี้ต้องเปิดเช็คก่อนบันทึก")
                is PendingSlips.Saved.Refused -> Notice("บันทึกไม่ได้ " + result.problems.joinToString(", "))
            }
            refreshSlips()
            readMoney()
            if (result == PendingSlips.Saved.Done) quietSync()
        }
    }

    /** Not a row, ever. Undoable from the notice, because a thumb on a list misses. */
    fun dismissSlip(slip: PendingSlip) {
        PendingSlips.remove(ctx, slip.id)
        Notifications.cancelSlip(ctx, slip.id)
        refreshSlips()
        notice = Notice(
            text = "ไม่บันทึกสลิปนี้",
            action = "เอาคืน",
            onAction = {
                PendingSlips.add(ctx, slip)
                refreshSlips()
            },
        )
    }

    // ── money coming in ─────────────────────────────────────────────────────

    /**
     * The list and the settings line. Whether the app may read notifications
     * is asked of the system only when the screen comes back: it can only
     * change in the system's own settings.
     */
    private fun refreshIncome(access: Boolean = incomeWatch.access) {
        incomes = BankNotices.waiting(ctx)
        incomeWatch = IncomeWatchView(
            on = BankNotices.isOn(ctx),
            access = access,
            last = BankNotices.lastLine(ctx, now.toLocalDateTime(zone).date),
        )
    }

    /**
     * On or off. True when the screen should open the phone's page for
     * reading notifications - on, and not allowed yet. The switch stays on
     * either way, and the settings screen says what is missing until it is not.
     */
    fun setIncomeWatch(on: Boolean): Boolean {
        // Pressing เปิด again changes nothing here, and still opens the page
        // when the phone has not said yes yet.
        if (on != BankNotices.isOn(ctx)) BankNotices.setOn(ctx, on)
        val access = BankNotices.hasAccess(ctx)
        refreshIncome(access)
        return on && !access
    }

    /** The one press, from the money tab. Saying what was saved, as the form does. */
    fun saveIncome(p: PendingIncome) {
        scope.launch {
            val result = try {
                BankNotices.save(ctx, p.id)
            } catch (e: Exception) {
                notice = Notice("บันทึกไม่ได้ " + (e.message ?: e.toString()))
                return@launch
            }
            notice = when (result) {
                BankNotices.Saved.Done -> Notice(incomeSaid(p))
                BankNotices.Saved.Gone -> null
                BankNotices.Saved.NeedsLook -> Notice("เงินเข้านี้ต้องเปิดเช็คก่อนบันทึก")
                is BankNotices.Saved.Refused -> Notice("บันทึกไม่ได้ " + result.problems.joinToString(", "))
            }
            refreshIncome()
            readMoney()
            if (result == BankNotices.Saved.Done) quietSync()
        }
    }

    /** "บันทึกแล้ว · เข้ากสิกรไทย +฿1": the row as the money tab will print it. */
    private fun incomeSaid(p: PendingIncome): String {
        val d = arrivalIncome(p.arrival)
        val line = moneyLine(true, d.source, d.note, "")
        return "บันทึกแล้ว · " + line.title + " " + moneyText(p.arrival.amount, d.currency ?: CURRENCY_FALLBACK, 1)
    }

    /** Not a row. Undoable from the notice, because a thumb on a list misses. */
    fun dismissIncome(p: PendingIncome) {
        BankNotices.dismiss(ctx, p.id)
        refreshIncome()
        notice = Notice(
            text = "ไม่บันทึกเงินเข้านี้",
            action = "เอาคืน",
            onAction = {
                BankNotices.restore(ctx, p.id)
                refreshIncome()
            },
        )
    }

    private companion object {
        /**
         * Exempt from Android's own pause-if-unused, which since Android 11 stops
         * an app nobody has opened for a few months - alarms included. A reminder
         * app is used through its notifications, so it is exactly the kind that
         * looks unused. Did not exist before Android 11.
         */
        fun hibernationExempt(ctx: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.packageManager.isAutoRevokeWhitelisted else true
    }
}
