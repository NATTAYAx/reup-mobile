package app.reup.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── PaletteTest ─────────────────────────────────────────────────────────────
//
// The contrast tests are the reason this file is worth having. Colours get
// picked by eye, at night, on one bright screen, and the person who finds out
// they are unreadable is the one holding the phone outside in the afternoon.
// These are the WCAG ratios, applied to every theme at once, so adding a theme
// means proving it can be read rather than promising it.

class PaletteTest {

    @Test
    fun `hex parses to opaque argb`() {
        assertEquals(0xFF7C3AED.toInt(), hex("#7C3AED"))
        assertEquals(0xFF7C3AED.toInt(), hex("7C3AED"))
        assertEquals(0xFF000000.toInt(), hex("#000000"))
        assertEquals(0xFFFFFFFF.toInt(), hex("#FFFFFF"))
    }

    @Test
    fun `an unknown theme is the default rather than a crash`() {
        // A saved preference outlives the version that wrote it.
        assertEquals(DEFAULT_PALETTE, paletteOf("a theme from next year"))
        assertEquals(DEFAULT_PALETTE, paletteOf(null))
    }

    @Test
    fun `the default is the one the desktop already uses`() {
        assertEquals("violet", DEFAULT_PALETTE.id)
        assertEquals(hex("#7C3AED"), DEFAULT_PALETTE.primary)
        assertEquals(hex("#030712"), DEFAULT_PALETTE.bg)
    }

    @Test
    fun `every theme has its own id and its own name`() {
        assertEquals(THEMES.size, THEMES.map { it.id }.toSet().size)
        assertEquals(THEMES.size, THEMES.map { it.name }.toSet().size)
        for (t in THEMES) assertEquals(t, paletteOf(t.id))
    }

    @Test
    fun `black on white is the extreme and a colour on itself is nothing`() {
        assertTrue(contrast(hex("#000000"), hex("#FFFFFF")) > 20.9)
        assertTrue(contrast(hex("#7C3AED"), hex("#7C3AED")) < 1.01)
    }

    @Test
    fun `ordinary text can be read on every theme`() {
        for (t in THEMES) {
            val r = contrast(t.text, t.bg)
            assertTrue(r >= 7.0, t.name + " text on bg is " + r)
            assertTrue(contrast(t.text, t.card) >= 7.0, t.name + " text on card")
            assertTrue(contrast(t.text, t.raised) >= 7.0, t.name + " text on raised")
        }
    }

    @Test
    fun `quiet text is quiet without becoming invisible`() {
        for (t in THEMES) {
            // The bar for body text at this size. Below it, a note is decoration
            // that happens to contain words.
            assertTrue(contrast(t.dim, t.card) >= 4.5, t.name + " dim on card")
            // Labels and marks are allowed to be fainter, but not to vanish.
            assertTrue(contrast(t.faint, t.card) >= 3.0, t.name + " faint on card")
        }
    }

    @Test
    fun `the label on the primary button can be read`() {
        // The most-pressed thing in the app is a word on this colour, and the
        // first run of this test is what proved that word cannot always be
        // white: 3.46 on the green and 3.11 on the orange.
        for (t in THEMES) {
            val r = contrast(t.onPrimary, t.primary)
            assertTrue(r >= 4.5, t.name + " label on primary is " + r)
        }
    }

    @Test
    fun `that label is ink or paper, never a grey in between`() {
        // A mid-grey would pass on nothing and look like a mistake on
        // everything. Each theme picks a side.
        for (t in THEMES) {
            val white = contrast(t.onPrimary, hex("#FFFFFF")) < 1.2
            val black = contrast(t.onPrimary, hex("#000000")) < 1.2
            assertTrue(white || black, t.name + " has a half-hearted label colour")
        }
    }

    @Test
    fun `the accent is the one that has to survive being text`() {
        // It is used for the lead row's meta line and for warnings' action
        // buttons, which are text on a dark panel rather than on a fill.
        for (t in THEMES) {
            assertTrue(contrast(t.accent, t.bg) >= 4.5, t.name + " accent on bg")
            assertTrue(contrast(t.accent, t.card) >= 4.5, t.name + " accent on card")
        }
    }

    @Test
    fun `the three colours that mean something stay apart from the theme`() {
        // Money coming in must not be the same colour as the button that took
        // you to the screen, or the colour stops meaning anything.
        for (t in THEMES) {
            assertTrue(
                contrast(t.positive, t.primary) >= 1.6,
                t.name + " positive is too close to primary",
            )
            assertTrue(contrast(t.warn, t.card) >= 4.5, t.name + " warn on card")
            assertTrue(contrast(t.danger, t.card) >= 3.5, t.name + " danger on card")
        }
    }

    @Test
    fun `surfaces stack in the order they are named`() {
        // bg darkest, then card, then raised: if that inverts on some theme the
        // panels look punched into the page instead of laid on it.
        for (t in THEMES) {
            assertTrue(luminance(t.bg) < luminance(t.card), t.name + " card is not above bg")
            assertTrue(luminance(t.card) <= luminance(t.raised), t.name + " raised is not above card")
            assertTrue(luminance(t.field) <= luminance(t.card), t.name + " field is not a hole")
        }
    }
}