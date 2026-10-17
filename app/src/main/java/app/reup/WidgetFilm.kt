// app/src/main/java/app/reup/WidgetFilm.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import app.reup.core.FILM_MAX_PICTURES
import app.reup.core.FILM_TICK_OLD_MS
import app.reup.core.FilmPace
import app.reup.core.PictureLight
import app.reup.core.pictureLight
import app.reup.core.clipLoop
import app.reup.core.filmFit
import app.reup.core.gifInfo
import app.reup.core.gifTicks
import app.reup.core.readGif
import app.reup.core.thinOut
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A few seconds of a clip or a GIF, kept for the widget to play as a flipbook.
 *
 * ─── WHAT IS KEPT ───────────────────────────────────────────────────────────
 *
 * The frames as JPEGs, no longer than [KEEP_LONGEST] on a side, a line that
 * says which frame each tick of the loop shows, and how long a tick is - in
 * widget/film/ in the app's own files, next to the picture, which is the
 * film's first frame. The
 * clip or GIF itself is read once, while the picker's permission lasts, and
 * not touched again. Nothing leaves the phone.
 *
 * ─── WHAT THE WIDGET IS HANDED ──────────────────────────────────────────────
 *
 * The frames cut to the widget's shape at the size filmFit allows (Film.kt),
 * at two bytes a pixel with the banding dithered away, and the same picture
 * object wherever a tick repeats one, which the system sends once. Cut again
 * only when the film or the widget's size changes; the last cut is kept.
 *
 * Android 12 and up only. Before it, a widget's corners are cut into its
 * pictures (see WidgetLook.pictureFor), which two bytes a pixel cannot hold,
 * and a film there would be a square inside a rounded card. Those phones show
 * the first frame.
 */
object WidgetFilm {

    private const val TAG = "WidgetFilm"

    private const val DIR = "widget"
    private const val FILM = "film"
    private const val TICKS = "ticks.txt"
    private const val TICK = "tick.txt"

    /**
     * The kept frames' longest side. A four-by-four on a sharp phone is about
     * 1,200 pixels tall, and filmFit draws it at about half that; this leaves
     * the room for a tall widget's crop out of a wide clip.
     */
    const val KEEP_LONGEST = 1280

    /** The largest GIF read, in bytes. Every byte of it is in memory while it is read. */
    private const val GIF_MAX_BYTES = 48L * 1024 * 1024

    /** How far along the making is: frames read so far, of how many. */
    fun interface Progress {
        fun at(done: Int, total: Int)
    }

    /** What a GIF turned out to be. */
    enum class GifKind {
        /** Kept as the film, its first frame as the picture. */
        FILM,

        /** One frame, or nothing moving: a picture, for the caller to keep as one. */
        PICTURE,

        /** Not readable as a GIF, or too large to read. */
        NONE,
    }

    /** Whether a film is kept. */
    fun exists(ctx: Context): Boolean = File(filmDir(ctx), TICKS).exists()

    /** The kept film gone, and anything cut from it. */
    fun drop(ctx: Context) {
        File(filmDir(ctx).parentFile, "$FILM.part").deleteRecursively()
        filmDir(ctx).deleteRecursively()
        forgetCut()
    }

    // ── making one ──────────────────────────────────────────────────────────

    /** A film cut for one widget: a picture per tick, and how long a tick is. */
    class Film(val frames: List<Bitmap>, val tickMs: Long)

    /**
     * [pace]'s length of [clip] from [startMs], kept as the film, its first
     * frame as the picture. False when too little of it could be read.
     *
     * Read in order, a frame at a time. The half second the loop fades back
     * into comes first in the clip and last in the loop, so those frames are
     * the only ones held in memory until the end.
     */
    suspend fun fromClip(ctx: Context, clip: ClipFrames, startMs: Long, pace: FilmPace, progress: Progress): Boolean =
        withContext(Dispatchers.IO) {
            val plan = clipLoop(clip.durationMs, startMs, pace)
            if (plan.frames.size < 2) return@withContext false
            val fade = plan.readAt.size - plan.frames.size
            val early = arrayOfNulls<Bitmap>(fade)
            var writer: Writer? = null
            var done = false
            try {
                val out = Writer(ctx).also { writer = it }
                var first: Bitmap? = null
                var last: Bitmap? = null
                for (i in plan.readAt.indices) {
                    // A frame that will not read is the one before it again; a clip
                    // whose first will not read is no film.
                    val frame = clip.at(plan.readAt[i], KEEP_LONGEST, exact = true) ?: last ?: return@withContext false
                    last = frame
                    progress.at(i + 1, plan.readAt.size)
                    if (i < fade) {
                        early[i] = frame
                        continue
                    }
                    val k = i - fade
                    val step = plan.frames[k]
                    val toward = if (step.toward >= 0) early[step.toward] else null
                    val picture = if (toward != null) blend(frame, toward, step.amount) else frame
                    out.write(k, picture)
                    if (k == 0) first = picture
                }
                val picture = first ?: return@withContext false
                out.finish(List(plan.frames.size) { it }, pace.tickMs)
                done = true
                kept(ctx, picture, pace.tickMs, out.light())
                true
            } catch (e: CancellationException) {
                // The screen that asked has gone: nothing kept, and nothing said.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "could not keep a film from a clip: " + e.javaClass.simpleName)
                false
            } finally {
                if (!done) writer?.abandon()
            }
        }

