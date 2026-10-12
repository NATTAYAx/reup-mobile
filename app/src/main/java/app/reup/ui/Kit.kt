package app.reup.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.reup.R

// ─── Kit.kt — the pieces every screen is built from ──────────────────────────
//
// The old screens each carried a private copy of the design - their own grey,
// their own row height, their own idea of a card - and five screens ended up
// with five greys. Everything a screen draws comes from here now, with the
// numbers from Theme.kt, so a sixth screen gets the design by using it rather
// than by copying it.
//
// Every pressable thing is at least 44 points on its short side. Nothing in
// here is a fixed height that text has to fit into; heights are minimums.

// ── the top of a tab ─────────────────────────────────────────────────────────

/**
 * The small line and the big name at the top of each tab.
 *
 * The small line's space is kept when there is none, so the three titles sit
 * on one line when switching tabs rather than hopping.
 */
@Composable
fun ScreenHeader(
    title: String,
    over: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Reup.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Size.gutter, end = Size.gutter, top = 28.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = over ?: "",
                style = ReupType.over,
                color = c.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = ReupType.title,
                color = c.text,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .semantics { heading() },
            )
        }
        if (trailing != null) trailing()
    }
}

/** A heading over a group of rows, with an optional reason said once for all of them. */
@Composable
fun SectionLabel(text: String, note: String? = null, @DrawableRes noteIcon: Int? = null) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Size.gutter, end = Size.gutter, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = ReupType.label,
            color = c.dim,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (note != null) {
            if (noteIcon != null) {
                Icon(
                    painter = painterResource(noteIcon),
                    contentDescription = null,
                    tint = c.faint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text = note, style = ReupType.support, color = c.dim)
        }
    }
}

// ── panels ───────────────────────────────────────────────────────────────────

/**
 * Rows on one panel, which is what makes them read as a list rather than as
 * things that happen to be in a column.
 */
@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    color: Color = Reup.colors.card,
    radius: Dp = Size.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(color),
        content = content,
    )
}

/** The line between two rows, starting where their text does. */
@Composable
fun Hairline(start: Dp, color: Color = Reup.colors.line) {
    Box(
        Modifier
            .padding(start = start)
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * A row that goes somewhere: an icon, two lines, and what it is set to.
 *
 * @param value what the setting currently is, printed before the chevron
 */
@Composable
fun NavRow(
    @DrawableRes icon: Int,
    title: String,
    detail: String?,
    onClick: (() -> Unit)?,
    value: String? = null,
    chevron: Boolean = onClick != null,
) {
    val c = Reup.colors
    val press = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Size.row)
            .then(press)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = c.dim,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = ReupType.body, color = c.text)
            if (detail != null) {
                Text(
                    text = detail,
                    style = ReupType.support,
                    color = c.dim,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = ReupType.figure.copy(fontWeight = ReupType.body.fontWeight),
                color = c.dim,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (chevron) {
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = c.faint,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
            )
        }
    }
}

/** A tinted square with an icon or an emoji in it, at the head of a money row. */
@Composable
fun IconTile(tint: Color, @DrawableRes icon: Int?, emoji: String? = null, background: Color = tint.copy(alpha = 0.12f)) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Size.tile))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        } else if (emoji != null) {
            Text(text = emoji, style = ReupType.body, textAlign = TextAlign.Center)
        }
    }
}

// ── things that have to be said ──────────────────────────────────────────────

enum class Tone { WARN, DANGER }

/**
 * Something on this phone that will stop a reminder arriving, and the button
 * that fixes it. Only ever drawn while it is true.
 */
@Composable
fun Banner(text: String, tone: Tone, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = Reup.colors
    val color = if (tone == Tone.DANGER) c.danger else c.warn
    Row(
        modifier = modifier
            .padding(horizontal = Size.gutter)
            .fillMaxWidth()
            .clip(RoundedCornerShape(Size.card))
            .background(color.copy(alpha = 0.10f))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_alert),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = ReupType.banner, color = c.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(4.dp))
        TextAction(text = action, color = color, onClick = onAction)
    }
}

/** A word that is a button: no fill, a 44-point target, the colour of what it fixes. */
@Composable
fun TextAction(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .heightIn(min = Size.touch)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = ReupType.button,
            color = if (enabled) color else Reup.colors.faint,
        )
    }
}

// ── controls ─────────────────────────────────────────────────────────────────

/**
 * Two or three choices of which exactly one holds, side by side.
 *
 * The picked one says so twice - a fill and a tick - because a fill alone is a
 * colour, and colour alone is not something everyone can see.
 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Reup.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Size.touch)
            .clip(CircleShape)
            .border(1.dp, c.outline, CircleShape),
    ) {
        for ((i, option) in options.withIndex()) {
            val on = i == selected
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .heightIn(min = Size.touch)
                        .background(c.outline),
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Size.touch)
                    .background(if (on) c.accentSoft else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { this.selected = on }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = c.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = option,
                    style = if (on) ReupType.control.copy(fontWeight = ReupType.label.fontWeight) else ReupType.control,
                    color = if (on) c.accent else c.soft,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The one action a panel is for, full width. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, @DrawableRes icon: Int? = null, enabled: Boolean = true) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) c.primary else c.raised)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) c.onPrimary else c.faint
        if (icon != null) {
            Icon(painter = painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, style = ReupType.button, color = ink)
    }
}

/** An action that is not the point of the screen: an outline, not a fill. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Reup.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, c.outline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = ReupType.button, color = if (enabled) c.text else c.faint)
    }
}

/**
 * The tab's main action, pinned above the bar where a thumb is.
 *
 * Long press is a second action for people who know it is there, and never
 * the only way to reach anything.
 */
