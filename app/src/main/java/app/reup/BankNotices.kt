// app/src/main/java/app/reup/BankNotices.kt
package app.reup

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.reup.sync.Arrival
import app.reup.sync.BANK_APPS
import app.reup.sync.Flow
import app.reup.sync.Movement
import app.reup.sync.Verdict
import app.reup.sync.arrivalCheck
import app.reup.sync.arrivalIncome
import app.reup.sync.arrivalLine
import app.reup.sync.arrivalOf
import app.reup.sync.bankNotice
import app.reup.sync.judgeArrivals
import app.reup.sync.looksLikeMoneyIn
import app.reup.sync.missedLine
import app.reup.sync.movementOf
import app.reup.sync.noticeId
import app.reup.sync.slipMovement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Money that came in, read off a bank's notification, and what the rules make
 * of it. Only ever [Verdict.Income] or [Verdict.Maybe]: the person's own money
 * moving is never in the list.
 */
data class PendingIncome(val arrival: Arrival, val verdict: Verdict) {
    val id: String get() = arrival.id

    /** Nothing left to check: one press may write it. */
    val oneTap: Boolean get() = verdict == Verdict.Income
}

/**
 * Money coming in, from the notifications K PLUS and Krungthai NEXT post.
 *
 * WHAT IS KEPT, AND FOR HOW LONG
 * ------------------------------
 * The same kind of thing PendingSlips keeps, for the same reasons: nothing in
 * it is a fact about the person's money until somebody presses save, so it is
 * this phone's business and never syncs. SharedPreferences, gone with the app.
 *
 * Four lists:
 *
 *  • money that arrived, waiting or dealt with - saved, not saved, or the
 *    person's own money moving, put away by itself;
 *  • money that left, from K PLUS's notices and from the slips SlipWatch read,
 *    kept only to be compared with what arrives;
 *  • the person's own accounts, masked as K PLUS prints them;
 *  • the ids of notices already read, so that a notice posted again - on
 *    reconnecting, or updated in the shade - is one arrival, not two.
 *
 * What arrived and what left are kept for two weeks, dealt with or not, so the
 * money that explained one arrival cannot be used to explain another. The
 * account money came from - somebody else's, as often as not - is kept an
 * hour, which is far longer than K PLUS takes to post the notice that says
 * whether it is the person's own, and then forgotten. No text from any notice
 * is kept, and no names: neither bank prints one.
 */
object BankNotices {

    private const val PREFS = "reup_income"
    private const val KEY_ON = "on"
    private const val KEY_SINCE = "since"
    private const val KEY_ARRIVALS = "arrivals"
    private const val KEY_MOVES = "moves"
    private const val KEY_OWN = "own"
    private const val KEY_SEEN = "seen"
    private const val KEY_LAST = "last"
    private const val KEY_MISSED = "missed"

    /** Waiting arrivals. A list that only grows is a list nobody looks at. */
    private const val KEEP_WAITING = 30
    private const val KEEP_DEALT = 200
    private const val KEEP_MOVES = 200
    private const val KEEP_SEEN = 300
    private const val KEEP_OWN = 6
    private const val KEEP_MS = 14L * 24 * 60 * 60 * 1000

    /** After this, the account an arrival came from has been compared and is forgotten. See [tidy]. */
    private const val SETTLE_MS = 60L * 60 * 1000

    /** Older than this, a notice found still in the shade is not read. */
    private const val CATCH_UP_MS = 2L * 24 * 60 * 60 * 1000

    private enum class State { WAITING, SAVED, DISMISSED, OWN }

    private class Kept(var arrival: Arrival, var state: State)

    private class Store(
        val arrivals: MutableList<Kept>,
        val moves: MutableList<Movement>,
        val own: MutableMap<String, MutableList<String>>,
        val seen: MutableList<String>,
        var last: String?,
    )

    /** Read-modify-write on one file, from the listener, the slip watcher and a screen at once. */
    private val lock = Any()

    /** One save at a time, so a double press is one row. */
    private val saving = Mutex()

    // ── the switch ───────────────────────────────────────────────────────────

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    /**
     * On means from now, as it does for slips: a notice already in the shade
     * when this is switched on is not read. Off lets the listener go.
     */
    fun setOn(ctx: Context, on: Boolean) {
        val edit = prefs(ctx).edit().putBoolean(KEY_ON, on)
        if (on) edit.putLong(KEY_SINCE, System.currentTimeMillis())
        edit.apply()
        BankNoticeListener.switched(ctx, on)
    }

