// app/src/main/java/app/reup/ScanSlipActivity.kt
package app.reup

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import app.reup.sync.SlipReading
import app.reup.sync.readSlip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The screen whose job is to stop the guessing.
 *
 * SlipText was written against field shapes taken from real slips, and tested
 * against text assembled by hand. Nobody has yet seen what a recogniser
 * actually returns for a Thai slip: the line order, what it does with the
 * two-column layouts, how badly the Thai comes out at that resolution.
 *
 * So this shows BOTH HALVES AT ONCE. The raw text on top, exactly as it came
 * back, and underneath it what the rules made of it. One real slip then answers
 * two questions in one look:
 *
 *   • the text is wrong        → the recogniser or the image is the problem
 *   • the text is right and
 *     the reading is wrong     → a rule in SlipText is the problem
 *
 * A screen showing only the result cannot tell those apart, and they are fixed
 * in different files. This is the entire reason this screen exists, and it is
 * why it does not write anything to the database yet: adding a row would make
 * it a feature, and a feature invites being used before anybody has looked at
 * whether it reads slips correctly.
 *
 * WHY A PICTURE FROM THE GALLERY AND NOT THE CAMERA
 * -------------------------------------------------
 * A slip is a screenshot. It is already in the phone, already flat, already in
 * focus, already the right way up. Pointing a camera at a screen adds glare,
 * keystone and a whole class of failure that has nothing to do with anything
 * being tested here. The camera can come later if paper receipts ever matter.
 */
class ScanSlipActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var status: TextView
    private lateinit var rawBox: TextView
    private lateinit var readingBox: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        title = TITLE

        val screen = Ui.screen(this)
        val column = screen.column

        val pick = Ui.primary(this, PICK) { choose() }

        status = Ui.note(this, "")
        rawBox = Ui.mono(this)
        readingBox = Ui.mono(this)
        // Selectable, so a reading that looks wrong can be copied into a
        // message rather than photographed off the screen and typed out again.
        rawBox.setTextIsSelectable(true)
        readingBox.setTextIsSelectable(true)

        column.addView(pick, Ui.row(this, 0f))
        column.addView(status, Ui.row(this, 8f))

        // Two panels rather than two blocks of text, because the whole point of
        // this screen is that they are two different answers and one of them
        // being wrong means something different from the other being wrong.
        val rawCard = Ui.card(this)
        rawCard.addView(Ui.label(this, RAW))
        rawCard.addView(rawBox, Ui.row(this, 8f))
        column.addView(rawCard, Ui.row(this, 16f))

        val readCard = Ui.card(this)
        readCard.addView(Ui.label(this, READ))
        readCard.addView(readingBox, Ui.row(this, 8f))
        column.addView(readCard, Ui.row(this, 12f))

        setContentView(screen.scroll)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun choose() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        scan(uri)
    }

    private fun scan(uri: Uri) {
        status.text = WORKING
        rawBox.text = ""
        readingBox.text = ""
        scope.launch {
            try {
                val bitmap = load(uri)
                if (bitmap == null) { status.text = NO_IMAGE; return@launch }
                val result = SlipOcr.read(this@ScanSlipActivity, bitmap)
                bitmap.recycle()
                when (result) {
                    is SlipRead.Ok -> {
                        rawBox.text = result.text
                        readingBox.text = describe(readSlip(result.text))
                    }
                    // The three below are why this returns a result and not a
                    // string. They are fixed in three different places, and a
                    // blank page says none of that.
                    is SlipRead.MissingData -> {
                        rawBox.text = NO_DATA
                        readingBox.text = "assets/tessdata: ${result.present}"
                    }
                    SlipRead.InitFailed -> {
                        rawBox.text = INIT_FAILED
                        readingBox.text = ""
                    }
                    SlipRead.NoText -> {
                        rawBox.text = NOTHING_READ
                        readingBox.text = describe(readSlip(""))
                    }
                    is SlipRead.Failed -> {
                        rawBox.text = "${FAILED} ${result.message}"
                        readingBox.text = ""
                    }
                }
                status.text = ""
            } catch (e: Exception) {
                status.text = "${FAILED} ${e.message ?: e.toString()}"
            }
        }
    }

    /**
     * The image, scaled down if it is enormous.
     *
     * Tesseract wants resolution and a phone screenshot already has plenty. What
     * it does not want is a forty megapixel photograph, which is slow and, on a
     * device with little memory left, is the one line in this file that can take
     * the whole app down.
     */
    private fun load(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * The reading, spelled out field by field including the empty ones.
     *
     * A field that read as nothing is the interesting result here, so it gets a
     * line of its own rather than being left out — a short list of what was
     * found reads like success no matter how much was missed.
     */
    private fun describe(r: SlipReading): String = buildString {
        appendLine("amount    ${r.amount ?: "—"}")
        appendLine("currency  ${r.currency ?: "—"}")
        appendLine("date      ${r.date ?: "—"}")
        appendLine("reference ${r.reference ?: "—"}")
        append("problems  ${if (r.problems.isEmpty()) "none" else r.problems.joinToString(", ")}")
    }



    companion object {
        private const val PICK_IMAGE = 4101
        private const val MAX_EDGE = 2200

        private const val TITLE = "\u0e2d\u0e48\u0e32\u0e19\u0e2a\u0e25\u0e34\u0e1b"
        private const val PICK = "\u0e40\u0e25\u0e37\u0e2d\u0e01\u0e23\u0e39\u0e1b\u0e2a\u0e25\u0e34\u0e1b"
        private const val WORKING = "\u0e01\u0e33\u0e25\u0e31\u0e07\u0e2d\u0e48\u0e32\u0e19"
        private const val FAILED = "\u0e2d\u0e48\u0e32\u0e19\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49"
        private const val NO_IMAGE = "\u0e40\u0e1b\u0e34\u0e14\u0e23\u0e39\u0e1b\u0e19\u0e35\u0e49\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49"
        private const val NOTHING_READ =
            "(\u0e2d\u0e48\u0e32\u0e19\u0e44\u0e14\u0e49 \u0e41\u0e15\u0e48\u0e44\u0e21\u0e48\u0e21\u0e35\u0e15\u0e31\u0e27\u0e2b\u0e19\u0e31\u0e07\u0e2a\u0e37\u0e2d\u0e1a\u0e19\u0e20\u0e32\u0e1e)"

        /** Not the same failure, and not fixed in the same file. */
        private const val NO_DATA =
            "(\u0e44\u0e21\u0e48\u0e1e\u0e1a\u0e44\u0e1f\u0e25\u0e4c\u0e20\u0e32\u0e29\u0e32 tha.traineddata \u0e43\u0e19 assets/tessdata)"

        private const val INIT_FAILED =
            "(\u0e21\u0e35\u0e44\u0e1f\u0e25\u0e4c\u0e20\u0e32\u0e29\u0e32 \u0e41\u0e15\u0e48 tesseract \u0e40\u0e23\u0e34\u0e48\u0e21\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49)"

        /** Deliberately in English: these two blocks are for reading, not using. */
        private const val RAW = "raw text"
        private const val READ = "what the rules made of it"
    }
}