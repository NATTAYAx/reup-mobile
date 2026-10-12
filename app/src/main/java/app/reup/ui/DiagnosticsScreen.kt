package app.reup.ui

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.reup.DeviceNotifyPrefs
import app.reup.Notifications
import app.reup.Scheduler
import app.reup.core.applyDeviceNotify
import app.reup.core.brandPowerFor
import app.reup.core.clockOf
import app.reup.core.remaining
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── DiagnosticsScreen.kt — everything the old screen said, kept whole ───────
//
// This is the readout that found the alarm firing at ten to midnight, and it
// stays exact: seconds in the countdown, the timezone spelled out, every
// permission listed whether it passes or not. The tabs round to the nearest
// minute because that is what a person reads; this does not, because it is what
// a bug is chased with. Selectable, so it can be pasted into a message as is.

@Composable
fun DiagnosticsScreen(model: HomeModel, actions: AppActions, onBack: () -> Unit) {
    val c = Reup.colors
    val ctx = LocalContext.current
    // Read again every second, like the panel it replaces: the countdowns in it
    // are the point.
    val report = diagnosticsReport(ctx, model, model.now, model.zone)
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "สถานะระบบ", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardGroup(Modifier.padding(top = 8.dp)) {
                SelectionContainer {
                    Text(
                        text = report,
                        style = ReupType.mono,
                        color = c.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = Size.gutter),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(
                    text = "ทดสอบ: แจ้งเตือนในอีก 60 วินาที",
                    onClick = {
                        actions.fireTest()
                        model.say("ตั้งแล้ว ปิดแอปแล้วล็อกจอรอได้เลย")
                    },
                )
                SecondaryButton(text = "เปิดหน้าตั้งค่าแอปในระบบ", onClick = actions::openAppSettings)
            }
        }
    }
}

/**
 * The readout, as text.
 *
 * Printed from the same values the scheduler used, so the queue in it and the
 * line about quiet hours above it cannot disagree.
 */
fun diagnosticsReport(ctx: Context, model: HomeModel, now: Instant, zone: TimeZone): String {
    val am = ctx.getSystemService(AlarmManager::class.java)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val pm = ctx.getSystemService(PowerManager::class.java)
    val channel = nm.getNotificationChannel(Notifications.CHANNEL_RESETS)

    val notificationsOn = nm.areNotificationsEnabled()
    val channelOn = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    val batteryExempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val hibernationExempt =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.packageManager.isAutoRevokeWhitelisted else true
    val systemAlarmSet = am.nextAlarmClock != null
    val mode = DeviceNotifyPrefs.get(ctx)
    val quiet = model.quiet

    val sb = StringBuilder()
    sb.append("theme    ").append(model.themeId).append("\n")
    sb.append("เครื่องนี้  ").append(mode.id).append("\n")
    sb.append("zone     ").append(zone.toString()).append("\n")
    sb.append("now      ").append(stamp(now, zone)).append("\n")
    // "ปิดอยู่" is a real state: it means the desktop says off, not that this
    // phone has not heard.
    sb.append("รอบเงียบ  ")
        .append(quiet?.let { it.start + " ถึง " + it.end } ?: "ปิดอยู่")
        .append("\n\n")

    sb.append("- สถานะที่มีผลกับการเตือน -\n")
    sb.append(mark(notificationsOn)).append(" อนุญาตแจ้งเตือน\n")
    sb.append(mark(channelOn)).append(" ช่องรอบรีเซ็ตเปิดอยู่\n")
    // "Android's layer" because on several brands it is not the only one, and a
    // green line here used to read as "nothing will stop this".
    sb.append(mark(batteryExempt)).append(" ยกเว้นการประหยัดแบต (ชั้นของ Android)\n")
    sb.append(mark(hibernationExempt)).append(" ไม่ถูกพักเมื่อไม่ได้ใช้\n")
    sb.append(mark(Scheduler.exactAllowed(ctx))).append(" เตือนตรงเวลา\n\n")

    // The layer this app cannot see, said as such rather than marked.
    val power = brandPowerFor(Build.MANUFACTURER ?: "", Build.BRAND ?: "")
    if (power != null) {
        sb.append("- ตัวจัดการแบตของ ").append(power.brand).append(" -\n")
        sb.append("แอปมองชั้นนี้ไม่เห็น เลยบอกไม่ได้ว่าตั้งไว้แล้วรึยัง\n")
        sb.append("ค้นในตั้งค่า:\n")
        for (step in power.steps) sb.append("· ").append(step).append("\n")
        sb.append("เส้นทางเมนูล่าสุด ").append(power.guide).append("\n\n")
    } else {
        sb.append("บางยี่ห้อมีตัวจัดการแบตของตัวเองอีกชั้น ดู dontkillmyapp.com\n\n")
    }

    val queued = applyDeviceNotify(model.queue, mode)
    sb.append("- คิวที่ส่งให้ระบบแล้ว (").append(queued.size).append(") -\n")
    if (queued.isEmpty()) {
        sb.append("(ว่าง)\n")
    } else {
        for (alarm in queued) {
            val label = model.infos[alarm.taskId]?.name ?: alarm.taskId
            sb.append(stamp(alarm.fireAt, zone)).append("  ").append(label).append("\n")
            sb.append("   อีก ").append(remaining(now, alarm.fireAt))
            if (alarm.silent) sb.append("  (ไม่มีเสียง)")
            sb.append("\n")
        }
    }
    sb.append("\n")

    sb.append(if (Scheduler.exactAllowed(ctx)) "โหมด exact\n" else "โหมด inexact\n")
    sb.append("system alarm clock: ").append(if (systemAlarmSet) "set" else "none").append("\n")
    sb.append(model.lastQuiet)
    return sb.toString()
}

private fun mark(ok: Boolean): String = if (ok) "[ผ่าน]" else "[ยังไม่ผ่าน]"

private fun stamp(at: Instant, zone: TimeZone): String {
    val t = at.toLocalDateTime(zone)
    return pad2(t.dayOfMonth) + "/" + pad2(t.monthNumber) + " " + clockOf(at, zone) + ":" + pad2(t.second)
}

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
