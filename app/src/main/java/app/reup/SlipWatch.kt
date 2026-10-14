// app/src/main/java/app/reup/SlipWatch.kt
package app.reup

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import app.reup.core.expenseLabel
import app.reup.sync.memoCategory
import app.reup.sync.slipDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Slips that arrive on their own: the bank's app saves one into an album, and
 * a moment later it is in Reup.
 *
 * ─── ONE OF TWO THINGS IN THIS APP THAT WAKE UP BY THEMSELVES ───────────────
 *
 * Repo.syncQuietly says this app does no work in the background, and means it:
 * the battery is worn, and a periodic worker is exactly what shows up in the
 * battery screen with the app's name beside it. This is one exception - the
 * other is BankNoticeListener, for money coming in - and it is shaped to cost
 * as close to nothing as an exception can:
 *
 *  • Off until it is turned on, in Settings, by the person.
 *  • Nothing periodic. The system wakes it when a picture is added to the
 *    phone, and only then — the same kind of wake as an alarm firing.
 *  • Most of those wakes are a photo or a screenshot, and cost one query that
 *    finds nothing in the albums that were picked. The recogniser runs only
 *    for a new picture inside a bank's album.
 *  • Opening the app reads whatever arrived while it was asleep, so a phone
 *    whose battery manager holds the wake back loses nothing; the slip is
 *    simply there the next time the app is open instead of at once.
 *
 * ─── WHAT IT NEVER DOES ──────────────────────────────────────────────────────
 *
 * It never writes a row. A slip becomes a PendingSlip and a notification; the
 * row is written when somebody presses save. And it never reads a picture
 * outside the albums that were picked, or one saved before it was turned on —
 * turning it on means "from now", not "go through my gallery".
 *
 * ─── WHY ALL PHOTOS AND NOT "SELECTED PHOTOS" ────────────────────────────────
 *
 * Since Android 14 a person can let an app see only the photos they pick,
 * there and then. A slip saved tomorrow was not picked today, so with that
 * kind of access the albums look empty forever and nothing says why. The
 * settings screen says it instead, and asks for the full kind.
 */
object SlipWatch {

    private const val TAG = "SlipWatch"
    private const val PREFS = "reup_slips"
    private const val KEY_ON = "watch_on"
    private const val KEY_ALBUMS = "albums"
    private const val KEY_SINCE = "since"
    private const val KEY_DONE = "done"
    private const val KEY_TRIES = "tries"
    private const val KEY_LAST_ALBUM = "last_album"
    private const val KEY_LAST_AT = "last_at"

    private const val JOB_ID = 4201

    /** Pictures already read, by gallery id. Enough to outlast any burst of slips. */
    private const val KEEP_DONE = 300

    /** A picture that will not open after this many wakes is left alone. */
    private const val MAX_TRIES = 3

    /**
     * Seconds before the last picture read that are looked at again.
     *
     * The gallery stamps a picture when it is added, to the second, and two
     * slips in the same second are a real thing. The ids already read are what
     * keeps this from reading one twice.
     */
    private const val LOOK_BACK = 120L

    /** One reader at a time: the wake and the app opening can arrive together. */
    private val reading = Mutex()

    /** An album in the gallery. [id] is the gallery's bucket id, as text. */
    data class Album(val id: String, val name: String, val count: Int = 0, val suggested: Boolean = false)

    /** How much of the gallery this app may see. */
    enum class Access { ALL, SELECTED, NONE }

    /**
     * Album names that are a bank's or a wallet's, picked before anybody has
     * chosen. Only a suggestion — the person sees the list and decides.
     */
    private val BANKS = listOf(
        "kplus", "k+", "krungthai", "ktb", "next", "scb", "bangkokbank", "bualuang", "krungsri", "kma",
        "ttb", "gsb", "mymo", "paotang", "เป๋าตัง", "truemoney", "linebk", "uob", "cimb", "kkp",
    )

