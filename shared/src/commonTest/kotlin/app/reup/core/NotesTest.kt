package app.reup.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// ─── NotesTest ───────────────────────────────────────────────────────────────
//
// The box a note is written in. What a note may say is pinned against the
// desktop by NoteVectorsTest; what is here is only the phone's box, which
// never holds more than a note may say and never loses what was already
// written to make room for a paste.

class NotesTest {

    private fun a(n: Int) = "a".repeat(n)

    @Test
    fun `what fits is left alone`() {
        assertNull(noteTyped("", "ซื้อนม"))
        assertNull(noteTyped(a(119), a(120)))
    }

    @Test
    fun `a paste at the end keeps as much of itself as fits`() {
        val (text, cursor) = noteTyped(a(100), a(100) + "b".repeat(50))!!
        assertEquals(a(100) + "b".repeat(20), text)
        assertEquals(120, cursor)
    }

    @Test
    fun `a paste in the middle keeps both sides of it`() {
        val was = a(50) + "z".repeat(60)
        val now = a(50) + "b".repeat(40) + "z".repeat(60)
        val (text, cursor) = noteTyped(was, now)!!
        assertEquals(a(50) + "b".repeat(10) + "z".repeat(60), text)
        assertEquals(60, cursor)
    }

    @Test
    fun `a full box takes nothing more and the cursor stays put`() {
        val was = a(60) + "z".repeat(60)
        val (text, cursor) = noteTyped(was, a(60) + "b" + "z".repeat(60))!!
        assertEquals(was, text)
        assertEquals(60, cursor)
    }

    @Test
    fun `an emoji is one and is never cut in half`() {
        val cat = "\uD83D\uDC31"
        assertEquals(1, pointsIn(cat))
        assertEquals(120, pointsIn(a(119) + cat))
        // Room for one more: the first cat goes in whole, the second not at all.
        val (text, cursor) = noteTyped(a(119), a(119) + cat + cat)!!
        assertEquals(a(119) + cat, text)
        assertEquals(121, cursor)
        // Into a full box, just before an emoji that starts with the same
        // unit as the one pasted: nothing goes in, and no half of either is
        // left behind.
        val dog = "\uD83D\uDC36"
        val was = a(119) + dog
        val (t2, c2) = noteTyped(was, a(119) + cat + dog)!!
        assertEquals(was, t2)
        assertEquals(119, c2)
    }

    @Test
    fun `the box and the saved note count alike`() {
        val text = a(119) + "\uD83D\uDC31"
        assertEquals(text, noteBody(text))
        assertEquals(pointsIn(text), pointsIn(noteBody(text)!!))
    }
}
