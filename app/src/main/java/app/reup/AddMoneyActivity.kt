package app.reup

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.reup.sync.Category
import app.reup.sync.ExpenseDraft
import app.reup.sync.IncomeDraft
import app.reup.sync.CURRENCY_FALLBACK
import app.reup.sync.MoneyEntry
import app.reup.sync.MoneySummary
import app.reup.sync.expenseProblems
import app.reup.sync.incomeProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Money, written down where it happens.
 *
 * The money going out is spent standing up — at a counter, on a bus, in a shop
 * — and the machine it was being recorded on is at a desk. Everything about
 * that gap is why the numbers in the app have never quite been the numbers.
 *
 * WHY BOTH DIRECTIONS ARE ONE SCREEN
 * ----------------------------------
 * They differ in two controls. Spending has category chips read from the
 * database; a payment has a box for the name on it. Everything else — the
 * amount, the unit beside it, the note, the date, every sentence the validator
 * can say — is the same, because the shared layer answers the same three
 * questions for both and returns the same codes.
 *
 * A second activity would be a copy of all of that on the one module where
 * nothing can be tested, which is the worst place to keep two of something. The
 * add and edit forms for tasks are one screen for the same reason.
 *
 * WHY THE CURRENCY IS ON SCREEN AND NOT ASSUMED
 * ---------------------------------------------
 * A number filed in the wrong unit is not visibly wrong anywhere. It is not a
 * red row or a failed save; it is simply in the wrong total, and every screen
 * that reads that total filters by unit and quietly leaves it out. money.ts
 * spends a page on this and the desktop still had to grow a "which currencies
 * are actually in your books" screen to make it findable.
 *
 * So the unit is printed next to the amount box, always, and where it came from
 * is printed under it. A person who sees THB when they meant USD can see it
 * before pressing anything.
 *
 * WHY THE LAST FEW ENTRIES ARE ON THIS SCREEN
 * ------------------------------------------
 * The line at the top says how much has gone this month, and a total is the one
 * thing a total cannot tell you: whether the coffee at eleven went in twice. Two
 * identical rows move it by exactly what one row for twice the price would.
 *
 * It is also the receipt. Until now this screen closed itself on a successful
 * save, so the only difference between a row landing and the app falling over
 * was that both ended with the screen gone. Now the top line moves and the row
 * appears at the head of the list — two confirmations that do not share a cause.
 *
 * WHY IT DOES NOT REFUSE WHEN THERE IS NO SETTING YET
 * ---------------------------------------------------
 * A phone that has not synced has no currency row, and refusing to record
 * anything until it has is a worse answer than the desktop's own: that one
 * guesses once from the machine and writes the guess down. This falls back to
 * the same value money.ts falls back to and says so on screen, which keeps the
 * number and makes the assumption visible instead of silent.
 */
class AddMoneyActivity : Activity() {

    private lateinit var amountBox: EditText
    private lateinit var sourceBox: EditText
    private lateinit var noteBox: EditText
    private lateinit var dateBox: EditText
    private lateinit var unitLabel: TextView
    private lateinit var unitNote: TextView
    private lateinit var categoryLabel: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var categoryRow: LinearLayout
    private lateinit var directionRow: LinearLayout
    private lateinit var outButton: Button
    private lateinit var inButton: Button
    private lateinit var saveButton: Button
    private lateinit var status: TextView
    private lateinit var monthLine: TextView
    private lateinit var recentLabel: TextView
    private lateinit var recentHint: TextView
    private lateinit var recentBox: LinearLayout

    private val chips = mutableListOf<Button>()
    private var categories: List<Category> = emptyList()
    private var recent: List<MoneyEntry> = emptyList()
    private var category = "other"
    private var currency = CURRENCY_FALLBACK
    private var currencyKnown = false
    private var incoming = false
    private var busy = false
    private var blocked = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incoming = intent.getBooleanExtra(EXTRA_INCOMING, false)

        amountBox = field("จำนวนเงิน", InputType.TYPE_NUMBER_FLAG_DECIMAL)
        sourceBox = field("เช่น TELUS", 0)
        noteBox = field("รายละเอียด", 0)
        dateBox = field("วันที่", 0)
        dateBox.setText(LocalDate.now().toString())