    /**
     * A GIF that moves, kept as the film; one that does not is a [GifKind.PICTURE].
     *
     * Laid on the smooth clock, twenty-four ticks a second; only the frames the
     * ticks land on are kept, at most FILM_MAX_PICTURES of them, decoded in one
     * pass through the file.
     */
    suspend fun fromGif(ctx: Context, uri: Uri, progress: Progress): GifKind = withContext(Dispatchers.IO) {
        val bytes = try {
            ctx.contentResolver.openInputStream(uri)?.use { readAtMost(it, GIF_MAX_BYTES) }
        } catch (e: Exception) {
            null
        } ?: return@withContext GifKind.NONE
        val info = gifInfo(bytes) ?: return@withContext GifKind.NONE
        val tick = FilmPace.SMOOTH.tickMs
        val ticks = thinOut(gifTicks(info.delaysMs, tickMs = tick), FILM_MAX_PICTURES)
        val wanted = ticks.toSortedSet()
        if (wanted.size < 2) return@withContext GifKind.PICTURE

        // The GIF's frame numbers, as the film's pictures: in the order they come.
        val number = HashMap<Int, Int>()
        val scale = min(1f, KEEP_LONGEST.toFloat() / max(info.width, info.height))
        var first: Bitmap? = null
        var writer: Writer? = null
        var done = false
        try {
            val out = Writer(ctx).also { writer = it }
            val lastWanted = wanted.last()
            val read = readGif(bytes) { index, pixels ->
                if (index in wanted) {
                    val picture = toBitmap(pixels, info.width, info.height, scale)
                    val n = number.size
                    out.write(n, picture)
                    number[index] = n
                    if (n == 0) first = picture
                    progress.at(number.size, wanted.size)
                }
                index < lastWanted
            }
            val picture = first
            if (!read || picture == null || number.size < 2) {
                return@withContext if (picture != null) GifKind.PICTURE else GifKind.NONE
            }
            // Any frame the file did not deliver shows the one before it.
            val got = number.keys.sorted()
            out.finish(ticks.map { want -> number.getValue(got.lastOrNull { it <= want } ?: got.first()) }, tick)
            done = true
            kept(ctx, picture, tick, out.light())
            GifKind.FILM
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not keep a film from a GIF: " + e.javaClass.simpleName)
            GifKind.NONE
        } finally {
            if (!done) writer?.abandon()
        }
    }

    /** All of [input], or null past [limit] bytes - found out before they are all in memory. */
    private fun readAtMost(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** The film written; now the one the widget plays, with its first frame as the picture. */
    private suspend fun kept(ctx: Context, first: Bitmap, tickMs: Long, light: PictureLight?) {
        forgetCut()
        WidgetLook.filmKept(ctx, tickMs, light)
        WidgetLook.keep(ctx, first, film = true)
    }

    /** [frame] faded [amount] of the way toward [toward]. */
    private fun blend(frame: Bitmap, toward: Bitmap, amount: Float): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (amount * 255f).roundToInt().coerceIn(0, 255)
        Canvas(out).drawBitmap(toward, null, Rect(0, 0, out.width, out.height), paint)
        return out
    }

