package app.reup.ui

import android.animation.ValueAnimator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import app.reup.clipTime
import kotlin.math.max

// ─── WidgetSettings.kt — the widget's panel in Settings ──────────────────────
//
// Four things, in the order somebody setting it up meets them: what is behind
// it, whether light moves across it, whether it shows money, and putting one
// on the home screen. The last is a row rather than a banner because a widget
// is a nice-to-have; nothing here is ever wrong.

/** The panel under the วิดเจ็ต heading. */
@Composable
internal fun WidgetCard(w: WidgetModel, actions: AppActions) {
    val c = Reup.colors
    val look = w.look
    val hasPicture = look.picture != 0L
    CardGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Size.row)
                .clickable(enabled = !w.reading, role = Role.Button, onClick = actions::pickWidgetPicture)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumb(w.thumb)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text = "พื้นหลัง", style = ReupType.body, color = c.text)
                Text(
                    text = when {
                        w.reading -> "กำลังเปิด…"
                        hasPicture -> "รูปที่เลือกไว้ แตะเพื่อเปลี่ยน"
                        else -> "สีของธีม แตะเพื่อเลือกรูปหรือคลิป"
                    },
                    style = ReupType.support,
                    color = c.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (hasPicture) {
                TextAction(text = "เอาออก", color = c.dim, onClick = w::forgetPicture)
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

        Hairline(start = 16.dp)
        TwoWay(
            title = "แสงวิ่ง",
            on = look.motion,
            onSet = w::setMotion,
            line = when {
                !look.motion -> "วิดเจ็ตนิ่ง ไม่มีอะไรขยับนอกจากนาฬิกา"
                // The widget follows the system's switch for animation, so a
                // phone with it off shows a still widget with this set to on.
                // Saying so here is the only place anybody would find out why.
                !ValueAnimator.areAnimatorsEnabled() ->
                    "ตอนนี้ระบบปิดภาพเคลื่อนไหวไว้ แสงเลยไม่วิ่ง ดูที่โหมดประหยัดแบต หรือ ตัวเลือกนักพัฒนา → ระยะเวลาภาพเคลื่อนไหว"
                else -> "แสงวิ่งผ่านแวบเดียว ราวทุกแปดวินาที หน้าจอหลักวาดเอง หยุดตอนจอปิดหรือเปิดแอปอื่น"
            },
        )

        Hairline(start = 16.dp)
        TwoWay(
            title = "ยอดเงินเดือนนี้",
            on = look.money,
            onSet = w::setMoney,
            line = if (look.money) {
                "ขึ้นที่วิดเจ็ตขนาดใหญ่ ใครเห็นหน้าจอก็เห็นยอดนี้ด้วย"
            } else {
                "ปิดไว้ ยอดเงินอยู่แค่ในแอป"
            },
        )

        Hairline(start = 50.dp)
        NavRow(
            icon = R.drawable.ic_plus,
            title = "วางบนหน้าจอหลัก",
            detail = if (w.placed > 0) {
                "วางไว้แล้ว ${w.placed} อัน แตะเพื่อวางเพิ่ม"
            } else {
                "หรือกดค้างที่หน้าจอหลัก เลือกวิดเจ็ต แล้วหา Reup"
            },
            onClick = actions::placeWidget,
        )
    }
}

/** A setting with two answers, said in a line underneath. */
@Composable
private fun TwoWay(title: String, on: Boolean, onSet: (Boolean) -> Unit, line: String) {
    val c = Reup.colors
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Text(text = title, style = ReupType.body, color = c.text)
        Segmented(
            options = listOf("ปิด", "เปิด"),
            selected = if (on) 1 else 0,
            onSelect = { onSet(it == 1) },
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(text = line, style = ReupType.support, color = c.dim, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The picture behind the widget, small and in its shape; the theme's colour when there is none. */
@Composable
private fun Thumb(picture: ImageBitmap?) {
    val c = Reup.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 28.dp)
            .clip(shape)
            .background(c.accentSoft)
            .border(1.dp, c.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = "พื้นหลังวิดเจ็ต",
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

/**
 * One frame out of a clip, for the widget to be drawn on.
 *
 * The slider shows the nearest key frame as it moves - what a phone can keep
 * up with - and ใช้เฟรมนี้ reads the exact one under it. The widget will cut
 * the middle of it to its own shape, which the line under the slider says so
 * that the first one placed is not a surprise.
 */
@Composable
internal fun FrameDialog(pick: FramePick, w: WidgetModel) {
    val c = Reup.colors
    AlertDialog(
        onDismissRequest = { if (!pick.keeping) w.closeFrames() },
        title = { Text("เลือกเฟรมจากคลิป") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.field),
                    contentAlignment = Alignment.Center,
                ) {
                    val preview = pick.preview
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = "เฟรมที่ " + clipTime(pick.atMs),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(text = "กำลังเปิด…", style = ReupType.support, color = c.dim)
                    }
                }
                Slider(
                    value = pick.atMs.toFloat(),
                    onValueChange = { w.seek(it.toLong()) },
                    modifier = Modifier.padding(top = 8.dp),
                    enabled = !pick.keeping && pick.durationMs > 0,
                    valueRange = 0f..max(1f, pick.durationMs.toFloat()),
                    colors = SliderDefaults.colors(
                        thumbColor = c.accent,
                        activeTrackColor = c.accent,
                        inactiveTrackColor = c.line,
                    ),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(text = clipTime(pick.atMs), style = ReupType.support, color = c.text)
                    Spacer(Modifier.weight(1f))
                    Text(text = clipTime(pick.durationMs), style = ReupType.support, color = c.faint)
                }
                Text(
                    text = "วิดเจ็ตจะใช้ตรงกลางภาพ ตัดให้พอดีกับขนาดของมัน",
                    style = ReupType.support,
                    color = c.dim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextAction(
                text = if (pick.keeping) "กำลังเก็บ…" else "ใช้เฟรมนี้",
                color = c.accent,
                onClick = w::useFrame,
                enabled = !pick.keeping && pick.preview != null,
            )
        },
        dismissButton = {
            TextAction(text = "ยกเลิก", color = c.text, onClick = w::closeFrames, enabled = !pick.keeping)
        },
        containerColor = c.raised,
        titleContentColor = c.text,
        textContentColor = c.dim,
    )
}
