package app.reup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.reup.R
import app.reup.core.currencySymbol
import app.reup.core.expenseLabel
import app.reup.core.moneyDayTitle
import app.reup.core.moneyText
import app.reup.core.otherUnitsLine
import app.reup.sync.Category
import app.reup.sync.MoneyEntry
import kotlinx.datetime.LocalDate

// ─── MoneyFormScreen.kt — writing down money, and changing what was ──────────
//
// The money tab's own pieces, so the form and the list it adds to look like one
// app: the rows under the form are the tab's rows, grouped under the tab's day
// headings, with ฿ and commas, opening and deleting the same way.
//
// WHY THE DAY IS THREE BUTTONS
//
// It used to be a text box holding 2026-10-13. Nobody thinks of a day that way,
// and a box that takes typing takes typos. Almost all money is written down the
// day it goes or the morning after, so those two are one tap each, and every
// other day is a calendar - never typed. The third button names the day it is
// set to, so the form can always be read back before saving.
//
// WHY ONLY FIVE ROWS UNDER IT
//
// They are a check before saving - did the coffee at eleven already go in -
// and the money tab, where a save goes back to, has twenty. A form followed by
// twenty rows is a list with a form on top.

/** How many of the last entries sit under the form. */
private const val RECENT_SHOWN = 5

