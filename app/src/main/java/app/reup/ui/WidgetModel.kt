package app.reup.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.reup.ClipFrames
import app.reup.ReupWidget
import app.reup.WidgetFilm
import app.reup.WidgetLook
import app.reup.core.FilmPace
import app.reup.core.InkMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── WidgetModel.kt — the widget, as the settings screen sets it up ──────────
//
// The widget draws itself from WidgetLook and the database; nothing here is
// needed for it to be right. This is the part with a person in front of it:
// picking a picture, a GIF, or a stretch of a clip; the switches; the tasks
// whose names it keeps to itself; and asking the home screen to take one.

/** A clip open in the frame picker. */
class FramePick(val durationMs: Long) {
    /** Where the slider is: the frame, or where the film starts. */
    var atMs by mutableStateOf(0L)

    /** The frame there, roughly: the nearest key frame, which is what a drag can keep up with. */
    var preview by mutableStateOf<ImageBitmap?>(null)

    /** A film from here rather than this one frame. Where a clip starts, since a clip was picked to move. */
    var moving by mutableStateOf(true)

    /** Twenty-four pages a second for two seconds, rather than twelve for four. */
    var smooth by mutableStateOf(true)

    /** The frame, or the film, is being read and kept. */
    var keeping by mutableStateOf(false)

    /** How far a film is: frames read, of how many. 0 of 0 before it starts. */
    var made by mutableStateOf(0)
    var total by mutableStateOf(0)
}

class WidgetModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
) {
    var look by mutableStateOf(WidgetLook.read(ctx))
        private set

    /** How many are on the home screen. Read again whenever the app comes back. */
    var placed by mutableStateOf(0)
        private set

    /** The kept picture, small, for the settings row. */
    var thumb by mutableStateOf<ImageBitmap?>(null)
        private set

    /** The clip a frame is being picked from, while it is. */
    var frames by mutableStateOf<FramePick?>(null)
        private set

    /** A picture is being read. */
    var reading by mutableStateOf(false)
        private set

    /** A GIF being made into a film: frames done, of how many. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** The list of tasks whose names the widget keeps to itself is open. */
    var hiding by mutableStateOf(false)
        private set

    private var clip: ClipFrames? = null
    private var asks: Channel<Long>? = null

    /** Placed or taken away, a picture kept: all of it can change while the app is away. */
    fun resume() {
        look = WidgetLook.read(ctx)
        placed = ReupWidget.count(ctx)
        loadThumb()
    }

    fun setMotion(on: Boolean) {
        if (on == look.motion) return
        WidgetLook.setMotion(ctx, on)
        look = WidgetLook.read(ctx)
    }

    fun setMoney(on: Boolean) {
        if (on == look.money) return
        WidgetLook.setMoney(ctx, on)
        look = WidgetLook.read(ctx)
    }

    fun forgetPicture() {
        WidgetLook.forget(ctx)
        look = WidgetLook.read(ctx)
        thumb = null
        say("เอาพื้นหลังออกแล้ว วิดเจ็ตใช้สีธีม")
    }

    fun setClear(on: Boolean) {
        if (on == look.clear) return
        WidgetLook.setClear(ctx, on)
        look = WidgetLook.read(ctx)
    }

    /** Which way round the words go over the picture. */
    fun setInk(mode: InkMode) {
        if (mode == look.ink) return
        WidgetLook.setInk(ctx, mode)
        look = WidgetLook.read(ctx)
    }

    /**
     * The moving wallpaper was just set from this app: the widget turns clear
     * so it plays behind it, as the wallpaper row said it would. False when it
     * already was.
     */
    fun wallpaperSet(): Boolean {
        if (look.clear) return false
        setClear(true)
        return true
    }

    fun openHidden() {
        hiding = true
    }

    fun closeHidden() {
        hiding = false
    }

    /** These tasks, and only these, called ส่วนตัว on the widget. */
    fun setHidden(ids: Set<String>) {
        hiding = false
        if (ids == look.hidden) return
        WidgetLook.setHidden(ctx, ids)
        look = WidgetLook.read(ctx)
        say(if (ids.isEmpty()) "วิดเจ็ตบอกชื่อทุกงานแล้ว" else "วิดเจ็ตจะขึ้นว่า ส่วนตัว แทนชื่อ ${ids.size} งาน")
    }

    /**
     * What the photo picker handed back: a picture is kept straight away, a
     * GIF that moves becomes a film straight away, and a clip opens the
     * picker for where to start. Read now, while the picker's permission to
     * read it lasts.
     */
    fun picked(uri: Uri) {
        if (reading) return
        reading = true
        scope.launch {
            try {
                when {
                    WidgetLook.isClip(ctx, uri) -> openClip(uri)
                    WidgetLook.isGif(ctx, uri) -> keepGif(uri)
                    else -> keepPicture(uri)
                }
            } finally {
                reading = false
                progress = null
            }
        }
    }

    private suspend fun keepGif(uri: Uri) {
        val kind = WidgetFilm.fromGif(ctx, uri) { done, total -> progress = done to total }
        when (kind) {
            WidgetFilm.GifKind.FILM -> keptFilm(true)
            // One frame, or not moving: the picture it is.
            WidgetFilm.GifKind.PICTURE -> keepPicture(uri)
            // Too large to read whole, or not quite a GIF: its first frame may
            // still decode the ordinary way.
            WidgetFilm.GifKind.NONE -> keepPicture(uri, done = "GIF นี้ใหญ่เกินหรืออ่านไม่ครบ เลยใช้ภาพแรกเป็นภาพนิ่งแทน")
        }
    }

    /** [done] said instead of the usual words, when it worked. */
    private suspend fun keepPicture(uri: Uri, done: String? = null) {
        val picture = WidgetLook.readPicture(ctx, uri)
        if (picture == null) {
            say("เปิดรูปนี้ไม่ได้")
            return
        }
        kept(runCatching { WidgetLook.keep(ctx, picture) }.isSuccess, done)
    }

    private suspend fun openClip(uri: Uri) {
        val opened = ClipFrames.open(ctx, uri)
        if (opened == null) {
            say("เปิดคลิปนี้ไม่ได้")
            return
        }
        closeFrames()
        clip = opened
        val pick = FramePick(opened.durationMs)
        frames = pick
        // One frame at a time, always the latest asked for: while one is
        // being read, the slider's newer positions replace each other here,
        // and the next read is wherever it stopped.
        val channel = Channel<Long>(Channel.CONFLATED)
        asks = channel
        scope.launch {
            for (ms in channel) {
                val frame = opened.at(ms, PREVIEW_LONGEST, exact = false) ?: continue
                if (frames === pick) pick.preview = frame.asImageBitmap()
            }
        }
        // A quarter in, past the black a clip so often opens on.
        seek(opened.durationMs / 4)
    }

    fun seek(ms: Long) {
        val pick = frames ?: return
        pick.atMs = ms.coerceIn(0L, pick.durationMs)
        asks?.trySend(pick.atMs)
    }

    /** That exact frame kept as the picture, or a film from there, as the picker is set. */
    fun useFrame() {
        val pick = frames ?: return
        val open = clip ?: return
        if (pick.keeping) return
        pick.keeping = true
        scope.launch {
            if (pick.moving) {
                val pace = if (pick.smooth) FilmPace.SMOOTH else FilmPace.LONG
                val ok = WidgetFilm.fromClip(ctx, open, pick.atMs, pace) { done, total ->
                    pick.made = done
                    pick.total = total
                }
                closeFrames()
                keptFilm(ok)
                return@launch
            }
            val frame = open.at(pick.atMs, WidgetLook.KEEP_LONGEST, exact = true)
            if (frame == null) {
                pick.keeping = false
                say("ดึงเฟรมนี้ไม่ได้ ลองเลื่อนไปจุดอื่น")
                return@launch
            }
            val ok = runCatching { WidgetLook.keep(ctx, frame) }.isSuccess
            closeFrames()
            kept(ok)
        }
    }

    fun closeFrames() {
        frames = null
        asks?.close()
        asks = null
        val open = clip ?: return
        clip = null
        scope.launch { open.close() }
    }

    private fun keptFilm(ok: Boolean) {
        if (!ok) {
            say("ทำภาพเคลื่อนไหวจากไฟล์นี้ไม่ได้")
            return
        }
        look = WidgetLook.read(ctx)
        loadThumb()
        say(
            when {
                placed == 0 -> "ตั้งพื้นหลังเคลื่อนไหวแล้ว วางวิดเจ็ตบนหน้าจอหลักได้เลย"
                !look.motion -> "ตั้งพื้นหลังเคลื่อนไหวแล้ว แต่ปิด ให้ขยับ ไว้ วิดเจ็ตเลยยังนิ่ง"
                else -> "ตั้งพื้นหลังเคลื่อนไหวแล้ว"
            },
        )
    }

    private fun kept(ok: Boolean, done: String? = null) {
        if (!ok) {
            say("เก็บรูปไม่ได้")
            return
        }
        look = WidgetLook.read(ctx)
        loadThumb()
        say(done ?: if (placed > 0) "ตั้งพื้นหลังวิดเจ็ตแล้ว" else "ตั้งพื้นหลังแล้ว วางวิดเจ็ตบนหน้าจอหลักได้เลย")
    }

    private fun loadThumb() {
        val version = look.picture
        if (version == 0L) {
            thumb = null
            return
        }
        scope.launch {
            val small = withContext(Dispatchers.IO) {
                try {
                    WidgetLook.pictureFor(ctx, version, THUMB_WIDTH, THUMB_HEIGHT, 0f)
                } catch (e: Exception) {
                    null
                }
            }
            thumb = small?.asImageBitmap()
        }
    }

    private companion object {
        /** Big enough to judge a frame by in a dialog, small enough to keep up with a slider. */
        const val PREVIEW_LONGEST = 720

        /** The settings row's thumbnail: four by two's shape, in pixels. */
        const val THUMB_WIDTH = 192
        const val THUMB_HEIGHT = 96
    }
}
