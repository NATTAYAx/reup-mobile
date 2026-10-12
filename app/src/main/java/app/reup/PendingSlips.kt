// app/src/main/java/app/reup/PendingSlips.kt
package app.reup

import android.content.Context
import android.content.Intent
import app.reup.sync.SlipDraft
import app.reup.sync.slipCheck
import app.reup.sync.slipExpense
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One slip that arrived on its own and has not been saved or thrown away yet.
 *
 * [id] is the picture's id in the phone's gallery, which is what makes it the
 * same slip each time it is looked at: the notification, the row in the money
 * tab and the form all reach it through that number.
 */
data class PendingSlip(
    val id: Long,
    /** The album it arrived in: K PLUS, Krungthai NEXT. */
    val album: String,
    /** When the picture was saved, in epoch milliseconds. */
    val savedAt: Long,
    val draft: SlipDraft,
) {
    /**
     * The notification's number. Posted under its own tag, so it cannot collide
     * with a reminder's, which are numbered by the hash of a task's uid.
     */
    val notificationId: Int get() = (id xor (id ushr 32)).toInt()
}

/**
 * Slips waiting for a yes or a no.
 *
 * WHY THESE ARE KEPT AT ALL
 * -------------------------
 * The notification is the fast way through, and a notification can be swiped
 * away by a thumb that meant something else. A slip that only existed as a
 * notification would then be gone, and the whole point of this feature is that
 * nothing has to be chased down later. So every slip is written here first and
 * the notification is a view of it; the money tab shows the same list.
 *
 * WHY HERE AND NOT IN THE DATABASE
 * --------------------------------
 * Nothing in it is a fact about the person's money yet. It is this phone's
 * reading of pictures in this phone's gallery, and a row that travelled to the
 * desktop as "maybe 45 baht, maybe not" would be the guess the ledger is built
 * to keep out. SharedPreferences, like DeviceNotifyPrefs: a device's own
 * business, never synced, gone with the app.
 *
 * Thirty at most. A list that only grows is a list nobody looks at.
 */
object PendingSlips {

    private const val PREFS = "reup_slips"
    private const val KEY = "pending"
    private const val KEEP = 30

    /** Read-modify-write on one key, from a receiver and a screen at once. */
    private val lock = Any()

    /** One save at a time, so a double press is one row. */
    private val saving = Mutex()

    fun all(ctx: Context): List<PendingSlip> = synchronized(lock) { load(ctx) }

    fun get(ctx: Context, id: Long): PendingSlip? = all(ctx).firstOrNull { it.id == id }

    fun hasReference(ctx: Context, reference: String): Boolean =
        all(ctx).any { it.draft.reference == reference }

    /** Newest first. The same picture read twice replaces itself. */
    fun add(ctx: Context, slip: PendingSlip) {
        synchronized(lock) {
            val rest = load(ctx).filter { it.id != slip.id }
            store(ctx, (listOf(slip) + rest).sortedByDescending { it.savedAt }.take(KEEP))
        }
    }

    fun remove(ctx: Context, id: Long) {
        synchronized(lock) {
            val all = load(ctx)
            if (all.any { it.id == id }) store(ctx, all.filter { it.id != id })
        }
    }

    /** How one press went. */
    sealed interface Saved {
        data object Done : Saved
        /** The same slip is already a row — scanned by hand, or saved twice. */
        data object Already : Saved
        /** Saved or thrown away already, from the other place it is shown. */
        data object Gone : Saved
        /** Not a slip one press may save; it has to go through the form. */
        data object NeedsLook : Saved
        data class Refused(val problems: List<String>) : Saved
    }