@Composable
fun ExtendedFab(
    text: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val c = Reup.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .heightIn(min = Size.fab)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(c.primary)
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = ReupType.button, color = c.onPrimary)
    }
}

/** A small rounded thing at the end of a header: the sync state, a shortcut. */
@Composable
fun Pill(
    text: String?,
    @DrawableRes icon: Int,
    iconTint: Color,
    onClick: () -> Unit,
    description: String,
) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .heightIn(min = Size.touch)
            .clip(CircleShape)
            .background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = if (text == null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = iconTint, modifier = Modifier.size(if (text == null) 20.dp else 16.dp))
        if (text != null) {
            Spacer(Modifier.width(6.dp))
            Text(text = text, style = ReupType.over, color = c.dim)
        }
    }
}

// ── task marks ───────────────────────────────────────────────────────────────

/** The app's own mark, open: a task still waiting. Same drawing as the icon. */
@Composable
fun RingMark(color: Color, size: Dp = 22.dp) {
    Icon(
        painter = painterResource(R.drawable.ic_ring),
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size),
    )
}

/** The same mark, filled and ticked: done for this round. */
@Composable
fun DoneMark(size: Dp = 22.dp) {
    val c = Reup.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(c.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_check),
            contentDescription = null,
            tint = c.onPrimary,
            modifier = Modifier.size(size * 0.64f),
        )
    }
}

// ── task categories ──────────────────────────────────────────────────────────

/**
 * The desktop's four task colours, as a dot and as text.
 *
 * Fixed across themes, the way money coming in is green in every theme: work is
 * orange on the desktop's calendar, and has to be orange here too or the colour
 * stops meaning anything.
 */
@Immutable
class CategoryTone(val dot: Color, val ink: Color)

fun categoryTone(key: String?): CategoryTone? = when (key) {
    "game" -> CategoryTone(Color(0xFFC084FC), Color(0xFFD8B4FE))
    "school" -> CategoryTone(Color(0xFF60A5FA), Color(0xFF93C5FD))
    "work" -> CategoryTone(Color(0xFFFB923C), Color(0xFFFDBA74))
    "personal" -> CategoryTone(Color(0xFF4ADE80), Color(0xFF86EFAC))
    else -> null
}

/** A category as a small labelled dot, for the card at the top of Today. */
@Composable
fun CategoryChip(name: String, tone: CategoryTone) {
    Row(
        modifier = Modifier
            .heightIn(min = 26.dp)
            .clip(CircleShape)
            .background(tone.dot.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(tone.dot),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = name, style = ReupType.chip, color = tone.ink)
    }
}

// ── money categories ─────────────────────────────────────────────────────────

/**
 * The colour of a spending category, after the desktop's gradient for it.
 *
 * Null for one somebody made: it has an emoji of its own, which is colour
 * enough, and a tint picked here would be a guess.
 */
fun expenseTone(key: String?): Color? = when (key) {
    "food" -> Color(0xFFFDBA74)
    "transport" -> Color(0xFF93C5FD)
    "entertainment" -> Color(0xFFF9A8D4)
    "shopping" -> Color(0xFFD8B4FE)
    "health" -> Color(0xFF86EFAC)
    "education" -> Color(0xFFA5B4FC)
    "bills" -> Color(0xFFCBD5E1)
    "game" -> Color(0xFFC4B5FD)
    "other" -> Color(0xFFD1D5DB)
    else -> null
}

@DrawableRes
fun expenseIcon(key: String?): Int? = when (key) {
    "food" -> R.drawable.ic_cat_food
    "transport" -> R.drawable.ic_cat_transport
    "entertainment" -> R.drawable.ic_cat_entertainment
    "shopping" -> R.drawable.ic_cat_shopping
    "health" -> R.drawable.ic_cat_health
    "education" -> R.drawable.ic_cat_education
    "bills" -> R.drawable.ic_cat_bills
    "game" -> R.drawable.ic_cat_game
    "other" -> R.drawable.ic_cat_other
    else -> null
}

// ── navigation ───────────────────────────────────────────────────────────────

@Immutable
class BarItem(val label: String, @DrawableRes val icon: Int, val badge: Boolean = false)

/**
 * The three tabs.
 *
 * Its own colour reaches under the gesture strip, so the bottom of the screen
 * is one surface rather than a bar with a stripe of page under it.
 */
@Composable
fun BottomBar(items: List<BarItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Reup.colors
    Column(Modifier.fillMaxWidth().background(c.field)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .heightIn(min = Size.bar - 1.dp),
        ) {
            for ((i, item) in items.withIndex()) {
                BarCell(item, on = i == selected, onClick = { onSelect(i) })
            }
        }
    }
}

@Composable
private fun RowScope.BarCell(item: BarItem, on: Boolean, onClick: () -> Unit) {
    val c = Reup.colors
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = Size.bar - 1.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = on },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) c.accentSoft else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    tint = if (on) c.accent else c.dim,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (item.badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 15.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c.warn),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.label,
            style = if (on) ReupType.tab.copy(fontWeight = ReupType.chip.fontWeight) else ReupType.tab,
            color = if (on) c.text else c.dim,
        )
    }
}

/** A screen opened from a tab: back on the left, its name beside it. */
@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    val c = Reup.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 10.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Size.touch)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = "กลับ", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = "กลับ",
                tint = c.text,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = ReupType.barTitle, color = c.text, modifier = Modifier.semantics { heading() })
    }
}

/** Room at the bottom of a list so its last row can scroll clear of the button. */
val UnderFab = PaddingValues(bottom = Size.fab + 40.dp)
