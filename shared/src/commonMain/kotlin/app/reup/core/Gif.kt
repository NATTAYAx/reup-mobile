package app.reup.core

// ─── Gif.kt — a GIF, read into its pictures ──────────────────────────────────
//
// Android shows the first frame of a GIF and no more until something calls
// start() on it, which nothing in a widget can. To lay one on the widget's
// flipbook (Film.kt) its frames are read here, by hand, the way the format
// describes them: each frame is a patch drawn over what the one before it left,
// and says what it leaves behind for the next one.
//
// Written out rather than borrowed from android.graphics.Movie, which has been
// deprecated since Android 9, and so that it can be tested on a desktop like
// the rest of this folder.
//
// WHAT IT DOES THE WAY BROWSERS DO
//
// A frame that says "restore to background" clears its patch to transparent,
// not to the background colour the file names; every browser does that, and
// GIFs are made to look right in browsers. A file that ends early keeps
// everything drawn up to where it ends.

/** A GIF's size, and how long each of its frames shows, read without decoding a single picture. */
class GifInfo(val width: Int, val height: Int, val delaysMs: List<Int>)

/** The largest GIF read, in pixels: two copies of a picture this size are 32 MB. */
const val GIF_MAX_PIXELS: Int = 4_000_000

/** Null for anything that is not a GIF, or is too large to read. */
fun gifInfo(bytes: ByteArray): GifInfo? {
    val delays = ArrayList<Int>()
    val screen = walk(bytes, null) { delays.add(it); true } ?: return null
    return GifInfo(screen.first, screen.second, delays)
}

/**
 * Every frame of a GIF in turn, as the whole picture stands once that frame is
 * drawn: ARGB, row by row, the GIF's width by its height, transparent where
 * nothing has been drawn.
 *
 * [each] is handed the frame's number and the pixels - the same array every
 * time, so it copies what it keeps - and returns false to stop there.
 *
 * @return false for anything that is not a GIF, or is too large to read
 */
fun readGif(bytes: ByteArray, each: (index: Int, pixels: IntArray) -> Boolean): Boolean =
    walk(bytes, each) { true } != null

// ── the file ─────────────────────────────────────────────────────────────────

private class GifEnd : Exception()

private class Cursor(val b: ByteArray) {
    var p = 0

    fun u8(): Int {
        if (p >= b.size) throw GifEnd()
        return b[p++].toInt() and 0xFF
    }

    fun u16(): Int {
        val lo = u8()
        return lo or (u8() shl 8)
    }

    fun skip(n: Int) {
        p += n
        if (p > b.size) throw GifEnd()
    }

    /** Past a run of sub-blocks and the empty one that ends it. */
    fun skipBlocks() {
        while (true) {
            val n = u8()
            if (n == 0) return
            skip(n)
        }
    }

