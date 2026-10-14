package app.reup.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reup.R
import app.reup.core.dayChip
import app.reup.core.resetTimeParts
import app.reup.core.resetTimeText
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import java.time.Instant
import java.time.ZoneOffset

// ─── FormKit.kt — what the forms are built from ──────────────────────────────
//
// The money form grew these, and the task form is built from the same ones, so
// a chip, a box, a calendar and a save button look and answer the same way on
// both. Kit.kt is the pieces every screen uses; these are the ones only a
// screen that writes something down needs.
//
// Nothing here is typed that can be picked instead. A day is a button or a
// calendar, a time is a clock, a choice is a chip; the only boxes are for
// words and amounts, which nothing else can give.

enum class StatusTone { INFO, DONE, WARN }

/** The line over the save button: what just happened, or what is in the way. */
class FormStatus(val text: String, val tone: StatusTone)

/**
 * A form's frame: the bar with back and the title, the fields scrolling under
 * it, and [bar] pinned at the bottom, riding on the keyboard when it is up.
 */
@Composable
internal fun FormScaffold(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState,
    bar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Reup.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = Size.reading),
        ) {
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                TopBar(title = title, onBack = onBack)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(bottom = 28.dp),
                content = content,
            )
            bar()
        }
    }
}

/**
 * Pinned where a thumb reaches, and riding on top of the keyboard, so a form
 * that grows cannot push the one thing it is for off the bottom of the glass.
 * What the last press did is said just above it, where the eye already is.
 *
 * @param cancelText a second, quieter button beside save, when there is one
 */
@Composable
internal fun SaveBar(
    status: FormStatus?,
    saveText: String,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    cancelText: String? = null,
    onCancel: () -> Unit = {},
    cancelEnabled: Boolean = true,
) {
    val c = Reup.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.line),
        )
        Column(Modifier.padding(start = Size.gutter, end = Size.gutter, top = 10.dp, bottom = 10.dp)) {
            if (status != null) StatusLine(status)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cancelText != null) {
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(text = cancelText, onClick = onCancel, enabled = cancelEnabled)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(if (cancelText != null) 1.4f else 1f)) {
                    PrimaryButton(text = saveText, onClick = onSave, enabled = saveEnabled)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(s: FormStatus) {
    val c = Reup.colors
    val (tint, icon) = when (s.tone) {
        StatusTone.WARN -> c.warn to R.drawable.ic_alert
        StatusTone.DONE -> c.positive to R.drawable.ic_check
        StatusTone.INFO -> c.dim to R.drawable.ic_info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = s.text, style = ReupType.support, color = if (s.tone == StatusTone.INFO) c.dim else c.text)
    }
}

/** A line under a field: what it means, or - [warn] - why it might be wrong. */
@Composable
internal fun Hint(text: String, warn: Boolean = false) {
    val c = Reup.colors
    val tint = if (warn) c.warn else c.dim
    Row(
        modifier = Modifier.padding(start = Size.gutter, end = Size.gutter, top = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = ReupType.support, color = tint)
    }
}

/**
 * Put the cursor in [focus], and so the keyboard up, each time [tick] goes up.
 *
 * Waits for the window to have focus first. Asked for in a new screen's first
 * frame, the keyboard can be refused because the window is not ready to take
 * it yet, and the box ends up focused with no keyboard under it.
 */
@Composable
internal fun FocusWhen(tick: Int, focus: FocusRequester) {
    val window = LocalWindowInfo.current
    LaunchedEffect(tick) {
        if (tick <= 0) return@LaunchedEffect
        snapshotFlow { window.isWindowFocused }.first { it }
        try {
            focus.requestFocus()
        } catch (e: IllegalStateException) {
            // Not attached yet. The box is one tap away regardless.
        }
    }
}

// ── boxes and chips ──────────────────────────────────────────────────────────

/**
 * A line of text on a card, with a word in grey until something is typed.
 *
 * @param modifier added after the box's own, for a focusRequester
 * @param last Done on the keyboard rather than Next
 */
@Composable
internal fun FieldBox(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    input: InputTransformation? = null,
    last: Boolean = true,
    enabled: Boolean = true,
) {
    val c = Reup.colors
    BasicTextField(
        state = state,
        modifier = Modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .then(modifier),
        enabled = enabled,
        inputTransformation = input,
        textStyle = ReupType.body.copy(color = c.text),
        keyboardOptions = KeyboardOptions(imeAction = if (last) ImeAction.Done else ImeAction.Next),
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(c.accent),
        decorator = { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.card)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (state.text.isEmpty()) Text(text = placeholder, style = ReupType.body, color = c.faint)
                field()
            }
        },
    )
}

/**
 * One of a set where exactly one holds - or, with [toggle], a thing that is
 * on or off by itself. Picked says so twice, a fill and an outline, and the
 * words go heavier, because a colour alone is not something everyone can see.
 * Long words shrink a little rather than lose their end.
 *
 * @param iconTint the icon's own colour whether picked or not, as a category's
 */
