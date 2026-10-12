package app.reup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reup.PendingSlip
import app.reup.R
import app.reup.core.expenseLabel
import app.reup.core.isBuiltInExpense
import app.reup.core.monthName
import app.reup.core.moneyDayTitle
import app.reup.core.moneyLine
import app.reup.core.moneyText
import app.reup.core.otherUnitsLine
import app.reup.sync.Category
import app.reup.sync.MoneyEntry
import app.reup.sync.slipFace
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime

// ─── MoneyScreen.kt — the month so far, and what it was made of ──────────────
//
// The total answers "how much" and the list answers the one thing a total
// cannot: whether the coffee at eleven went in twice. They are read together,
// in one call, so the two can never be showing different moments.
//
// Writing a row is still the old screen this round. Tapping a row opens it
// there to be edited; holding one offers edit or delete here, because removing
// a row is not what this screen is for and should not be one stray tap away.

@Composable
fun MoneyScreen(model: HomeModel, actions: AppActions, fabLift: Dp) {
    val c = Reup.colors
    val m = model.money
    val today = model.now.toLocalDateTime(model.zone).date
    var menuFor by remember { mutableStateOf<String?>(null) }
    var slipMenuFor by remember { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<MoneyEntry?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = UnderFab) {
            item(key = "head") {
                ScreenHeader(
                    title = "เงิน",
                    over = monthName(today.monthNumber),
                    trailing = {
                        Pill(
                            text = null,
                            icon = R.drawable.ic_scan,
                            iconTint = c.text,
                            onClick = actions::openSlip,
                            description = "อ่านสลิป",
                        )
                    },
                )
            }
            item(key = "summary") { Summary(m) }

            // Above the rows that are already in the books, because these are
            // the ones still asking for something. Gone the moment the last is
            // dealt with: an empty "waiting" box is a box that says nothing.
            val waiting = model.slips
            if (waiting.isNotEmpty()) {
                item(key = "slips-label") { SectionLabel("สลิปรอบันทึก") }
                item(key = "slips") {
                    CardGroup {
                        for ((i, s) in waiting.withIndex()) {
                            if (i > 0) Hairline(start = 70.dp)
                            val key = s.draft.category
                            SlipRow(
                                slip = s,
                                label = expenseLabel(key, key?.let { m.categories[it] }?.label),
                                today = today,
                                menuOpen = slipMenuFor == s.id,
                                onOpen = { actions.editSlip(s.id) },
                                onSave = { model.saveSlip(s) },
                                onMenu = { slipMenuFor = s.id },
                                onDismissMenu = { slipMenuFor = null },
                                onDiscard = {
                                    slipMenuFor = null
                                    model.dismissSlip(s)
                                },
                            )
                        }
                    }
                }
            }

            val error = m.error
            when {
                error != null -> item(key = "error") {
                    Text(
                        text = "เปิดฐานข้อมูลไม่ได้ $error",
                        style = ReupType.support,
                        color = c.warn,
                        modifier = Modifier.padding(horizontal = Size.gutter, vertical = 24.dp),
                    )
                }
                !m.loaded -> Unit
                m.entries.isEmpty() -> item(key = "empty") {
                    Text(
                        // Two different facts: a phone that has synced and has
                        // nothing, and one that has not been told anything yet.
                        text = if (m.currencyKnown) {
                            "ยังไม่มีรายการ กดบันทึกด้านล่างเพื่อเริ่ม"
                        } else {
                            "ยังไม่มีรายการในเครื่องนี้ ซิงก์กับคอมแล้วจะดึงของเก่าลงมา"
                        },
                        style = ReupType.calmBody,
                        color = c.dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 40.dp, end = 40.dp, top = 48.dp),
                    )
                }
                else -> {
                    // Insertion-ordered: the rows arrive newest day first, and
                    // the days stay in that order.
                    val days = m.entries.groupBy { it.date.take(10) }
                    for ((date, entries) in days) {
                        item(key = "label:$date") { SectionLabel(moneyDayTitle(date, today)) }
                        item(key = "card:$date") {
                            CardGroup {
                                for ((i, e) in entries.withIndex()) {
                                    if (i > 0) Hairline(start = 70.dp)
                                    MoneyRow(
                                        entry = e,
                                        category = m.categories[e.tag],
                                        menuOpen = menuFor == e.uid,
                                        onOpen = { actions.editMoney(e.uid) },
                                        onMenu = { menuFor = e.uid },
                                        onDismissMenu = { menuFor = null },
                                        onEdit = {
                                            menuFor = null
                                            actions.editMoney(e.uid)
                                        },
                                        onDelete = {
                                            menuFor = null
                                            confirm = e
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "tail") {
                        Text(
                            text = "รายการล่าสุด ${m.entries.size} รายการ · ดูทั้งหมดได้บนคอม",
                            style = ReupType.support,
                            color = c.faint,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                    }
                }
            }
        }

        // Spending is the common case; holding the button opens the payment
        // side directly, and the screen it opens can switch either way.
        ExtendedFab(
            text = "บันทึก",
            icon = R.drawable.ic_plus,
            onClick = { actions.addMoney(false) },
            onLongClick = { actions.addMoney(true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = Size.gutter, bottom = 16.dp + fabLift),
        )
    }

    val doomed = confirm
    if (doomed != null) {
        val label = labelFor(doomed, m.categories[doomed.tag])
        val line = moneyLine(doomed.incoming, doomed.tag, doomed.note, label)
        // Says which row it means, because the row that was pressed and the row
        // being described have to be the same one, and a thumb on a list of
        // short lines is not evidence of that.
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("ลบรายการนี้") },
            text = {
                Text(line.title + "  " + moneyText(doomed.amount, doomed.currency, if (doomed.incoming) 1 else -1))
            },
            confirmButton = {
                TextAction(text = "ลบ", color = c.danger, onClick = {
                    confirm = null
                    model.deleteMoney(doomed)
                })
            },
            dismissButton = {
                TextAction(text = "ไม่ลบ", color = c.text, onClick = { confirm = null })
            },
            containerColor = c.raised,
            titleContentColor = c.text,
            textContentColor = c.dim,
        )
    }
}

/**
 * A slip that arrived on its own: what it is and how much, then the category
 * and when — or, when it cannot be one press, why not.
 *
 * Tapping opens it in the form. The save button is on the row only when the
 * notification had one too; holding offers the other answer, not saving it.
 */
@Composable
private fun SlipRow(
    slip: PendingSlip,
    label: String,
    today: LocalDate,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = Reup.colors
    val d = slip.draft
    val face = slipFace(d, label, today)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Size.tileRow)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "เปิดดู",
                    onLongClickLabel = "ไม่บันทึก",
                    onLongClick = onMenu,
                    onClick = onOpen,
                )
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(tint = c.accent, icon = R.drawable.ic_scan)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = face.title,
                    style = ReupType.rowTitle,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = face.text,
                    style = ReupType.support,
                    // A reason to look is said in the warning colour; a
                    // transfer to oneself is not a problem, only a fact.
                    color = if (d.oneTap || d.toSelf) c.dim else c.warn,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (d.oneTap) {
                Spacer(Modifier.width(4.dp))
                TextAction(text = "บันทึก", color = c.accent, onClick = onSave)
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            containerColor = c.raised,
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text("เปิดดู", style = ReupType.body, color = c.text) },
                onClick = {
                    onDismissMenu()
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text("ไม่บันทึก", style = ReupType.body, color = c.danger) },
                onClick = onDiscard,
            )
        }
    }
}

