// app/src/main/java/app/reup/WidgetLook.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import app.reup.core.InkMode
import app.reup.core.PictureLight
import app.reup.core.linear
import app.reup.core.pictureLight
import app.reup.core.pictureLightOf
import app.reup.core.saved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How the widget looks: the picture behind it, whether light moves across it,
 * and whether it says the month's money.
 *
 * ─── WHERE THE PICTURE COMES FROM ───────────────────────────────────────────
 *
 * The system's photo picker, which hands over the one picture or clip that was
 * picked and nothing else: no gallery permission, no look at anything else.
 * A clip gives one frame, the one the person stops the slider on.
 *
 * ─── WHY A COPY ─────────────────────────────────────────────────────────────
 *
 * What the picker hands over can be read for as long as the screen that asked
 * is open. The widget is drawn for months after that, from a receiver, so the
 * picture is read once, scaled down, and kept as this app's own JPEG. The
 * original is not touched, and nothing leaves the phone.
 *
 * ─── WHAT MOVES ─────────────────────────────────────────────────────────────
 *
 * A widget cannot play a clip or a GIF. A few seconds of one can be kept as a
 * film instead - a flipbook the home screen turns itself, twelve pages a
 * second - and then the picture is its first frame. See WidgetFilm and
 * core/Film.kt. Without a film, what moves is a band of light drawn by the home
 * screen, a dozen frames across the card and then several seconds of nothing;
 * see res/drawable/widget_shine.xml. Either one stops while the home screen is
 * not on show.
 *
 * ─── NO BACKGROUND AT ALL ───────────────────────────────────────────────────
 *
 * Clear: no card, no picture, a light shade behind the words and nothing else,
 * for a moving wallpaper to be seen through it - the smooth way to have a clip
 * behind the widget (WallClip says why).
 *
 * ─── NAMES IT DOES NOT SAY ──────────────────────────────────────────────────
 *
 * A home screen is read by whoever is next to the phone. A task can be marked
 * to show on it as ส่วนตัว instead of its name, at its time as before. Kept
 * on this phone only: it is about this phone's home screen, and a desk has its
 * own.
 */
object WidgetLook {

    private const val PREFS = "reup_widget"
    private const val KEY_MOTION = "motion"
    private const val KEY_MONEY = "money"
    /** When the kept picture was saved, which is also its version; 0 for none. */
    private const val KEY_PICTURE = "picture"
    /** When the kept film was saved, likewise. Its first frame is the picture. */
    internal const val KEY_FILM = "film"
    /** How long a page of the film is, in ms. */
    private const val KEY_FILM_TICK = "film_tick"
    /** Tasks shown as ส่วนตัว, by id. */
    private const val KEY_HIDDEN = "hidden"
    /** No background of its own: the wallpaper shows through. */
    private const val KEY_CLEAR = "clear"
    /** Which way round the words go over a picture: auto, light or dark. */
    private const val KEY_INK = "ink"
    /** How bright the picture or film is, as "version|dark,mid,bright". See core/WidgetInk.kt. */
    private const val KEY_LIGHT = "light"

    /** Steps in the histogram a picture's light is measured with. */
    private const val LIGHT_BINS = 64

    /** What a task marked [Look.hidden] is called on the widget. */
    const val HIDDEN_NAME = "ส่วนตัว"

    private const val DIR = "widget"
    private const val FILE = "picture.jpg"

    /**
     * The kept copy's longest side. A widget four cells square is about 1,100
     * pixels on a sharp phone; this leaves room for a bigger one without
     * keeping a camera's twelve megapixels to draw a card.
     */
    const val KEEP_LONGEST = 1440