        unitLabel = label("")
        unitNote = note("")
        categoryLabel = label("หมวด")
        sourceLabel = label("ได้รับจาก")

        outButton = Button(this)
        outButton.text = "จ่ายออก"
        outButton.setOnClickListener { setDirection(false) }
        inButton = Button(this)
        inButton.text = "รับเข้า"
        inButton.setOnClickListener { setDirection(true) }

        directionRow = LinearLayout(this)
        directionRow.orientation = LinearLayout.HORIZONTAL
        directionRow.addView(outButton, chipParams())
        directionRow.addView(inButton, chipParams())

        categoryRow = LinearLayout(this)
        categoryRow.orientation = LinearLayout.VERTICAL

        saveButton = Button(this)
        saveButton.setOnClickListener { save() }

        monthLine = note("")
        recentLabel = label(RECENT_TITLE)
        recentHint = note(RECENT_HINT)
        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.VERTICAL

        status = TextView(this)
        status.setTextColor(Color.parseColor("#E8E8EA"))
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        status.typeface = Typeface.MONOSPACE

        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(48, 48, 48, 64)
        // Above everything, because the useful thing to know while deciding
        // whether to buy something is what has already gone this month.
        column.addView(monthLine, rowParams())
        column.addView(directionRow, rowParams())
        column.addView(unitLabel, rowParams())
        column.addView(amountBox, rowParams())
        column.addView(unitNote, rowParams())
        column.addView(categoryLabel, rowParams())
        column.addView(categoryRow, rowParams())
        column.addView(sourceLabel, rowParams())
        column.addView(sourceBox, rowParams())
        column.addView(noteBox, rowParams())
        column.addView(label("วันที่"), rowParams())
        column.addView(dateBox, rowParams())
        column.addView(saveButton, rowParams())
        column.addView(status, rowParams())
        column.addView(recentLabel, rowParams())
        column.addView(recentHint, rowParams())
        column.addView(recentBox, rowParams())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#0F0F12"))
        scroll.addView(column)
        setContentView(scroll)

        render()
        load()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun setDirection(value: Boolean) {
        incoming = value
        // Cleared rather than kept. The old message was about the form as it
        // was a moment ago, and a complaint left over from the other direction
        // reads as a complaint about this one.
        status.text = ""
        render()
    }

    /**
     * The categories and the unit, both from the database rather than from a
     * list in this file.
     *
     * A hardcoded set of categories here would be a second answer to a question
     * the desktop already answers, and the two would disagree the first time
     * one was renamed. An empty table is not a failure either — it is what a
     * phone looks like before its first sync, and everything files under
     * `other`, which is the behaviour rather than a bug.
     */
    private fun load() {
        busy = true
        render()
        scope.launch {
            try {
                val repo = Repo.money(this@AddMoneyActivity)
                categories = repo.categories()
                category = categories.firstOrNull()?.key ?: "other"
                val stored = repo.currency()
                currencyKnown = stored != null
                currency = stored ?: CURRENCY_FALLBACK
                buildChips()
                refresh(repo)
            } catch (e: Exception) {
                status.text = "เปิดฐานข้อมูลไม่ได้ ${e.message ?: e.toString()}"
                blocked = true
            } finally {
                busy = false
                render()
            }
        }
    }

    /**
     * The month so far, in one line.
     *
     * Whole units, no decimals: this is a glance while standing up, and the
     * satang have never been the part that decides anything.
     *
     * The count of rows in other units is printed rather than dropped. A screen
     * that filters by currency in silence is how a confident, wrong-looking
     * zero happens — the desktop needed a whole extra panel to make that
     * findable after the fact.
     */
    private fun showMonth(m: MoneySummary) {
        val out = m.spent.toLong()
        val income = m.received.toLong()
        val line = StringBuilder("เดือนนี้  จ่าย $out  รับ $income  $currency")
        // Not "look on the computer" any more. The list below does not filter by
        // unit, so the rows this total left out are a few centimetres away.
        if (m.otherRows > 0) line.append("\n(อีก ${m.otherRows} รายการเป็นสกุลอื่น อยู่ในรายการข้างล่าง)")
        monthLine.text = line.toString()
    }

