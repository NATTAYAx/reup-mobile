package app.reup.core

import kotlin.math.pow

// ─── Palette.kt — the colours, as data ──────────────────────────────────────
//
// WHY THIS FILE EXISTS
//
// Until now the phone's colours were sixteen `Color.parseColor("#…")` constants
// sitting in Ui.kt. That is the bad kind of hardcoding by the definition this
// project already settled on: a value that should have come from somewhere,
// baked into code. Not because a violet is wrong, but because it was the only
// violet possible, and nobody could choose otherwise without editing a file and
// rebuilding an APK.
//
// The desktop has had a theme picker for months. The phone had one colour.
//
// WHY IT IS HERE AND NOT IN Ui.kt
//
// Two reasons. A palette is data, and data that decides what a screen looks
// like should be checkable — see [contrast] below, which is the whole point of
// the file. And commonMain is the only place both platforms can eventually read
// from, on the day the desktop stops keeping its theme in localStorage.
//
// WHAT IS DELIBERATELY NOT HERE
//
// Where the choice is stored. That is a platform question with a platform
// answer, and it is answered in Ui.load.

/** "#7C3AED" to an opaque ARGB int, without needing Android to do it. */
fun hex(rgb: String): Int {
    val c = rgb.removePrefix("#")
    require(c.length == 6) { "expected #RRGGBB, got $rgb" }
    return (0xFF shl 24) or c.toInt(16)
}

/**
 * Every colour a screen is allowed to use.
 *
 * There is no "some other blue" here on purpose. A screen that needs a colour
 * this class does not have is a screen that is about to invent one, which is
 * how five screens ended up with five greys.
 */
data class Palette(
    val id: String,
    val name: String,

    /** The page. */
    val bg: Int,
    /** A panel on the page. */
    val card: Int,
    /** A pressable thing on a panel, so it reads as an object. */
    val raised: Int,
    /** Somewhere to type. Darker than the card, because an input is a hole. */
    val field: Int,
    /** Hairlines and edges. */
    val line: Int,

    /** The one thing a screen is for. */
    val primary: Int,
    /** The same idea, lighter, for text and edges that must stay legible. */
    val accent: Int,

    val text: Int,
    val dim: Int,
    val faint: Int,

    /**
     * What is written on top of [primary].
     *
     * Not always white, and this is the one place a theme cannot be chosen by
     * eye. White on the orange and the green failed the readability test below
     * at 3.1 and 3.5 — legible enough on a desk at night, not legible outdoors,
     * which is where money gets written down. Those themes take dark ink
     * instead, which is the same answer Material gives for a light primary.
     */
    val onPrimary: Int = hex("#FFFFFF"),

    // The three that mean something rather than decorate. They keep their own
    // hues across themes: money coming in is green in every theme, because a
    // person learns that once and should not have to learn it again after
    // changing a colour they thought was only decoration.
    val positive: Int = hex("#8ED0A8"),
    val warn: Int = hex("#E0B457"),
    val danger: Int = hex("#E06C75"),
)

/**
 * The choices.
 *
 * Fewer than the desktop offers, and that is the right shape: the desktop can
 * pull a palette out of a wallpaper video, which is a thing you do while
 * sitting down. A phone wants a short list you can get through with a thumb.
 *
 * The first is the default and matches the desktop's, so the two machines look
 * like one app out of the box.
 */
val THEMES: List<Palette> = listOf(
    Palette(
        id = "violet",
        name = "ม่วง",
        bg = hex("#030712"), card = hex("#111827"), raised = hex("#161F2E"),
        field = hex("#0A101C"), line = hex("#1F2937"),
        primary = hex("#7C3AED"), accent = hex("#A78BFA"),
        text = hex("#E8E8EA"), dim = hex("#9CA3AF"), faint = hex("#6B7280"),
    ),
    Palette(
        id = "ocean",
        name = "ทะเลลึก",
        bg = hex("#020814"), card = hex("#0E1A2B"), raised = hex("#132234"),
        field = hex("#07101E"), line = hex("#1E3048"),
        primary = hex("#2563EB"), accent = hex("#93C5FD"),
        text = hex("#E6EDF5"), dim = hex("#9BB0C6"), faint = hex("#6B8199"),
    ),
    Palette(
        id = "forest",
        name = "ป่าดึก",
        bg = hex("#03100C"), card = hex("#0E2018"), raised = hex("#132A20"),
        field = hex("#071711"), line = hex("#1E3A2C"),
        primary = hex("#0F9D6E"), accent = hex("#6EE7B7"),
        text = hex("#E6F0EA"), dim = hex("#9CB8AA"), faint = hex("#6C8A7B"),
        onPrimary = hex("#000000"),
        // The one theme where the ordinary green would disappear into the
        // furniture. Money coming in has to stay findable, so it moves.
        positive = hex("#7DD3FC"),
    ),
    Palette(
        id = "ember",
        name = "ถ่านไฟ",
        bg = hex("#100A06"), card = hex("#221610"), raised = hex("#2B1D15"),
        field = hex("#170F0A"), line = hex("#3A281D"),
        primary = hex("#EA6E1F"), accent = hex("#FDBA74"),
        text = hex("#F2E9E2"), dim = hex("#C0A996"), faint = hex("#8E7666"),
        onPrimary = hex("#000000"),
        // Amber next to orange says nothing at all.
        warn = hex("#FDE047"),
    ),
    Palette(
        id = "rose",
        name = "ชมพูหม่น",
        bg = hex("#12060A"), card = hex("#241119"), raised = hex("#2D1720"),
        field = hex("#190A10"), line = hex("#3B1F2B"),
        primary = hex("#D83F63"), accent = hex("#FDA4AF"),
        text = hex("#F4E8EC"), dim = hex("#C6A3AF"), faint = hex("#94707D"),
        onPrimary = hex("#000000"),
    ),
    Palette(
        // For the days when a colour that wants something from you is too much.
        // Nothing on the screen is bright, and the only accent left is a grey
        // one — which is a real preference and not a joke option.
        id = "quiet",
        name = "ไม่มีสี",
        bg = hex("#08090B"), card = hex("#15171A"), raised = hex("#1B1E22"),
        field = hex("#0E1013"), line = hex("#282C31"),
        primary = hex("#4B5563"), accent = hex("#C3C8CF"),
        text = hex("#E5E7EB"), dim = hex("#A1A6AD"), faint = hex("#71767D"),
    ),
)

val DEFAULT_PALETTE: Palette = THEMES[0]

/** Unknown ids are a saved preference from a version that had more themes. */
fun paletteOf(id: String?): Palette =
    THEMES.firstOrNull { it.id == id } ?: DEFAULT_PALETTE

// ─── the part that makes this worth having as data ──────────────────────────

/** One channel of sRGB, straightened out. */
private fun channel(value: Int): Double {
    val v = value / 255.0
    return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
}

/** How bright a colour actually looks, which is not how bright its number is. */
fun luminance(color: Int): Double {
    val r = channel((color shr 16) and 0xFF)
    val g = channel((color shr 8) and 0xFF)
    val b = channel(color and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * The ratio between two colours, 1 (identical) to 21 (black on white).
 *
 * This is the WCAG definition, and it exists here so that a theme cannot be
 * added with text nobody can read. Picking colours by eye at three in the
 * morning on a bright screen is exactly how that ships, and the person who
 * finds out is the one holding the phone outdoors.
 */
fun contrast(a: Int, b: Int): Double {
    val x = luminance(a)
    val y = luminance(b)
    val hi = if (x > y) x else y
    val lo = if (x > y) y else x
    return (hi + 0.05) / (lo + 0.05)
}