// app/src/main/java/app/reup/SlipOcr.kt
package app.reup

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * Copied by size rather than by "does it exist", because the failure worth
 * catching is a half-written file from a copy that was interrupted — which
 * exists, and which Tesseract will open and then fail on in a way that reads as
 * a broken install rather than a broken file.
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
     * Every line of text on the image, or an empty string.
     *
     * Empty rather than an exception on failure: this is called from a screen
     * whose job is to show what was read, and a blank page there is a readable
     * answer. What went wrong goes to the log, where it can be looked at without
     * being put in front of somebody who was trying to record a coffee.
     */
    suspend fun read(ctx: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dir = prepare(ctx) ?: return@withContext ""
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dir.absolutePath, LANGUAGES)) {
                Log.e(TAG, "tesseract refused to initialise with $LANGUAGES in $dir")
                return@withContext ""
            }
            tess.setImage(bitmap)
            tess.utF8Text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "could not read the image", e)
            ""
        } finally {
            // Native memory. Not garbage collected, and a second scan on a
            // leaked instance is how this ends up being blamed for the phone
            // getting warm.
            try { tess.recycle() } catch (_: Exception) { }
        }
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
            try {
                val expected = ctx.assets.openFd("tessdata/$name").use { it.length }
                if (target.exists() && target.length() == expected) continue
                ctx.assets.open("tessdata/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                // Almost always the file simply not being in assets yet. Said
                // plainly, because the alternative is Tesseract failing to
                // initialise later with a message about a language pack.
                Log.e(TAG, "missing or unreadable asset tessdata/$name", e)
                return null
            }
        }
        return parent
    }
}