    /**
     * The two reads that answer "how is the month" and "what did I write down",
     * always together.
     *
     * One call site rather than two, because a screen where the total has moved
     * and the list has not is a screen saying two different things about the
     * same save.
     */
    private suspend fun refresh(repo: app.reup.sync.MoneyRepo) {
        val today = LocalDate.now().toString()
        showMonth(repo.summary(today.substring(0, 7), currency))
        recent = repo.recent()
        showRecent(today)
    }

    /**
     * The last twenty entries, newest first.
     *
     * Each row prints its own unit, which is why this list is allowed not to
     * filter by one: nothing here is being added together. The totals above have
     * to filter, and this is where what they filtered out becomes visible rather
     * than merely counted.
     *
     * The category is looked up in the table the chips came from, so a renamed
     * label reads the same in both places, and one that is not there any more
     * falls back to the key rather than to nothing — a row filed under a
     * category that has since been deleted is still a row that happened.
     */
    private fun showRecent(today: String) {
        recentBox.removeAllViews()
        if (recent.isEmpty()) {
            recentBox.addView(note(if (currencyKnown) NO_ENTRIES else NO_ENTRIES_YET), rowParams())
            return
        }
        for (e in recent) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL

            val left = TextView(this)
            left.text = "${dayOf(e.date, today)}  ${nameOf(e)}"
            left.setTextColor(Color.parseColor("#B9B9C0"))
            left.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            left.maxLines = 1
            left.ellipsize = android.text.TextUtils.TruncateAt.END

            val right = TextView(this)
            // The sign carries the direction on its own; the colour only agrees
            // with it, so nothing is lost reading this in sunlight.
            val sign = if (e.incoming) "+" else "-"
            right.text = "$sign${e.amount.toLong()} ${e.currency}"
            right.setTextColor(Color.parseColor(if (e.incoming) "#8ED0A8" else "#E8E8EA"))
            right.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            right.typeface = Typeface.MONOSPACE

            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(left, lp)
            row.addView(
                right,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            // Long press rather than a delete button on every line. A button
            // sitting next to twenty amounts is twenty chances to remove the
            // wrong one with a thumb, and removing a row is not the thing this
            // screen is for.
            row.setOnLongClickListener { confirmDelete(e); true }

            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            p.topMargin = 16
            recentBox.addView(row, p)
        }
    }

    /** `2026-08-20` as something short, and as a word when it is today. */
    private fun dayOf(date: String, today: String): String =
        if (date == today) TODAY else if (date.length >= 10) date.substring(5).replace('-', '/') else date

    /** What the row is about: the category going out, the name coming in. */
    private fun nameOf(e: MoneyEntry): String {
        // Named memo rather than note: this file already has a note() for the
        // field and a note() for a grey label, and a local that shadows both is
        // one edit away from an error nobody expects.
        val memo = e.note?.trim().orEmpty()
        if (e.incoming) {
            val from = e.tag?.trim().orEmpty()
            return listOf(from, memo).filter { it.isNotEmpty() }.joinToString("  ").ifEmpty { INCOMING }
        }
        val c = categories.firstOrNull { it.key == e.tag }
        val label = c?.let { it.emoji + " " + (it.label ?: it.key) } ?: (e.tag ?: "")
        return listOf(label, memo).filter { it.isNotEmpty() }.joinToString("  ").ifEmpty { OUTGOING }
    }

    /**
     * Asks before removing, and says which row it means.
     *
     * The dialog repeats the amount and the name because the row that was
     * pressed and the row being described have to be the same one, and a thumb
     * on a list of twenty short lines is not evidence of that. Naming it is what
     * turns a mis-press into a cancel rather than into a number that is gone.
     *
     * There is no undo behind this. What there is instead is the desktop: the
     * row is tombstoned rather than removed, so it is still there to be looked
     * at on a bigger screen, and building an undo here would be a second way of
     * putting a row back that only one of the two machines knows about.
     */
    private fun confirmDelete(e: MoneyEntry) {
        if (busy || blocked) return
        val sign = if (e.incoming) "+" else "-"
        AlertDialog.Builder(this)
            .setTitle(DELETE_TITLE)
            .setMessage("${nameOf(e)}\n$sign${e.amount.toLong()} ${e.currency}")
            .setNegativeButton(DELETE_NO, null)
            .setPositiveButton(DELETE_YES) { _, _ -> doDelete(e) }
            .show()
    }

