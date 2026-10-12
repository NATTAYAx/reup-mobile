// app/src/main/java/app/reup/SlipOcr.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import app.reup.sync.MemoRead
import app.reup.sync.SlipHints
import app.reup.sync.SlipReading
import app.reup.sync.betterMemo
import app.reup.sync.binarizeForOcr
import app.reup.sync.binarizeLine
import app.reup.sync.lumaOf
import app.reup.sync.memoCut
import app.reup.sync.memoStart
import app.reup.sync.readSlip
import app.reup.sync.slipSampleSize
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Currency
import java.util.Locale

/** What came off an image, or why nothing did. */
sealed interface SlipRead {
    /**
     * Text, and at least one non-blank character of it.
     *
     * [languages], [width], [height] and [threshold] are the working, shown on
     * the scan screen under the raw text: a reading that went wrong is fixed
     * in a different place depending on which of them was off.
     */
    data class Ok(
        val text: String,
        val languages: String,
        val width: Int,
        val height: Int,
        val threshold: Int,
        /**
         * The memo after its line was read a second time on its own — see
         * SlipOcr.memoAgain — or null when there was no memo to read again.
         */
        val memo: String? = null,
        /** That second reading as the recogniser gave it, for the scan screen. */
        val memoLine: String? = null,
    ) : SlipRead {
        /** What the rules make of [text], with the memo the second reading settled on. */
        fun reading(hints: SlipHints): SlipReading {
            val r = readSlip(text, hints)
            return if (memo != null && r.memo != null) r.copy(memo = memo) else r
        }
    }

    /** No language data. [present] is whatever is in assets/tessdata instead. */
    data class MissingData(val present: String) : SlipRead

    /** The language data is there and Tesseract would not start on it. */
    data object InitFailed : SlipRead

    /** It read the image and there was nothing on it. */
    data object NoText : SlipRead

    /** Anything else, said in whatever words the exception used. */
    data class Failed(val message: String) : SlipRead
}

/**
 * Reading the words off a slip, on the phone.
 *
 * ─── WHY TESSERACT AND NOT ML KIT ───────────────────────────────────────────
 *
 * ML Kit's text recognition covers Latin, Chinese, Devanagari, Japanese and
 * Korean. Thai is not among them, and would not be read at all — while the
 * digits around it came back looking perfectly healthy. A reader that returns
 * most of the page and silently drops the part the rules depend on is worse
 * than one that fails. Tesseract reads Thai, runs entirely on the device, and
 * needs no account, no key and no network.
 *
 * ─── WHY THE PICTURE IS TURNED BLACK AND WHITE FIRST ────────────────────────
 *
 * Until this round the slip went in exactly as it was, in colour. Five real
 * slips read that way came back with a seventeen baht top-up as 170 and a two
 * hundred and seventy baht one as 7, and no dates at all: Tesseract binarises
 * internally, and over KBank's embossed building it was keeping the texture
 * and losing the letters. The same five, turned black and white by
 * SlipImage.kt before they reach Tesseract, came back with every field right.
 * That step is shared code with its own tests; this file only hands it the
 * pixels.
 *
 * ─── AND WHY THE MEMO IS READ TWICE ─────────────────────────────────────────
 *
 * One threshold suits the whole slip and not every line on it. On a real
 * Krungthai slip it left the loopless ท of ทดสอบ101 looking like เท, and the
 * note said เทดสอบ101. The memo is the one line no rule can check, so its
 * words are cut out of the grey picture after the page is read, thresholded
 * on their own and read again as a single line. [memoAgain] does that and
 * SlipText's betterMemo decides between the two.
 *
 * ─── WHICH LANGUAGES ────────────────────────────────────────────────────────
 *
 * Whatever is in assets/tessdata. The APK ships Thai and English, which between
 * them read Thai and every language written in Latin letters. A build for
 * somewhere else adds that language's file there and nothing else changes:
 * jpn.traineddata, kor.traineddata, chi_sim.traineddata, vie.traineddata. The
 * words those slips use are already in SlipText.kt.
 *
 * Thai always goes first when it is there. With English first, Tesseract puts
 * a space between every Thai letter — จ ํ า น ว น — and no Thai label matches
 * anything. With Thai first, English comes back exactly as well as on its own;
 * that was checked on English, Indonesian, German and Vietnamese slips.
 *
 * ─── WHY THE DATA IS COPIED OUT OF ASSETS ───────────────────────────────────
 *
 * Tesseract takes a filesystem path, and assets inside an APK have none. The
 * files are copied to the app's private directory through a `.part` name and
 * renamed when complete, so an interrupted copy is retried rather than opened.
 * (openFd, which would have given the length to check against, only works on
 * assets aapt left uncompressed, and .traineddata is not one of them.)
 *
 * ─── AND WHY IT SAYS WHICH FAILURE ──────────────────────────────────────────
 *
 * Every path used to end in an empty string, and the screen this feeds exists
 * to tell causes apart: no language file, Tesseract refusing to start, an image
 * with no text on it, and a crash are fixed in four different places.
 */
