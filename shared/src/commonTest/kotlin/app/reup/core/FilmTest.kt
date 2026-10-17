package app.reup.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── FilmTest ────────────────────────────────────────────────────────────────
//
// The arithmetic of a film: which frames a clip is read at and how its loop is
// closed, how a GIF lands on twelve ticks a second, and how much of either a
// widget can afford. The pictures themselves are Android's; see WidgetFilm.

class FilmTest {

    // ── a clip ──────────────────────────────────────────────────────────────

    private val tick = FilmPace.SMOOTH.tickMs

    @Test
    fun `two smooth seconds of a long clip, read half a second longer than the loop`() {
        val loop = clipLoop(durationMs = 60_000L, startMs = 2_000L)
        assertEquals(48 + 12, loop.readAt.size)
        assertEquals(2_000L, loop.readAt.first())
        assertEquals(2_000L + 59 * tick, loop.readAt.last())
        assertEquals(48, loop.frames.size)
        // The loop starts after the stretch it will fade back into.
        assertEquals(LoopFrame(frame = 12), loop.frames.first())
        assertEquals(LoopFrame(frame = 47), loop.frames[35])
    }

    @Test
    fun `the last half second fades into the frames just before the first`() {
        val frames = clipLoop(durationMs = 60_000L, startMs = 0L).frames
        val fading = frames.drop(36)
        assertEquals((0..11).toList(), fading.map { it.toward })
        assertEquals((48..59).toList(), fading.map { it.frame })
        // Further each tick, and never all the way: the next tick is frame 12,
        // which comes straight after the last one faded toward.
        for (i in 1 until fading.size) assertTrue(fading[i].amount > fading[i - 1].amount)
        assertTrue(fading.first().amount > 0f && fading.last().amount < 1f)
    }

    @Test
    fun `a long film is four seconds at twelve a second, on as many pictures`() {
        val loop = clipLoop(durationMs = 60_000L, startMs = 2_000L, pace = FilmPace.LONG)
        assertEquals(48, loop.frames.size)
        assertEquals(48 + 6, loop.readAt.size)
        assertEquals(2_000L + 53 * FilmPace.LONG.tickMs, loop.readAt.last())
    }

    @Test
    fun `a start too near the end is moved back until the stretch fits`() {
        val loop = clipLoop(durationMs = 4_000L, startMs = 3_900L)
        assertEquals(60, loop.readAt.size)
        assertTrue(loop.readAt.last() <= 4_000L - 100L, "last read at ${loop.readAt.last()}")
        assertEquals(4_000L - 100L - 59 * tick, loop.readAt.first())
    }

    @Test
    fun `a two second clip loops three quarters of itself and fades over the rest`() {
        val loop = clipLoop(durationMs = 2_000L, startMs = 0L)
        assertEquals(34, loop.frames.size)
        assertEquals(34 + 11, loop.readAt.size)
        assertTrue(loop.readAt.last() <= 1_900L)
        assertEquals(11, loop.frames.count { it.toward >= 0 })
    }

    @Test
    fun `under a second there is no fade, and a blink is a picture`() {
        val short = clipLoop(durationMs = 600L, startMs = 300L)
        assertEquals(12, short.frames.size)
        // Half a second of it from 300 ms would run past the end.
        assertEquals(38L, short.readAt.first())
        assertTrue(short.frames.all { it.toward == -1 })
        assertEquals(1, clipLoop(durationMs = 150L, startMs = 0L).frames.size)
        assertEquals(0, clipLoop(durationMs = 0L, startMs = 0L).frames.size)
    }

    // ── a GIF ───────────────────────────────────────────────────────────────

    @Test
    fun `a GIF at ten a second is laid on twenty-four ticks, every frame on two or three`() {
        val ticks = gifTicks(List(30) { 100 })
        // Three seconds of GIF; 71 ticks, each a hair over 42 ms.
        assertEquals(71, ticks.size)
        assertEquals(listOf(0, 0, 0, 1, 1, 2, 2), ticks.take(7))
        assertEquals(29, ticks.last())
        assertEquals(30, ticks.distinct().size)
    }