    data class Look(
        val motion: Boolean,
        val money: Boolean,
        /** 0 when there is no picture. */
        val picture: Long,
        /** 0 when there is no film. With one, [picture] is its first frame. */
        val film: Long = 0L,
        /** How long a page of the film is, in ms. */
        val filmTick: Long = 0L,
        /** Tasks shown as [HIDDEN_NAME], by id. */
        val hidden: Set<String> = emptySet(),
        /**
         * No card, picture or film behind the words, only a light shade: the
         * wallpaper shows through. What the card would have been is kept for
         * when this is turned off again.
         */
        val clear: Boolean = false,
        /** White words, dark words, or whichever the picture needs less veil for. */
        val ink: InkMode = InkMode.AUTO,
    )

    fun read(ctx: Context): Look {
        val p = prefs(ctx)
        val picture = if (file(ctx).exists()) p.getLong(KEY_PICTURE, 0L) else 0L
        return Look(
            motion = p.getBoolean(KEY_MOTION, true),
            money = p.getBoolean(KEY_MONEY, false),
            picture = picture,
            film = if (picture != 0L && WidgetFilm.exists(ctx)) p.getLong(KEY_FILM, 0L) else 0L,
            filmTick = p.getLong(KEY_FILM_TICK, app.reup.core.FILM_TICK_OLD_MS),
            hidden = p.getStringSet(KEY_HIDDEN, null)?.toSet() ?: emptySet(),
            clear = p.getBoolean(KEY_CLEAR, false),
            ink = when (p.getString(KEY_INK, null)) {
                "light" -> InkMode.LIGHT
                "dark" -> InkMode.DARK
                else -> InkMode.AUTO
            },
        )
    }

    fun setInk(ctx: Context, mode: InkMode) {
        val word = when (mode) {
            InkMode.LIGHT -> "light"
            InkMode.DARK -> "dark"
            InkMode.AUTO -> "auto"
        }
        prefs(ctx).edit().putString(KEY_INK, word).apply()
        ReupWidget.redraw(ctx)
    }

