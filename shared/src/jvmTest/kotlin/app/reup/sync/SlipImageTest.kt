// shared/src/jvmTest/kotlin/app/reup/sync/SlipImageTest.kt
//
// The picture half of slip reading, on pictures small enough to draw by hand.
//
// What these cannot prove is that black and white reads better than colour —
// that was measured once, on five real slips, and the numbers are in the
// header of SlipImage.kt. What they do prove is that the step does what it
// says on every kind of page: light, dark, and light with a dark band across it.

package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlipImageTest {

    private val ink: Byte = 0
    private val paper: Byte = -1

    /** A [w] × [h] page of one grey, with [paint] laying rectangles on it. */
    private fun page(w: Int, h: Int, grey: Int, paint: (set: (x0: Int, y0: Int, x1: Int, y1: Int, v: Int) -> Unit) -> Unit): ByteArray {
        val px = ByteArray(w * h) { grey.toByte() }
        paint { x0, y0, x1, y1, v ->
            for (y in y0 until y1) for (x in x0 until x1) px[y * w + x] = v.toByte()
        }
        return px
    }

    @Test
    fun `otsu puts the threshold between the page and the ink`() {
        val h = IntArray(256)
        h[230] = 9000
        h[225] = 3000
        h[40] = 500
        h[60] = 200
        val t = otsuThreshold(h)
        assertTrue(t in 60 until 225, "threshold was $t")
    }

    @Test
    fun `a picture of one grey has nothing to split`() {
        val h = IntArray(256)
        h[200] = 100
        assertEquals(0, otsuThreshold(h))
    }

    @Test
    fun `dark writing on a light page stays that way round`() {
        val w = 60
        val px = page(w, 60, 230) { set -> set(10, 20, 40, 30, 40) }
        binarizeForOcr(px, w, 60)
        assertEquals(ink, px[25 * w + 20], "the writing")
        assertEquals(paper, px[5 * w + 5], "the page")
    }

    @Test
    fun `light writing on a dark band comes out black on white`() {
        // A header: 80 rows of dark colour with light lettering, over a light
        // page with dark lettering. Both lots of writing must come out as ink.
        val w = 100
        val h = 200
        val px = page(w, h, 235) { set ->
            set(0, 0, w, 80, 30)
            set(20, 30, 70, 40, 240)
            set(20, 120, 70, 130, 20)
        }
        binarizeForOcr(px, w, h)
        assertEquals(ink, px[35 * w + 40], "light writing in the band")
        assertEquals(paper, px[10 * w + 5], "the band itself")
        assertEquals(ink, px[125 * w + 40], "dark writing on the page")
        assertEquals(paper, px[180 * w + 5], "the page")
    }

    @Test
    fun `one line is split by its own threshold, not the page's`() {
        // A memo's line in grey ink a whole page's threshold would have thinned:
        // paper at 250, strokes at 120, their edges at 170.
        val w = 40
        val h = 12
        val px = page(w, h, 250) { set ->
            set(5, 3, 35, 9, 170)
            set(6, 4, 34, 8, 120)
        }
        binarizeLine(px, w, h)
        assertEquals(ink, px[6 * w + 20], "the stroke")
        assertEquals(paper, px[1 * w + 1], "the paper")
        // The edge goes with the ink here: on this strip 170 is nearer the
        // strokes than the paper. On a whole slip it need not be.
        assertEquals(ink, px[3 * w + 20], "the edge")
    }

    @Test
    fun `a line of light writing on a dark band comes out black on white`() {
        val w = 40
        val h = 12
        val px = page(w, h, 40) { set -> set(6, 4, 34, 8, 230) }
        binarizeLine(px, w, h)
        assertEquals(ink, px[6 * w + 20], "the writing")
        assertEquals(paper, px[1 * w + 1], "the band")
    }

    @Test
    fun `a thin rule across the page is not a band`() {
        // One dark row, edge to edge: a divider. Flipping it would turn the
        // page around it black for that row, which is harmless, and flipping
        // the rows around it would not be. It stays ink.
        val w = 100
        val px = page(w, 100, 235) { set ->
            set(0, 50, w, 51, 30)
            set(10, 20, 40, 30, 30)
        }
        binarizeForOcr(px, w, 100)
        assertEquals(ink, px[50 * w + 50], "the rule")
        assertEquals(paper, px[49 * w + 50], "the row above it")
    }

    @Test
    fun `dark mode is read as if it were light`() {
        val w = 80
        val px = page(w, 120, 18) { set -> set(10, 40, 60, 52, 235) }
        binarizeForOcr(px, w, 120)
        assertEquals(ink, px[45 * w + 20], "the writing")
        assertEquals(paper, px[100 * w + 70], "the page")
    }

    @Test
    fun `a transparent corner is paper, not ink`() {
        assertEquals(255, lumaOf(0x00000000))
        assertEquals(0, lumaOf(0xFF000000.toInt()))
        assertEquals(255, lumaOf(0xFFFFFFFF.toInt()))
        assertEquals(76, lumaOf(0xFFFF0000.toInt()), "red is dark, as far as luma goes")
    }

    @Test
    fun `screenshots are read at full size and photographs are not`() {
        assertEquals(1, slipSampleSize(1260, 2750), "a tall screenshot — the one the old rule halved")
        assertEquals(1, slipSampleSize(1080, 2400))
        assertEquals(1, slipSampleSize(1440, 3200))
        assertEquals(2, slipSampleSize(4000, 3000), "a phone camera photo")
        assertEquals(2, slipSampleSize(2560, 1600), "a tablet in landscape")
        assertEquals(4, slipSampleSize(8000, 6000))
    }
}
