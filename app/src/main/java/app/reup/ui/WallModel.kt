package app.reup.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.reup.WallClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── WallModel.kt — the moving wallpaper, as the settings screen sets it up ──
//
// Two steps, done once each: pick a clip, which is copied in (WallClip), and
// set the wallpaper, which only the system's own page can do. After that,
// picking another clip is the whole of changing it - the wallpaper plays the
// new copy by itself.

class WallModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
    /** The wallpaper was just set from here. True when the widget was made clear for it. */
    private val activated: () -> Boolean,
) {
    var clip by mutableStateOf(WallClip.read(ctx))
        private set

    /** The kept clip's frame, for the row. */
    var thumb by mutableStateOf<ImageBitmap?>(null)
        private set

    /** This app's wallpaper is the one on the home screen. */
    var active by mutableStateOf(false)
        private set

    /** What the kept clip asks of the phone; null until it has been weighed. */
    var weight by mutableStateOf(WallClip.weight(ctx))
        private set

    /** Why the wallpaper is holding still right now (WallClip.resting); null while it plays. */
    var resting by mutableStateOf<String?>(null)
        private set

    /** A picked clip is being copied in. */
    var copying by mutableStateOf(false)
        private set

    /** The system's page was opened from here; whether it was set is known when the app comes back. */
    private var asked = false

    /** Back in the app: the wallpaper may have been set, or changed to another, while it was away. */
    fun resume() {
        clip = WallClip.read(ctx)
        active = WallClip.isActive(ctx)
        resting = WallClip.resting(ctx)
        weight = WallClip.weight(ctx)
        loadThumb()
        // A clip kept before clips were weighed: weighed now, once.
        if (clip != null && weight == null) {
            scope.launch { weight = WallClip.weigh(ctx) }
        }
        if (!asked) return
        asked = false
        if (active) {
            say(if (activated()) "ตั้งวอลเปเปอร์แล้ว วิดเจ็ตเปลี่ยนเป็นพื้นหลังโปร่งให้ด้วย จะได้เห็นคลิปข้างหลัง" else "ตั้งวอลเปเปอร์แล้ว")
        }
    }

    /** The system's page is about to open. */
    fun opening() {
        asked = true
    }

    /** It did not open after all. */
    fun notOpened() {
        asked = false
    }

    /** What the picker handed back: copied in now, while there is permission to read it. */
    fun picked(uri: Uri) {
        if (copying) return
        copying = true
        scope.launch {
            val kept = try {
                WallClip.keep(ctx, uri)
            } finally {
                copying = false
            }
            when (kept) {
                WallClip.Kept.KEPT -> {
                    clip = WallClip.read(ctx)
                    weight = WallClip.weight(ctx)
                    loadThumb()
                    say(if (active) "เปลี่ยนคลิปแล้ว" else "เก็บคลิปแล้ว กด ตั้งเป็นวอลเปเปอร์ ต่อได้เลย")
                }
                WallClip.Kept.TOO_BIG -> say("คลิปใหญ่เกิน ${WallClip.MAX_MB} MB ตัดให้สั้นลงในแกลเลอรีก่อน")
                WallClip.Kept.NOT_A_CLIP -> say("เปิดคลิปนี้ไม่ได้")
                WallClip.Kept.NO_ROOM -> say("ที่ในเครื่องไม่พอเก็บคลิปนี้")
            }
        }
    }

    fun forget() {
        WallClip.forget(ctx)
        clip = null
        thumb = null
        weight = null
        say(if (active) "เอาคลิปออกแล้ว วอลเปเปอร์จะหยุดเล่นจนกว่าจะเลือกคลิปใหม่ หรือเปลี่ยนวอลเปเปอร์ในเครื่อง" else "เอาคลิปออกแล้ว")
    }

    private fun loadThumb() {
        if (clip == null) {
            thumb = null
            return
        }
        scope.launch {
            thumb = withContext(Dispatchers.IO) { WallClip.thumb(ctx) }?.asImageBitmap()
        }
    }
}