    fun setMotion(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MOTION, on).apply()
        ReupWidget.redraw(ctx)
    }

    fun setMoney(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MONEY, on).apply()
        ReupWidget.redraw(ctx)
    }

    fun setClear(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CLEAR, on).apply()
        ReupWidget.redraw(ctx)
    }

    /** The tasks shown as [HIDDEN_NAME], replacing the ones that were. */
    fun setHidden(ctx: Context, ids: Set<String>) {
        // A copy: the set handed to SharedPreferences must not change after.
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, HashSet(ids)).apply()
        ReupWidget.redraw(ctx)
    }

    // ── the picture ─────────────────────────────────────────────────────────

    /**
     * Kept as this app's own copy, replacing whatever was there - a film too,
     * unless [film] says this is the first frame of the one just kept.
     */
    suspend fun keep(ctx: Context, picture: Bitmap, film: Boolean = false) = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("no room for the picture")
        // Written beside and then moved over, so a widget drawn halfway
        // through reads the old picture or the new one, never half of one.
        val part = File(dir, "$FILE.part")
        FileOutputStream(part).use { out ->
            if (!picture.compress(Bitmap.CompressFormat.JPEG, 90, out)) throw IOException("could not write the picture")
        }
        if (!part.renameTo(file(ctx))) throw IOException("could not keep the picture")
        val version = System.currentTimeMillis()
        val edit = prefs(ctx).edit().putLong(KEY_PICTURE, version)
        if (!film) {
            edit.putLong(KEY_FILM, 0L)
            WidgetFilm.drop(ctx)
            // A film has its light measured over every frame by WidgetFilm.
            lightOf(picture)?.let { edit.putString(KEY_LIGHT, "$version|" + it.saved()) }
        }
        edit.apply()
        forgetDrawn()
        ReupWidget.redraw(ctx)
    }

    fun forget(ctx: Context) {
        file(ctx).delete()
        prefs(ctx).edit().putLong(KEY_PICTURE, 0L).putLong(KEY_FILM, 0L).apply()
        WidgetFilm.drop(ctx)
        forgetDrawn()
        ReupWidget.redraw(ctx)
    }

    /**
     * A film just written by WidgetFilm, made the one the widget plays, with
     * [light] measured over all of its frames. Its first frame is kept next, as
     * the picture.
     */
    internal fun filmKept(ctx: Context, tickMs: Long, light: PictureLight?) {
        val version = System.currentTimeMillis()
        val edit = prefs(ctx).edit().putLong(KEY_FILM, version).putLong(KEY_FILM_TICK, tickMs)
        if (light != null) edit.putString(KEY_LIGHT, "$version|" + light.saved())
        edit.apply()
    }

    // ── how bright it is ────────────────────────────────────────────────────

    /**
     * How bright the picture or film of [version] is, for the veil and the
     * colour of the words (core/WidgetInk.kt). Measured when it was kept; a
     * picture kept before this was measured is measured now, once, from a
     * small decode of the kept copy.
     */
    fun light(ctx: Context, version: Long): PictureLight? {
        if (version == 0L) return null
        val stored = prefs(ctx).getString(KEY_LIGHT, null)
        if (stored != null && stored.substringBefore('|') == version.toString()) {
            pictureLightOf(stored.substringAfter('|', ""))?.let { return it }
        }
        val small = try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
            BitmapFactory.decodeFile(file(ctx).path, opts)
        } catch (e: Exception) {
            null
        } ?: return null
        val light = lightOf(small) ?: return null
        prefs(ctx).edit().putString(KEY_LIGHT, "$version|" + light.saved()).apply()
        return light
    }

    /** A new histogram for [addLight] to fill. */
    internal fun newLight(): IntArray = IntArray(LIGHT_BINS)

    /**
     * [picture]'s pixels into [histogram], by luminance. A 48-pixel sketch of
     * it is enough: what is asked is how bright its brightest tenth is, not
     * where.
     */
    internal fun addLight(histogram: IntArray, picture: Bitmap) {
        val w = 48
        val h = max(1, (48f * picture.height / max(1, picture.width)).roundToInt()).coerceAtMost(96)
        val sketch = try {
            Bitmap.createScaledBitmap(picture, w, h, true)
        } catch (e: Exception) {
            return
        }
        val px = IntArray(w * h)
        sketch.getPixels(px, 0, w, 0, 0, w, h)
        if (sketch !== picture) sketch.recycle()
        for (c in px) {
            val l = 0.2126 * linear(Color.red(c) / 255.0) +
                0.7152 * linear(Color.green(c) / 255.0) +
                0.0722 * linear(Color.blue(c) / 255.0)
            histogram[(l * histogram.size).toInt().coerceIn(0, histogram.size - 1)]++
        }
    }

    private fun lightOf(picture: Bitmap): PictureLight? {
        val h = newLight()
        addLight(h, picture)
        return pictureLight(h)
    }

    /**
     * A picture the photo picker handed over, its longest side at most
     * [longest]. Null for anything that will not decode.
     *
     * ImageDecoder where there is one, because it turns a phone photo the
     * right way up; the older decoder does not read which way up a camera
     * was held.
     */
    suspend fun readPicture(ctx: Context, uri: Uri, longest: Int = KEEP_LONGEST): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = longest.toFloat() / max(w, h)
                    if (scale < 1f) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
                    // In ordinary memory, so it can be written out as a JPEG.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val resolver = ctx.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.let { fit(it, longest) }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Whether what the picker handed over is a clip rather than a picture. */
    fun isClip(ctx: Context, uri: Uri): Boolean =
        try {
            ctx.contentResolver.getType(uri)?.startsWith("video/") == true
        } catch (e: Exception) {
            false
        }

    /** Whether it is a GIF, which may be a film or may be one picture. */
    fun isGif(ctx: Context, uri: Uri): Boolean =
        try {
            ctx.contentResolver.getType(uri) == "image/gif"
        } catch (e: Exception) {
            false
        }

    // ── for the widget ──────────────────────────────────────────────────────

    private val drawnLock = Any()
    /** The last few pictures cut for a widget, by version and size. Drawing again is the common case. */
    private val drawn = LinkedHashMap<String, Bitmap>()

    /**
     * The kept picture cut to fill [width] by [height] pixels, the way the
     * widget's centerCrop would, with its corners rounded to [corner] pixels
     * when the home screen will not round them itself; null when there is none.
     *
     * Cut here rather than handed over whole, because the home screen keeps a
     * copy of every picture a widget is drawn with, in its own memory, for as
     * long as the widget is there.
     */
    fun pictureFor(ctx: Context, version: Long, width: Int, height: Int, corner: Float): Bitmap? {
        if (version == 0L || width <= 0 || height <= 0) return null
        val key = "$version:$width:$height:$corner"
        synchronized(drawnLock) { drawn[key]?.let { return it } }
        val path = file(ctx).path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // The largest halving that still covers the widget, then the rest of
        // the way by scaling: decoding at full size to throw most of it away
        // is a few megabytes for nothing.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val cut = cover(decoded, width, height)
        val out = if (corner > 0f) rounded(cut, corner) else cut
        synchronized(drawnLock) {
            if (drawn.size >= 3) drawn.remove(drawn.keys.first())
            drawn[key] = out
        }
        return out
    }

    private fun forgetDrawn() {
        synchronized(drawnLock) { drawn.clear() }
    }

    /** Scaled until it covers [w] by [h], then cut from the middle. */
    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = max(w, (src.width * scale).roundToInt())
        val sh = max(h, (src.height * scale).roundToInt())
        val scaled = if (sw == src.width && sh == src.height) src else Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
    }

    /** The same picture inside a rounded card, for a home screen too old to clip one. */
    private fun rounded(src: Bitmap, corner: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        Canvas(out).drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), corner, corner, paint)
        return out
    }

    /** No longer than [longest] on either side. */
    internal fun fit(src: Bitmap, longest: Int): Bitmap {
        val scale = longest.toFloat() / max(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, max(1, (src.width * scale).roundToInt()), max(1, (src.height * scale).roundToInt()), true)
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, DIR), FILE)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * One clip, open while a frame is picked from it.
 *
 * One frame at a time, behind a lock: the retriever is one decoder, and the
 * slider asks faster than it answers. Closed when the picking is done, which
 * is also when the picker's permission to read the clip stops mattering.
 */
