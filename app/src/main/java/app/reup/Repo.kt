package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import app.reup.sync.SyncConfigs
import app.reup.sync.driveTokenSource
import app.reup.core.QuietHours
import app.reup.sync.MoneyRepo
import app.reup.sync.QuietSetting
import app.reup.core.ScheduledTask
import app.reup.core.nextReset
import app.reup.sync.TaskRepo
import app.reup.sync.doneUntil
import app.reup.sync.isDoneNow
import app.reup.sync.isoMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

// ─── Repo.kt — the one way into storage from the Android side ────────────────
//
// Three things live here because each of them has exactly one right answer and
// several plausible wrong ones, and because a screen, an alarm and a reboot all
// need the same answer.
//
// It replaces Samples.kt, whose own header said it existed because two things
// needed the same list and neither could own it: the screen, and the receiver
// that runs when the app is closed. A database is what owns it. There are three
// callers now rather than two, which only makes the point harder.

object Repo {

    /**
     * The night to use before this phone has ever been told what night means.
     *
     * It used to be the whole answer, because quiet hours lived in
     * `app_settings`, which is deliberately not synced — it holds the pairing
     * key and the WebDAV password. The proper fix was named there as work of
     * its own: promote the rows that describe a person into a table of their
     * own. That is `user_settings`, and it is done.
     *
     * So this is now a first-run default rather than a permanent guess, and it
     * applies to exactly one case: a phone that has been installed and has not
     * synced yet. Once a row arrives, this value is never consulted again —
     * including when that row says quiet hours are off, which is why
     * [QuietSetting] keeps "off" and "never said" apart.
     */
    val DEFAULT_QUIET = QuietHours(start = "23:00", end = "08:00")

    @Volatile
    private var opened: TaskRepo? = null

    /**
     * The repository, with the bootstrap already run.
     *
     * Run once per process rather than once per call: the bootstrap is 119
     * statements, 24 of which are expected to fail, and paying that on every
     * alarm that fires is waste rather than safety. It stays correct under a
     * race — two callers arriving at once means the bootstrap runs twice, and
     * running it twice is the property it was designed around.
     */
    /**
     * The money half, on the same connection and nothing more.
     *
     * Not cached like [open] is, because it holds no state worth keeping: it is
     * a handful of statements around the one database handle, and the screen
     * that uses it is opened by hand rather than by an alarm going off.
     */
    suspend fun money(ctx: Context): MoneyRepo = MoneyRepo(AndroidDb.shared(ctx))

    suspend fun open(ctx: Context): TaskRepo {
        opened?.let { return it }
        val repo = TaskRepo(AndroidDb.shared(ctx)).open()
        opened = repo
        return repo
    }

    /**
     * Read through one function so the screen shows the window the scheduler
     * used, and so the three cases are resolved in one place rather than at
     * each call site with an elvis operator that cannot tell them apart.
     *
     * Null means no quiet hours, which [Horizon] already takes as its default.
     */
    suspend fun quietHours(ctx: Context): QuietHours? = when (val q = open(ctx).quietSetting()) {
        QuietSetting.Unknown -> DEFAULT_QUIET
        QuietSetting.Off -> null
        is QuietSetting.Window -> QuietHours(start = q.start, end = q.end)
    }

    /**
     * Tick one task done, or take the tick back.
     *
     * This is the first thing this device writes. Everything before it was
     * one-way: rows arrived, alarms were set, nothing here ever changed a row.
     *
     * It is the first write on purpose. `merge.ts` singles out `completed_until`
     * as the one field that is not last-write-wins, for exactly this case: the
     * notification goes off on the phone, it is ticked here, and a desktop
     * holding a slightly newer row must not hand back the version from before
     * the tick. The riskiest write is the one that already had a rule written
     * for it, and every write after this one is a smaller version of a problem
     * that is already solved.
     *
     * WHAT THE CALLER DOES NOT HAVE TO KNOW
     *
     * Nothing here mentions the outbox, a timestamp, or sync at all. The
     * trigger on the table queues the row and the clock stamps it; the next
     * sync finds it. A caller that had to remember to queue something would
     * eventually be a caller that forgot.
     *
     * @return the value written, or null when nothing was, so the screen can
     *         say which happened rather than guess from a redraw.
     */
    suspend fun toggleDone(
        ctx: Context,
        task: ScheduledTask,
        now: Instant,
        zone: TimeZone,
    ): String? {
        val repo = open(ctx)
        if (isDoneNow(repo.completions()[task.id], isoMillis(now.toEpochMilliseconds()))) {
            repo.clearDone(task.id)
            return null
        }

        val until = untilFor(task, now, zone) ?: return null
        repo.markDone(task.id, until)
        return until
    }

    /**
     * What `completed_until` becomes for one tick of this task.
     *
     * Pulled out of toggleDone so that the notification's own button cannot end
     * up with a second answer to the same question. It is the kind of rule that
     * looks safe to retype — four lines, no branches to speak of — and would
     * then be two rules the day either one is changed.
     *
     * Both instants are spelled by isoMillis rather than by toString, because
     * kotlinx-datetime prints the shortest form it can and the desktop always
     * prints milliseconds. This column is compared as a string and never parsed,
     * so two spellings of one moment is a tick that looks like it moved when
     * nothing happened. See isoMillis.
     */
    private fun untilFor(task: ScheduledTask, now: Instant, zone: TimeZone): String? {
        val next = nextReset(task.spec, now, zone)?.let { isoMillis(it.toEpochMilliseconds()) }
        return doneUntil(task.spec.resetType, next, isoMillis(endOfLocalDay(now, zone)))
    }

