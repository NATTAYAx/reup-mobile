package app.reup.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── GifTest ─────────────────────────────────────────────────────────────────
//
// Four GIFs small enough to read by eye, written by hand byte for byte: two
// frames of colour, a patch that is cleared after it is seen, a patch that is
// put back, and a picture stored interlaced. Real GIFs from Pillow, ImageMagick
// and ffmpeg were checked against Pillow's own reading, pixel for pixel, while
// this was written; these keep the rules those checks found.

class GifTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Every frame as the whole picture stands after it, copied. */
    private fun frames(bytes: ByteArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        assertTrue(readGif(bytes) { _, px -> out.add(px.copyOf()); true })
        return out
    }

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** 4 by 2: all red for 50 ms, then all blue for 150. */
    private val blink = hex(
        "47494638396104000200f00000ff00000000ff21ff0b4e45545343415045322e30030100000021f904000500" +
            "00002c000000000400020000020504081020500021f904000f0000002c00000000040002000002054c98306152003b",
    )

    /**
     * 6 by 4: green; then a red 2 by 2 at (1, 1) that says clear me when done;
     * then a 2 by 2 at (3, 1) that is transparent but for one blue pixel at (4, 2).
     */
    private val patch = hex(
        "47494638396106000400f1000000ff00ff00000000ffffffff21ff0b4e45545343415045322e30030100000021" +
            "f904040a0000002c000000000600040000020e04081020408000010204081020500021f904090a0003002c0100" +
            "0100020002000002034c98140021f904050a0003002c0300010002000200000203dc3815003b",
    )

    /** 5 by 3: green; then a red 2 by 2 at the corner that says put back what was under me; then blue at (3, 1). */
    private val previous = hex(
        "47494638396105000300f1000000ff00ff00000000ffffffff21ff0b4e45545343415045322e30030100000021" +
            "f904040a0000002c00000000050003000002090408102040800001a20021f9040c0a0000002c000000000200" +
            "02000002034c98140021f904040a0000002c03000100010001000002025401003b",
    )

    /** 3 by 10, interlaced: row y in red, green, blue, white by y % 4; then the same moved on one colour. */
    private val interlaced = hex(
        "47494638396103000a00f10000ff000000ff000000ffffffff21ff0b4e45545343415045322e30030100000021" +
            "f90400000000002c0000000003000a0040021204081020408812252a4cb87161c28d0b93020021f904000000" +
            "00002c0000000003000a004002124c983061c28c1b374e940810a2448010a502003b",
    )

    @Test
    fun `the size and every frame's delay, without decoding a picture`() {
        val info = gifInfo(blink)!!
        assertEquals(4, info.width)
        assertEquals(2, info.height)
        assertEquals(listOf(50, 150), info.delaysMs)
    }

    @Test
    fun `each frame is the whole picture as it stands`() {
        val f = frames(blink)
        assertEquals(2, f.size)
        assertTrue(f[0].all { it == red })
        assertTrue(f[1].all { it == blue })
    }

    @Test
    fun `a patch says clear me, and it is cleared to transparent as browsers do`() {
        val f = frames(patch)
        val at = { x: Int, y: Int -> y * 6 + x }
        assertEquals(red, f[1][at(1, 1)])
        assertEquals(green, f[1][at(0, 0)])
        // Gone by the next frame: not to green, not to the background colour,
        // but to nothing.
        assertEquals(0, f[2][at(1, 1)])
        assertEquals(0, f[2][at(2, 2)])
        assertEquals(green, f[2][at(0, 0)])
        // The last patch's transparent pixels leave what is under them.
        assertEquals(blue, f[2][at(4, 2)])
        assertEquals(green, f[2][at(3, 1)])
    }

    @Test
    fun `a patch says put back what was under me, and it is`() {
        val f = frames(previous)
        val at = { x: Int, y: Int -> y * 5 + x }
        assertEquals(red, f[1][at(0, 0)])
        assertEquals(green, f[2][at(0, 0)])
        assertEquals(green, f[2][at(1, 1)])
        assertEquals(blue, f[2][at(3, 1)])
    }

    @Test
    fun `interlaced rows land where they belong`() {
        val f = frames(interlaced)
        val colours = listOf(red, green, blue, white)
        for (y in 0 until 10) {
            for (x in 0 until 3) {
                assertEquals(colours[y % 4], f[0][y * 3 + x], "first frame, row $y")
                assertEquals(colours[(y + 1) % 4], f[1][y * 3 + x], "second frame, row $y")
            }
        }
    }

    @Test
    fun `reading stops when it is told to`() {
        var seen = 0
        assertTrue(readGif(blink) { _, _ -> seen++; false })
        assertEquals(1, seen)
    }

    @Test
    fun `a file cut short keeps its whole frames and drops the broken one`() {
        val cut = blink.copyOf(blink.size - 6)
        assertEquals(listOf(50), gifInfo(cut)!!.delaysMs)
        assertEquals(1, frames(cut).size)
    }

    @Test
    fun `something that is not a GIF is nothing`() {
        assertNull(gifInfo(byteArrayOf(1, 2, 3)))
        assertFalse(readGif(hex("474946")) { _, _ -> true })
        assertNull(gifInfo(hex("89504e470d0a1a0a")))
    }
}