object SlipOcr {

    private const val TAG = "SlipOcr"
    private const val SUFFIX = ".traineddata"

    /**
     * What came off the image, or why nothing did.
     *
     * Never throws: this is called from a screen whose job is to show what was
     * read, and an exception there would replace the answer with a stack trace.
     */
    suspend fun read(ctx: Context, bitmap: Bitmap): SlipRead = withContext(Dispatchers.IO) {
        val languages = languagesIn(ctx)
        if (languages.isEmpty()) return@withContext SlipRead.MissingData(assetList(ctx))
        val dir = prepare(ctx, languages) ?: return@withContext SlipRead.MissingData(assetList(ctx))
        val joined = languages.joinToString("+")
        val tess = TessBaseAPI()
        var page: Bitmap? = null
        try {
            if (!tess.init(dir.absolutePath, joined)) {
                Log.e(TAG, "tesseract refused to initialise with $joined in $dir")
                return@withContext SlipRead.InitFailed
            }
            // ── WHY THE PAGE MODE IS NOT THE DEFAULT ─────────────────────
            //
            // The default assumes one block of text in one column. A slip is
            // a headline, a large amount, two columns of labels, a QR code and
            // a footer, and the single-column assumption treated the one line
            // printed larger than the rest — the amount — as furniture. Sparse
            // text looks for writing anywhere, which is what a slip is. It
            // finds more nonsense too, and SlipText refuses to guess from it.
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            val grey = greyOf(bitmap)
            val (black, threshold) = blackAndWhite(bitmap, grey.copyOf())
            page = black
            tess.setImage(black)
            val text = tess.utF8Text ?: ""
            if (text.isBlank()) {
                SlipRead.NoText
            } else {
                val (memo, line) = memoAgain(tess, grey, black.width, black.height, text)
                SlipRead.Ok(text, joined, bitmap.width, bitmap.height, threshold, memo, line)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not read the image", e)
            SlipRead.Failed(e.message ?: e.toString())
        } catch (e: OutOfMemoryError) {
            // A picture too big for what is left of the heap is a reading that
            // failed, not a reason for the app to close in somebody's hand.
            Log.e(TAG, "out of memory reading the image", e)
            SlipRead.Failed("รูปใหญ่เกินไป")
        } finally {
            // Native memory. Not garbage collected, and a second scan on a
            // leaked instance is how this ends up being blamed for the phone
            // getting warm.
            try { tess.recycle() } catch (_: Exception) { }
            page?.let { if (it !== bitmap) it.recycle() }
        }
    }

    /**
     * The picture in grey, one byte a pixel.
     *
     * One row at a time: an IntArray for the whole of a 1260 × 2750 screenshot
     * would be fourteen megabytes for nothing. The grey itself is kept — three
     * and a half megabytes for that screenshot — because the memo's line is
     * cut out of it again after the page has been read; see [memoAgain].
     */
    private fun greyOf(source: Bitmap): ByteArray {
        val w = source.width
        val h = source.height
        val luma = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) {
            source.getPixels(row, 0, w, 0, y, w, 1)
            val base = y * w
            for (x in 0 until w) luma[base + x] = lumaOf(row[x]).toByte()
        }
        return luma
    }