    /** A colour table of [size] entries, as opaque ARGB. */
    fun table(size: Int): IntArray = IntArray(size) {
        val r = u8()
        val g = u8()
        val bl = u8()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

/**
 * Through the file once, frame by frame: every frame's delay to [delay], and,
 * when [each] is given, every frame decoded and drawn. The screen's size, or
 * null for something that is not a GIF or is too large.
 *
 * Stops quietly where the file ends, or at anything it does not recognise:
 * what was read up to there stands.
 */
private fun walk(bytes: ByteArray, each: ((Int, IntArray) -> Boolean)?, delay: (Int) -> Boolean): Pair<Int, Int>? {
    val c = Cursor(bytes)
    val width: Int
    val height: Int
    var global: IntArray? = null
    try {
        if (c.u8() != 'G'.code || c.u8() != 'I'.code || c.u8() != 'F'.code) return null
        c.skip(3)
        width = c.u16()
        height = c.u16()
        val packed = c.u8()
        c.skip(2)
        if (packed and 0x80 != 0) global = c.table(2 shl (packed and 7))
    } catch (e: GifEnd) {
        return null
    }
    if (width <= 0 || height <= 0 || width.toLong() * height > GIF_MAX_PIXELS) return null

    val canvas = if (each != null) IntArray(width * height) else null
    var saved: IntArray? = null
    var indices: ByteArray? = null
    var decoder: Lzw? = null

    // The graphic control extension before a frame, which it applies to.
    var disposal = 0
    var frameDelay = 0
    var transparent = -1
    // What the frame before this one asked to be done once it had been seen.
    var undo = 0
    var undoX = 0
    var undoY = 0
    var undoW = 0
    var undoH = 0
    var index = 0

    try {
        loop@ while (true) {
            when (c.u8()) {
                0x21 -> {
                    if (c.u8() == 0xF9) {
                        val n = c.u8()
                        if (n >= 4) {
                            val packed = c.u8()
                            disposal = (packed shr 2) and 7
                            frameDelay = c.u16() * 10
                            transparent = if (packed and 1 != 0) c.u8() else c.u8().let { -1 }
                            c.skip(n - 4)
                        } else {
                            c.skip(n)
                        }
                    }
                    c.skipBlocks()
                }
                0x2C -> {
                    val x = c.u16()
                    val y = c.u16()
                    val w = c.u16()
                    val h = c.u16()
                    val packed = c.u8()
                    val local = if (packed and 0x80 != 0) c.table(2 shl (packed and 7)) else null
                    val interlaced = packed and 0x40 != 0
                    val minCode = c.u8()
                    if (canvas == null || each == null) {
                        c.skipBlocks()
                    } else {
                        // What the last frame left behind, cleared or put back.
                        when (undo) {
                            2 -> clear(canvas, width, height, undoX, undoY, undoW, undoH)
                            3 -> saved?.copyInto(canvas)
                        }
                        if (disposal == 3) {
                            val copy = saved ?: IntArray(canvas.size).also { saved = it }
                            canvas.copyInto(copy)
                        }
                        if (w.toLong() * h > GIF_MAX_PIXELS) {
                            // A frame claiming to be larger than any screen: passed over, nothing drawn.
                            c.skipBlocks()
                        } else {
                            val count = w * h
                            val pixels = indices?.takeIf { it.size >= count } ?: ByteArray(count).also { indices = it }
                            val lzw = decoder ?: Lzw().also { decoder = it }
                            val got = lzw.decode(c, minCode, pixels, count)
                            draw(canvas, width, height, pixels, got, x, y, w, h, interlaced, local ?: global, transparent)
                        }
                        undo = disposal
                        undoX = x
                        undoY = y
                        undoW = w
                        undoH = h
                    }
                    // A frame counts once its data is read to the end. One the file
                    // stops partway through is not a frame, here or in gifInfo.
                    if (!delay(frameDelay)) break@loop
                    if (canvas != null && each != null && !each(index, canvas)) break@loop
                    index++
                    disposal = 0
                    frameDelay = 0
                    transparent = -1
                }
                else -> break@loop
            }
        }
    } catch (e: GifEnd) {
        // The file ended early. Everything read up to here stands.
    }
    return width to height
}

/** A frame's patch back to transparent, where it lies on the screen. */
private fun clear(canvas: IntArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int) {
    val right = minOf(width, x + w)
    val bottom = minOf(height, y + h)
    for (row in y until bottom) {
        if (x < right) canvas.fill(0, row * width + x, row * width + right)
    }
}

/**
 * The frame's [got] decoded pixels onto the screen at [x], [y]. Rows come in
 * the order they were stored: an interlaced frame stores every eighth row,
 * then the fourth rows between those, then every other row, then the rest.
 */
private fun draw(
    canvas: IntArray,
    width: Int,
    height: Int,
    pixels: ByteArray,
    got: Int,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    interlaced: Boolean,
    table: IntArray?,
    transparent: Int,
) {
    if (table == null || w <= 0) return
    var pass = 0
    var step = 8
    var row = 0
    for (stored in 0 until h) {
        val line: Int
        if (interlaced) {
            // On to the next pass that has a row in it; a short frame has empty ones.
            while (row >= h && pass < 3) {
                pass++
                when (pass) {
                    1 -> { row = 4; step = 8 }
                    2 -> { row = 2; step = 4 }
                    else -> { row = 1; step = 2 }
                }
            }
            if (row >= h) break
            line = row
            row += step
        } else {
            line = stored
        }
        val sy = y + line
        if (sy >= height) continue
        val from = stored * w
        if (from >= got) break
        val end = minOf(w, got - from)
        val base = sy * width
        for (i in 0 until end) {
            val sx = x + i
            if (sx >= width) break
            val p = pixels[from + i].toInt() and 0xFF
            if (p == transparent || p >= table.size) continue
            canvas[base + sx] = table[p]
        }
    }
}

/**
 * The GIF flavour of LZW: codes from [minCode] + 1 bits wide up to 12, a clear
 * code that starts the table again, an end code, and a table that stops growing
 * at 4,096 entries without being cleared. Kept between frames for its tables.
 */
private class Lzw {
    private val prefix = ShortArray(4096)
    private val suffix = ByteArray(4096)
    private val stack = ByteArray(4097)

    /**
     * Up to [count] palette indices into [out], from the data sub-blocks at
     * [c], which it reads to their end whatever it finds in them. How many
     * pixels it got.
     */
    fun decode(c: Cursor, minCode: Int, out: ByteArray, count: Int): Int {
        if (minCode < 1 || minCode > 11) {
            c.skipBlocks()
            return 0
        }
        val clear = 1 shl minCode
        val end = clear + 1
        for (code in 0 until clear) {
            prefix[code] = 0
            suffix[code] = code.toByte()
        }
        var size = minCode + 1
        var mask = (1 shl size) - 1
        var avail = clear + 2
        var old = -1
        var first = 0
        var datum = 0
        var bits = 0
        var written = 0
        var done = false

        while (true) {
            val n = c.u8()
            if (n == 0) break
            if (done) {
                c.skip(n)
                continue
            }
            for (k in 0 until n) {
                val byte = c.u8()
                // Finished partway through a block: the rest of it is read and let go.
                if (done) continue
                datum = datum or (byte shl bits)
                bits += 8
                while (bits >= size && !done) {
                    var code = datum and mask
                    datum = datum ushr size
                    bits -= size
                    if (code == clear) {
                        size = minCode + 1
                        mask = (1 shl size) - 1
                        avail = clear + 2
                        old = -1
                        continue
                    }
                    if (code == end || code > avail || (old == -1 && code >= clear)) {
                        done = true
                        break
                    }
                    if (old == -1) {
                        if (written < count) out[written++] = suffix[code]
                        old = code
                        first = code
                        continue
                    }
                    val incoming = code
                    var top = 0
                    if (code == avail) {
                        stack[top++] = first.toByte()
                        code = old
                    }
                    while (code >= clear) {
                        stack[top++] = suffix[code]
                        code = prefix[code].toInt()
                    }
                    first = suffix[code].toInt() and 0xFF
                    stack[top++] = first.toByte()
                    if (avail < 4096) {
                        prefix[avail] = old.toShort()
                        suffix[avail] = first.toByte()
                        avail++
                        if (avail and mask == 0 && avail < 4096) {
                            size++
                            mask += avail
                        }
                    }
                    old = incoming
                    while (top > 0) {
                        top--
                        if (written < count) out[written++] = stack[top]
                    }
                    if (written >= count) done = true
                }
            }
        }
        return written
    }
}