    /**
     * A tombstone, which is what the desktop writes too.
     *
     * The list is not edited in place afterwards, it is read again. A screen
     * that removes the line itself is a screen that has decided the write
     * succeeded before anything told it so, and this is the one action here
     * where being wrong about that means a number nobody can see any more.
     */
    private fun doDelete(e: MoneyEntry) {
        busy = true
        render()
        scope.launch {
            try {
                val repo = Repo.money(this@AddMoneyActivity)
                repo.delete(e)
                status.text = DELETED
                refresh(repo)
            } catch (x: Exception) {
                status.text = "\u0e25\u0e1a\u0e44\u0e21\u0e48\u0e44\u0e14\u0e49 ${x.message ?: x.toString()}"
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun buildChips() {
        categoryRow.removeAllViews()
        chips.clear()
        if (categories.isEmpty()) {
            categoryRow.addView(note(NO_CATEGORIES), rowParams())
            return
        }
        var row = newChipRow()
        for ((i, c) in categories.withIndex()) {
            if (i % 3 == 0 && i > 0) {
                categoryRow.addView(row, rowParams())
                row = newChipRow()
            }
            val b = Button(this)
            b.text = c.emoji + " " + (c.label ?: c.key)
            b.tag = c.key
            b.setOnClickListener { category = c.key; render() }
            chips.add(b)
            row.addView(b, chipParams())
        }
        categoryRow.addView(row, rowParams())
    }

    private fun newChipRow(): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    /**
     * Everything that changes on screen, in one place, after every change.
     *
     * Rows are hidden rather than disabled: a control that does not apply to
     * the direction chosen is not temporarily unavailable, it has no meaning,
     * and leaving it visible-but-grey invites filling it in and wondering why
     * nothing happened.
     */
    private fun render() {
        title = if (incoming) "บันทึกรายรับ" else "บันทึกรายจ่าย"

        outButton.alpha = if (incoming) 0.55f else 1f
        inButton.alpha = if (incoming) 1f else 0.55f
        outButton.typeface = if (incoming) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        inButton.typeface = if (incoming) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

        unitLabel.text = "จำนวนเงิน ($currency)"
        unitNote.text = if (currencyKnown) "" else UNIT_GUESSED
        unitNote.visibility = if (currencyKnown) TextView.GONE else TextView.VISIBLE

        val show = { v: android.view.View, on: Boolean ->
            v.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        }
        show(categoryLabel, !incoming)
        show(categoryRow, !incoming)
        show(sourceLabel, incoming)
        show(sourceBox, incoming)

        for (b in chips) {
            val mine = b.tag == category
            b.alpha = if (mine) 1f else 0.55f
            b.typeface = if (mine) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        saveButton.isEnabled = !busy && !blocked
        saveButton.text = if (busy) "กำลังบันทึก" else "บันทึก"
    }

    private fun amount() = amountBox.text.toString().trim()
    private fun note() = noteBox.text.toString().trim()
    private fun date() = dateBox.text.toString().trim()

    private fun expense() = ExpenseDraft(
        amount = amount(),
        currency = currency,
        category = category,
        note = note(),
        date = date(),
    )

    private fun income() = IncomeDraft(
        amount = amount(),
        source = sourceBox.text.toString().trim(),
        note = note(),
        date = date(),
        currency = currency,
    )

    private fun save() {
        if (busy || blocked) return
        val known = categories.map { it.key }

        // Asked here as well as inside the repository, so the screen can say
        // what is wrong without a round trip. The repository asks again because
        // it is what actually writes, and a check that only exists in front of
        // a screen is a check the next caller skips.
        val problems =
            if (incoming) incomeProblems(income()) else expenseProblems(expense(), known)
        if (problems.isNotEmpty()) {
            status.text = problems.joinToString("\n") { sentence(it) }
            return
        }

        busy = true
        render()
        scope.launch {
            try {
                val repo = Repo.money(this@AddMoneyActivity)
                val refused =
                    if (incoming) repo.addIncome(income()) else repo.addExpense(expense(), known)
                if (refused.isNotEmpty()) {
                    status.text = refused.joinToString("\n") { sentence(it) }
                    return@launch
                }
                // No queue to rebuild: money does not ring. It goes out on the
                // next sync like any other row.
                //
                // And the screen stays. Closing on success meant the only sign a
                // row had landed was the screen being gone, which is also what a
                // crash looks like. Now the top line moves and the row appears at
                // the head of the list underneath.
                //
                // Clearing the amount is the guard against a second press as
                // well as a courtesy: the validator refuses an empty one, so the
                // duplicate this screen could most easily produce cannot be
                // produced by pressing the same button twice. The direction, the
                // category and the date are kept, because the next thing written
                // down at a counter is usually the same kind of thing.
                amountBox.setText("")
                noteBox.setText("")
                if (incoming) sourceBox.setText("")
                amountBox.requestFocus()
                status.text = SAVED
                refresh(repo)
            } catch (e: Exception) {
                status.text = "บันทึกไม่ได้ ${e.message ?: e.toString()}"
            } finally {
                busy = false
                render()
            }
        }
    }

    /**
     * A code turned into a sentence, here and nowhere else.
     *
     * One list for both directions, which is the visible half of the shared
     * layer answering the same three questions for each: is this a number, is
     * it more than nothing, and what unit is it counted in.
     */
    private fun sentence(code: String): String = when (code) {
        "amount-missing" -> "ยังไม่ได้ใส่จำนวนเงิน"
        "amount-not-a-number" -> "จำนวนเงินต้องเป็นตัวเลข"
        "amount-not-positive" -> "จำนวนเงินต้องมากกว่าศูนย์"
        "currency-missing" -> "ยังไม่รู้ว่าเป็นเงินสกุลไหน ซิงก์กับคอมก่อน"
        "date-missing" -> "ยังไม่ได้ใส่วันที่"
        "date-malformed" -> "วันที่ต้องเป็นแบบ 2026-08-20"
        else -> code
    }

    private fun field(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = if (type == 0) InputType.TYPE_CLASS_TEXT else InputType.TYPE_CLASS_NUMBER or type
        e.setTextColor(Color.parseColor("#E8E8EA"))
        e.setHintTextColor(Color.parseColor("#6B6B72"))
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        return e
    }

    private fun label(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#E8E8EA"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun note(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(Color.parseColor("#8A8A92"))
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        return t
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = 24
        return p
    }

    private fun chipParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    companion object {
        /** Open straight into the payment side. Absent means spending. */
        const val EXTRA_INCOMING = "app.reup.incoming"


        private const val UNIT_GUESSED =
            "ยังไม่เคยซิงก์สกุลเงินจากคอม อันนี้เป็นค่าตั้งต้น เช็คก่อนบันทึก"

        private const val RECENT_TITLE = "ล่าสุด"

        /** Nothing on a row says it can be pressed, so the heading says it. */
        private const val RECENT_HINT = "กดค้างที่รายการเพื่อลบ"

        private const val SAVED = "บันทึกแล้ว"

        private const val DELETED = "ลบแล้ว"

        private const val DELETE_TITLE = "ลบรายการนี้"

        private const val DELETE_YES = "ลบ"

        private const val DELETE_NO = "ไม่ลบ"

        private const val TODAY = "วันนี้"

        /** A row with neither a label nor a note still has to say something. */
        private const val OUTGOING = "รายจ่าย"

        private const val INCOMING = "รายรับ"

        /**
         * Two empty states, because they are two different facts.
         *
         * A phone that has synced and has nothing is a person who has not
         * written anything down. A phone that has never synced is a phone that
         * has not been told anything yet, and telling it to start recording
         * would be answering a question it was not asked.
         */
        private const val NO_ENTRIES = "ยังไม่มีรายการในเครื่องนี้"

        private const val NO_ENTRIES_YET =
            "ยังไม่มีรายการในเครื่องนี้ ซิงก์กับคอมแล้วจะดึงของเก่าลงมา"

        private const val NO_CATEGORIES =
            "ยังไม่มีหมวดในเครื่องนี้ จะบันทึกเป็น other แล้วแก้บนคอมทีหลังได้"
    }
}