/** The category as words, going out; nothing for money coming in. */
private fun labelFor(e: MoneyEntry, category: Category?): String =
    if (e.incoming) "" else expenseLabel(e.tag, category?.label)

// ── the month ────────────────────────────────────────────────────────────────

/**
 * Spent and received this month, in the unit the desktop counts in.
 *
 * Whole units for baht: this is a glance, and the satang have never been the
 * part that decides anything. Rows in another unit are counted under the
 * totals rather than dropped in silence, which is how a confident, wrong-looking
 * zero happens.
 */
@Composable
private fun Summary(m: MoneyView) {
    val c = Reup.colors
    val s = m.summary
    Column(
        modifier = Modifier
            .padding(start = Size.gutter, end = Size.gutter, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Size.hero))
            .background(c.raised)
            .padding(20.dp),
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(text = "จ่ายไปเดือนนี้", style = ReupType.support, color = c.dim)
                Total(if (s == null) "—" else moneyText(s.spent, m.currency))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_income),
                        contentDescription = null,
                        tint = c.positive,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(text = "รับมา", style = ReupType.support, color = c.dim)
                }
                Total(if (s == null) "—" else moneyText(s.received, m.currency))
            }
        }
        val footer = when {
            !m.loaded -> null
            !m.currencyKnown -> "ยังไม่ได้รับสกุลเงินจากคอม นับเป็น ${m.currency} ไปก่อน"
            else -> s?.let { otherUnitsLine(it.otherRows) }
        }
        if (footer != null) {
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(c.lineOnRaised),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = null,
                    tint = c.dim,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = footer, style = ReupType.support, color = c.dim)
            }
        }
    }
}

/** A total, shrinking rather than wrapping when a month runs to seven figures. */
@Composable
private fun Total(text: String) {
    Text(
        text = text,
        style = ReupType.amount,
        color = Reup.colors.text,
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 28.sp, stepSize = 1.sp),
        modifier = Modifier.padding(top = 4.dp),
    )
}

// ── a row ────────────────────────────────────────────────────────────────────

@Composable
private fun MoneyRow(
    entry: MoneyEntry,
    category: Category?,
    menuOpen: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Reup.colors
    val label = labelFor(entry, category)
    val line = moneyLine(entry.incoming, entry.tag, entry.note, label)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Size.tileRow)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "แก้ไข",
                    onLongClickLabel = "แก้หรือลบ",
                    onLongClick = onMenu,
                    onClick = onOpen,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.incoming) {
                IconTile(tint = c.positive, icon = R.drawable.ic_income, background = c.positive.copy(alpha = 0.14f))
            } else if (isBuiltInExpense(entry.tag)) {
                IconTile(tint = expenseTone(entry.tag) ?: c.dim, icon = expenseIcon(entry.tag))
            } else {
                // One somebody made: its own emoji, on a neutral tile.
                IconTile(tint = c.dim, icon = null, emoji = category?.emoji ?: "📦")
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = line.title,
                    style = ReupType.rowTitle,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = line.detail
                if (detail != null) {
                    Text(
                        text = detail,
                        style = ReupType.support,
                        color = c.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // The sign carries the direction on its own; the colour only agrees
            // with it, so nothing is lost reading this in sunlight.
            Text(
                text = moneyText(entry.amount, entry.currency, if (entry.incoming) 1 else -1),
                style = if (entry.incoming) ReupType.figure.copy(fontWeight = ReupType.label.fontWeight) else ReupType.figure,
                color = if (entry.incoming) c.positive else c.text,
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            containerColor = c.raised,
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text("แก้ไข", style = ReupType.body, color = c.text) },
                onClick = onEdit,
            )
            DropdownMenuItem(
                text = { Text("ลบ", style = ReupType.body, color = c.danger) },
                onClick = onDelete,
            )
        }
    }
}
