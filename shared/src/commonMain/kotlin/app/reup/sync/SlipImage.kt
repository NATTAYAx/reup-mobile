// shared/src/commonMain/kotlin/app/reup/sync/SlipImage.kt
//
// What a picture of a slip is turned into before anything tries to read it.
//
// ─── WHY THIS EXISTS ─────────────────────────────────────────────────────────
//
// Five slips from one evening, read the way the app read them until this
// round, came back with two amounts that were wrong and looked right: a
// seventeen baht top-up as 170, and a two hundred and seventy baht one as 7.
// The dates did not come back at all. The recogniser was being handed the slip
// in colour, over KBank's embossed building, and was reading the texture as
// ink and the ink as texture.
//
// The same five pictures, turned black and white first, came back with every
// amount, every date and every reference right. Nothing else changed. So the
// step that matters most is this one, and it lives in shared code, where it
// can be tested on a laptop rather than argued about on a phone.
//
// ─── WHAT IT DOES ────────────────────────────────────────────────────────────
//
// One threshold for the whole picture, chosen by Otsu's method: the grey level
// that splits the pixels into two groups as unlike each other as possible. A
// slip is exactly that shape — a background and the writing on it. Local
// thresholds (Sauvola, which adapts to every neighbourhood) were tried on the
// same slips and did worse: they adapt to the embossing too, and read it back
// in as letters.
//
// Then the one thing a single threshold gets wrong: a band of colour with
// light writing on it, like a header, or a whole slip in dark mode. Rows that
// are mostly dark for longer than a line of text is tall are flipped, so their
// writing comes out black on white like everything else.
//
// ─── AND WHAT IT DOES NOT DO ─────────────────────────────────────────────────
//
// Enlarge. That was tried once, on a picture that had already been shrunk on
// the way in, and it made the reading worse. The size decision is made once,
// in [slipSampleSize], where the picture is decoded.

package app.reup.sync

/**
 * Luma of an ARGB pixel, 0..255, Rec. 601 weights in integer arithmetic.
 *
 * Seen against white: a transparent corner of a PNG is the page, not ink.
 */
fun lumaOf(argb: Int): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    val y = (r * 299 + g * 587 + b * 114 + 500) / 1000
    return if (a == 255) y else (y * a + 255 * (255 - a) + 127) / 255
}

/**
 * Otsu's threshold for a 256-bin histogram.
 *
 * Levels at or below the answer are one class and levels above it the other.
 * A picture of a single grey level has nothing to split, and gets 0.
 */
fun otsuThreshold(histogram: IntArray): Int {
    require(histogram.size == 256) { "a histogram has 256 bins, not ${histogram.size}" }
    var total = 0L
    var sum = 0.0
    for (i in 0 until 256) {
        total += histogram[i]
        sum += i.toDouble() * histogram[i]
    }
    var weightLow = 0L
    var sumLow = 0.0
    var best = 0.0
    var threshold = 0
    for (t in 0 until 256) {
        weightLow += histogram[t]
        if (weightLow == 0L) continue
        val weightHigh = total - weightLow
        if (weightHigh == 0L) break
        sumLow += t.toDouble() * histogram[t]
        val meanLow = sumLow / weightLow
        val meanHigh = (sum - sumLow) / weightHigh
        val between = weightLow.toDouble() * weightHigh.toDouble() * (meanLow - meanHigh) * (meanLow - meanHigh)
        if (between > best) {
            best = between
            threshold = t
        }
    }
    return threshold
}

/**
 * Black writing on white paper, in place.
 *
 * [luma] holds one byte per pixel, row after row, read as 0..255. Afterwards
 * every byte is 0 (ink) or 255 (paper, stored as -1). Returns the threshold, so
 * the screen that shows its working can show this too.
 */
fun binarizeForOcr(luma: ByteArray, width: Int, height: Int): Int {
    require(width > 0 && height > 0 && luma.size >= width * height) { "no picture" }
    val histogram = IntArray(256)
    for (i in 0 until width * height) histogram[luma[i].toInt() and 0xFF]++
    val t = otsuThreshold(histogram)
    val flip = darkBands(luma, width, height, t)
    for (y in 0 until height) {
        val row = y * width
        val inverted = flip[y]
        for (x in 0 until width) {
            val dark = (luma[row + x].toInt() and 0xFF) <= t
            luma[row + x] = if (dark != inverted) INK else PAPER
        }
    }
    return t
}

private const val INK: Byte = 0
private const val PAPER: Byte = -1

/**
 * Which rows belong to a dark band rather than to a light page.
 *
 * A row counts when more than half of it is dark, and a stretch of such rows
 * counts when it is taller than a line of text is likely to be — a twentieth
 * of the width, which is roughly two lines on a phone screenshot. A heading
 * set in heavy type can darken a few rows past half; it cannot darken sixty.
 *
 * Inside a band, rows through dense light lettering can dip under half, so a
 * dip that stays above a third and is no taller than a line of large type is
 * bridged rather than allowed to cut the band — and the letters in it — into
 * slices that are half flipped and half not.
 */
internal fun darkBands(luma: ByteArray, width: Int, height: Int, t: Int): BooleanArray {
    val fraction = DoubleArray(height)
    for (y in 0 until height) {
        var dark = 0
        val row = y * width
        for (x in 0 until width) if ((luma[row + x].toInt() and 0xFF) <= t) dark++
        fraction[y] = dark.toDouble() / width
    }
    val marked = BooleanArray(height) { fraction[it] > 0.5 }
    val bridge = maxOf(16, width / 10)
    var last = -1
    for (y in 0 until height) {
        if (fraction[y] <= 0.5) continue
        if (last >= 0 && y - last in 2..bridge && (last + 1 until y).all { fraction[it] > 0.3 }) {
            for (k in last + 1 until y) marked[k] = true
        }
        last = y
    }
    val tallest = maxOf(16, width / 20)
    val out = BooleanArray(height)
    var y = 0
    while (y < height) {
        if (!marked[y]) {
            y++
            continue
        }
        var end = y
        while (end < height && marked[end]) end++
        if (end - y >= tallest) for (k in y until end) out[k] = true
        y = end
    }
    return out
}

/**
 * How much to shrink a picture while decoding it: 1, 2, 4…
 *
 * Phone screenshots are never shrunk. The old rule capped the long edge at
 * 2200, which halved every tall screenshot — Pao Tang's is 1260 × 2750 — and
 * a recogniser shown half-size Thai reads half the words. What is shrunk is
 * what is genuinely too big to be worth the time: a camera photo, a tablet in
 * landscape. Width decides, because text on a screen scales with the width;
 * a long scrolling screenshot is tall, not large.
 */
fun slipSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_WIDTH || (width / sample).toLong() * (height / sample) > MAX_PIXELS) sample *= 2
    return sample
}

private const val MAX_WIDTH = 2000
private const val MAX_PIXELS = 12_000_000L