    /**
     * Whether the person has let this app read notifications - in the phone's
     * settings, the only place that can be said. Asked of the system every
     * time: it is changed there, never here.
     */
    fun hasAccess(ctx: Context): Boolean {
        val me = ComponentName(ctx, BankNoticeListener::class.java)
        return try {
            if (Build.VERSION.SDK_INT >= 27) {
                ctx.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(me) == true
            } else {
                val enabled = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
                enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The page where that is said: this app's own switch from Android 11, the
     * list of every app that asked before that.
     */
    fun accessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(ctx, BankNoticeListener::class.java).flattenToString(),
                )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    // ── what arrives ─────────────────────────────────────────────────────────

    /**
     * One notification from one of the two banks' apps.
     *
     * @param postTime when the system says it was posted, epoch milliseconds
     * @return whether anything was kept
     */
    fun posted(ctx: Context, app: String, title: String?, text: String?, postTime: Long): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean(KEY_ON, false)) return false
        val now = System.currentTimeMillis()
        if (postTime < p.getLong(KEY_SINCE, Long.MAX_VALUE) || now - postTime > CATCH_UP_MS) return false
        val posted = Instant.fromEpochMilliseconds(postTime).toLocalDateTime(TimeZone.currentSystemDefault())
        val n = bankNotice(app, title, text, posted)
        if (n == null) {
            // Money in by its title, in words the rules cannot read: said under
            // the switch, so a bank changing its wording is noticed instead of
            // missed in silence. Which bank and when are all that is kept.
            if (looksLikeMoneyIn(app, title)) {
                p.edit().putString(KEY_MISSED, (BANK_APPS[app] ?: app) + "|" + postTime).apply()
            }
            return false
        }
        val id = noticeId(n)
        synchronized(lock) {
            val s = load(ctx)
            if (id in s.seen) return false
            s.seen += id
            // K PLUS prints the account each notice is about, which is always
            // one of the person's own: that is how Krungthai's "from" is known
            // to be them.
            n.account?.let { learn(s, n.bank, it) }
            when (n.flow) {
                Flow.OUT -> s.moves += movementOf(n, postTime)
                Flow.IN -> {
                    s.arrivals += Kept(arrivalOf(n, postTime), State.WAITING)
                    s.last = id
                }
            }
            tidy(s, now)
            store(ctx, s)
        }
        return true
    }

    /**
     * A slip SlipWatch has just read, as money that left: Krungthai's slip to
     * oneself is what says K PLUS's money in a minute later was not income.
     */
    fun slipRead(ctx: Context, slip: PendingSlip) {
        if (!isOn(ctx)) return
        val m = slipMovement(slip.id, slip.savedAt, slip.album, slip.draft) ?: return
        synchronized(lock) {
            val s = load(ctx)
            if (s.moves.any { it.id == m.id }) return
            s.moves += m
            tidy(s, System.currentTimeMillis())
            store(ctx, s)
        }
    }

    // ── the list ─────────────────────────────────────────────────────────────

    /**
     * What is waiting, newest first, judged now: a slip read a few seconds
     * after the notice can still take an arrival off the list.
     */
    fun waiting(ctx: Context): List<PendingIncome> = synchronized(lock) {
        val s = load(ctx)
        val verdicts = judge(s)
        s.arrivals.filter { it.state == State.WAITING }
            .mapNotNull { k ->
                val v = verdicts[k.arrival.id] ?: Verdict.Income
                if (v == Verdict.Own) null else PendingIncome(k.arrival, v)
            }
            .sortedByDescending { it.arrival.at }
    }

    fun get(ctx: Context, id: String): PendingIncome? = waiting(ctx).firstOrNull { it.id == id }

    /** How one press went. */
    sealed interface Saved {
        data object Done : Saved
        /** Saved or thrown away already, from the other place it is shown. */
        data object Gone : Saved
        /** Not one press may save: it has to go through the form. */
        data object NeedsLook : Saved
        data class Refused(val problems: List<String>) : Saved
    }

    /**
     * The one press: the row the form would have written, through the same
     * repository and the same validation. Asked again here rather than
     * trusted from when the row was drawn, because a slip read since may have
     * shown the money to be the person's own.
     */
    suspend fun save(ctx: Context, id: String): Saved = saving.withLock {
        val p = get(ctx, id) ?: return@withLock Saved.Gone
        if (!p.oneTap) return@withLock Saved.NeedsLook
        val refused = Repo.money(ctx).addIncome(arrivalIncome(p.arrival))
        if (refused.isNotEmpty()) return@withLock Saved.Refused(refused)
        settle(ctx, id, State.SAVED)
        Saved.Done
    }

