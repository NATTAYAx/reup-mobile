// app/src/main/java/app/reup/SlipOcr.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What came off an image, or why nothing did. */
sealed interface SlipRead {
    /** Text, and at least one non-blank character of it. */
    data class Ok(val text: String) : SlipRead

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
 * ML Kit's text recognition is the obvious answer and it is the wrong one here.
 * It covers Latin, Chinese, Devanagari, Japanese and Korean scripts. Thai is not
 * among them, and would not be read at all.
 *
 * That would not have failed loudly. The digits on a slip are Arabic numerals,
 * so ML Kit would have returned the amounts, the date and the reference number
 * looking perfectly healthy — and none of the Thai words around them. Which is
 * precisely the half SlipText needs, because the difference between the amount
 * and the fee on a K PLUS slip is the word in front of them, not the number.
 *
 * A reader that returns most of the page and silently drops the part the rules
 * depend on is worse than one that fails.
 *
 * Tesseract with tha.traineddata reads Thai, runs entirely on the device, and
 * needs no account, no key and no network. The trade is size and speed: a few
 * megabytes of language data in the APK, and a second or two per image rather
 * than a fraction of one. For something done a few times a week from a still
 * image, neither is a cost worth optimising.
 *
 * ─── WHY THE DATA IS COPIED OUT OF ASSETS ───────────────────────────────────
 *
 * Tesseract is a native library and takes a filesystem path, not an asset
 * stream. Assets inside an APK have neither, so the file is copied to the app's
 * private directory on first use and read from there afterwards.
 *
 * The first version of this compared the copy against the asset's length, read
 * with openFd, so that a half-written file from an interrupted copy would be
 * replaced rather than opened. openFd only works on assets aapt left
 * uncompressed, and .traineddata is not on its list of extensions to leave
 * alone, so it threw on every run — was caught, logged as a missing asset, and
 * returned no text at all. The screen showed a blank page for a file that was
 * sitting right there.
 *
 * Copied through a `.part` file and renamed instead. An interrupted copy leaves
 * a `.part` behind and never becomes the real name, which is the thing the
 * length check was protecting against, without needing to know the length.
 *
 * ─── AND WHY IT SAYS WHICH FAILURE ──────────────────────────────────────────
 *
 * Every path used to end in an empty string. The screen this feeds exists
 * specifically to tell causes apart, and it was being handed one answer for
 * four of them: no language file, Tesseract refusing to start, an image with no
 * text on it, and a crash. Nothing about the blank page said which, and the
 * answer was in logcat, which is a place nobody is standing when they are
 * looking at a slip.
 */
object SlipOcr {

    /**
     * Thai first, English second.
     *
     * Both, because slips are bilingual: the labels are Thai and the bank name,
     * the reference and often the word THB are Latin. Tesseract takes them in
     * one pass and the order is a preference, not an exclusion.
     */
    private const val LANGUAGES = "tha+eng"

    private const val TAG = "SlipOcr"

    /** The names in `app/src/main/assets/tessdata/`. */
    private val FILES = listOf("tha.traineddata", "eng.traineddata")

    /**
     * What came off the image, or why nothing did.
     *
     * A result rather than a string, so the screen can say which of the four
     * things happened. It still never throws: this is called from a screen
     * whose job is to show what was read, and an exception there would replace
     * the answer with a stack trace.
     */
    suspend fun read(ctx: Context, bitmap: Bitmap): SlipRead = withContext(Dispatchers.IO) {
        val dir = prepare(ctx) ?: return@withContext SlipRead.MissingData(assetList(ctx))
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dir.absolutePath, LANGUAGES)) {
                Log.e(TAG, "tesseract refused to initialise with $LANGUAGES in $dir")
                return@withContext SlipRead.InitFailed
            }
            tess.setImage(bitmap)
            val text = tess.utF8Text ?: ""
            if (text.isBlank()) SlipRead.NoText else SlipRead.Ok(text)
        } catch (e: Exception) {
            Log.e(TAG, "could not read the image", e)
            SlipRead.Failed(e.message ?: e.toString())
        } finally {
            // Native memory. Not garbage collected, and a second scan on a
            // leaked instance is how this ends up being blamed for the phone
            // getting warm.
            try { tess.recycle() } catch (_: Exception) { }
        }
    }

    /**
     * What is actually in assets/tessdata, for the message when nothing is.
     *
     * The two failures here look identical from the outside — an empty folder
     * and a file under a name this does not expect — and they are fixed
     * differently. Listing what is there says which without anybody opening a
     * terminal.
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
    private fun prepare(ctx: Context): File? {
        val parent = File(ctx.filesDir, "tesseract")
        val data = File(parent, "tessdata")
        if (!data.exists() && !data.mkdirs()) {
            Log.e(TAG, "could not create $data")
            return null
        }
        for (name in FILES) {
            val target = File(data, name)
            if (target.exists() && target.length() > 0L) continue
            val part = File(data, "$name.part")
            try {
                ctx.assets.open("tessdata/$name").use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                // Only now does it get the name Tesseract looks for. A copy cut
                // short leaves a .part behind and is retried next time, rather
                // than leaving a truncated file that opens and then fails in a
                // way that reads as a broken install.
                if (!part.renameTo(target)) {
                    Log.e(TAG, "could not put $part in place")
                    return null
                }
            } catch (e: Exception) {
                // Almost always the file simply not being in assets yet.
                Log.e(TAG, "missing or unreadable asset tessdata/$name", e)
                part.delete()
                return null
            }
        }
        return parent
    }
}