class ClipFrames private constructor(
    private val retriever: MediaMetadataRetriever,
    /** 0 when the clip did not say. */
    val durationMs: Long,
) {
    private val lock = Mutex()
    private var closed = false

    /**
     * The frame at [ms], its longest side at most [longest]. [exact] for that
     * very frame, which means decoding up to it from the key frame before; the
     * nearest key frame otherwise, which is what a slider being dragged wants.
     */
    suspend fun at(ms: Long, longest: Int, exact: Boolean): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed) return@withLock null
            try {
                grab(ms, longest, exact)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun grab(ms: Long, longest: Int, exact: Boolean): Bitmap? {
        val us = ms.coerceIn(0L, max(0L, durationMs)) * 1000L
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // Inside a square of [longest] on a side, keeping the clip's shape.
            return retriever.getScaledFrameAtTime(us, option, longest, longest)
        }
        val frame = retriever.getFrameAtTime(us, option) ?: return null
        return WidgetLook.fit(frame, longest)
    }

    suspend fun close() {
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Released or not, it is not used again.
                }
            }
        }
    }

    companion object {
        /** Null for a clip that will not open. */
        suspend fun open(ctx: Context, uri: Uri): ClipFrames? = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uri)
                val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                ClipFrames(retriever, max(0L, ms))
            } catch (e: Exception) {
                try {
                    retriever.release()
                } catch (e2: Exception) {
                    // Nothing more to do with it.
                }
                null
            }
        }
    }
}

/** How a clip's length is said under the slider: "0:07", "1:02:05". */
fun clipTime(ms: Long): String {
    val total = max(0L, ms) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = if (s < 10) "0$s" else s.toString()
    if (h == 0L) return "$m:$ss"
    val mm = if (m < 10) "0$m" else m.toString()
    return "$h:$mm:$ss"
}
