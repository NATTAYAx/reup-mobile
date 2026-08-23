// app/src/main/java/app/reup/ScanSlipActivity.kt
package app.reup

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(40, 40, 40, 40)
        column.setBackgroundColor(Color.parseColor("#0E0E12"))

        val pick = Button(this)
        pick.text = PICK
        pick.setOnClickListener { choose() }

        status = note("")
        rawBox = mono()
        readingBox = mono()

        column.addView(pick, row())
        column.addView(status, row())
        column.addView(label(RAW), row())
        column.addView(rawBox, row())
        column.addView(label(READ), row())
        column.addView(readingBox, row())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0E0E12"))
        scroll.addView(column)
        setContentView(scroll)
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
                val text = SlipOcr.read(this@ScanSlipActivity, bitmap)
                bitmap.recycle()
                rawBox.text = if (text.isBlank()) NOTHING_READ else text
                readingBox.text = describe(readSlip(text))
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

    private fun label(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.setTextColor(Color.parseColor("#8A8A93"))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        return v
    }

    private fun note(text: String): TextView {
        val v = TextView(this)
        v.text = text
        v.setTextColor(Color.parseColor("#B9B9C0"))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        return v
    }

    private fun mono(): TextView {
        val v = TextView(this)
        v.setTextColor(Color.parseColor("#E8E8EA"))
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.typeface = Typeface.MONOSPACE
        // Selectable so a reading that looks wrong can be copied into a message
        // rather than photographed off the screen and typed out again.
        v.setTextIsSelectable(true)
        return v
    }

    private fun row(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = 24
        return p
    }

    companion object {
        private const val PICK_IMAGE = 4101
        private const val MAX_EDGE = 2200

        private const val TITLE = "\u0e2d\u0e48\u0e32\u0e19\u0e2a\u0e25\u0e34\u0e1b"
        private const val PICK = "\u0e40\u0e25\u0e37\u0e2d\u0e01\u0e23\u0e39\u0e1b\u0e2a\u0e25\u0e34\u0e1b"
        private const val WORKING = "\u0e01\u0e33\u0e25\u0e31\u0e07\u0e2d\u0e48\u0e32\u0e19"
        private const val FAILED = "\u0e2d\u0e48\u0e32\u0e19\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49"
        private const val NO_IMAGE = "\u0e40\u0e1b\u0e34\u0e14\u0e23\u0e39\u0e1b\u0e19\u0e35\u0e49\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49"
        private const val NOTHING_READ = "(\u0e44\u0e21\u0e48\u0e21\u0e35\u0e02\u0e49\u0e2d\u0e04\u0e27\u0e32\u0e21)"

        /** Deliberately in English: these two blocks are for reading, not using. */
        private const val RAW = "raw text"
        private const val READ = "what the rules made of it"
    }
}