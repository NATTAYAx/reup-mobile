package app.reup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.reup.R
import app.reup.core.Calm
import app.reup.core.HomeRow
import app.reup.core.SetupWarning
import app.reup.core.categoryName
import app.reup.core.clockOf
import app.reup.core.headerDate
import app.reup.core.noteTilt
import app.reup.core.heroLine
import app.reup.core.rowClock
import app.reup.core.rowLine
import app.reup.core.todayFace
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

// ─── TodayScreen.kt — what to do now, and what comes after ───────────────────
//
// The order on this screen is the order of the questions someone has when they
// open it: what is next, what else is today, what is coming. The first gets a
// card with a button on it; the rest are rows, and a tap on a row ticks it, as
// it always has. Holding a row opens it for editing - deliberately the slower
// gesture, because opening a form by accident while reaching to tick something
// off is worse than editing being one second away.

@Composable
fun TodayScreen(model: HomeModel, actions: AppActions, fabLift: Dp) {
    val now = model.now
    val zone = model.zone
    val rows = model.rows()
    val face = todayFace(rows, now, zone)
    val blocking = model.warnings.firstOrNull { it == SetupWarning.NOTIFICATIONS_OFF || it == SetupWarning.CHANNEL_OFF }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = UnderFab,
        ) {
            item(key = "head") {
                ScreenHeader(
                    title = "วันนี้",
                    over = headerDate(now, zone),
                    trailing = { SyncPill(model, zone, onClick = actions::openSync) },
                )
            }

            // The notes first: they are the one thing on this screen somebody
            // put there themselves so as not to forget it.
            item(key = "notes") {
                NoteBoard(model, actions)
            }

            // Only the two that mean nothing will arrive at all. The rest are in
            // Settings, with a dot on its tab; this screen is for the day.
            if (blocking != null) {
                item(key = "blocking") {
                    Banner(
                        text = if (blocking == SetupWarning.NOTIFICATIONS_OFF) {
                            "แอปโพสต์แจ้งเตือนไม่ได้ ปิดอยู่ในตั้งค่าระบบ"
                        } else {
                            "ช่องรอบรีเซ็ตถูกปิดเสียงไว้ การเตือนจะไม่ขึ้น"
                        },
                        tone = Tone.DANGER,
                        action = "เปิด",
                        onAction = actions::openNotificationSettings,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            val error = model.loadError
            when {
                error != null -> item(key = "error") {
                    InfoCard(
                        title = "อ่านฐานข้อมูลบนเครื่องนี้ไม่ได้",
                        body = error,
                        warn = true,
                    )
                }
                !model.loaded -> item(key = "loading") {
                    InfoCard(title = "กำลังอ่าน", body = null)
                }
                model.tasks.isEmpty() -> item(key = "empty") {
                    EmptyToday(onSync = actions::openSync)
                }
                else -> {
                    val next = face.next
                    val calm = face.calm
                    if (next != null) {
                        item(key = "next") {
                            NextCard(
                                row = next,
                                now = now,
                                zone = zone,
                                enabled = !model.writing,
                                onDone = { model.toggle(next.id) },
                                onEdit = { actions.editTask(next.id) },
                            )
                        }
                    } else if (calm != null) {
                        item(key = "calm") {
                            CalmBlock(calm, alone = face.sections.isEmpty())
                        }
                    }
                    for (section in face.sections) {
                        item(key = "label:" + section.title) {
                            SectionLabel(
                                text = section.title,
                                note = section.note,
                                noteIcon = if (section.note != null) R.drawable.ic_moon else null,
                            )
                        }
                        item(key = "card:" + section.title) {
                            CardGroup {
                                for ((i, row) in section.rows.withIndex()) {
                                    if (i > 0) Hairline(start = 54.dp)
                                    TaskRow(
                                        row = row,
                                        line = rowLine(row, now, zone, hoisted = section.note != null),
                                        clock = if (row.done) "" else rowClock(row, zone),
                                        enabled = !model.writing,
                                        onTick = { model.toggle(row.id) },
                                        onEdit = { actions.editTask(row.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        ExtendedFab(
            text = "เพิ่มงาน",
            icon = R.drawable.ic_plus,
            onClick = actions::addTask,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Size.gutter, bottom = 16.dp + fabLift),
        )
    }
}

// ── the notes ────────────────────────────────────────────────────────────────

/** The tick's disc on dark paper; the widget's widget_tick_dark is the same colour. */
private val TickDark = Color(0xF21F2433)

/**
 * The notes that are up, as squares of paper in a row, newest first, and a
 * square to stick up another. A tap opens one to change it; the round button
 * on its corner takes it off, as on the widget. A note taken off in the last
 * few minutes - here or from the widget - can be put back from the line under
 * the row.
 */
@Composable
private fun NoteBoard(model: HomeModel, actions: AppActions) {
    val c = Reup.colors
    Column(Modifier.padding(top = 14.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Size.gutter, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(model.notes, key = { it.uid }) { note ->
                Box(
                    modifier = Modifier
                        .clickable(onClickLabel = "แก้โน้ต") { actions.editNote(note.uid) }
                        .semantics { contentDescription = "โน้ต: " + note.body },
                ) {
                    val look = noteColors(model.noteStyle, note.color)
                    NoteSquare(
                        body = note.body,
                        color = note.color,
                        style = model.noteStyle,
                        tilt = noteTilt(note.uid),
                        width = 132.dp,
                        height = 100.dp,
                        modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(Size.touch)
                            .clickable(role = Role.Button, onClickLabel = "แกะออก") { model.peelNote(note) }
                            .semantics { contentDescription = "แกะออก: " + note.body },
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        // As on the widget: white on light paper; on dark
                        // paper a dark disc, so the tick is the paper's ink.
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (look.dark) TickDark else Color.White)
                                .border(
                                    1.dp,
                                    if (look.dark) Color.White.copy(alpha = 0.25f) else look.ink.copy(alpha = 0.25f),
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = look.ink,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
            item(key = "add") {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(width = if (model.notes.isEmpty()) 132.dp else 96.dp, height = 100.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, c.outline, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = actions::addNote),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_note_add),
                            contentDescription = null,
                            tint = c.dim,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "แปะโน้ต",
                            style = ReupType.support,
                            color = c.dim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        val gone = model.peeled
        if (gone != null) {
            Row(
                modifier = Modifier.padding(start = Size.gutter, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "เพิ่งแกะ: " + gone.body.replace('\n', ' '),
                    style = ReupType.support,
                    color = c.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextAction(text = "เอาคืน", color = c.accent, onClick = { model.unpeelNote(gone) })
            }
        }
    }
}

// ── the header's sync state ──────────────────────────────────────────────────

/**
 * When the phone last heard from the computer, in a corner of the header.
 *
 * Success is the absence of news, which on its own looks identical to "this has
 * not run in ten minutes" - a different problem. A time beside a tick says
 * which, and a tap opens the screen that can do something about it.
 */
@Composable
private fun SyncPill(model: HomeModel, zone: TimeZone, onClick: () -> Unit) {
    val c = Reup.colors
    val last = model.lastSync
    when {
        last == null || last.notSetUp -> Pill(
            text = if (last == null) null else "ยังไม่ได้ตั้ง",
            icon = R.drawable.ic_cloud,
            iconTint = c.dim,
            onClick = onClick,
            description = "ยังไม่ได้ซิงก์ เปิดหน้าซิงก์",
        )
        last.error != null -> Pill(
            text = "ซิงก์ไม่ได้",
            icon = R.drawable.ic_cloud,
            iconTint = c.warn,
            onClick = onClick,
            description = "ซิงก์ครั้งล่าสุดไม่สำเร็จ เปิดหน้าซิงก์",
        )
        else -> {
            val at = clockOf(Instant.fromEpochMilliseconds(last.atMillis), zone)
            Pill(
                text = at,
                icon = R.drawable.ic_cloud_check,
                iconTint = c.positive,
                onClick = onClick,
                description = "ซิงก์แล้วเมื่อ $at เปิดหน้าซิงก์",
            )
        }
    }
}

// ── the card at the top ──────────────────────────────────────────────────────

@Composable
private fun NextCard(
    row: HomeRow,
    now: Instant,
    zone: TimeZone,
    enabled: Boolean,
    onDone: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = Reup.colors
    Column(
        modifier = Modifier
            .padding(start = Size.gutter, end = Size.gutter, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Size.hero))
            .background(c.raised)
            .combinedClickable(
                enabled = enabled,
                onClickLabel = "แก้ไข",
                onClick = onEdit,
                onLongClick = onEdit,
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ถัดไป",
                style = ReupType.label,
                color = c.accent,
                modifier = Modifier.weight(1f),
            )
            val tone = categoryTone(row.category)
            val name = categoryName(row.category)
            if (tone != null && name != null) CategoryChip(name, tone)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = ReupType.heroName,
                    color = c.text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = heroLine(row, now, zone),
                    style = ReupType.heroLine,
                    color = c.dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            // The one big button on the screen, and the thing it does is the
            // thing this app is for.
            Box(
                modifier = Modifier
                    .size(Size.fab)
                    .clip(CircleShape)
                    .background(if (enabled) c.primary else c.card)
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "ทำแล้ว", onClick = onDone)
                    .semantics { contentDescription = "ทำแล้ว: " + row.name },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = if (enabled) c.onPrimary else c.faint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/**
 * Nothing left today, in words.
 *
 * Centred in the screen when there is nothing under it; at the top, with room,
 * when the days after follow.
 */
@Composable
private fun CalmBlock(calm: Calm, alone: Boolean) {
    val c = Reup.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = if (alone) 120.dp else 48.dp, bottom = if (alone) 0.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RingMark(color = c.accent, size = if (alone) 96.dp else 72.dp)
        Text(
            text = calm.title,
            style = ReupType.calmTitle,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        val lines = listOfNotNull(calm.nextAt, calm.nextName)
        if (lines.isNotEmpty()) {
            Text(
                text = lines.joinToString("\n"),
                style = ReupType.calmBody,
                color = c.dim,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

// ── a row ────────────────────────────────────────────────────────────────────

@Composable
private fun TaskRow(
    row: HomeRow,
    line: String?,
    clock: String,
    enabled: Boolean,
    onTick: () -> Unit,
    onEdit: () -> Unit,
) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Size.row)
            .combinedClickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClickLabel = if (row.done) "ยกเลิกทำแล้ว" else "ทำแล้ว",
                onLongClickLabel = "แก้ไข",
                onLongClick = onEdit,
                onClick = onTick,
            )
            .semantics { stateDescription = if (row.done) "ทำแล้ว" else "ยังไม่ได้ทำ" }
            .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Size.touch), contentAlignment = Alignment.Center) {
            if (row.done) DoneMark() else RingMark(color = c.dim)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = ReupType.rowTitle,
                color = if (row.done) c.dim else c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (line != null) {
                Text(
                    text = line,
                    style = ReupType.support,
                    color = if (row.done) c.faint else c.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (clock.isNotEmpty()) {
            Spacer(Modifier.width(12.dp))
            if (row.silent) {
                Icon(
                    painter = painterResource(R.drawable.ic_moon),
                    contentDescription = "ไม่มีเสียง",
                    tint = c.faint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = clock, style = ReupType.figure, color = c.text)
        }
    }
}

// ── when there is no list ────────────────────────────────────────────────────

@Composable
private fun InfoCard(title: String, body: String?, warn: Boolean = false) {
    val c = Reup.colors
    CardGroup(modifier = Modifier.padding(top = 16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(text = title, style = ReupType.rowTitle, color = if (warn) c.warn else c.text)
            if (body != null) {
                Text(text = body, style = ReupType.support, color = c.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * A phone with no tasks. Said plainly, with the way to get some: they come from
 * the computer. Until they do, reminders are silent, which is right.
 */
@Composable
private fun EmptyToday(onSync: () -> Unit) {
    val c = Reup.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, top = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RingMark(color = c.faint, size = 72.dp)
        Text(
            text = "ยังไม่มีงานในเครื่องนี้",
            style = ReupType.calmTitle,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "ซิงก์กับคอมเพื่อดึงงานลงมา หรือกดเพิ่มงานด้านล่าง",
            style = ReupType.calmBody,
            color = c.dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.size(20.dp))
        TextAction(text = "ตั้งค่าซิงก์", color = c.accent, onClick = onSync)
    }
}