@Composable
fun MoneyFormScreen(model: MoneyFormModel, onBack: () -> Unit, onPicture: (Long) -> Unit) {
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val amountFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<MoneyEntry?>(null) }
    val today = remember(model.today) { LocalDate.parse(model.today) }
    val editing = model.editing

    // A blank form opens with the cursor in the amount and the keyboard up: a
    // number is what it was opened to type.
    FocusWhen(model.focusAmount, amountFocus)
    // A row picked from the list is edited up here; go to where that is.
    LaunchedEffect(editing?.uid) {
        if (editing != null) scroll.animateScrollTo(0)
    }

    FormScaffold(
        title = titleOf(model),
        onBack = onBack,
        scroll = scroll,
        bar = {
            SaveBar(
                status = model.status,
                saveText = when {
                    model.saving -> "กำลังบันทึก"
                    editing != null -> "บันทึกการแก้"
                    else -> "บันทึก"
                },
                saveEnabled = model.loaded && !model.busy && !model.blocked,
                onSave = model::save,
                cancelText = if (editing != null) "ยกเลิกแก้" else null,
                onCancel = model::cancelEdit,
                cancelEnabled = !model.busy,
            )
        },
    ) {
        MonthLine(model)
        SlipPanel(model, onPicture)
        // Not while editing: a row lives in one table or the other, and the
        // title already says which.
        if (editing == null) {
            Segmented(
                options = listOf("จ่ายออก", "รับเข้า"),
                selected = if (model.incoming) 1 else 0,
                onSelect = { model.setDirection(it == 1) },
                modifier = Modifier.padding(start = Size.gutter, end = Size.gutter, top = 16.dp),
            )
        }
        SectionLabel("จำนวนเงิน")
        AmountBox(model, amountFocus)
        val unitNote = model.unitNote
        if (unitNote != null) Hint(unitNote, warn = true)
        if (model.incoming) {
            SectionLabel("ได้รับจาก")
            FieldBox(state = model.source, placeholder = "เช่น TELUS", input = model.typing, last = false, enabled = !model.blocked)
        } else {
            SectionLabel("หมวด")
            Categories(model)
        }
        SectionLabel("โน้ต")
        FieldBox(state = model.note, placeholder = "รายละเอียด", input = model.typing, enabled = !model.blocked)
        SectionLabel("วันที่")
        DayChoices(
            date = model.date,
            today = model.today,
            near = model.yesterday,
            nearLabel = "เมื่อวาน",
            onPick = model::pickDate,
            onCalendar = {
                focus.clearFocus()
                picking = true
            },
        )
        Recent(
            model = model,
            today = today,
            menuFor = menuFor,
            onMenu = { menuFor = it },
            onDelete = { confirm = it },
        )
    }

    if (picking) {
        DateDialog(date = model.date, onPick = model::pickDate, onDismiss = { picking = false })
    }
    val doomed = confirm
    if (doomed != null) {
        DeleteMoneyDialog(
            entry = doomed,
            category = model.categories.firstOrNull { it.key == doomed.tag },
            onDelete = {
                confirm = null
                model.delete(doomed)
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun titleOf(m: MoneyFormModel): String = when {
    m.editing != null && m.incoming -> "แก้รายรับ"
    m.editing != null -> "แก้รายจ่าย"
    m.incoming -> "บันทึกรายรับ"
    else -> "บันทึกรายจ่าย"
}

// ── above the form ───────────────────────────────────────────────────────────

/**
 * The month so far, in the books' unit, the same totals as the money tab.
 * Above everything, because the useful thing to know while deciding whether
 * to buy something is what has already gone.
 */
@Composable
private fun MonthLine(model: MoneyFormModel) {
    val c = Reup.colors
    val s = model.summary ?: return
    val unit = model.bookUnit
    Column(Modifier.padding(start = Size.gutter, end = Size.gutter, top = 2.dp)) {
        Text(
            text = "เดือนนี้จ่ายไป ${moneyText(s.spent, unit)} · รับมา ${moneyText(s.received, unit)}",
            style = ReupType.support,
            color = c.dim,
        )
        val others = otherUnitsLine(s.otherRows)
        if (others != null) {
            Text(text = others, style = ReupType.support, color = c.faint, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The slip these boxes were read off: its picture, to hold them up against,
 * and what about it needs a look. The picture row goes when the picture cannot
 * be opened any more - a row promising a picture that is not there is worse
 * than none - and the whole panel goes once the slip is saved.
 */
@Composable
private fun SlipPanel(model: MoneyFormModel, onPicture: (Long) -> Unit) {
    val c = Reup.colors
    val id = model.slipId
    val check = model.slipCheck
    val thumb = if (id >= 0L) rememberThumb(id) else Thumb.Missing
    val picture = thumb != Thumb.Missing
    if (!picture && check == null) return
    CardGroup(modifier = Modifier.padding(top = 14.dp)) {
        if (picture) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Size.tileRow)
                    .clickable(role = Role.Button, onClickLabel = "ดูรูปสลิป") { onPicture(id) }
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlipThumb(thumb, onClick = null)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(text = "รูปสลิป", style = ReupType.body, color = c.text)
                    Text(
                        text = "แตะเพื่อดูเต็มจอ",
                        style = ReupType.support,
                        color = c.dim,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = c.faint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (check != null) {
            if (picture) Hairline(start = 16.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_alert),
                    contentDescription = null,
                    tint = c.warn,
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = check, style = ReupType.support, color = c.text)
            }
        }
    }
}

// ── the boxes ────────────────────────────────────────────────────────────────

/**
 * The amount, large, with its unit on both sides of it: the sign in front
 * when the unit has one only it uses, and the code at the end always. A person
 * who sees THB when they meant USD can see it before pressing anything.
 */
@Composable
private fun AmountBox(model: MoneyFormModel, focus: FocusRequester) {
    val c = Reup.colors
    val symbol = currencySymbol(model.currency)
    Row(
        modifier = Modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(Size.card))
            .background(c.card)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) {
            Text(text = symbol, style = ReupType.amount, color = c.dim)
            Spacer(Modifier.width(8.dp))
        }
        BasicTextField(
            state = model.amount,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus),
            enabled = !model.blocked,
            inputTransformation = model.amountInput,
            textStyle = ReupType.amount.copy(color = c.text),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            lineLimits = TextFieldLineLimits.SingleLine,
            cursorBrush = SolidColor(c.accent),
            decorator = { field ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (model.amount.text.isEmpty()) Text(text = "0", style = ReupType.amount, color = c.faint)
                    field()
                }
            },
        )
        Spacer(Modifier.width(12.dp))
        Text(text = model.currency, style = ReupType.label, color = c.dim)
    }
}

/**
 * The categories as chips, three to a line, read from the database rather
 * than from a list here. The nine the desktop seeds have their drawn icons in
 * their own colours, as on the money tab; one somebody made has its emoji.
 */
@Composable
private fun Categories(model: MoneyFormModel) {
    val c = Reup.colors
    // A row being edited keeps a category this phone does not list, and says
    // what it is, rather than being moved into one of the chips.
    val stray = model.strayCategory
    val list = if (stray == null) model.categories else model.categories + Category(stray, "📦", null)
    if (list.isEmpty()) {
        // Not a failure: a phone before its first sync, filing under other.
        if (model.loaded) {
            Text(
                text = NO_CATEGORIES,
                style = ReupType.support,
                color = c.dim,
                modifier = Modifier.padding(horizontal = Size.gutter),
            )
        }
        return
    }
    Column(
        modifier = Modifier.padding(horizontal = Size.gutter),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (line in list.chunked(3)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (cat in line) {
                    Choice(
                        text = expenseLabel(cat.key, cat.label),
                        on = cat.key == model.category,
                        onClick = { model.pickCategory(cat.key) },
                        modifier = Modifier.weight(1f),
                        icon = expenseIcon(cat.key),
                        iconTint = expenseTone(cat.key),
                        emoji = cat.emoji.takeIf { it.isNotBlank() } ?: "📦",
                    )
                }
                // The last line keeps the others' widths rather than stretching.
                repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ── under the form ───────────────────────────────────────────────────────────

/**
 * The last few entries, as the money tab draws them. Tapping one puts it in
 * the form above to be changed; holding one offers edit or delete.
 */
@Composable
private fun Recent(
    model: MoneyFormModel,
    today: LocalDate,
    menuFor: String?,
    onMenu: (String?) -> Unit,
    onDelete: (MoneyEntry) -> Unit,
) {
    val c = Reup.colors
    if (!model.loaded || model.blocked) return
    Text(
        text = "ล่าสุด",
        style = ReupType.barTitle,
        color = c.text,
        modifier = Modifier
            .padding(start = Size.gutter, end = Size.gutter, top = 36.dp)
            .semantics { heading() },
    )
    val shown = model.recent.take(RECENT_SHOWN)
    if (shown.isEmpty()) {
        // Synced or not is the books' question. A unit read off a slip says
        // nothing about whether this phone has met the computer.
        Text(
            text = if (model.storedCurrency != null) NO_ENTRIES else NO_ENTRIES_YET,
            style = ReupType.support,
            color = c.dim,
            modifier = Modifier.padding(start = Size.gutter, end = Size.gutter, top = 8.dp),
        )
        return
    }
    val byKey = model.categories.associateBy { it.key }
    // Insertion-ordered: the rows arrive newest day first, and stay that way.
    for ((date, entries) in shown.groupBy { it.date.take(10) }) {
        SectionLabel(moneyDayTitle(date, today))
        CardGroup {
            for ((i, e) in entries.withIndex()) {
                if (i > 0) Hairline(start = 70.dp)
                MoneyRow(
                    entry = e,
                    category = byKey[e.tag],
                    menuOpen = menuFor == e.uid,
                    onOpen = { model.startEdit(e) },
                    onMenu = { onMenu(e.uid) },
                    onDismissMenu = { onMenu(null) },
                    onEdit = {
                        onMenu(null)
                        model.startEdit(e)
                    },
                    onDelete = {
                        onMenu(null)
                        onDelete(e)
                    },
                    highlighted = model.editing?.uid == e.uid,
                    enabled = !model.busy,
                )
            }
        }
    }
    if (model.recent.size > shown.size) {
        Text(
            text = "ที่เก่ากว่านี้อยู่ในแท็บเงิน",
            style = ReupType.support,
            color = c.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )
    }
}

/**
 * Two empty states, because they are two different facts. A phone that has
 * synced and has nothing is a person who has not written anything down; a
 * phone that has never synced has not been told anything yet.
 */
private const val NO_ENTRIES = "ยังไม่มีรายการในเครื่องนี้"
private const val NO_ENTRIES_YET = "ยังไม่มีรายการในเครื่องนี้ ซิงก์กับคอมแล้วจะดึงของเก่าลงมา"
private const val NO_CATEGORIES = "ยังไม่มีหมวดในเครื่องนี้ จะบันทึกเป็น other แล้วแก้บนคอมทีหลังได้"