    /**
     * One frame of a GIF as a picture, no larger than [scale] of it. What is
     * transparent comes out black in a JPEG, which is what a widget's scrim is
     * nearest to anyway.
     */
    private fun toBitmap(pixels: IntArray, width: Int, height: Int, scale: Float): Bitmap {
        val full = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if (scale >= 1f) return full
        return Bitmap.createScaledBitmap(full, max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()), true)
    }

    /** A film being written beside the kept one, and put in its place whole. */
    private class Writer(ctx: Context) {
        private val base = File(ctx.filesDir, DIR)
        private val part = File(base, "$FILM.part")

        init {
            part.deleteRecursively()
            if (!part.mkdirs()) throw IOException("no room for the film")
        }

        /** Every frame's light, for the veil the widget's words need over the whole film. */
        private val light = WidgetLook.newLight()

        fun write(n: Int, picture: Bitmap) {
            FileOutputStream(File(part, "$n.jpg")).use { out ->
                if (!picture.compress(Bitmap.CompressFormat.JPEG, 88, out)) throw IOException("could not write a frame")
            }
            WidgetLook.addLight(light, picture)
        }

        /** How bright the film is, over every frame written. */
        fun light(): PictureLight? = pictureLight(light)

        /** [ticks] and their length written, then the whole of it moved over the film that was there. */
        fun finish(ticks: List<Int>, tickMs: Long) {
            File(part, TICKS).writeText(ticks.joinToString(","))
            File(part, TICK).writeText(tickMs.toString())
            val film = File(base, FILM)
            val old = File(base, "$FILM.old")
            old.deleteRecursively()
            if (film.exists() && !film.renameTo(old)) throw IOException("could not move the old film")
            if (!part.renameTo(film)) throw IOException("could not keep the film")
            old.deleteRecursively()
        }

        fun abandon() {
            part.deleteRecursively()
        }
    }

    // ── for the widget ──────────────────────────────────────────────────────

    private val cutLock = Any()
    private var cutKey: String? = null
    private var cut: Film? = null

    private fun forgetCut() {
        synchronized(cutLock) {
            cutKey = null
            cut = null
        }
    }

    /**
     * The film for a widget [widthPx] by [heightPx]: one picture per tick, cut
     * to the widget's shape at the size filmFit allows in [budget] bytes, the
     * same Bitmap wherever a tick shows one again, and the tick it plays at.
     * Null with no film, before Android 12, or when the widget cannot afford
     * two pictures.
     */
    fun framesFor(ctx: Context, version: Long, widthPx: Int, heightPx: Int, budget: Long): Film? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || version == 0L || widthPx <= 0 || heightPx <= 0) return null
        val key = "$version:$widthPx:$heightPx:$budget"
        synchronized(cutLock) {
            if (cutKey == key) return cut
        }
        val dir = filmDir(ctx)
        val ticks = readTicks(dir) ?: return null
        val pictures = ticks.toSortedSet()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(dir, "${pictures.first()}.jpg").path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val fit = filmFit(widthPx, heightPx, pictures.size, bounds.outWidth, bounds.outHeight, budget)
        if (fit.keep < 2) return null
        val shown = thinOut(ticks, fit.keep)
        val made = HashMap<Int, Bitmap>()
        for (p in shown.toSortedSet()) {
            val src = decode(File(dir, "$p.jpg"), fit.width, fit.height) ?: continue
            made[p] = cut565(src, fit.width, fit.height)
        }
        if (made.size < 2) return null
        // A frame that would not decode: the one before it, held.
        var held: Bitmap? = made[shown.first()] ?: made.values.first()
        val out = Film(shown.map { p -> (made[p] ?: held)!!.also { held = it } }, readTick(dir))
        synchronized(cutLock) {
            cutKey = key
            cut = out
        }
        return out
    }

    /** How long a tick is; a film from before there was a choice has twelve a second. */
    private fun readTick(dir: File): Long =
        try {
            File(dir, TICK).readText().trim().toLong().takeIf { it in 20L..1_000L } ?: FILM_TICK_OLD_MS
        } catch (e: Exception) {
            FILM_TICK_OLD_MS
        }

    /** Which picture each tick shows, or null for a film that is not all there. */
    private fun readTicks(dir: File): List<Int>? =
        try {
            val ticks = File(dir, TICKS).readText().split(',').map { it.trim().toInt() }
            if (ticks.size < 2 || ticks.toSet().any { !File(dir, "$it.jpg").exists() }) null else ticks
        } catch (e: Exception) {
            null
        }

    /** A kept frame, decoded no larger than it needs to be to cover [w] by [h]. */
    private fun decode(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * [src] scaled to cover [w] by [h] and cut from the middle, at two bytes a
     * pixel. Dithered on the way down, or skies and shadows break into bands.
     * Handed over as shared memory, which the system can pass on without
     * copying it; a picture it could change, it copies every time.
     */
    private fun cut565(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = w / scale
        val sh = h / scale
        val left = (src.width - sw) / 2f
        val top = (src.height - sh) / 2f
        val from = Rect(left.roundToInt(), top.roundToInt(), (left + sw).roundToInt(), (top + sh).roundToInt())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) out.asShared() else out
    }

    private fun filmDir(ctx: Context) = File(File(ctx.filesDir, DIR), FILM)
}
