package app.reup.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── WidgetInkTest ───────────────────────────────────────────────────────────
//
// The words on a picture, checked by what they promise rather than by the
// numbers that came out last time: whatever the picture, the words clear 4.5
// to one against the worst of it, and the colours on top clear 3 - unless the
// veil had to stop at its limit, which is said out loud below.

class WidgetInkTest {

    private val violet = 0xA78BFA

    private fun textContrast(ink: WidgetInk) = lumContrast(luminance(ink.text), ink.worst)

    private fun pictures(): List<PictureLight> = listOf(
        PictureLight(0.0, 0.0, 0.0),
        PictureLight(0.01, 0.05, 0.12),
        // A pink photo, light all over with a darker corner.
        PictureLight(0.079, 0.446, 0.739),
        PictureLight(0.058, 0.293, 0.675),
        // A pastel sticker sheet.
        PictureLight(0.55, 0.8, 0.97),
        PictureLight(1.0, 1.0, 1.0),
        PictureLight(0.0, 0.5, 1.0),
    )

    @Test
    fun `the words clear four and a half to one on any picture, either way round`() {
        for (p in pictures()) {
            for (mode in InkMode.entries) {
                val ink = widgetInk(p, mode, violet)!!
                if (ink.veil >= VEIL_MAX) continue
                assertTrue(textContrast(ink) >= INK_TARGET - 0.01, "$p $mode: " + textContrast(ink))
                assertTrue(lumContrast(luminance(ink.accent), ink.worst) >= ACCENT_TARGET - 0.01, "$p $mode accent")
                assertTrue(lumContrast(luminance(ink.warn), ink.worst) >= ACCENT_TARGET - 0.01, "$p $mode warn")
            }
        }
    }

    @Test
    fun `white words on a pink photo, with a veil short of half`() {
        val ink = widgetInk(PictureLight(0.079, 0.446, 0.739), InkMode.AUTO, violet)!!
        assertFalse(ink.darkText)
        assertTrue(ink.veil >= 0.4 && ink.veil <= 0.5, "veil " + ink.veil)
    }

    @Test
    fun `dark words on a pastel picture, where white would need a heavy veil`() {
        val ink = widgetInk(PictureLight(0.55, 0.8, 0.97), InkMode.AUTO, violet)!!
        assertTrue(ink.darkText)
        near(VEIL_MIN_DARK, ink.veil, 1e-12)
        assertEquals(INK_DARK, ink.text)
    }

    @Test
    fun `a dark picture still gets a little veil, and no more`() {
        val ink = widgetInk(PictureLight(0.01, 0.05, 0.12), InkMode.AUTO, violet)!!
        assertFalse(ink.darkText)
        near(VEIL_MIN_LIGHT, ink.veil, 1e-12)
    }

    @Test
    fun `what the person chose wins over the picture`() {
        val p = PictureLight(0.55, 0.8, 0.97)
        assertFalse(widgetInk(p, InkMode.LIGHT, violet)!!.darkText)
        assertTrue(widgetInk(PictureLight(0.01, 0.05, 0.12), InkMode.DARK, violet)!!.darkText)
    }

    @Test
    fun `no picture is no veil, the plain card being the theme's own`() {
        assertNull(widgetInk(null, InkMode.AUTO, violet))
    }

    @Test
    fun `the theme colour moves only as far as it has to`() {
        // Already readable: left alone.
        assertEquals(0xFFFFFF, readableOn(0xFFFFFF, 0xFFFFFF, 0.0))
        val moved = readableOn(violet, 0xFFFFFF, 0.18)
        assertTrue(lumContrast(luminance(moved), 0.18) >= ACCENT_TARGET)
        // One step less would not have done.
        val before = mixRgb(violet, 0xFFFFFF, 0.0)
        assertTrue(lumContrast(luminance(before), 0.18) < ACCENT_TARGET || moved == before)
    }

    @Test
    fun `luminance and contrast as WCAG has them`() {
        assertTrue(abs(luminance(0xFFFFFF) - 1.0) < 1e-9)
        near(0.0, luminance(0x000000), 1e-12)
        assertTrue(abs(lumContrast(1.0, 0.0) - 21.0) < 1e-9)
        assertTrue(abs(encoded(linear(0.5)) - 0.5) < 1e-9)
    }

    @Test
    fun `a picture's light from its histogram`() {
        val h = IntArray(10)
        h[0] = 10; h[5] = 80; h[9] = 10
        val p = pictureLight(h)!!
        near(0.05, p.dark, 1e-9)
        near(0.95, p.bright, 1e-9)
        assertTrue(abs(p.mid - (10 * 0.05 + 80 * 0.55 + 10 * 0.95) / 100) < 1e-9)
        assertNull(pictureLight(IntArray(10)))
    }

    @Test
    fun `kept and read back, and nothing else is read`() {
        val p = PictureLight(0.1, 0.4, 0.8)
        assertEquals(p, pictureLightOf(p.saved()))
        assertNull(pictureLightOf(null))
        assertNull(pictureLightOf("0.1,0.4"))
        assertNull(pictureLightOf("0.1,0.4,1.8"))
        assertNull(pictureLightOf("dark,mid,bright"))
    }

    private fun near(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")
}
