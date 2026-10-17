// app/src/main/java/app/reup/WallClip.kt
package app.reup

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The clip the wallpaper plays (ReupWallpaper), picked in Settings.
 *
 * ─── WHY A WALLPAPER ────────────────────────────────────────────────────────
 *
 * A widget can only flip through pictures handed to it, a screen and a half of
 * them in all (core/Film.kt), so smooth there means short or blurred. A
 * wallpaper is given the whole screen and plays a clip the way a video player
 * does, on the phone's own video decoder: as long and as smooth as the clip
 * itself. With the widget's background clear (WidgetLook), the clip plays
 * behind the widget and everything else on the home screen.
 *
 * ─── WHY A COPY ─────────────────────────────────────────────────────────────
 *
 * What the photo picker hands over can be read while the screen that asked is
 * open; the wallpaper plays it for weeks. So the clip is copied into the app's
 * own files once, whole, and the copy is what plays. Picking another replaces
 * it; the clip in the gallery is never touched, and nothing leaves the phone.
 *
 * ─── HOW HARD IT WORKS THE PHONE ────────────────────────────────────────────
 *
 * Decoding a clip costs what the clip asks for, not what the screen shows: a
 * 4K clip is decoded at 4K and then shrunk, sixty frames a second is twice the
 * work of thirty, and a clip in a format the phone's video chip does not take
 * is decoded by the processor, which warms a phone quickly. So the clip is
 * weighed when it is kept ([weigh]), and Settings says plainly when one is
 * heavier than a phone's screen needs. And the wallpaper holds still on its
 * frame while battery saver is on, or while the phone says it is warm
 * ([resting]), and plays again on its own once neither is true.
 *
 * ─── CHANGING IT ────────────────────────────────────────────────────────────
 *
 * The wallpaper is set once, through the system's own page (setIntents). After
 * that a new clip is only a new copy here: the wallpaper hears [KEY_VERSION]
 * change and plays the new one, with nothing to set again.
 */
object WallClip {

    private const val PREFS = "reup_wallpaper"

    /** When the kept clip was saved, which is also its version; 0 for none. ReupWallpaper listens for it. */
    const val KEY_VERSION = "version"
    private const val KEY_DURATION = "duration"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_FPS = "fps"
    private const val KEY_MIME = "mime"
    /** 1 the video chip decodes it, 0 the processor does, -1 the phone did not say. */
    private const val KEY_CHIP = "chip"
    private const val KEY_HDR = "hdr"

    private const val DIR = "wallpaper"
    private const val FILE = "clip"
    private const val THUMB = "thumb.jpg"

    /**
     * The largest clip copied in. A copy lasts as long as it is the wallpaper,
     * and a clip worth looping behind a home screen is seconds long, not an
     * episode.
     */
    const val MAX_MB = 500
    private const val MAX_BYTES = MAX_MB * 1024L * 1024L

    /** The frame kept for the settings row and the wallpaper's colours. */
    private const val THUMB_LONGEST = 540

    data class Clip(
        /** When it was kept. */
        val version: Long,
        /** 0 when the clip did not say. */
        val durationMs: Long,
    )

    /** How keeping a picked clip went. */
    enum class Kept { KEPT, TOO_BIG, NOT_A_CLIP, NO_ROOM }

