package app.reup.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reup.R
import app.reup.core.DEFAULT_PALETTE
import app.reup.core.Palette

// ─── Theme.kt — the palette, the type and the measurements, once ─────────────
//
// WHY THE COLOURS STILL COME FROM Palette.kt
//
// Palette.kt is data with a contrast test, and that test is the reason the
// phone's colours can be trusted outdoors. Compose gets its colours from there
// rather than from a second table here; the handful that are new in this design
// are worked out FROM the palette, so every one of the six themes gets them and
// none can be added by eye.
//
// WHY THIS IS NOT JUST MaterialTheme
//
// Material's scheme has no slot for "the card under the card" or for a hairline
// that has to show on two different surfaces, and filling the nearest Material
// slot with the wrong idea is how a dialog ends up the colour of a warning. So
// the app reads [Reup.colors], and MaterialTheme is filled in from it only so
// that the Material pieces this uses - dialogs, menus, the switch, the ripple -
// match without being told.

/** Every colour a screen is allowed to use. See Palette.kt for the first fifteen. */
@Immutable
class ReupColors(
    val bg: Color,
    val card: Color,
    val raised: Color,
    val field: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    /** Primary, light enough to be text. Palette tests it against bg and card. */
    val accent: Color,
    val text: Color,
    val dim: Color,
    val faint: Color,
    val positive: Color,
    val warn: Color,
    val danger: Color,

    /** A selected tab, a picked chip: primary at a fifth, behind accent text. */
    val accentSoft: Color,
    /** Between text and dim, for the choices in a control that are not picked. */
    val soft: Color,
    /** An edge that has to read as a control rather than as a divider. */
    val outline: Color,
    /** A hairline drawn on [raised], where [line] would vanish. */
    val lineOnRaised: Color,
)

fun reupColors(p: Palette): ReupColors {
    val text = Color(p.text)
    val dim = Color(p.dim)
    val line = Color(p.line)
    val raised = Color(p.raised)
    val primary = Color(p.primary)
    return ReupColors(
        bg = Color(p.bg),
        card = Color(p.card),
        raised = raised,
        field = Color(p.field),
        line = line,
        primary = primary,
        onPrimary = Color(p.onPrimary),
        accent = Color(p.accent),
        text = text,
        dim = dim,
        faint = Color(p.faint),
        positive = Color(p.positive),
        warn = Color(p.warn),
        danger = Color(p.danger),
        accentSoft = primary.copy(alpha = 0.22f),
        soft = lerp(dim, text, 0.5f),
        outline = lerp(line, dim, 0.25f),
        lineOnRaised = lerp(raised, text, 0.08f),
    )
}

private val LocalReupColors = staticCompositionLocalOf { reupColors(DEFAULT_PALETTE) }

/** How a screen reaches the palette: `Reup.colors.card`. */
object Reup {
    val colors: ReupColors
        @Composable get() = LocalReupColors.current
}

// ─── type ───────────────────────────────────────────────────────────────────
//
// IBM Plex Sans Thai, bundled rather than downloaded, because a downloadable
// font arrives a moment after the first frame and a screen that jumps once it
// has is worse than one in the system font. It carries Latin and ฿ itself, so a
// row mixing "Genshin" with Thai is one face rather than two, and its digits are
// all one width, which is what lets a column of times or amounts line up without
// asking for tabular figures.
//
// Sizes in sp, so they follow the phone's own text size; every height that text
// sits in is a minimum rather than a fixed number, so a row grows instead of
// clipping when somebody turns the text up.
//
// The line heights are the design's. Thai stacks its marks above and below the
// line, and Plex's own box is 1.65 em, which is roomier than the design wants;
// centring the line box on the text, as CSS does, is what keeps a tone mark on
// top of a vowel from touching the line above.

val Plex = FontFamily(
    Font(R.font.ibm_plex_sans_thai_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_thai_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_thai_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_thai_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_medium, FontWeight.Medium))

private val Centred = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private val Base = TextStyle(fontFamily = Plex, lineHeightStyle = Centred)

object ReupType {
    /** A tab's name, at the top of it. */
    val title = Base.copy(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    /** The small line above a title: the date, the month. */
    val over = Base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** A section's heading. */
    val label = Base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    /** The name on a task or a money row. */
    val rowTitle = Base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    /** A settings row, which is a place rather than a thing. */
    val body = Base.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** The second line of anything. */
    val support = Base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    /** A clock or an amount at the end of a row. */
    val figure = Base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val heroName = Base.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val heroLine = Base.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val amount = Base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val chip = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val tab = Base.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val button = Base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val control = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val barTitle = Base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    val calmTitle = Base.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
    val calmBody = Base.copy(fontSize = 15.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)
    val banner = Base.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val mono = TextStyle(fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}

// ─── measurements ───────────────────────────────────────────────────────────
//
// One gutter, three radii, one row height. A screen that needs a number this
// list does not have is a screen about to invent one.

object Size {
    val gutter = 20.dp
    val card = 20.dp
    val hero = 24.dp
    val tile = 12.dp
    val row = 64.dp
    val tileRow = 68.dp
    val touch = 44.dp
    val bar = 80.dp
    val fab = 56.dp
    /** Wider than this and a line of text is too long to come back from. */
    val reading = 600.dp
}

@Composable
fun ReupTheme(colors: ReupColors, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.accent,
        secondary = colors.accent,
        onSecondary = colors.bg,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.card,
        onSurface = colors.text,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.dim,
        surfaceContainerLowest = colors.bg,
        surfaceContainerLow = colors.card,
        surfaceContainer = colors.card,
        surfaceContainerHigh = colors.raised,
        surfaceContainerHighest = colors.raised,
        outline = colors.outline,
        outlineVariant = colors.line,
        error = colors.danger,
        onError = colors.bg,
        inverseSurface = colors.text,
        inverseOnSurface = colors.bg,
        inversePrimary = colors.primary,
    )
    val type = Typography(
        titleLarge = ReupType.barTitle,
        titleMedium = ReupType.rowTitle,
        bodyLarge = ReupType.body,
        bodyMedium = ReupType.banner,
        bodySmall = ReupType.support,
        labelLarge = ReupType.button,
        labelMedium = ReupType.chip,
        headlineSmall = ReupType.calmTitle,
    )
    CompositionLocalProvider(LocalReupColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