    /**
     * Tick one task done by uid, and never the other way.
     *
     * This is what the button in the notification shade calls, and the
     * difference from [toggleDone] is the whole reason it exists rather than
     * reusing that one.
     *
     * A toggle asks "what state is it in" and flips. From a screen that is fine:
     * the row is in front of somebody and the answer is visible. From the shade
     * it is a trap. The notification was posted at eight; the tick could have
     * happened on the desktop at ten past and synced down at quarter past; the
     * notification is still sitting there because nothing takes it back. Pressing
     * it then would UNTICK a task that was already done, silently, and the only
     * evidence would be an alarm going off again later.
     *
     * So already-done is success and writes nothing. The button means done, not
     * "the other one".
     *
     * @return false only when there is no such task any more, which happens when
     *         it was deleted on the desktop while the notification sat in the
     *         shade. The shade is not a place to report that to anybody, but the
     *         log is.
     */
    suspend fun markDoneOnce(
        ctx: Context,
        taskId: String,
        now: Instant,
        zone: TimeZone,
    ): Boolean {
        val repo = open(ctx)
        val task = repo.tasks().firstOrNull { it.id == taskId } ?: return false
        if (isDoneNow(repo.completions()[taskId], isoMillis(now.toEpochMilliseconds()))) return true
        val until = untilFor(task, now, zone) ?: return false
        repo.markDone(taskId, until)
        return true
    }

    /**
     * The last millisecond of today where the person is, as epoch millis.
     *
     * The desktop's own version of this is `setHours(23, 59, 59, 999)` on a
     * local Date, which is the same thing said in the one zone a desktop ever
     * has. A phone can be somewhere else, so the zone is passed in rather than
     * assumed.
     */
    private fun endOfLocalDay(now: Instant, zone: TimeZone): Long {
        val today = now.toLocalDateTime(zone).date
        return LocalDateTime(today.year, today.monthNumber, today.dayOfMonth, 23, 59, 59, 999_000_000)
            .toInstant(zone)
            .toEpochMilliseconds()
    }

    /**
     * A sync nobody asked for, on opening the app and after a tick.
     *
     * WHY THIS AND NOT A BACKGROUND JOB
     *
     * The rule this project set early, and is keeping, is that this app does no
     * work in the background: the phone's battery is worn and a periodic worker
     * is exactly the thing that would show up in the battery screen with this
     * app's name beside it. So the two moments that are already awake are used
     * instead — the app being opened, and a task being ticked. Both are the
     * person doing something, which is when there is news worth carrying.
     *
     * The honest cost, said out loud rather than discovered: a task ticked on
     * the desktop does not silence this phone's alarm until this app is opened.
     * The alarm still checks the local database before it fires, but the local
     * database is only as fresh as the last time this ran.
     *
     * Silent both ways. Nothing here is worth a toast: a sync that worked is
     * the absence of news, and one that failed because the train went into a
     * tunnel is not a thing to interrupt somebody about. The sync screen is
     * where an outcome belongs, and it has its own button.
     *
     * @return true when rows actually moved, so a caller can redraw instead of
     *         redrawing on every resume for nothing.
     */
    /**
     * What the last quiet attempt did, in one short line for the home screen.
     *
     * WHY THIS IS HERE AT ALL
     *
     * A sync nobody asked for is also a sync nobody can see fail. The first
     * version of this was silent in both directions on the grounds that success
     * is the absence of news — true, and it left no way to tell "everything
     * agrees" apart from "this has not run once in ten minutes", which are the
     * same picture on screen and completely different problems. One line costs
     * nothing and turns waiting into reading.
     */
    var lastQuiet: String = "ยังไม่ได้ลองซิงก์อัตโนมัติ"
        private set

    private fun stamp(): String {
        val t = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
    }

    suspend fun syncQuietly(ctx: Context): Boolean = try {
        val db = AndroidDb.shared(ctx)
        val http = AndroidHttpTransport()
        val tokens = driveTokenSource(db, http, AndroidSignIn.clientId()) {
            System.currentTimeMillis() / 1000
        }
        val report = SyncConfigs.syncNow(db, http, AndroidAeadCipher(), tokens)
        lastQuiet = when {
            report == null -> "อัตโนมัติ ${stamp()} · ยังตั้งค่าไม่ครบ"
            else -> "อัตโนมัติ ${stamp()} · รับ ${report.applied} ส่ง ${report.pushed}"
        }
        report != null && (report.applied > 0 || report.pushed > 0)
    } catch (e: Exception) {
        lastQuiet = "อัตโนมัติ ${stamp()} · ${e.message ?: e.toString()}"
        // Swallowed on purpose, and only here. Not being set up, no signal, a
        // folder that has moved — none of them are things this caller can do
        // anything about, and all of them are things the sync screen says
        // properly when someone goes looking.
        Log.i("Repo", "quiet sync did not run: ${e.message}")
        false
    }
}

/**
 * Work that outlives onReceive, without the two ways of getting it wrong.
 *
 * A BroadcastReceiver is dead the moment onReceive returns, and anything still
 * running on a coroutine after that is running in a process the system is free
 * to kill. goAsync() is what asks for the extra time — about ten seconds, which
 * is generous for a database read measured in milliseconds.
 *
 * The two mistakes it exists to prevent: forgetting finish(), which leaves the
 * system holding a wake lock for this app until it times out and complains; and
 * losing an exception, which on a receiver means an alarm chain that stopped
 * rebuilding itself with nothing anywhere saying so. The finally clause covers
 * the first and the catch covers the second.
 */
fun BroadcastReceiver.finishLater(tag: String, block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try {
            block()
        } catch (e: Throwable) {
            Log.e(tag, "background work failed", e)
        } finally {
            pending.finish()
        }
    }
}