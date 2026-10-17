package app.reup.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.reup.R
import app.reup.WallClip
import app.reup.clipTime

// ─── WallSettings.kt — the moving wallpaper's panel in Settings ──────────────
//
// Two rows, in the order they are done: the clip, then setting it as the
// wallpaper. The second says when it is already set, because after the first
// time it never needs pressing again - a new clip in the first row is enough.

/** The panel under the วอลเปเปอร์ heading. */
@Composable
internal fun WallpaperCard(m: WallModel, actions: AppActions) {
    val c = Reup.colors
    val clip = m.clip
    CardGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Size.row)
                .clickable(enabled = !m.copying, role = Role.Button, onClick = actions::pickWallpaperClip)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipThumb(m.thumb)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = "คลิปวอลเปเปอร์", style = ReupType.body, color = c.text)
                val weight = m.weight
                Text(
                    text = when {
                        m.copying -> "กำลังคัดลอกคลิป…"
                        clip == null -> "ยังไม่ได้เลือก แตะเพื่อเลือกคลิป"
                        else -> listOfNotNull(
                            if (clip.durationMs > 0) "ยาว " + clipTime(clip.durationMs) else null,
                            weight?.let { WallClip.describe(it) }?.takeIf { it.isNotEmpty() },
                        ).joinToString(" · ").ifEmpty { "คลิปที่เลือกไว้" } + " แตะเพื่อเปลี่ยน"
                    },
                    style = ReupType.support,
                    color = c.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Said in the warning colour, since it is the reason a phone runs warm.
                val heavy = if (clip != null && !m.copying) weight?.let { WallClip.heavy(it) } else null
                if (heavy != null) {
                    Text(
                        text = heavy,
                        style = ReupType.support,
                        color = c.warn,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (clip != null && !m.copying) {
                TextAction(text = "เอาออก", color = c.dim, onClick = m::forget)
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = c.faint,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(20.dp),
                )
            }
        }

        Hairline(start = 50.dp)
        NavRow(
            icon = R.drawable.ic_star,
            title = "ตั้งเป็นวอลเปเปอร์",
            detail = when {
                clip == null -> "เลือกคลิปก่อน"
                m.active && m.resting != null -> "ใช้อยู่ ตอนนี้หยุดพักบนภาพเดิม เพราะ" + m.resting
                m.active -> "ใช้อยู่ เปลี่ยนคลิปที่แถวบนได้เลย ไม่ต้องตั้งใหม่"
                else -> "เปิดหน้าตั้งวอลเปเปอร์ของเครื่อง แล้วกดตั้ง"
            },
            onClick = if (clip != null && !m.active) actions::setWallpaper else null,
        )
    }
}

/** The clip's frame, small and upright like a phone; a star when there is none. */
@Composable
private fun ClipThumb(picture: ImageBitmap?) {
    val c = Reup.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 50.dp)
            .clip(shape)
            .background(c.accentSoft)
            .border(1.dp, c.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = "คลิปวอลเปเปอร์",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_star),
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