    /**
     * The one press: the row the form would have written, through the same
     * repository and the same validation.
     *
     * Asked again here rather than trusted from when the notification was
     * built, because the button can be pressed a day later: the slip may have
     * been saved by hand since, or the reading may not have been one press in
     * the first place.
     */
    suspend fun save(ctx: Context, id: Long): Saved = saving.withLock {
        val slip = get(ctx, id) ?: return@withLock Saved.Gone
        if (!slip.draft.oneTap) return@withLock Saved.NeedsLook
        val repo = Repo.money(ctx)
        val reference = slip.draft.reference
        if (reference != null && repo.slipRecorded(reference)) {
            remove(ctx, id)
            return@withLock Saved.Already
        }
        val known = repo.categories().map { it.key }
        val refused = repo.addExpense(slipExpense(slip.draft), known)
        if (refused.isNotEmpty()) return@withLock Saved.Refused(refused)
        remove(ctx, id)
        Saved.Done
    }

    /**
     * The form, filled in from the slip, for anything that needs a look.
     *
     * Carries the slip's id so that saving there takes it off this list and
     * out of the shade — otherwise the slip would sit here asking to be saved
     * after it had been.
     */
    fun editIntent(ctx: Context, slip: PendingSlip): Intent {
        val d = slip.draft
        val intent = Intent(ctx, AddMoneyActivity::class.java)
            .putExtra(AddMoneyActivity.EXTRA_DATE, d.date)
            .putExtra(AddMoneyActivity.EXTRA_NOTE, d.note)
            .putExtra(AddMoneyActivity.EXTRA_SLIP_ID, slip.id)
        d.amount?.let { a ->
            intent.putExtra(
                AddMoneyActivity.EXTRA_AMOUNT,
                if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString(),
            )
        }
        d.category?.let { intent.putExtra(AddMoneyActivity.EXTRA_CATEGORY, it) }
        d.reference?.let { intent.putExtra(AddMoneyActivity.EXTRA_SLIP_REF, it) }
        d.currency?.let { intent.putExtra(AddMoneyActivity.EXTRA_CURRENCY, it) }
        if ("currency-ambiguous" in d.problems) intent.putExtra(AddMoneyActivity.EXTRA_CURRENCY_UNSURE, true)
        slipCheck(d)?.let { intent.putExtra(AddMoneyActivity.EXTRA_SLIP_CHECK, it) }
        return intent
    }

    // ── on disk ──────────────────────────────────────────────────────────────

    private fun load(ctx: Context): List<PendingSlip> {
        val text = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { i -> fromJson(array.getJSONObject(i)) }
        } catch (_: Exception) {
            // Unreadable is empty. These are readings of pictures that are
            // still in the gallery, and the scan screen reads them again.
            emptyList()
        }
    }

    private fun store(ctx: Context, slips: List<PendingSlip>) {
        val array = JSONArray()
        for (s in slips) array.put(toJson(s))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    private fun toJson(s: PendingSlip): JSONObject {
        val d = s.draft
        val o = JSONObject()
        o.put("id", s.id)
        o.put("album", s.album)
        o.put("at", s.savedAt)
        d.amount?.let { o.put("amount", it) }
        o.putOpt("currency", d.currency)
        o.put("date", d.date)
        o.put("clock", d.clock)
        o.putOpt("ref", d.reference)
        o.putOpt("memo", d.memo)
        o.put("note", d.note)
        o.putOpt("category", d.category)
        o.put("self", d.toSelf)
        o.put("problems", JSONArray(d.problems))
        return o
    }

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun fromJson(o: JSONObject): PendingSlip? {
        if (!o.has("id")) return null
        val problems = o.optJSONArray("problems")
        return PendingSlip(
            id = o.getLong("id"),
            album = o.optString("album", ""),
            savedAt = o.optLong("at", 0L),
            draft = SlipDraft(
                amount = if (o.has("amount") && !o.isNull("amount")) o.getDouble("amount") else null,
                currency = o.text("currency"),
                date = o.text("date") ?: return null,
                clock = o.text("clock") ?: "",
                reference = o.text("ref"),
                memo = o.text("memo"),
                note = o.text("note") ?: "",
                category = o.text("category"),
                toSelf = o.optBoolean("self", false),
                problems = if (problems == null) emptyList() else (0 until problems.length()).map { problems.getString(it) },
            ),
        )
    }
}
