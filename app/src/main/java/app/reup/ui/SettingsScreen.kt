package app.reup.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.reup.BuildConfig
import app.reup.R
import app.reup.Repo
import app.reup.SlipWatch
import app.reup.core.DeviceNotify
import app.reup.core.Palette
import app.reup.core.SetupWarning
import app.reup.core.THEMES
import app.reup.core.brandPowerFor
import app.reup.core.clockOf
import app.reup.core.dayPhrase
import app.reup.core.paletteOf
import app.reup.sync.SyncBackend
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

// ─── SettingsScreen.kt — how this phone is set up ────────────────────────────
//
// WHAT IS SAID ONLY WHILE IT IS TRUE
//
// Nothing about permissions appears while nothing is wrong. A checklist that
// reads all-clear every day is a checklist nobody reads on the day it changes.
// When something is wrong it is the first thing on this screen, with the
// button that fixes it, and the tab carries a dot until it is fixed.
//
// AND THE DIAGNOSTICS ARE STILL HERE
//
// One row, at the bottom of the first panel. They are what the eight o'clock
// reminder that never arrived was chased down with, and dropping them to make
// the screen prettier would be trading a real tool for a nice photograph.

@Composable
fun SettingsScreen(model: HomeModel, actions: AppActions, openDiagnostics: () -> Unit) {
    val c = Reup.colors
    val watch = model.slipWatch
    val income = model.incomeWatch
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") { ScreenHeader(title = "ตั้งค่า", over = null) }

        for ((i, w) in model.warnings.withIndex()) {
            item(key = "warn:" + w.name) {
                WarningBanner(
                    warning = w,
                    actions = actions,
                    onDeviceOn = { model.chooseDeviceNotify(DeviceNotify.ON) },
                    modifier = Modifier.padding(top = if (i == 0) 16.dp else 12.dp),
                )
            }
        }

        item(key = "places") {
            CardGroup(Modifier.padding(top = 16.dp)) {
                NavRow(
                    icon = R.drawable.ic_cloud,
                    title = "ซิงก์กับคอม",
                    detail = syncDetail(model.backend, model.lastSync, model.zone),
                    onClick = actions::openSync,
                )
                Hairline(start = 50.dp)
                NavRow(
                    icon = R.drawable.ic_scan,
                    title = "อ่านสลิป",
                    detail = "ดึงยอดเงินกับวันที่จากรูปสลิป",
                    onClick = actions::openSlip,
                )
                Hairline(start = 50.dp)
                NavRow(
                    icon = R.drawable.ic_activity,
                    title = "สถานะระบบ",
                    detail = "คิวเตือนและสิทธิ์ สำหรับไล่หาปัญหา",
                    onClick = openDiagnostics,
                )
            }
        }

        // A question about devices, so each device answers it and the answer
        // never syncs: turning this phone off does not turn the computer off.
        // Quiet hours are about the person, so they do sync, and say so.
        item(key = "notify-label") { SectionLabel("การเตือนบนเครื่องนี้") }
        item(key = "notify") {
            CardGroup {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                    val modes = listOf(DeviceNotify.ON, DeviceNotify.SILENT, DeviceNotify.OFF)
                    Segmented(
                        options = listOf("ปกติ", "เงียบ", "ปิด"),
                        selected = modes.indexOf(model.deviceNotify),
                        onSelect = { model.chooseDeviceNotify(modes[it]) },
                    )
                    Text(
                        text = when (model.deviceNotify) {
                            DeviceNotify.ON -> "สั่นตามปกติ ในช่วงเงียบขึ้นแบบไม่สั่น"
                            DeviceNotify.SILENT -> "ขึ้นในแถบแจ้งเตือนทุกครั้ง แต่ไม่สั่นเลย"
                            DeviceNotify.OFF -> "ไม่เตือนบนเครื่องนี้ งานยังซิงก์ คอมยังเตือนตามปกติ"
                        },
                        style = ReupType.support,
                        color = c.dim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Hairline(start = 50.dp)
                NavRow(
                    icon = R.drawable.ic_moon,
                    title = "ช่วงเงียบ",
                    detail = "ใช้ร่วมกับคอม ตั้งได้ที่คอม",
                    onClick = null,
                    value = model.quiet?.let { it.start + "–" + it.end } ?: "ปิดอยู่",
                )
            }
        }

        // A question about this phone's gallery, so this phone answers it and
        // the answer never syncs. Said only while it is wrong, like the
        // warnings at the top: on, and not allowed to see what it watches.
        item(key = "slips-label") { SectionLabel("สลิปเข้า Reup เอง") }
        if (watch.on && watch.access != SlipWatch.Access.ALL) {
            item(key = "slips-access") {
                Banner(
                    text = if (watch.access == SlipWatch.Access.SELECTED) {
                        "ให้ดูรูปไว้แค่บางรูป สลิปใหม่จะไม่เข้ามา ต้องเป็น \"อนุญาตทั้งหมด\""
                    } else {
                        "ยังไม่ได้ให้สิทธิ์ดูรูป สลิปใหม่จะไม่เข้ามา"
                    },
                    tone = Tone.WARN,
                    action = "แก้",
                    onAction = actions::openAppSettings,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        item(key = "slips") {
            CardGroup {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                    Segmented(
                        options = listOf("ปิด", "เปิด"),
                        selected = if (watch.on) 1 else 0,
                        onSelect = { i -> if (model.setSlipWatch(i == 1)) actions.requestPhotoAccess() },
                    )
                    Text(
                        text = slipWatchLine(watch, model),
                        style = ReupType.support,
                        color = c.dim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Hairline(start = 50.dp)
                NavRow(
                    icon = R.drawable.ic_scan,
                    title = "อัลบั้มที่ดู",
                    detail = if (watch.albums.isEmpty()) "ยังไม่ได้เลือก" else watch.albums.joinToString(", ") { it.name },
                    onClick = model::loadAlbums,
                )
            }
        }

        // The same shape as slips: this phone's question, never synced, and
        // what is missing said only while it is missing. Reading notifications
        // is allowed in the phone's settings and nowhere else, so the banner's
        // button goes there.
        item(key = "income-label") { SectionLabel("เงินเข้า Reup เอง") }
        if (income.on && !income.access) {
            item(key = "income-access") {
                Banner(
                    text = "ยังไม่ได้ให้ Reup อ่านแจ้งเตือน เงินเข้าจะยังไม่มา",
                    tone = Tone.WARN,
                    action = "อนุญาต",
                    onAction = actions::openNotificationAccess,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
        item(key = "income") {
            CardGroup {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
                    Segmented(
                        options = listOf("ปิด", "เปิด"),
                        selected = if (income.on) 1 else 0,
                        onSelect = { i -> if (model.setIncomeWatch(i == 1)) actions.openNotificationAccess() },
                    )
                    Text(
                        text = incomeWatchLine(income),
                        style = ReupType.support,
                        color = c.dim,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // The widget is drawn in the theme below, so it sits above it.
        item(key = "widget-label") { SectionLabel("วิดเจ็ต") }
        item(key = "widget") { WidgetCard(model.widget, actions) }

        item(key = "theme-label") { SectionLabel("ธีม · " + paletteOf(model.themeId).name) }
        item(key = "themes") {
            CardGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (p in THEMES) {
                        Swatch(p, on = p.id == model.themeId, onPick = { model.setTheme(p.id) })
                    }
                }
            }
        }

        item(key = "version") {
            Text(
                text = "Reup " + BuildConfig.VERSION_NAME,
                style = ReupType.support,
                color = c.faint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp),
            )
        }
    }

    val frames = model.widget.frames
    if (frames != null) FrameDialog(frames, model.widget)

    val choices = model.albumChoices
    if (choices != null) {
        AlbumDialog(
            albums = choices,
            chosen = watch.albums.map { it.id }.toSet(),
            onDone = model::chooseAlbums,
            onCancel = model::closeAlbums,
        )
    }
}

/** What the slip switch is doing, in one line. */
private fun slipWatchLine(w: SlipWatchView, model: HomeModel): String {
    if (!w.on) return "สลิปจากแอปธนาคารเข้ามาเป็นรายการรอบันทึกเอง ไม่ต้องไปเลือกรูป อ่านในเครื่อง ไม่ส่งรูปไปไหน"
    if (w.albums.isEmpty()) return "เลือกอัลบั้มที่แอปธนาคารเซฟสลิปไว้ก่อน"
    val last = w.last ?: return "รอสลิปใบต่อไป อ่านเฉพาะรูปใหม่ในอัลบั้มที่เลือก"
    val at = Instant.fromEpochMilliseconds(last.second)
    return "ล่าสุด " + dayPhrase(at, model.now, model.zone) + " " + clockOf(at, model.zone) + " · " + last.first
}

/** What the money-in switch is doing, in one line. */
private fun incomeWatchLine(w: IncomeWatchView): String = when {
    !w.on -> "อ่านแจ้งเตือนเงินเข้าของ K PLUS กับ Krungthai NEXT มาเป็นรายการรอบันทึก ไม่อ่านแอปอื่น อ่านในเครื่อง ไม่ส่งไปไหน"
    !w.access -> "รอสิทธิ์อ่านแจ้งเตือนจากหน้าตั้งค่าของเครื่อง"
    w.last != null -> "ล่าสุด " + w.last
    else -> "รอเงินเข้าครั้งต่อไป เงินที่โอนระหว่างบัญชีตัวเองจะไม่ขึ้น"
}

/**
 * The albums, with the ones that look like a bank's already ticked the first
 * time — K PLUS saves into one called K PLUS, Krungthai NEXT into its own.
 *
 * Ticked with the same marks the task list uses, so a tick means the same
 * thing everywhere in the app.
 */
@Composable
private fun AlbumDialog(
    albums: List<SlipWatch.Album>,
    chosen: Set<String>,
    onDone: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    val c = Reup.colors
    var picked by remember(albums) {
        mutableStateOf(if (chosen.isEmpty()) albums.filter { it.suggested }.map { it.id }.toSet() else chosen)
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("อัลบั้มที่ดู") },
        text = {
            if (albums.isEmpty()) {
                Text("ไม่เจออัลบั้มรูปในเครื่อง", style = ReupType.support, color = c.dim)
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    item(key = "why") {
                        Text(
                            text = "เลือกอัลบั้มที่แอปธนาคารเซฟสลิปไว้ Reup จะอ่านเฉพาะรูปใหม่ในอัลบั้มพวกนี้",
                            style = ReupType.support,
                            color = c.dim,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(albums, key = { it.id }) { a ->
                        val on = a.id in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Size.touch)
                                .clickable(role = Role.Checkbox) { picked = if (on) picked - a.id else picked + a.id }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (on) DoneMark() else RingMark(color = c.dim)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = a.name,
                                style = ReupType.body,
                                color = c.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(text = a.count.toString(), style = ReupType.support, color = c.faint)
                        }
                    }
                }
            }
        },
        confirmButton = { TextAction(text = "ใช้", color = c.accent, onClick = { onDone(picked) }) },
        dismissButton = { TextAction(text = "ยกเลิก", color = c.text, onClick = onCancel) },
        containerColor = c.raised,
        titleContentColor = c.text,
        textContentColor = c.dim,
    )
}

/**
 * One theme, drawn in its own colour. The picked one has a ring with a gap,
 * so it reads as chosen on every theme, including the grey one.
 */
@Composable
private fun Swatch(p: Palette, on: Boolean, onPick: () -> Unit) {
    val c = Reup.colors
    Box(
        modifier = Modifier
            .size(Size.touch)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onPick)
            .semantics {
                contentDescription = p.name
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .then(if (on) Modifier.border(2.dp, c.text, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(p.primary)),
            )
        }
    }
}

/** "Google Drive · ซิงก์แล้ว 12:20", or what is missing. */
private fun syncDetail(backend: SyncBackend, last: Repo.QuietSync?, zone: TimeZone): String {
    val where = when (backend) {
        SyncBackend.Off -> return "ยังไม่ได้ตั้งค่า"
        SyncBackend.Drive -> "Google Drive"
        is SyncBackend.WebDav -> "เซิร์ฟเวอร์ในบ้าน"
    }
    val state = when {
        last == null -> null
        last.notSetUp -> "ยังตั้งไม่ครบ"
        last.error != null -> "ซิงก์ไม่ได้"
        else -> "ซิงก์แล้ว " + clockOf(Instant.fromEpochMilliseconds(last.atMillis), zone)
    }
    return if (state == null) where else "$where · $state"
}

/** The line for one thing that will stop a reminder arriving, and its fix. */
@Composable
private fun WarningBanner(
    warning: SetupWarning,
    actions: AppActions,
    onDeviceOn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (warning) {
        SetupWarning.NOTIFICATIONS_OFF -> Banner(
            text = "แอปโพสต์แจ้งเตือนไม่ได้ ปิดอยู่ในตั้งค่าระบบ",
            tone = Tone.DANGER,
            action = "เปิด",
            onAction = actions::openNotificationSettings,
            modifier = modifier,
        )
        SetupWarning.CHANNEL_OFF -> Banner(
            text = "ช่องรอบรีเซ็ตถูกปิดเสียงไว้ การเตือนจะไม่ขึ้น",
            tone = Tone.DANGER,
            action = "เปิด",
            onAction = actions::openNotificationSettings,
            modifier = modifier,
        )
        // Not a warning for its own sake: a battery manager that stops waking
        // the app stops its alarms with it, and nothing tells the person.
        // Named for the phone it is actually on.
        SetupWarning.BATTERY_MANAGED -> Banner(
            text = batteryWarning(),
            tone = Tone.WARN,
            action = "แก้",
            onAction = actions::openBatterySettings,
            modifier = modifier,
        )
        SetupWarning.HIBERNATION -> Banner(
            text = "Android อาจพักแอปนี้ถ้าไม่ได้เปิดนาน แล้วการเตือนจะหยุดไปเฉย ๆ",
            tone = Tone.WARN,
            action = "ปิดการพัก",
            onAction = actions::openHibernationSettings,
            modifier = modifier,
        )
        SetupWarning.DEVICE_OFF -> Banner(
            text = "ปิดการเตือนบนเครื่องนี้อยู่ งานยังซิงก์ตามปกติ",
            tone = Tone.WARN,
            action = "เปิด",
            onAction = onDeviceOn,
            modifier = modifier,
        )
        SetupWarning.INEXACT_ALARMS -> Banner(
            text = "การเตือนอาจสายได้ถึงครึ่งชั่วโมง",
            tone = Tone.WARN,
            action = "อนุญาต",
            onAction = actions::openExactAlarmSettings,
            modifier = modifier,
        )
    }
}

/** The battery line, naming the phone it is on rather than assuming one. */
private fun batteryWarning(): String {
    val brand = brandPowerFor(Build.MANUFACTURER ?: "", Build.BRAND ?: "")?.brand
    return if (brand != null) {
        "ระบบแบตของ $brand อาจหยุดปลุกแอปนี้ แล้วการเตือนจะเงียบโดยไม่มีอะไรบอก"
    } else {
        "ระบบอาจหยุดปลุกแอปนี้เพื่อประหยัดแบต แล้วการเตือนจะเงียบโดยไม่มีอะไรบอก"
    }
}
