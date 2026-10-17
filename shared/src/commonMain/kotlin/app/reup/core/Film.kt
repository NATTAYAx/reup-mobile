package app.reup.core

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ─── Film.kt — a few seconds of a clip or a GIF, as a widget can play them ───
//
// WHY A FLIPBOOK
//
// A widget cannot play a video, and cannot play a GIF either: the home screen
// draws it from a short list of views, and none of them plays anything. One of
// them, ViewFlipper, shows its children one at a time on a timer that the home
// screen runs itself. Given one child per frame, it is a flipbook. Its timer
// stops whenever the home screen is not on show - another app open, the screen
// off - so a film costs nothing then.
//
// TWENTY-FOUR A SECOND, OR TWELVE
//
// The flipper turns a page at the film's own tick, set every time the widget
// is drawn. A smooth film ticks every 42 ms, twenty-four a second, the rate
// anime is timed at: characters are mostly drawn on twos and threes within it,
// but pans, light and effects move at the full rate, and those are what twelve
// a second made stutter. A long film ticks every 83 ms, twelve a second, and
// runs twice as long on the same pictures. A GIF is laid on the smooth clock:
// each tick shows whichever of its frames would be showing at that moment.
//
// WHAT LIMITS IT
//
// Every picture a widget is drawn with is copied to the system and on to the
// home screen, and the system refuses a widget whose pictures add up to more
// than a screen and a half (six bytes for every pixel of the screen). That is
// the whole budget, and [filmFit] spends it: as many frames as the film has,
// each as large as what is left allows. When the widget is too big for that
// to look like anything, frames are dropped instead and the ones kept are
// held longer. A tick that shows a picture again costs nothing, because the
// same picture handed over twice is sent once.

/** How a clip becomes a film: how often a page turns, and how long it runs before going round. */
enum class FilmPace(val tickMs: Long, val loopMs: Long) {
    /** Twenty-four pages a second, for two seconds. */
    SMOOTH(42L, 2_000L),

    /** Twelve a second, for four: as many pictures, half as smooth, twice as long. */
    LONG(83L, 4_000L),
}

/** The tick of a film made before there was a choice, which was twelve a second. */
const val FILM_TICK_OLD_MS: Long = 83L

/** The longest a film runs before it goes round again. */
const val FILM_MAX_MS: Long = 5_000L

/** The most different pictures a film keeps on the phone. */
const val FILM_MAX_PICTURES: Int = 48

/**
 * Below this share of the widget's own size a picture is stretched into mush,
 * and [filmFit] keeps fewer of them instead of shrinking them further.
 */
const val FILM_MIN_SCALE: Double = 0.5

/** How far from a clip's end its frames are read; the very last one is not always there to be read. */
private const val CLIP_END_MS: Long = 100L

// ── a clip ───────────────────────────────────────────────────────────────────

/** One picture of a loop: a frame read from the clip, faded toward another near the end. */
data class LoopFrame(
    /** An index into [ClipLoop.readAt]. */
    val frame: Int,
    /** Another index into [ClipLoop.readAt], or -1. */
    val toward: Int = -1,
    /** How far toward it, from 0 to 1. */
    val amount: Float = 0f,
)

/** A stretch of a clip: the frames to read from it, and the loop they make. */
data class ClipLoop(
    /** Where each frame is read, in ms from the start of the clip, in order. */
    val readAt: List<Long>,
    /** The loop, one per tick. Fewer than two is a picture, not a film. */
    val frames: List<LoopFrame>,
)

/**
 * [pace]'s length of a clip from [startMs], made into a loop on its tick.
 *
 * WHY IT FADES
 *
 * A stretch cut from a clip ends somewhere other than where it began, and
 * played round and round it jumps once a loop. So the stretch read is half a
 * second longer than the loop, and over the loop's last half second the picture
 * fades into the half second just before the loop starts. By the last tick the
 * picture is nearly the one before the first, and the loop goes round without
 * a seam.
 *
 * A clip too short for that loops what it has: from a second up, three quarters
 * of it with a fade over the rest; under a second, all of it, with no fade.
 * [startMs] is moved back as far as it takes for the stretch to fit.
 */
fun clipLoop(durationMs: Long, startMs: Long, pace: FilmPace = FilmPace.SMOOTH, fadeMs: Long = 500L): ClipLoop {
    val usable = durationMs - CLIP_END_MS
    if (usable <= 0L) return ClipLoop(emptyList(), emptyList())
    val tick = pace.tickMs
    val loopMs = pace.loopMs
    var loop = loopMs
    var fade = fadeMs
    if (usable < loop + fade) {
        if (usable >= 1_000L) {
            fade = min(fadeMs, usable / 4)
            loop = usable - fade
        } else {
            fade = 0L
            loop = usable
        }
    }
    val n = max(1, (loop.toDouble() / tick).roundToInt())
    val f = (fade.toDouble() / tick).roundToInt().coerceIn(0, n / 2)
    val span = (n + f - 1) * tick
    val start = startMs.coerceIn(0L, max(0L, usable - span))
    val readAt = List(n + f) { start + it * tick }
    val frames = List(n) { k ->
        if (f > 0 && k >= n - f) {
            LoopFrame(frame = k + f, toward = k + f - n, amount = (k - (n - f) + 1).toFloat() / (f + 1))
        } else {
            LoopFrame(frame = k + f)
        }
    }
    return ClipLoop(readAt, frames)
}

// ── a GIF ────────────────────────────────────────────────────────────────────