    // ── the switch ───────────────────────────────────────────────────────────

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    /**
     * On means from now. The pictures already in the albums are not read:
     * nobody turning this on is asking for last year's slips to arrive at once.
     */
    fun setOn(ctx: Context, on: Boolean) {
        val edit = prefs(ctx).edit().putBoolean(KEY_ON, on)
        if (on) edit.putLong(KEY_SINCE, System.currentTimeMillis() / 1000)
        edit.apply()
        arm(ctx)
    }

    /** Everything it needs: switched on, allowed to see the gallery, an album to watch. */
    fun ready(ctx: Context): Boolean = isOn(ctx) && access(ctx) == Access.ALL && chosen(ctx).isNotEmpty()

    /** The permission that means "all of the gallery", which changed name in Android 13. */
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /**
     * What to ask for. From Android 14 the selected-photos permission is asked
     * for alongside, as the platform asks apps to: then a person who picks
     * "selected photos" leaves a state [access] can see and the settings
     * screen can explain, instead of a temporary grant that quietly lapses
     * when the app is closed.
     */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else -> arrayOf(permission())
    }

    fun access(ctx: Context): Access {
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return when {
            has(permission()) -> Access.ALL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.SELECTED
            else -> Access.NONE
        }
    }

    // ── which albums ─────────────────────────────────────────────────────────

    fun chosen(ctx: Context): List<Album> {
        val text = prefs(ctx).getString(KEY_ALBUMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Album(o.getString("id"), o.optString("name", ""))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun choose(ctx: Context, albums: List<Album>) {
        val array = JSONArray()
        for (a in albums) array.put(JSONObject().put("id", a.id).put("name", a.name))
        prefs(ctx).edit().putString(KEY_ALBUMS, array.toString()).apply()
        arm(ctx)
    }

    /**
     * Every album with pictures in it, banks first, then the biggest.
     *
     * Counted here rather than asked of the gallery, which has no GROUP BY to
     * offer an app since Android 10: two short columns per picture, once, when
     * the list is opened.
     */
    suspend fun albums(ctx: Context): List<Album> = withContext(Dispatchers.IO) {
        if (access(ctx) == Access.NONE) return@withContext emptyList()
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        try {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        val id = c.getString(idCol) ?: continue
                        val name = c.getString(nameCol) ?: NO_NAME
                        val had = counts[id]
                        counts[id] = name to ((had?.second ?: 0) + 1)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "could not list the albums", e)
        }
        counts.map { (id, v) -> Album(id, v.first, v.second, bankLike(v.first)) }
            .sortedWith(compareByDescending<Album> { it.suggested }.thenByDescending { it.count })
    }

    private fun bankLike(name: String): Boolean {
        val n = name.lowercase().replace(" ", "").replace("_", "").replace("-", "")
        return BANKS.any { n.contains(it) }
    }

    // ── being woken ──────────────────────────────────────────────────────────

    /**
     * Ask the system to wake [SlipWatchJob] when a picture is added, or stop
     * asking when there is nothing to watch.
     *
     * A wake of this kind is spent when it fires, so the job asks again each
     * time it finishes, with [again] — and that ask is never skipped: the
     * system may still be holding the job that just finished, and a check for
     * "already waiting" would find that one and wait for nothing. From
     * anywhere else, asking while one is already waiting would only replace it
     * with the same thing, so that is skipped.
     */
    fun arm(ctx: Context, again: Boolean = false) {
        val jobs = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (!ready(ctx)) {
            jobs.cancel(JOB_ID)
            return
        }
        if (!again && jobs.getPendingJob(JOB_ID) != null) return
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, SlipWatchJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                ),
            )
            // A bank's app writes a slip in two steps — the entry, then the
            // pixels. A second and a half after the last change, the picture
            // is whole; ten seconds is the most a burst of changes may hold
            // the read back.
            .setTriggerContentUpdateDelay(1_500L)
            .setTriggerContentMaxDelay(10_000L)
            .build()
        try {
            jobs.schedule(info)
        } catch (e: Exception) {
            Log.e(TAG, "could not ask to be woken", e)
        }
    }

    // ── reading ──────────────────────────────────────────────────────────────

    private class Picture(val id: Long, val addedAt: Long, val album: String)

    /**
     * Read every new picture in the chosen albums, once.
     *
     * @return how many slips arrived, so a screen knows whether to redraw
     */
    suspend fun scan(ctx: Context): Int = reading.withLock {
        if (!ready(ctx)) return@withLock 0
        withContext(Dispatchers.IO) {
            var arrived = 0
            val tried = HashSet<Long>()
            // Twice more if pictures keep coming: a second slip saved while the
            // first was being read would otherwise wait for the next wake.
            for (round in 0 until 3) {
                val fresh = newPictures(ctx).filter { it.id !in tried }
                if (fresh.isEmpty()) break
                for (p in fresh) {
                    tried += p.id
                    try {
                        if (read(ctx, p)) arrived++
                    } catch (e: CancellationException) {
                        // Stopped by the system halfway through. Not this
                        // picture's fault: it stays unread for the next wake.
                        throw e
                    } catch (e: Exception) {
                        // One picture that breaks the reader is that picture's
                        // problem. It is marked read so it cannot break every
                        // wake after this one.
                        Log.e(TAG, "could not read picture ${p.id}", e)
                        markDone(ctx, p)
                    }
                }
            }
            arrived
        }
    }

    private fun newPictures(ctx: Context): List<Picture> {
        val albums = chosen(ctx).map { it.id }.toSet()
        if (albums.isEmpty()) return emptyList()
        val since = prefs(ctx).getLong(KEY_SINCE, 0L)
        val done = doneIds(ctx)
        val out = mutableListOf<Picture>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            MediaStore.Images.Media.DATE_ADDED + " >= ?",
            arrayOf((since - LOOK_BACK).coerceAtLeast(0L).toString()),
            MediaStore.Images.Media.DATE_ADDED + " ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id in done) continue
                if (c.getString(bucketCol) !in albums) continue
                out += Picture(id, c.getLong(addedCol), c.getString(nameCol) ?: NO_NAME)
            }
        }
        return out
    }

    /**
     * One picture: read it, and if it is a slip nobody has saved yet, put it in
     * the list and in the shade.
     *
     * A picture with no amount anywhere is not a slip — K PLUS saves its own QR
     * code into the same album — and goes quietly. A slip already in the books,
     * by its reference, goes quietly too: it was scanned by hand, or this is a
     * second copy of it.
     */
    private suspend fun read(ctx: Context, p: Picture): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, p.id)
        val bitmap = try {
            SlipOcr.decode(ctx, uri)
        } catch (e: Exception) {
            null
        }
        if (bitmap == null) {
            // Not written all the way yet, or gone. Tried again on the next
            // wake, a few times, then left alone.
            if (tryAgainLater(ctx, p)) return false
            markDone(ctx, p)
            return false
        }
        val result = SlipOcr.read(ctx, bitmap)
        bitmap.recycle()
        // Marked read at each way out rather than once up here: a wake stopped
        // between reading and listing must leave the picture unread, or the
        // slip on it is gone.
        val ok = result as? SlipRead.Ok
        if (ok == null) {
            markDone(ctx, p)
            return false
        }
        val text = ok.text

        val reading = ok.reading(SlipOcr.hints(ctx))
        if (reading.amount == null && "no-amount" in reading.problems) {
            markDone(ctx, p)
            return false
        }

        val repo = Repo.money(ctx)
        val reference = reading.reference
        if (reference != null && (repo.slipRecorded(reference) || PendingSlips.hasReference(ctx, reference))) {
            markDone(ctx, p)
            return false
        }

        val categories = repo.categories()
        var split = memoCategory(reading.memo, categories)
        if (split.category == null && split.note.isNotEmpty()) {
            split = split.copy(category = repo.categoryUsedFor(split.note))
        }
        val zone = TimeZone.currentSystemDefault()
        val savedAt = Instant.fromEpochSeconds(p.addedAt).toLocalDateTime(zone)
        val draft = slipDraft(reading, text, savedAt, split)
        val slip = PendingSlip(p.id, p.album, p.addedAt * 1000L, draft)
        PendingSlips.add(ctx, slip)
        // Money that left, for money coming in to be compared with: a slip
        // to oneself here is what says K PLUS's money in a minute later is
        // the same baht, moved.
        BankNotices.slipRead(ctx, slip)
        markDone(ctx, p)

        val label = expenseLabel(draft.category, categories.firstOrNull { it.key == draft.category }?.label)
        Notifications.postSlip(ctx, slip, label)
        prefs(ctx).edit().putString(KEY_LAST_ALBUM, p.album).putLong(KEY_LAST_AT, p.addedAt * 1000L).apply()
        return true
    }

    /** The last slip that arrived, as an album and a time, for the settings line. */
    fun last(ctx: Context): Pair<String, Long>? {
        val p = prefs(ctx)
        val album = p.getString(KEY_LAST_ALBUM, null) ?: return null
        val at = p.getLong(KEY_LAST_AT, 0L)
        return if (at > 0L) album to at else null
    }

    // ── what has been read ───────────────────────────────────────────────────

    private fun doneIds(ctx: Context): Set<Long> =
        prefs(ctx).getString(KEY_DONE, "")!!.split(',').mapNotNull { it.toLongOrNull() }.toSet()

    private fun markDone(ctx: Context, p: Picture) {
        val p0 = prefs(ctx)
        val ids = p0.getString(KEY_DONE, "")!!.split(',').mapNotNull { it.toLongOrNull() }
        val kept = (ids.filter { it != p.id } + p.id).takeLast(KEEP_DONE)
        val tries = triesOf(ctx)
        tries.remove(p.id.toString())
        val since = maxOf(p0.getLong(KEY_SINCE, 0L), p.addedAt)
        p0.edit()
            .putString(KEY_DONE, kept.joinToString(","))
            .putString(KEY_TRIES, tries.toString())
            .putLong(KEY_SINCE, since)
            .apply()
    }

    /** True while a picture that would not open still has tries left. */
    private fun tryAgainLater(ctx: Context, p: Picture): Boolean {
        val tries = triesOf(ctx)
        val n = tries.optInt(p.id.toString(), 0) + 1
        if (n >= MAX_TRIES) return false
        tries.put(p.id.toString(), n)
        prefs(ctx).edit().putString(KEY_TRIES, tries.toString()).apply()
        return true
    }

    private fun triesOf(ctx: Context): JSONObject = try {
        JSONObject(prefs(ctx).getString(KEY_TRIES, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val NO_NAME = "(ไม่มีชื่อ)"
}

/**
 * What the system wakes when a picture is added.
 *
 * It reads, asks to be woken again, and finishes. Everything it does is in
 * [SlipWatch], where opening the app can do the same thing.
 */
class SlipWatchJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters): Boolean {
        val ctx = applicationContext
        scope.launch {
            try {
                // Nothing new can mean the bank's app has not finished writing:
                // a picture still being written is hidden from other apps until
                // it is done. Once more, a moment later, costs one query.
                if (SlipWatch.scan(ctx) == 0) {
                    delay(3_000L)
                    SlipWatch.scan(ctx)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("SlipWatchJob", "reading the new pictures failed", e)
            } finally {
                jobFinished(params, false)
                // A wake of this kind is spent once it fires: without this,
                // the next slip would wait for the app to be opened.
                SlipWatch.arm(ctx, again = true)
            }
        }
        return true
    }

    /**
     * Stopped by the system before it finished — out of time, or replaced by
     * the ask above. Either way the next wake has to exist, and a picture this
     * one did not get to is read by that one, or when the app opens.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        SlipWatch.arm(applicationContext, again = true)
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
