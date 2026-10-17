package app.reup.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// ─── WidgetInk.kt — what colour the words are, over a picture ────────────────
//
// A picture the person picked goes behind the widget's words, and pictures go
// from a night sky to a pastel sticker sheet. White words on the second are as
// unreadable as dark words on the first, and a colour on top of either is worse
// than both - that is what happened to the purple time on a pink picture.
//
// WHAT DECIDES IT
//
// Not the words' colour on its own: a picture can be bright on one side and
// dark on the other, and a film is bright in one frame and dark in the next, so
// any one colour of word loses somewhere. What makes words readable over all of
// it is a veil between the two - dark under white words, light under dark ones
// - and the thing worth working out is how thick it has to be.
//
// So the picture is measured once, when it is kept (for a film, every frame of
// it): how bright its brightest tenth is, and how dark its darkest tenth. White
// words need a dark veil just thick enough that the brightest tenth, seen
// through it, still leaves them at [INK_TARGET] to one. Dark words need a light
// veil thick enough that the darkest tenth does the same. Thicker than that only
// hides the picture; thinner and some of the words go.
//
// WHICH OF THE TWO
//
// White words, the way the desktop's board writes them over its own wallpaper,
// unless the picture is so bright that white would need a veil past [LIGHT_LIMIT]
// and dark words would need less. A pastel picture under a dark veil reads as
// grey; under a light one it is still the picture. The person can also choose.
//
// COLOURS ON TOP
//
// The theme's colour and the warning amber are moved toward the words' own -
// lighter over a dark veil, darker over a light one - until they clear
// [ACCENT_TARGET] against the worst the veil leaves. A lower bar than the words
// get, because they are never the only thing saying something: the label beside
// them says it in words, and the time beside the warning is written in full.
//
// THE NUMBERS
//
// Relative luminance and contrast as WCAG defines them, from Palette.kt. The veil is blended
// over the picture in sRGB, which is how Android composes views, so the
// arithmetic is done on encoded values and only compared in luminance.

/** How bright a picture is where the words go: the darkest tenth, the average, the brightest tenth. Luminance, 0 to 1. */
data class PictureLight(val dark: Double, val mid: Double, val bright: Double)

/** What the person asked for in Settings: let the picture decide, or always one or the other. */
enum class InkMode { AUTO, LIGHT, DARK }

/**
 * The veil and the words over a picture.
 *
 * [darkText] says which way round it is: dark words over a light veil, or
 * white words over a dark one. [veil] is how opaque the veil is, 0 to 1.
 */
data class WidgetInk(
    val darkText: Boolean,
    val veil: Double,
    /** 0xRRGGBB for the words. */
    val text: Int,
    /** The theme's colour, moved until it reads here. */
    val accent: Int,
    /** The warning amber, likewise. */
    val warn: Int,
    /** The worst background the veil leaves, in luminance - what the colours above were measured against. */
    val worst: Double,
)

/** What the words have to clear against the worst of the picture. */
const val INK_TARGET: Double = 4.5

/** What the colours on top of them have to clear. */
const val ACCENT_TARGET: Double = 3.0

/** White words take the veil they need up to here; past it, dark words are tried. */
const val LIGHT_LIMIT: Double = 0.5

/** The thinnest veil under white words: a little depth even on a dark picture, as the board's mist has. */
const val VEIL_MIN_LIGHT: Double = 0.22

/** The thinnest under dark words. */
const val VEIL_MIN_DARK: Double = 0.18

/** Never thicker than this: past it the picture is not there any more. */
const val VEIL_MAX: Double = 0.85

/** Dark words: near-black, a touch warm, the same on every picture. */
const val INK_DARK: Int = 0x1E1B26

const val INK_LIGHT: Int = 0xFFFFFF

/** The amber every screen warns in. */
const val WARN_AMBER: Int = 0xFBBF24

/** sRGB-encoded 0..1 to linear. */
fun linear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

/** Linear to sRGB-encoded 0..1. */
fun encoded(l: Double): Double = if (l <= 0.0031308) 12.92 * l else 1.055 * l.pow(1.0 / 2.4) - 0.055

/** WCAG contrast between two luminances, where Palette's [contrast] takes two colours. */
fun lumContrast(a: Double, b: Double): Double = (max(a, b) + 0.05) / (min(a, b) + 0.05)