    /** Not a row, ever. Kept as dealt with, so the money that explained it explains nothing else. */
    fun dismiss(ctx: Context, id: String) = settle(ctx, id, State.DISMISSED)

    /** Back on the list: "เอาคืน", after a thumb that missed. */
    fun restore(ctx: Context, id: String) = settle(ctx, id, State.WAITING)

    /** Written down through the form it was opened in. */
    fun savedByForm(ctx: Context, id: String) = settle(ctx, id, State.SAVED)

    /** Whether one press, or another copy of the form, already wrote it down. */
    fun wasSaved(ctx: Context, id: String): Boolean = synchronized(lock) {
        load(ctx).arrivals.any { it.arrival.id == id && it.state == State.SAVED }
    }

    /**
     * The form, filled in, for one that needs a look - or for one that does
     * not, to put a name in ได้รับจาก before saving. Carries the notice's id,
     * so saving there takes it off the list.
     */
    fun editIntent(ctx: Context, p: PendingIncome): Intent {
        val d = arrivalIncome(p.arrival)
        val intent = Intent(ctx, AddMoneyActivity::class.java)
            .putExtra(AddMoneyActivity.EXTRA_INCOMING, true)
            .putExtra(AddMoneyActivity.EXTRA_AMOUNT, d.amount)
            .putExtra(AddMoneyActivity.EXTRA_DATE, d.date)
            .putExtra(AddMoneyActivity.EXTRA_NOTE, d.note)
            .putExtra(AddMoneyActivity.EXTRA_CURRENCY, d.currency)
            .putExtra(AddMoneyActivity.EXTRA_NOTICE_ID, p.id)
        arrivalCheck(p.verdict)?.let { intent.putExtra(AddMoneyActivity.EXTRA_SLIP_CHECK, it) }
        return intent
    }

    /**
     * The last money-in notice and what became of it, for the settings
     * screen: the way to see this working on a day when everything that came
     * in was the person's own money, and so nothing shows anywhere else - or
     * to see that a bank's notice arrived and could not be read.
     */
    fun lastLine(ctx: Context, today: LocalDate): String? {
        val missed = prefs(ctx).getString(KEY_MISSED, null)?.split('|')
            ?.takeIf { it.size == 2 }
            ?.let { (bank, at) -> at.toLongOrNull()?.let { bank to it } }
        synchronized(lock) {
            val s = load(ctx)
            val k = s.last?.let { id -> s.arrivals.firstOrNull { it.arrival.id == id } }
            if (missed != null && (k == null || missed.second > k.arrival.at)) {
                val at = Instant.fromEpochMilliseconds(missed.second).toLocalDateTime(TimeZone.currentSystemDefault())
                return missedLine(missed.first, at, today)
            }
            if (k == null) return null
            val v = if (k.state == State.OWN) Verdict.Own else judge(s)[k.arrival.id] ?: Verdict.Income
            return arrivalLine(k.arrival, v, today)
        }
    }

    // ── keeping it tidy ──────────────────────────────────────────────────────

    private fun settle(ctx: Context, id: String, state: State) {
        synchronized(lock) {
            val s = load(ctx)
            val k = s.arrivals.firstOrNull { it.arrival.id == id } ?: return
            k.state = state
            tidy(s, System.currentTimeMillis())
            store(ctx, s)
        }
    }

    private fun judge(s: Store): Map<String, Verdict> =
        judgeArrivals(s.arrivals.map { it.arrival }, s.moves, s.own)

    private fun learn(s: Store, bank: String, mask: String) {
        val list = s.own.getOrPut(bank) { mutableListOf() }
        if (list.any { it.equals(mask, ignoreCase = true) }) return
        list += mask
        while (list.size > KEEP_OWN) list.removeAt(0)
    }

    /**
     * An hour on, the account an arrival came from has done its work: it is
     * compared with the person's own the moment K PLUS has printed them, and
     * that is within minutes. One that was the person's own money is put away
     * for good, and the account is forgotten.
     *
     * One still waiting can yet be taken off the list by a slip read late - a
     * battery manager can hold the watcher back for hours, and a slip carries
     * the time it was saved, not the time it was read.
     */
    private fun tidy(s: Store, now: Long) {
        val settled = s.arrivals.filter { now - it.arrival.at >= SETTLE_MS }
        if (settled.any { it.state == State.WAITING || it.arrival.fromAccount != null }) {
            val verdicts = judge(s)
            for (k in settled) {
                if (k.state == State.WAITING && verdicts[k.arrival.id] == Verdict.Own) k.state = State.OWN
                if (k.arrival.fromAccount != null) k.arrival = k.arrival.copy(fromAccount = null)
            }
        }
        val cutoff = now - KEEP_MS
        val waiting = s.arrivals.filter { it.state == State.WAITING }.sortedByDescending { it.arrival.at }.take(KEEP_WAITING)
        val dealt = s.arrivals.filter { it.state != State.WAITING && it.arrival.at >= cutoff }
            .sortedByDescending { it.arrival.at }.take(KEEP_DEALT)
        s.arrivals.clear()
        s.arrivals += waiting + dealt
        val moves = s.moves.filter { it.at >= cutoff }.sortedByDescending { it.at }.take(KEEP_MOVES)
        s.moves.clear()
        s.moves += moves
        while (s.seen.size > KEEP_SEEN) s.seen.removeAt(0)
    }