@Composable
internal fun Choice(
    text: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color? = null,
    emoji: String? = null,
    toggle: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Reup.colors
    val shape = RoundedCornerShape(14.dp)
    val press = if (toggle) {
        Modifier.toggleable(value = on, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier = modifier
            .heightIn(min = Size.touch)
            .clip(shape)
            .background(if (on) c.accentSoft else c.card)
            .then(if (on) Modifier.border(1.5.dp, c.accent, shape) else Modifier)
            .then(press)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint ?: if (on) c.accent else c.dim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
        } else if (emoji != null) {
            Text(text = emoji, style = ReupType.control)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = if (on) ReupType.control.copy(fontWeight = ReupType.label.fontWeight) else ReupType.control,
            color = if (on) c.accent else c.soft,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 14.sp, stepSize = 0.5.sp),
        )
    }
}

/**
 * Today, the day beside it, or a day from the calendar. The third button says
 * the day it is set to - ศ. 9 ต.ค. - so what will be saved can be read back,
 * and opens the calendar again to change it.
 *
 * @param near the day beside today that most of these are: yesterday for
 *   money, which is written down after; tomorrow for a task, which is ahead
 */
@Composable
internal fun DayChoices(
    date: String,
    today: String,
    near: String,
    nearLabel: String,
    onPick: (String) -> Unit,
    onCalendar: () -> Unit,
    enabled: Boolean = true,
) {
    val other = date != today && date != near
    val todayDate = runCatching { LocalDate.parse(today) }.getOrNull()
    Row(
        modifier = Modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Choice(
            text = "วันนี้",
            on = date == today,
            onClick = { onPick(today) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Choice(
            text = nearLabel,
            on = date == near,
            onClick = { onPick(near) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Choice(
            text = if (other && todayDate != null) dayChip(date, todayDate) else "เลือกวัน",
            on = other,
            onClick = onCalendar,
            modifier = Modifier.weight(1.3f),
            icon = R.drawable.ic_calendar,
            enabled = enabled,
        )
    }
}

/** An outline in the colour of a warning, for the one button that takes something away. */
@Composable
internal fun DangerButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Reup.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, c.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = ReupType.button, color = if (enabled) c.danger else c.faint)
    }
}

// ── the calendar and the clock ───────────────────────────────────────────────

/**
 * Material's date picker, in the app's colours, opened on the day the form is
 * set to. Only the calendar: the picker's own typing mode is the text box this
 * replaced.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateDialog(date: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Reup.colors
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(date) ?: utcMillis(java.time.LocalDate.now().toString()),
        yearRange = PICKER_YEARS,
    )
    val colors = DatePickerDefaults.colors(
        containerColor = c.raised,
        titleContentColor = c.dim,
        headlineContentColor = c.text,
        weekdayContentColor = c.dim,
        subheadContentColor = c.dim,
        navigationContentColor = c.text,
        yearContentColor = c.text,
        currentYearContentColor = c.accent,
        selectedYearContentColor = c.onPrimary,
        selectedYearContainerColor = c.primary,
        dayContentColor = c.text,
        selectedDayContentColor = c.onPrimary,
        selectedDayContainerColor = c.primary,
        todayContentColor = c.accent,
        todayDateBorderColor = c.accent,
        dividerColor = c.lineOnRaised,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction(
                text = "ตกลง",
                color = c.accent,
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(isoOfUtc(it)) }
                    onDismiss()
                },
            )
        },
        dismissButton = {
            TextAction(text = "ยกเลิก", color = c.text, onClick = onDismiss)
        },
        colors = colors,
    ) {
        DatePicker(state = state, colors = colors, showModeToggle = false)
    }
}

/**
 * Material's clock, twenty-four hour whatever the phone is set to, because
 * that is how the desktop writes a time; opened on the time the form is set
 * to, or on 04:00 - the example the old box gave - when it is set to none.
 * What it answers is turned into "04:00" by resetTimeText, which has tests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(time: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Reup.colors
    val (hour, minute) = resetTimeParts(time) ?: (4 to 0)
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextAction(
                text = "ตกลง",
                color = c.accent,
                onClick = {
                    onPick(resetTimeText(state.hour, state.minute))
                    onDismiss()
                },
            )
        },
        dismissButton = {
            TextAction(text = "ยกเลิก", color = c.text, onClick = onDismiss)
        },
        title = { Text("เลือกเวลา") },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Only the colours every version of the picker names the same
                // way; the rest follow the theme.
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.card,
                        clockDialSelectedContentColor = c.onPrimary,
                        selectorColor = c.primary,
                        containerColor = c.raised,
                        timeSelectorSelectedContainerColor = c.accentSoft,
                        timeSelectorSelectedContentColor = c.accent,
                    ),
                )
            }
        },
        containerColor = c.raised,
        titleContentColor = c.text,
        textContentColor = c.text,
    )
}

/**
 * The years the calendar offers: Material's own default, written down so the
 * check below asks about the same years. A day outside them opens the picker
 * with nothing picked, and older versions of it refused to open at all.
 */
private val PICKER_YEARS = 1900..2100

/**
 * `YYYY-MM-DD` the way the picker counts days: UTC midnight, in milliseconds.
 * Null for a date that is not one, or one the picker cannot open on - a slip
 * read with its Buddhist year, say - and the calendar opens on today instead.
 */
private fun utcMillis(iso: String): Long? =
    runCatching {
        val day = java.time.LocalDate.parse(iso.take(10))
        if (day.year in PICKER_YEARS) day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else null
    }.getOrNull()

/** The picker's day back as `YYYY-MM-DD`. */
private fun isoOfUtc(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