/** The black veil white words need over [bright], clamped. */
fun lightVeil(bright: Double): Double {
    val limit = encoded(1.05 / INK_TARGET - 0.05)
    val v = encoded(bright.coerceIn(0.0, 1.0))
    val need = if (v <= limit) 0.0 else 1.0 - limit / v
    return need.coerceIn(VEIL_MIN_LIGHT, VEIL_MAX)
}

/** The white veil dark words need over [dark], clamped. */
fun darkVeil(dark: Double): Double {
    val floor = encoded(INK_TARGET * (luminance(INK_DARK) + 0.05) - 0.05)
    val v = encoded(dark.coerceIn(0.0, 1.0))
    val need = if (v >= floor) 0.0 else (floor - v) / (1.0 - v)
    return need.coerceIn(VEIL_MIN_DARK, VEIL_MAX)
}

/** [c] moved [t] of the way toward [to], channel by channel. */
fun mixRgb(c: Int, to: Int, t: Double): Int {
    fun ch(x: Int, s: Int) = (x shr s) and 0xFF
    fun lerp(a: Int, b: Int) = (a + (b - a) * t + 0.5).toInt().coerceIn(0, 255)
    return (lerp(ch(c, 16), ch(to, 16)) shl 16) or (lerp(ch(c, 8), ch(to, 8)) shl 8) or lerp(ch(c, 0), ch(to, 0))
}

/** The least move of [c] toward [to] that clears [target] against [background]. */
fun readableOn(c: Int, to: Int, background: Double, target: Double = ACCENT_TARGET): Int {
    for (k in 0..100) {
        val m = mixRgb(c, to, k / 100.0)
        if (lumContrast(luminance(m), background) >= target) return m
    }
    return to
}

/**
 * The veil and the words for a picture measured as [light], or null for no
 * picture at all - the plain card, which is the theme's and already readable.
 */
fun widgetInk(light: PictureLight?, mode: InkMode, accent: Int): WidgetInk? {
    if (light == null) return null
    val forLight = lightVeil(light.bright)
    val forDark = darkVeil(light.dark)
    val dark = when (mode) {
        InkMode.LIGHT -> false
        InkMode.DARK -> true
        InkMode.AUTO -> forLight > LIGHT_LIMIT && forDark < forLight
    }
    return if (dark) {
        val v = encoded(light.dark.coerceIn(0.0, 1.0))
        val worst = linear(v + forDark * (1.0 - v))
        WidgetInk(true, forDark, INK_DARK, readableOn(accent, 0x000000, worst), readableOn(WARN_AMBER, 0x000000, worst), worst)
    } else {
        val v = encoded(light.bright.coerceIn(0.0, 1.0))
        val worst = linear(v * (1.0 - forLight))
        WidgetInk(false, forLight, INK_LIGHT, readableOn(accent, 0xFFFFFF, worst), readableOn(WARN_AMBER, 0xFFFFFF, worst), worst)
    }
}

/**
 * A picture's light from a histogram of its luminance in equal steps from 0
 * to 1: where its darkest tenth ends, the mean, and where its brightest tenth
 * begins.
 */
fun pictureLight(histogram: IntArray): PictureLight? {
    val n = histogram.sum()
    if (n <= 0) return null
    val bins = histogram.size
    val tenth = n / 10.0
    // From each end, the first step that has taken in a tenth of the picture:
    // the darkest tenth is at or below the one, the brightest at or above the other.
    var seen = 0.0
    var dark = 1.0
    for (i in 0 until bins) {
        seen += histogram[i]
        if (seen >= tenth) { dark = (i + 0.5) / bins; break }
    }
    seen = 0.0
    var bright = 0.0
    for (i in bins - 1 downTo 0) {
        seen += histogram[i]
        if (seen >= tenth) { bright = (i + 0.5) / bins; break }
    }
    var sum = 0.0
    for (i in 0 until bins) sum += histogram[i] * ((i + 0.5) / bins)
    return PictureLight(dark = dark, mid = sum / n, bright = bright)
}

/** "dark,mid,bright", as kept in the widget's settings. */
fun PictureLight.saved(): String = "$dark,$mid,$bright"

/** Read back what [saved] wrote, or null for anything else. */
fun pictureLightOf(text: String?): PictureLight? {
    val p = text?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
    if (p.size != 3 || p.any { it < 0.0 || it > 1.0 }) return null
    return PictureLight(p[0], p[1], p[2])
}
