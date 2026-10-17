package app.reup.ui

import android.animation.ValueAnimator
import android.os.Build
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reup.R
import app.reup.core.InkMode
import app.reup.clipTime
import app.reup.core.HomeRow
import kotlin.math.max

// ─── WidgetSettings.kt — the widget's panel in Settings ──────────────────────
//
// Six things, in the order somebody setting it up meets them: what is behind
// it, or nothing at all, whether anything on it moves, whether it shows money,
// which names it keeps to itself, and putting one on the home screen. The last
// is a row rather than a banner because a widget is a nice-to-have; nothing
// here is ever wrong.

/** The panel under the วิดเจ็ต heading. */
@Composable
internal fun WidgetCard(w: WidgetModel, actions: AppActions) {
    val c = Reup.colors
    val look = w.look
    val hasPicture = look.picture != 0L
    val hasFilm = look.film != 0L
    val making = w.progress
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
                        making != null -> "กำลังทำภาพเคลื่อนไหว ${making.first}/${making.second}"
                        w.reading -> "กำลังเปิด…"
                        look.clear && (hasFilm || hasPicture) -> "เก็บไว้ แต่ไม่ขึ้นตอนพื้นหลังโปร่ง"
                        hasFilm -> "ภาพเคลื่อนไหว " + perSecond(look.filmTick) + " ภาพ/วิ แตะเพื่อเปลี่ยน"
                        hasPicture -> "รูปที่เลือกไว้ แตะเพื่อเปลี่ยน"
                        else -> "สีของธีม แตะเพื่อเลือกรูป คลิป หรือ GIF"
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
            title = "พื้นหลังโปร่ง",
            on = look.clear,
            onSet = w::setClear,
            line = if (look.clear) {
                "ไม่มีพื้น เห็นวอลเปเปอร์ข้างหลัง มีเงาบาง ๆ ให้อ่านตัวหนังสือออก"
            } else {
                "ใช้คู่กับวอลเปเปอร์คลิปด้านล่าง วิดเจ็ตจะเหมือนขยับไปกับคลิป"
            },
        )

        // Only where there is something behind the words for them to be read
        // against: on the theme's own card the colours are the theme's.
        if (hasPicture || look.clear) {
            Hairline(start = 16.dp)
            InkChoice(look.ink, w::setInk, clear = look.clear)
        }

        Hairline(start = 16.dp)
        TwoWay(
            title = "ให้ขยับ",
            on = look.motion,
            onSet = w::setMotion,
            line = when {
                look.clear -> "ตอนพื้นหลังโปร่ง ตัววิดเจ็ตไม่มีอะไรขยับเอง ที่ขยับคือวอลเปเปอร์ข้างหลัง"
                !look.motion && hasFilm -> "วิดเจ็ตนิ่ง พื้นหลังหยุดอยู่ที่ภาพแรก"
                !look.motion -> "วิดเจ็ตนิ่ง ไม่มีอะไรขยับนอกจากนาฬิกา"
                // The widget follows the system's switch for animation, so a
                // phone with it off shows a still widget with this set to on.
                // Saying so here is the only place anybody would find out why.
                !ValueAnimator.areAnimatorsEnabled() ->
                    "ตอนนี้ระบบปิดภาพเคลื่อนไหวไว้ วิดเจ็ตเลยนิ่ง ดูที่โหมดประหยัดแบต หรือ ตัวเลือกนักพัฒนา → ระยะเวลาภาพเคลื่อนไหว"
                hasFilm && Build.VERSION.SDK_INT < Build.VERSION_CODES.S ->
                    "Android รุ่นนี้เล่นภาพเคลื่อนไหวในวิดเจ็ตไม่ได้ พื้นหลังเลยเป็นภาพแรก"
                hasFilm -> "พื้นหลังเล่นวน " + perSecond(look.filmTick) + " ภาพต่อวินาที หน้าจอหลักเล่นเอง หยุดตอนจอปิดหรือเปิดแอปอื่น"
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
            icon = R.drawable.ic_user,
            title = "ชื่องานบนวิดเจ็ต",
            detail = if (look.hidden.isEmpty()) {
                "บอกชื่อทุกงาน แตะเพื่อเลือกงานที่ไม่อยากให้ใครเห็นชื่อ"
            } else {
                "${look.hidden.size} งานขึ้นว่า ส่วนตัว แทนชื่อ"
            },
            onClick = w::openHidden,
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

/**
 * White words or dark ones over the picture, or whichever it needs less veil
 * for (core/WidgetInk.kt). Said underneath, since "auto" means nothing until
 * somebody says what it decides by.
 */
@Composable
private fun InkChoice(mode: InkMode, onSet: (InkMode) -> Unit, clear: Boolean) {
    val c = Reup.colors
    val modes = listOf(InkMode.AUTO, InkMode.LIGHT, InkMode.DARK)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Text(text = "สีตัวหนังสือ", style = ReupType.body, color = c.text)
        Segmented(
            options = listOf("อัตโนมัติ", "ตัวขาว", "ตัวเข้ม"),
            selected = modes.indexOf(mode),
            onSelect = { onSet(modes[it]) },
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = when (mode) {
                InkMode.AUTO -> if (clear) {
                    "ตามที่วอลเปเปอร์บอกว่าเหมาะกับตัวสีไหน มีม่านบาง ๆ ใต้ตัวหนังสือ"
                } else {
                    "ดูความสว่างของรูปแล้วเลือกเอง ปกติเป็นตัวขาวบนม่านมืด รูปที่สว่างมากจะเป็นตัวเข้มบนม่านขาว ม่านหนาเท่าที่ต้องใช้ให้อ่านออกทุกจุด"
                }
                InkMode.LIGHT -> "ตัวขาวบนม่านมืด ม่านหนาตามความสว่างของรูป"
                InkMode.DARK -> "ตัวเข้มบนม่านขาว ม่านหนาตามความมืดของรูป"
            },
            style = ReupType.support,
            color = c.dim,
            modifier = Modifier.padding(top = 8.dp),
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
 * A stretch of a clip to move, or one frame of it to stand still, for the
 * widget to be drawn on.
 *
 * The slider shows the nearest key frame as it moves - what a phone can keep
 * up with. For a film it is where the three seconds start; for a picture,
 * ใช้เฟรมนี้ reads the exact frame under it. Either way the widget cuts the
 * middle to its own shape, which the line under the slider says, so that the
 * first one placed is not a surprise.
 */
@Composable
internal fun FrameDialog(pick: FramePick, w: WidgetModel) {
    val c = Reup.colors
    AlertDialog(
        onDismissRequest = { if (!pick.keeping) w.closeFrames() },
        title = { Text("เลือกจากคลิป") },
        text = {
            Column {
                Segmented(
                    options = listOf("เคลื่อนไหว", "ภาพนิ่ง"),
                    selected = if (pick.moving) 0 else 1,
                    onSelect = { if (!pick.keeping) pick.moving = it == 0 },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // How the pictures a widget can hold are spent: on smoothness,
                // or on length. The same number either way.
                if (pick.moving) {
                    Segmented(
                        options = listOf("ลื่น", "ยาว"),
                        selected = if (pick.smooth) 0 else 1,
                        onSelect = { if (!pick.keeping) pick.smooth = it == 0 },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
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
                    text = if (pick.moving && pick.smooth) {
                        "เริ่มตรงนี้ 24 ภาพต่อวินาที ยาวราว 2 วินาที แล้ววนต่อกันแบบค่อย ๆ จาง วิดเจ็ตใช้ตรงกลางภาพ"
                    } else if (pick.moving) {
                        "เริ่มตรงนี้ 12 ภาพต่อวินาที ยาวราว 4 วินาที แล้ววนต่อกันแบบค่อย ๆ จาง วิดเจ็ตใช้ตรงกลางภาพ"
                    } else {
                        "วิดเจ็ตจะใช้ตรงกลางภาพ ตัดให้พอดีกับขนาดของมัน"
                    },
                    style = ReupType.support,
                    color = c.dim,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextAction(
                text = when {
                    pick.keeping && pick.moving -> if (pick.total > 0) "กำลังทำ ${pick.made}/${pick.total}" else "กำลังทำ…"
                    pick.keeping -> "กำลังเก็บ…"
                    pick.moving -> "ใช้ช่วงนี้"
                    else -> "ใช้เฟรมนี้"
                },
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

/**
 * The tasks the widget calls ส่วนตัว. A ticked one keeps its place and its time
 * on the home screen, not its name; the app itself still says it. Only the
 * tasks there are now are listed and kept, so one deleted since drops out.
 */
@Composable
internal fun HiddenDialog(rows: List<HomeRow>, w: WidgetModel) {
    val c = Reup.colors
    var picked by remember(rows) { mutableStateOf(rows.map { it.id }.filter { it in w.look.hidden }.toSet()) }
    AlertDialog(
        onDismissRequest = w::closeHidden,
        title = { Text("ไม่บอกชื่อบนวิดเจ็ต") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item(key = "why") {
                    Text(
                        text = "งานที่เลือกจะขึ้นบนวิดเจ็ตว่า ส่วนตัว เวลายังบอกเหมือนเดิม ในแอปยังเห็นชื่อปกติ",
                        style = ReupType.support,
                        color = c.dim,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (rows.isEmpty()) {
                    item(key = "none") { Text("ยังไม่มีงาน", style = ReupType.support, color = c.dim) }
                }
                items(rows, key = { it.id }) { r ->
                    val on = r.id in picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Size.touch)
                            .clickable(role = Role.Checkbox) { picked = if (on) picked - r.id else picked + r.id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (on) DoneMark() else RingMark(color = c.dim)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = r.name,
                            style = ReupType.body,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { TextAction(text = "ใช้", color = c.accent, onClick = { w.setHidden(picked) }) },
        dismissButton = { TextAction(text = "ยกเลิก", color = c.text, onClick = w::closeHidden) },
        containerColor = c.raised,
        titleContentColor = c.text,
        textContentColor = c.dim,
    )
}

/** Pages a second for a tick of [tickMs]: 24, 12. */
private fun perSecond(tickMs: Long): Int = if (tickMs <= 0) 12 else (1000.0 / tickMs).let { kotlin.math.round(it).toInt() }
