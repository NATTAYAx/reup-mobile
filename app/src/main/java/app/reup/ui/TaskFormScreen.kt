package app.reup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.reup.R

// ─── TaskFormScreen.kt — adding a task, and changing one ─────────────────────
//
// Built from the same pieces as the money form, so the two read as one app:
// chips for every choice, the same three day buttons and calendar, a clock for
// the time, the save button pinned above the keyboard.
//
// WHY THE TIME IS A CLOCK NOW
//
// The old form had a text box for "04:00" and said why: the system picker
// hands back an hour and a minute in the phone's locale and no string, and
// every path from those to what the desktop writes was code on a screen no
// test could see. That path is resetTimeText now, in the shared module, with
// a test that walks all 1,440 minutes of a day through it and through the
// same check the save makes. The picker is twenty-four hour regardless of the
// phone, which is how the desktop writes a time anyway.

private enum class Picking { DATE, TIME }

/** Sunday first, because reset_day counts Sunday as zero. */
private val DAYS = listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")

@Composable
fun TaskFormScreen(model: TaskFormModel, onBack: () -> Unit) {
    val c = Reup.colors
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf<Picking?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The fields of a row that cannot be saved are shown, not offered.
    val open = model.loaded && !model.blocked

    // A new task opens with the cursor in the name: the one thing to type.
    FocusWhen(model.focusName, nameFocus)

    FormScaffold(
        title = if (model.editing) "แก้ไขงาน" else "เพิ่มงาน",
        onBack = onBack,
        scroll = scroll,
        bar = {
            SaveBar(
                status = model.status,
                saveText = when {
                    model.saving -> "กำลังบันทึก"
                    model.editing -> "บันทึกการแก้ไข"
                    else -> "บันทึก"
                },
                saveEnabled = open && !model.busy,
                onSave = model::save,
            )
        },
    ) {
        SectionLabel("ชื่องาน")
        FieldBox(
            state = model.name,
            placeholder = "เช่น เช็คอินรายวัน",
            modifier = Modifier.focusRequester(nameFocus),
            input = model.typing,
            enabled = open,
        )

        SectionLabel("กลับมาเมื่อไหร่")
        Column(
            modifier = Modifier.padding(horizontal = Size.gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (line in TaskFormModel.TYPES.chunked(3)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in line) {
                        Choice(
                            text = label,
                            on = model.type == value,
                            onClick = { model.pickType(value) },
                            modifier = Modifier.weight(1f),
                            enabled = open,
                        )
                    }
                    repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        // Only what the kind of task asks for. A field that does not apply is
        // not temporarily unavailable, it has no meaning, and showing it grey
        // invites filling it in and wondering why nothing happened.
        when (model.type) {
            "weekly", "biweekly" -> {
                SectionLabel("วันไหน")
                Row(
                    modifier = Modifier
                        .padding(horizontal = Size.gutter)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((i, d) in DAYS.withIndex()) {
                        Choice(
                            text = d,
                            on = model.day == i,
                            onClick = { model.pickDay(i) },
                            modifier = Modifier.weight(1f),
                            enabled = open,
                        )
                    }
                }
            }
            "custom_days" -> {
                SectionLabel("ทำซ้ำทุกกี่วัน")
                Stepper(model, enabled = open)
            }
            "specific_date" -> {
                SectionLabel("วันที่")
                DayChoices(
                    date = model.date,
                    today = model.today,
                    near = model.tomorrow,
                    nearLabel = "พรุ่งนี้",
                    onPick = model::pickDate,
                    onCalendar = {
                        focus.clearFocus()
                        picking = Picking.DATE
                    },
                    enabled = open,
                )
            }
        }

        SectionLabel("เวลา")
        Row(
            modifier = Modifier
                .padding(horizontal = Size.gutter)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Choice(
                text = "สิ้นวัน",
                on = model.time == null,
                onClick = model::clearTime,
                modifier = Modifier.weight(1f),
                enabled = open,
            )
            Choice(
                text = model.time ?: "เลือกเวลา",
                on = model.time != null,
                onClick = {
                    focus.clearFocus()
                    picking = Picking.TIME
                },
                modifier = Modifier.weight(1.3f),
                icon = R.drawable.ic_clock,
                enabled = open,
            )
        }
        Hint("สิ้นวันคือไม่ใส่เวลา นับถึงจบวันนั้น แบบเดียวกับบนคอม")

        SectionLabel("ทำเครื่องหมาย")
        Column(
            modifier = Modifier.padding(horizontal = Size.gutter),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(
                    text = "สำคัญ",
                    on = model.priority,
                    onClick = model::togglePriority,
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_star,
                    toggle = true,
                    enabled = open,
                )
                Choice(
                    text = "ด่วน",
                    on = model.urgent,
                    onClick = model::toggleUrgent,
                    modifier = Modifier.weight(1f),
                    icon = R.drawable.ic_flame,
                    toggle = true,
                    enabled = open,
                )
            }
            // A line of its own, because it is not a third kind of importance:
            // it answers whether this may wake somebody.
            Choice(
                text = "ดังได้ในช่วงเงียบ",
                on = model.wake,
                onClick = model::toggleWake,
                modifier = Modifier.fillMaxWidth(),
                icon = R.drawable.ic_bell_ring,
                toggle = true,
                enabled = open,
            )
        }
        Hint("ช่วงเงียบปกติเตือนแบบไม่สั่น อันนี้ให้งานนี้สั่นได้")

        // Only when there is something to delete, and nowhere near save: the
        // thing reached for on this screen is almost always save.
        if (model.editing) {
            Spacer(Modifier.height(36.dp))
            DangerButton(text = "ลบงานนี้", onClick = { confirmDelete = true }, enabled = model.loaded && !model.busy)
        }
    }

    when (picking) {
        Picking.DATE -> DateDialog(date = model.date, onPick = model::pickDate, onDismiss = { picking = null })
        Picking.TIME -> TimeDialog(time = model.time, onPick = model::pickTime, onDismiss = { picking = null })
        null -> Unit
    }

    if (confirmDelete) {
        val name = model.name.text.toString().trim()
        // Says which task it means, and that it goes to the bin rather than
        // nowhere: the desktop keeps it there before it is gone for good.
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("ลบงานนี้") },
            text = { Text((if (name.isEmpty()) "" else "$name\n") + "จะย้ายไปถังขยะบนคอม") },
            confirmButton = {
                TextAction(text = "ลบ", color = c.danger, onClick = {
                    confirmDelete = false
                    model.delete()
                })
            },
            dismissButton = {
                TextAction(text = "ไม่ลบ", color = c.text, onClick = { confirmDelete = false })
            },
            containerColor = c.raised,
            titleContentColor = c.text,
            textContentColor = c.dim,
        )
    }
}

/**
 * Every how many days: one fewer, the number, one more. The number can be
 * typed as well, for a month rather than thirty presses.
 */
@Composable
private fun Stepper(model: TaskFormModel, enabled: Boolean) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(Size.card))
            .background(c.card)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(icon = R.drawable.ic_minus, label = "น้อยลงหนึ่งวัน", enabled = enabled) { model.step(-1) }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "ทุก", style = ReupType.body, color = c.dim)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                state = model.interval,
                modifier = Modifier.width(64.dp),
                enabled = enabled,
                inputTransformation = model.intervalInput,
                textStyle = ReupType.heroName.copy(color = c.text, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = SolidColor(c.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = "วัน", style = ReupType.body, color = c.dim)
        }
        StepButton(icon = R.drawable.ic_plus, label = "มากขึ้นหนึ่งวัน", enabled = enabled) { model.step(1) }
    }
}

@Composable
private fun StepButton(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Reup.colors
    Box(
        modifier = Modifier
            .size(Size.touch)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = if (enabled) c.text else c.faint,
            modifier = Modifier.size(20.dp),
        )
    }
}