    @Test
    fun `a delay of nothing is a tenth of a second, the way browsers play it`() {
        assertEquals(gifTicks(List(12) { 100 }), gifTicks(List(12) { 0 }))
        assertEquals(gifTicks(List(12) { 100 }), gifTicks(List(12) { 10 }))
    }

    @Test
    fun `a short GIF goes round until it fills a second`() {
        val ticks = gifTicks(listOf(150, 150))
        // 300 ms a round, four rounds: 1.2 s, 29 ticks.
        assertEquals(29, ticks.size)
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1, 1, 0), ticks.take(9))
        assertEquals(1, ticks.last())
    }

    @Test
    fun `a long GIF is cut at five seconds`() {
        val ticks = gifTicks(List(100) { 100 })
        assertEquals(119, ticks.size)
        // The last tick, 118 of them in at 4,956 ms, is in the 50th frame.
        assertEquals(49, ticks.last())
    }

    @Test
    fun `one frame is a picture`() {
        assertEquals(emptyList(), gifTicks(listOf(100)))
        assertEquals(emptyList(), gifTicks(emptyList()))
    }

    // ── fewer pictures ──────────────────────────────────────────────────────

    @Test
    fun `thinning keeps every tick in its place and holds the picture before it`() {
        val ticks = List(36) { it }
        val half = thinOut(ticks, 18)
        assertEquals(36, half.size)
        assertEquals(18, half.distinct().size)
        assertEquals(listOf(0, 0, 2, 2, 4, 4), half.take(6))
        assertEquals(ticks, thinOut(ticks, 36))
        assertEquals(ticks, thinOut(ticks, 50))
    }

    @Test
    fun `a GIF's repeats survive thinning`() {
        val ticks = listOf(0, 0, 1, 1, 2, 2, 3, 3, 0, 0, 1, 1, 2, 2, 3, 3)
        assertEquals(listOf(0, 0, 0, 0, 2, 2, 2, 2, 0, 0, 0, 0, 2, 2, 2, 2), thinOut(ticks, 2))
    }

    // ── what a widget can afford ────────────────────────────────────────────

    private val budget = filmBudget(1260, 2720, FILM_BUDGET_SHARES.first())

    @Test
    fun `four by two keeps every frame of a smooth film, at over half its size`() {
        val fit = filmFit(1140, 600, pictures = 48, sourceW = 1280, sourceH = 720, budget = budget)
        assertEquals(48, fit.keep)
        assertTrue(fit.width >= 570 && fit.width <= 1140, "width ${fit.width}")
        assertTrue(48L * fit.width * fit.height * 2 <= budget)
    }

    @Test
    fun `four by four keeps fewer frames rather than blur them`() {
        val fit = filmFit(1140, 1200, pictures = 48, sourceW = 1280, sourceH = 720, budget = budget)
        assertTrue(fit.keep in 2 until 48, "keep ${fit.keep}")
        assertTrue(fit.width >= 1140 / 2 - 1, "width ${fit.width}")
        assertTrue(fit.keep.toLong() * fit.width * fit.height * 2 <= budget)
    }

    @Test
    fun `never larger than the frames themselves`() {
        // A GIF 320 by 180, cut to four by two's shape, covers about a quarter of it.
        val fit = filmFit(1140, 600, pictures = 20, sourceW = 320, sourceH = 180, budget = budget)
        assertEquals(20, fit.keep)
        assertTrue(fit.width <= 320 && fit.height <= 180, "${fit.width} by ${fit.height}")
    }

    @Test
    fun `a budget too small for two pictures is no film`() {
        assertTrue(filmFit(1140, 600, pictures = 48, sourceW = 800, sourceH = 450, budget = 300_000L).keep < 2)
        assertEquals(0, filmFit(1140, 600, pictures = 1, sourceW = 800, sourceH = 450, budget = budget).keep)
    }
}
