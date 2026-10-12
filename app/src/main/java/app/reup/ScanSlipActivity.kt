// app/src/main/java/app/reup/ScanSlipActivity.kt
package app.reup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.reup.sync.Category
import app.reup.sync.SlipReading
import app.reup.sync.memoCategory
import app.reup.sync.readSlip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The screen whose job is to stop the guessing.
 *
 * It shows BOTH HALVES AT ONCE. The raw text on top, exactly as it came
 * back, and underneath it what the rules made of it. One real slip then answers
 * two questions in one look:
 *
 *   • the text is wrong        → the recogniser or the image is the problem
 *   • the text is right and
 *     the reading is wrong     → a rule in SlipText is the problem
 *
 * A screen showing only the result cannot tell those apart, and they are fixed
 * in different files. That is how the October slips were fixed: the raw text
 * from the colour picture was rubbish, so the picture was the problem, not the
 * rules. Under the reading is a third line, the working — the size the picture
 * was read at, the languages, the grey level that split ink from paper — for
 * the next time the raw text is rubbish.
 *
 * It still writes nothing itself. The button hands the reading to the money
 * screen, where a person looks at it before anything is saved.
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
    private lateinit var intro: TextView
    private lateinit var rawBox: TextView
    private lateinit var readingBox: TextView
    private lateinit var rawCard: LinearLayout
    private lateinit var readCard: LinearLayout
    private lateinit var useButton: Button

    /** The last reading, kept so the button below has something to hand over. */
    private var reading: SlipReading? = null

    /** Whether anything has been asked of this screen yet. */
    private var picked = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        // Before any view exists: everything below reads its colours out of
        // Ui, and a screen that repaints into a different theme a moment
        // after opening is worse than one with no choice at all.
        Ui.load(this)
        title = TITLE

        val screen = Ui.sticky(this)
        val column = screen.column
        column.addView(
            Ui.header(this, "อ่านสลิป", "ข้อความดิบที่ตัวอ่านเห็น กับสิ่งที่กฎอ่านออกมา"),
            Ui.row(this, 0f),
        )

        val pick = Ui.primary(this, PICK) { choose() }

        status = Ui.status(this)
        rawBox = Ui.mono(this)
        readingBox = Ui.mono(this)
        // Selectable, so a reading that looks wrong can be copied into a
        // message rather than photographed off the screen and typed out again.
        rawBox.setTextIsSelectable(true)
        readingBox.setTextIsSelectable(true)

        column.addView(status, Ui.row(this, 12f))

        // Before a slip has been chosen there is nothing to show, so nothing is
        // shown. Two labelled boxes with nothing inside them is the screen
        // claiming to have something and then not having it, and that empty
        // space was most of what this screen was.
        intro = Ui.note(
            this,
            "เลือกรูปสลิปจากในเครื่อง แล้วหน้านี้จะโชว์สองอย่าง คือข้อความดิบที่" +
                    "ตัวอ่านเห็น กับสิ่งที่กฎอ่านออกมาจากข้อความนั้น ยังไม่มีอะไรถูกบันทึก" +
                    "ลงฐานข้อมูล",
        )
        column.addView(intro, Ui.row(this, 20f))

        // Two panels rather than two blocks of text, because the whole point of
        // this screen is that they are two different answers and one of them
        // being wrong means something different from the other being wrong.
        rawCard = Ui.card(this)
        rawCard.addView(Ui.label(this, RAW))
        rawCard.addView(rawBox, Ui.row(this, 8f))
        column.addView(rawCard, Ui.row(this, 16f))

        readCard = Ui.card(this)
        readCard.addView(Ui.label(this, READ))
        readCard.addView(readingBox, Ui.row(this, 8f))
        // Inside the card rather than in the bar at the bottom, because it is
        // about these five lines and not about the screen. It also keeps the
        // bar to one main action: two filled buttons side by side is two things
        // claiming to be the point of the screen.
        useButton = Ui.primary(this, USE) { use() }
        readCard.addView(useButton, Ui.row(this, 14f))
        column.addView(readCard, Ui.row(this, 12f))

        dress()

        screen.bar.addView(pick, Ui.cell(this, true))
        setContentView(screen.root)
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

    /**
     * A panel exists while it has something in it, and not before.
     *
     * The one branch worth naming: a failed read fills the raw panel with the
     * reason and leaves the reading panel empty, and an empty reading panel
     * under a heading reads as "the rules found nothing" rather than as "the
     * rules were never asked". So it goes away instead.
     */
    private fun dress() {
        Ui.show(intro, !picked)
        Ui.show(rawCard, rawBox.text.isNotEmpty())
        Ui.show(readCard, readingBox.text.isNotEmpty())
        // Nothing to carry over without an amount, and a button that opens a
        // form with one field filled in is a button that wasted a tap.
        Ui.show(useButton, reading?.amount != null)
    }

    /**
     * Hand the reading to the money screen, filled in but not saved.
     *
     * This is the whole difference between this screen being a diagnostic and
     * being a feature, and it is the same shape the desktop uses: read, show,
     * let a person look at it, and only then write. Nothing here touches the
     * database except to read the category list.
     *
     * The note is the memo typed at the bank, with its first word taken off
     * when that word names a category, which then becomes the category. The
     * reference no longer goes in the note: it has its own column, slip_ref,
     * where it keeps the same slip from being saved twice and stays out of the
     * one box people actually read. A row can still be traced back to its slip
     * from there.
     */
    private fun use() {
        val r = reading ?: return
        val amount = r.amount ?: return
        val text =
            if (amount == amount.toLong().toDouble()) amount.toLong().toString()
            else amount.toString()
        scope.launch {
            // The same two steps a slip arriving on its own goes through: the
            // memo's first word, and failing that, where the same note went
            // last time.
            var split = memoCategory(r.memo, emptyList<Category>())
            try {
                val repo = Repo.money(this@ScanSlipActivity)
                split = memoCategory(r.memo, repo.categories())
                if (split.category == null && split.note.isNotEmpty()) {
                    split = split.copy(category = repo.categoryUsedFor(split.note))
                }
            } catch (_: Exception) {
                // No categories is a form that starts on its first chip, which
                // is what it did before slips had memos.
            }
            val intent = Intent(this@ScanSlipActivity, AddMoneyActivity::class.java)
                .putExtra(AddMoneyActivity.EXTRA_AMOUNT, text)
                .putExtra(AddMoneyActivity.EXTRA_DATE, r.date ?: "")
                .putExtra(AddMoneyActivity.EXTRA_NOTE, split.note)
            split.category?.let { intent.putExtra(AddMoneyActivity.EXTRA_CATEGORY, it) }
            r.reference?.let { intent.putExtra(AddMoneyActivity.EXTRA_SLIP_REF, it) }
            // The unit goes with the number. A slip in dollars filed under the
            // phone's own currency is a row that is wrong by a factor nobody sees,
            // so when the slip says, the form is told; and when the slip says
            // something several currencies share, the form is told that instead,
            // so it can say so next to the unit rather than look certain.
            r.currency?.let { intent.putExtra(AddMoneyActivity.EXTRA_CURRENCY, it) }
            if ("currency-ambiguous" in r.problems) intent.putExtra(AddMoneyActivity.EXTRA_CURRENCY_UNSURE, true)
            startActivity(intent)
        }
    }

    private fun scan(uri: Uri) {
        picked = true
        reading = null
        status.text = WORKING
        rawBox.text = ""
        readingBox.text = ""
        dress()
        scope.launch {
            try {
                // Off the main thread: a full-size screenshot takes long enough
                // to decode that doing it here froze the screen it was on.
                val bitmap = withContext(Dispatchers.IO) { SlipOcr.decode(this@ScanSlipActivity, uri) }
                if (bitmap == null) { status.text = NO_IMAGE; return@launch }
                val result = SlipOcr.read(this@ScanSlipActivity, bitmap)
                bitmap.recycle()
                when (result) {
                    is SlipRead.Ok -> {
                        rawBox.text = result.text
                        val read = result.reading(SlipOcr.hints(this@ScanSlipActivity))
                        reading = read
                        readingBox.text = describe(read) + "\n" +
                            "memo line ${result.memoLine ?: "—"}\n\n" +
                            "image     ${result.width}×${result.height} · ${result.languages} · cut ${result.threshold}"
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
            dress()
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
        appendLine("memo      ${r.memo ?: "—"}")
        append("problems  ${if (r.problems.isEmpty()) "none" else r.problems.joinToString(", ")}")
    }



    companion object {
        private const val PICK_IMAGE = 4101

        private const val TITLE = "\u0e2d\u0e48\u0e32\u0e19\u0e2a\u0e25\u0e34\u0e1b"
        private const val PICK = "\u0e40\u0e25\u0e37\u0e2d\u0e01\u0e23\u0e39\u0e1b\u0e2a\u0e25\u0e34\u0e1b"

        /** บันทึกเป็นรายจ่าย */
        private const val USE =
            "\u0e1a\u0e31\u0e19\u0e17\u0e36\u0e01\u0e40\u0e1b\u0e47\u0e19\u0e23\u0e32\u0e22\u0e08\u0e48\u0e32\u0e22"
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