    /**
     * The picture as black writing on white, and the grey level that split it.
     *
     * [luma] is the picture's grey, which is turned black and white in place.
     * Written into [source] itself when it can be (a mutable ARGB_8888 bitmap,
     * which is how the scan screen decodes it), so a large screenshot is not
     * held in memory twice more.
     */
    private fun blackAndWhite(source: Bitmap, luma: ByteArray): Pair<Bitmap, Int> {
        val w = source.width
        val h = source.height
        val row = IntArray(w)
        val threshold = binarizeForOcr(luma, w, h)
        val target =
            if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) source
            else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) row[x] = if (luma[base + x].toInt() == 0) BLACK else WHITE
            target.setPixels(row, 0, w, 0, y, w, 1)
        }
        return target to threshold
    }

    private const val BLACK = -0x1000000
    private const val WHITE = -0x1

    /** One word the page reading found, where it is, how sure, and which line it is on. */
    private class Word(val text: String, val confidence: Float, val box: Rect, val line: Int) {
        fun edges(): IntArray = intArrayOf(box.left, box.top, box.right, box.bottom)
    }

    /**
     * The memo, settled by reading its line a second time on its own; and that
     * second reading. Both null when the page has no memo.
     *
     * The page went to the recogniser black and white by one threshold for the
     * whole slip. The memo's words are found on the page by where the
     * recogniser put them, cut out of [grey] with room around them — memoCut
     * says how much — given a threshold of their own by binarizeLine, and read
     * again as one line. SlipText's betterMemo picks between the two readings,
     * and says why the second one usually wins.
     *
     * The words have to be collected before the second reading: setting a new
     * image throws the page's results away.
     *
     * Never throws. A memo that could not be read again is the page's memo,
     * which is what every slip had before this existed.
     */
    private fun memoAgain(tess: TessBaseAPI, grey: ByteArray, w: Int, h: Int, text: String): Pair<String?, String?> {
        val memo = readSlip(text).memo ?: return null to null
        return try {
            var span: List<Word> = emptyList()
            var before: Word? = null
            for (words in pageWords(tess).groupBy { it.line }.values) {
                val k = memoStart(words.map { it.text }, memo)
                if (k < 0) continue
                span = words.subList(k, words.size)
                before = words.getOrNull(k - 1)
                break
            }
            if (span.isEmpty()) return memo to null
            val (x0, y0, x1, y1) = memoCut(span.map { it.edges() }, before?.edges(), before?.text, w, h)
            if (x1 - x0 < 8 || y1 - y0 < 8) return memo to null
            val cw = x1 - x0
            val ch = y1 - y0
            val cut = ByteArray(cw * ch)
            for (y in 0 until ch) System.arraycopy(grey, (y0 + y) * w + x0, cut, y * cw, cw)
            binarizeLine(cut, cw, ch)
            val bitmap = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            try {
                val row = IntArray(cw)
                for (y in 0 until ch) {
                    for (x in 0 until cw) row[x] = if (cut[y * cw + x].toInt() == 0) BLACK else WHITE
                    bitmap.setPixels(row, 0, cw, 0, y, cw, 1)
                }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                tess.setImage(bitmap)
                val again = tess.utF8Text ?: ""
                val sure = tess.meanConfidence().toFloat()
                val page = MemoRead(memo, span.map { it.confidence }.average().toFloat())
                (betterMemo(page, MemoRead(again, sure)) ?: memo) to again
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read the memo a second time", e)
            memo to null
        } catch (e: OutOfMemoryError) {
            // The page is read already. Losing it over the memo would be the
            // wrong way round.
            Log.w(TAG, "out of memory reading the memo a second time", e)
            memo to null
        }
    }

    /** Every word of the page reading, in reading order, each with the number of its line. */
    private fun pageWords(tess: TessBaseAPI): List<Word> {
        val out = ArrayList<Word>()
        val it = tess.resultIterator ?: return out
        try {
            it.begin()
            var line = -1
            do {
                if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!t.isNullOrBlank()) {
                    out += Word(
                        t.trim(),
                        it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD),
                        it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD),
                        maxOf(line, 0),
                    )
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        return out
    }

    /**
     * The image, at full size unless it is a photograph.
     *
     * Here rather than in the scan screen since slips started arriving on their
     * own: the watcher reads the same pictures the same way, and two copies of
     * "how big a slip is read at" would be two answers the first time either
     * changed.
     *
     * The long edge used to be capped at 2200 pixels, which halved every tall
     * screenshot on the way in — Pao Tang's is 1260 × 2750 — and Thai at half
     * size is Thai the recogniser cannot read. [slipSampleSize] decides by width
     * instead, and only shrinks what is genuinely too big to be worth the time:
     * a camera photo, a tablet in landscape.
     *
     * Decoded mutable, so [read] can turn it black and white in place instead of
     * holding a second copy of it. Null when the picture cannot be opened or is
     * not finished being written yet.
     */
    fun decode(ctx: Context, uri: Uri): Bitmap? {
        val resolver = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = slipSampleSize(bounds.outWidth, bounds.outHeight)
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /**
     * What this phone knows that a slip might not say.
     *
     * The currency the books are kept in — synced from the computer, or the
     * phone's own region's before the first sync — settles a `$` or a `¥`. The
     * region decides whether 03/04 is March. Neither fills in a field the slip
     * left empty.
     */
    suspend fun hints(ctx: Context): SlipHints {
        val stored = try {
            Repo.money(ctx).currency()
        } catch (_: Exception) {
            null
        }
        val locale = Locale.getDefault()
        val regional = try {
            Currency.getInstance(locale).currencyCode
        } catch (_: Exception) {
            null
        }
        return SlipHints(homeCurrency = stored ?: regional, monthFirst = locale.country in MONTH_FIRST)
    }

    /** Where 03/04/2026 is the fourth of March. */
    private val MONTH_FIRST = setOf("US", "PR", "PH", "FM", "MH", "PW", "GU", "AS", "MP", "VI", "UM")

    /** The languages in assets/tessdata, Thai first and English last. */
    private fun languagesIn(ctx: Context): List<String> {
        val names = try {
            ctx.assets.list("tessdata")?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val codes = names.filter { it.endsWith(SUFFIX) }.map { it.removeSuffix(SUFFIX) }.sorted()
        return codes.filter { it == "tha" } + codes.filter { it != "tha" && it != "eng" } + codes.filter { it == "eng" }
    }

    /**
     * What is actually in assets/tessdata, for the message when nothing is.
     *
     * An empty folder and a file under a name this does not expect look the
     * same from outside and are fixed differently.
     */
    private fun assetList(ctx: Context): String = try {
        val names = ctx.assets.list("tessdata")?.toList() ?: emptyList()
        if (names.isEmpty()) "(empty)" else names.joinToString(", ")
    } catch (_: Exception) {
        "(no tessdata folder)"
    }

    /**
     * The directory Tesseract is initialised with — the PARENT of `tessdata`,
     * which is the one part of this API that catches everybody once.
     */
    private fun prepare(ctx: Context, languages: List<String>): File? {
        val parent = File(ctx.filesDir, "tesseract")
        val data = File(parent, "tessdata")
        if (!data.exists() && !data.mkdirs()) {
            Log.e(TAG, "could not create $data")
            return null
        }
        for (code in languages) {
            val name = code + SUFFIX
            val target = File(data, name)
            if (target.exists() && target.length() > 0L) continue
            val part = File(data, "$name.part")
            try {
                ctx.assets.open("tessdata/$name").use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                // Only now does it get the name Tesseract looks for.
                if (!part.renameTo(target)) {
                    Log.e(TAG, "could not put $part in place")
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "missing or unreadable asset tessdata/$name", e)
                part.delete()
                return null
            }
        }
        return parent
    }
}