    // ── on disk ──────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): Store {
        val p = prefs(ctx)
        val own = HashMap<String, MutableList<String>>()
        for (o in objects(p.getString(KEY_OWN, null))) {
            val bank = o.text("bank") ?: continue
            val mask = o.text("mask") ?: continue
            own.getOrPut(bank) { mutableListOf() } += mask
        }
        return Store(
            arrivals = objects(p.getString(KEY_ARRIVALS, null)).mapNotNull { keptOf(it) }.toMutableList(),
            moves = objects(p.getString(KEY_MOVES, null)).mapNotNull { moveOf(it) }.toMutableList(),
            own = own,
            seen = (p.getString(KEY_SEEN, null) ?: "").split(',').filter { it.isNotEmpty() }.toMutableList(),
            last = p.getString(KEY_LAST, null),
        )
    }

    private fun store(ctx: Context, s: Store) {
        val arrivals = JSONArray()
        for (k in s.arrivals) arrivals.put(keptJson(k))
        val moves = JSONArray()
        for (m in s.moves) moves.put(moveJson(m))
        val own = JSONArray()
        for ((bank, masks) in s.own) for (mask in masks) own.put(JSONObject().put("bank", bank).put("mask", mask))
        prefs(ctx).edit()
            .putString(KEY_ARRIVALS, arrivals.toString())
            .putString(KEY_MOVES, moves.toString())
            .putString(KEY_OWN, own.toString())
            .putString(KEY_SEEN, s.seen.joinToString(","))
            .putString(KEY_LAST, s.last)
            .apply()
        // The widget counts what is waiting.
        ReupWidget.redraw(ctx)
    }

    /**
     * Every object in a stored array, one at a time: one that will not read is
     * that one gone, not the list. These are readings of notices, and a lost
     * one is a payment to type in by hand rather than a broken screen.
     */
    private fun objects(text: String?): List<JSONObject> {
        if (text == null) return emptyList()
        val array = try {
            JSONArray(text)
        } catch (_: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { i ->
            try {
                array.getJSONObject(i)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun keptJson(k: Kept): JSONObject {
        val a = k.arrival
        return JSONObject()
            .put("id", a.id)
            .put("at", a.at)
            .put("date", a.date)
            .put("clock", a.clock)
            .put("amount", a.amount)
            .put("bank", a.bank)
            .putOpt("account", a.account)
            .putOpt("fromBank", a.fromBank)
            .putOpt("fromAccount", a.fromAccount)
            .put("state", k.state.name)
    }

    private fun keptOf(o: JSONObject): Kept? {
        val id = o.text("id") ?: return null
        val date = o.text("date") ?: return null
        val bank = o.text("bank") ?: return null
        return try {
            Kept(
                Arrival(
                    id = id,
                    at = o.getLong("at"),
                    date = date,
                    clock = o.optString("clock", ""),
                    amount = o.getDouble("amount"),
                    bank = bank,
                    account = o.text("account"),
                    fromBank = o.text("fromBank"),
                    fromAccount = o.text("fromAccount"),
                ),
                State.entries.firstOrNull { it.name == o.optString("state", "") } ?: State.WAITING,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun moveJson(m: Movement): JSONObject = JSONObject()
        .put("id", m.id)
        .put("at", m.at)
        .put("amount", m.amount)
        .putOpt("bank", m.bank)
        .putOpt("account", m.account)
        .put("self", m.toSelf)

    private fun moveOf(o: JSONObject): Movement? {
        val id = o.text("id") ?: return null
        return try {
            Movement(
                id = id,
                at = o.getLong("at"),
                amount = o.getDouble("amount"),
                bank = o.text("bank"),
                account = o.text("account"),
                toSelf = o.optBoolean("self", false),
            )
        } catch (_: Exception) {
            null
        }
    }
}