    /** What playing a clip asks of the phone. */
    data class Weight(
        val width: Int,
        val height: Int,
        /** Frames a second, rounded; 0 when it could not be told. */
        val fps: Int,
        val mime: String,
        /** Decoded by the video chip; false for the processor; null when the phone did not say. */
        val chip: Boolean?,
        /** HDR, whose colours are worked out again for the screen every frame. */
        val hdr: Boolean = false,
    )

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kept clip, or null for none. */
    fun read(ctx: Context): Clip? {
        val p = prefs(ctx)
        val version = p.getLong(KEY_VERSION, 0L)
        if (version == 0L || !file(ctx).exists()) return null
        return Clip(version, p.getLong(KEY_DURATION, 0L))
    }

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE)

    /** The kept clip's frame, small; null for none. */
    fun thumb(ctx: Context): Bitmap? =
        try {
            val f = File(File(ctx.filesDir, DIR), THUMB)
            if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        } catch (e: Exception) {
            null
        }

    /**
     * What the picker handed over, copied in as the clip, replacing the one
     * there was. Checked before it replaces anything: it has to be a video
     * this phone can open, and no larger than [MAX_MB].
     */
    suspend fun keep(ctx: Context, uri: Uri): Kept = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return@withContext Kept.NO_ROOM
        val part = File(dir, "$FILE.part")
        val thumbPart = File(dir, "$THUMB.part")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return@withContext Kept.NOT_A_CLIP
            input.use { from ->
                FileOutputStream(part).use { to ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        val n = from.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return@withContext Kept.TOO_BIG
                        to.write(buffer, 0, n)
                    }
                }
            }

            // A video this phone opens: its length, and a frame a quarter in.
            val retriever = MediaMetadataRetriever()
            var duration = 0L
            var frame: Bitmap? = null
            try {
                retriever.setDataSource(part.path)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    return@withContext Kept.NOT_A_CLIP
                }
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val us = duration * 1000L / 4
                frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_LONGEST, THUMB_LONGEST)
                } else {
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { WidgetLook.fit(it, THUMB_LONGEST) }
                }
            } catch (e: Exception) {
                return@withContext Kept.NOT_A_CLIP
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    // Released or not, it is not used again.
                }
            }

            val thumb = File(dir, THUMB)
            if (frame != null) {
                FileOutputStream(thumbPart).use { frame.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (!thumbPart.renameTo(thumb)) thumb.delete()
            } else {
                thumb.delete()
            }
            val weight = measure(part)
            // Moved over the old one whole: a wallpaper still playing it keeps
            // the file it has open, and the next one it opens is the new clip.
            if (!part.renameTo(file(ctx))) return@withContext Kept.NO_ROOM
            val edit = prefs(ctx).edit().putLong(KEY_DURATION, duration)
            put(edit, weight)
            edit.putLong(KEY_VERSION, System.currentTimeMillis()).apply()
            Kept.KEPT
        } catch (e: IOException) {
            Kept.NO_ROOM
        } finally {
            // Gone already when it was kept; a half-copied clip otherwise.
            part.delete()
            thumbPart.delete()
        }
    }

    /** No clip: the wallpaper stops until another is picked or the wallpaper is changed. */
    fun forget(ctx: Context) {
        file(ctx).delete()
        File(File(ctx.filesDir, DIR), THUMB).delete()
        prefs(ctx).edit().putLong(KEY_VERSION, 0L).putLong(KEY_DURATION, 0L).remove(KEY_MIME).apply()
    }

    // ── how heavy ───────────────────────────────────────────────────────────

    /** The kept clip's weight, as measured when it was kept; null when it never was. */
    fun weight(ctx: Context): Weight? {
        val p = prefs(ctx)
        val mime = p.getString(KEY_MIME, null) ?: return null
        val chip = when (p.getInt(KEY_CHIP, -1)) {
            1 -> true
            0 -> false
            else -> null
        }
        return Weight(p.getInt(KEY_WIDTH, 0), p.getInt(KEY_HEIGHT, 0), p.getInt(KEY_FPS, 0), mime, chip, p.getBoolean(KEY_HDR, false))
    }

    /** The kept clip weighed now, for one kept before clips were weighed. Off the main thread. */
    suspend fun weigh(ctx: Context): Weight? = withContext(Dispatchers.IO) {
        if (read(ctx) == null) return@withContext null
        val w = measure(file(ctx)) ?: return@withContext null
        val edit = prefs(ctx).edit()
        put(edit, w)
        edit.apply()
        w
    }

    private fun put(edit: SharedPreferences.Editor, w: Weight?) {
        if (w == null) {
            edit.remove(KEY_MIME)
            return
        }
        edit.putInt(KEY_WIDTH, w.width)
            .putInt(KEY_HEIGHT, w.height)
            .putInt(KEY_FPS, w.fps)
            .putString(KEY_MIME, w.mime)
            .putInt(KEY_CHIP, if (w.chip == true) 1 else if (w.chip == false) 0 else -1)
            .putBoolean(KEY_HDR, w.hdr)
    }

    /** The clip's picture track: its size, its frames a second, and who would decode it. */
    private fun measure(file: File): Weight? {
        val ex = MediaExtractor()
        return try {
            FileInputStream(file).use { ex.setDataSource(it.fd) }
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            val f = ex.getTrackFormat(track)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: return null
            val w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
            val h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
            var fps = frameRate(f)
            if (fps <= 0) {
                // Not said: counted, over the first two seconds of frames.
                ex.selectTrack(track)
                var n = 0
                var first = -1L
                var last = -1L
                while (n < 600) {
                    val t = ex.sampleTime
                    if (t < 0) break
                    if (first < 0) first = t
                    last = max(last, t)
                    n++
                    if (t - first >= 2_000_000L) break
                    if (!ex.advance()) break
                }
                fps = if (n > 1 && last > first) ((n - 1) * 1_000_000.0 / (last - first)).roundToInt() else 0
            }
            Weight(w, h, fps, mime, chip(f), hdr(f))
        } catch (e: Exception) {
            null
        } finally {
            try {
                ex.release()
            } catch (e: Exception) {
                // Nothing more to do with it.
            }
        }
    }

    /** A format's frame rate, which a file may give as a whole number or not. */
    private fun frameRate(f: MediaFormat): Int {
        if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) return 0
        return try {
            f.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (e: ClassCastException) {
            try {
                f.getFloat(MediaFormat.KEY_FRAME_RATE).roundToInt()
            } catch (e2: Exception) {
                0
            }
        }
    }

    /** HDR10 or HLG, by the colour transfer the clip says it uses. */
    private fun hdr(f: MediaFormat): Boolean =
        try {
            f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                f.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let {
                    it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG
                }
        } catch (e: Exception) {
            false
        }

    /** Whether the decoder this phone would use for [f] is its video chip. */
    private fun chip(f: MediaFormat): Boolean? =
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val name = list.findDecoderForFormat(f)
            when {
                name == null -> null
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    list.codecInfos.firstOrNull { it.name == name }?.isHardwareAccelerated
                // Before Android 10 the phone does not say; the decoders the
                // system itself ships are the processor ones, by name.
                else -> !(name.startsWith("OMX.google.") || name.startsWith("c2.android."))
            }
        } catch (e: Exception) {
            null
        }

    /** "1080p · 30 ภาพ/วิ · H.264". */
    fun describe(w: Weight): String {
        val short = min(w.width, w.height)
        val size = when {
            short <= 0 -> null
            short >= 2160 -> "4K"
            short >= 1440 -> "1440p"
            short >= 1080 -> "1080p"
            short >= 720 -> "720p"
            else -> "${short}p"
        }
        val codec = when (w.mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/av01" -> "AV1"
            else -> null
        }
        return listOfNotNull(size, if (w.fps > 0) "${w.fps} ภาพ/วิ" else null, codec, if (w.hdr) "HDR" else null).joinToString(" · ")
    }

    /** What makes a clip heavier than a phone's screen needs, in a line; null when nothing does. */
    fun heavy(w: Weight): String? {
        val short = min(w.width, w.height)
        return when {
            w.chip == false -> "เครื่องนี้ถอดรหัสคลิปแบบนี้ด้วยหน่วยประมวลผล ไม่ใช่ชิปวิดีโอ กินแบตและทำให้ร้อน ใช้คลิป H.264 แทนดีกว่า"
            short > 1440 -> "4K ละเอียดเกินจอโทรศัพท์ไปมาก เครื่องทำงานหนักเปล่า ๆ ย่อเป็น 1080p ในแกลเลอรีก่อนดีกว่า"
            w.hdr -> "คลิป HDR เครื่องต้องแปลงสีให้จอใหม่ทุกภาพ ทำงานหนักกว่าคลิปปกติ ถ้าแกลเลอรีส่งออกแบบไม่ใช่ HDR ได้จะเบากว่า"
            w.fps > 32 -> "${w.fps} ภาพ/วิ ลื่นสุด แต่เครื่องทำงานหนักกว่า 30 ภาพ/วิ ราวเท่าตัว"
            else -> null
        }
    }

    /**
     * Why the wallpaper is holding still on its frame right now, said in a few
     * words; null while it plays. Battery saver, which asks every app to do
     * less, and a phone that says it has started to run warm - the first step
     * of its own cooling, before anything is slowed that anybody would notice.
     */
    fun resting(ctx: Context): String? {
        val power = ctx.getSystemService(PowerManager::class.java) ?: return null
        if (power.isPowerSaveMode) return "เปิดโหมดประหยัดแบตอยู่ ปิดเมื่อไหร่ก็เล่นต่อเอง"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            power.currentThermalStatus >= PowerManager.THERMAL_STATUS_LIGHT
        ) {
            return "เครื่องอุ่นอยู่ เย็นลงเมื่อไหร่ก็เล่นต่อเอง"
        }
        return null
    }

    fun component(ctx: Context) = ComponentName(ctx, ReupWallpaper::class.java)

    /**
     * Whether this app's wallpaper is the one on the home screen. The system
     * names the wallpaper's owner only to that owner, which is all this asks.
     */
    fun isActive(ctx: Context): Boolean =
        try {
            WallpaperManager.getInstance(ctx).wallpaperInfo?.component == component(ctx)
        } catch (e: Exception) {
            false
        }

    /**
     * The system's pages for setting it, best first: this wallpaper's own
     * preview with its set button, then the list of moving wallpapers, for a
     * phone whose maker left the first one out.
     */
    fun setIntents(ctx: Context): List<Intent> = listOf(
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx)),
        Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER),
    )
}