/**
 * A GIF laid on the flipbook's clock: for each tick, which of its frames shows.
 *
 * Delays are read the way browsers read them: a delay of 0 or 10 ms, which is
 * a GIF not saying, is 100 ms. A GIF shorter than a second goes round as many
 * times as fill one, so a two-frame blink is not over before the flipper's
 * first page; the ticks are stretched a hair so the rounds end on a tick. One
 * longer than [maxMs] is cut there.
 *
 * Empty for a GIF of one frame, which is a picture.
 */
fun gifTicks(delaysMs: List<Int>, maxMs: Long = FILM_MAX_MS, tickMs: Long = FilmPace.SMOOTH.tickMs): List<Int> {
    if (delaysMs.size < 2) return emptyList()
    val delays = delaysMs.map { if (it <= 10) 100 else it }
    val round = delays.sum().toLong()
    val starts = LongArray(delays.size)
    var acc = 0L
    for (i in delays.indices) {
        starts[i] = acc
        acc += delays[i]
    }
    val rounds = if (round >= 1_000L) 1L else ceil(1_000.0 / round).toLong()
    val span = round * rounds
    val ticks: Int
    val step: Double
    if (span <= maxMs) {
        ticks = max(2, (span.toDouble() / tickMs).roundToInt())
        step = span.toDouble() / ticks
    } else {
        ticks = max(2, (maxMs / tickMs).toInt())
        step = tickMs.toDouble()
    }
    return List(ticks) { k ->
        // A hair past the tick, so one landing exactly on a frame's start is that frame.
        val t = (k * step + 1e-6) % round
        var i = delays.size - 1
        while (i > 0 && starts[i] > t) i--
        i
    }
}

// ── fewer pictures ───────────────────────────────────────────────────────────

/**
 * At most [keep] different pictures, for a film that has more: an even spread
 * of them, each held until the next one kept. Every tick stays where it is in
 * time and shows the kept picture before it in place of its own, so a film
 * with half its pictures plays at six a second rather than twice as fast.
 *
 * @param ticks for each tick, its picture
 * @return the same ticks, each one pointing at a kept picture
 */
fun thinOut(ticks: List<Int>, keep: Int): List<Int> {
    val order = ticks.distinct()
    if (order.size <= keep) return ticks
    val k = max(1, keep)
    val kept = BooleanArray(order.size)
    for (i in 0 until k) kept[(i.toLong() * order.size / k).toInt()] = true
    val place = HashMap<Int, Int>()
    order.forEachIndexed { p, picture -> place[picture] = p }
    val held = IntArray(order.size)
    var last = 0
    for (p in order.indices) {
        if (kept[p]) last = p
        held[p] = last
    }
    return ticks.map { order[held[place.getValue(it)]] }
}

// ── what a widget can afford ─────────────────────────────────────────────────

/** What a widget can afford of a film. */
data class FilmFit(
    /** The size every picture is cut to, in pixels. */
    val width: Int,
    val height: Int,
    /** How many different pictures. Fewer than the film has means [thinOut]; under two, no film at all. */
    val keep: Int,
)

/**
 * The shares of what the system allows that a film is cut to, tried in turn:
 * nearly all of it first, and a good deal less if the system refuses that.
 * The system measures the screen it started with, and the app sees the screen
 * less its bars, which is smaller - unless the screen's resolution has been
 * raised since the phone started, which is what the second try is for. A
 * refusal costs nothing but the try; a still picture is the last resort.
 */
val FILM_BUDGET_SHARES: List<Double> = listOf(0.95, 0.6)

/**
 * The bytes a widget's pictures may add up to on a screen of [widthPx] by
 * [heightPx], at [share] of what the system allows: a screen and a half at
 * four bytes a pixel. Nothing else is drawn with a picture while a film plays.
 */
fun filmBudget(widthPx: Int, heightPx: Int, share: Double): Long =
    (6.0 * max(0, widthPx) * max(0, heightPx) * share).toLong()

/**
 * The most of a film of [pictures] that a widget of [widthPx] by [heightPx]
 * pixels can afford in [budget] bytes, at [bytesPerPixel].
 *
 * Every picture is cut to one size, the widget's shape: all of them if that
 * leaves each at least [FILM_MIN_SCALE] of the widget, as large as the budget
 * allows. Never larger than the widget, nor than the part of the film's own
 * frames ([sourceW] by [sourceH]) that covers it, which would spend the budget
 * on enlarging. When all of them would be smaller than that, fewer are kept.
 */
fun filmFit(
    widthPx: Int,
    heightPx: Int,
    pictures: Int,
    sourceW: Int,
    sourceH: Int,
    budget: Long,
    bytesPerPixel: Int = 2,
): FilmFit {
    if (widthPx <= 0 || heightPx <= 0 || sourceW <= 0 || sourceH <= 0 || pictures < 2 || budget <= 0L) {
        return FilmFit(0, 0, 0)
    }
    val area = widthPx.toDouble() * heightPx
    // The source, cut to the widget's shape, is this share of the widget's size.
    val cover = min(1.0, min(sourceW.toDouble() / widthPx, sourceH.toDouble() / heightPx))
    val least = min(FILM_MIN_SCALE, cover)
    val most = floor(budget / (area * least * least * bytesPerPixel)).toLong()
    val keep = min(pictures.toLong(), most).toInt()
    if (keep < 2) return FilmFit(0, 0, max(0, keep))
    val scale = min(cover, sqrt(budget / (keep * area * bytesPerPixel)))
    val w = max(1, floor(widthPx * scale).toInt())
    val h = max(1, floor(heightPx * scale).toInt())
    return FilmFit(w, h, keep)
}
