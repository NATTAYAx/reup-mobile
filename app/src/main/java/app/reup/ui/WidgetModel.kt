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
import app.reup.WidgetLook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── WidgetModel.kt — the widget, as the settings screen sets it up ──────────
//
// The widget draws itself from WidgetLook and the database; nothing here is
// needed for it to be right. This is the part with a person in front of it:
// picking a picture, picking a frame out of a clip, the two switches, and
// asking the home screen to take one.

/** A clip open in the frame picker. */
class FramePick(val durationMs: Long) {
    /** Where the slider is. */
    var atMs by mutableStateOf(0L)

    /** The frame there, roughly: the nearest key frame, which is what a drag can keep up with. */
    var preview by mutableStateOf<ImageBitmap?>(null)

    /** The exact frame is being read and kept. */
    var keeping by mutableStateOf(false)
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

    /**
     * What the photo picker handed back: a picture is kept straight away, a
     * clip opens the frame picker. Read now, while the picker's permission to
     * read it lasts.
     */
    fun picked(uri: Uri) {
        if (reading) return
        reading = true
        scope.launch {
            try {
                if (WidgetLook.isClip(ctx, uri)) openClip(uri) else keepPicture(uri)
            } finally {
                reading = false
            }
        }
    }

    private suspend fun keepPicture(uri: Uri) {
        val picture = WidgetLook.readPicture(ctx, uri)
        if (picture == null) {
            say("เปิดรูปนี้ไม่ได้")
            return
        }
        kept(runCatching { WidgetLook.keep(ctx, picture) }.isSuccess)
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

    /** That exact frame, at full size, kept as the picture. */
    fun useFrame() {
        val pick = frames ?: return
        val open = clip ?: return
        if (pick.keeping) return
        pick.keeping = true
        scope.launch {
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

    private fun kept(ok: Boolean) {
        if (!ok) {
            say("เก็บรูปไม่ได้")
            return
        }
        look = WidgetLook.read(ctx)
        loadThumb()
        say(if (placed > 0) "ตั้งพื้นหลังวิดเจ็ตแล้ว" else "ตั้งพื้นหลังแล้ว วางวิดเจ็ตบนหน้าจอหลักได้เลย")
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
