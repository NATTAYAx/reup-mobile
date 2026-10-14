package app.reup.ui

import android.content.Context
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.reup.BankNotices
import app.reup.Notifications
import app.reup.PendingSlips
import app.reup.Repo
import app.reup.core.expenseLabel
import app.reup.core.moneyLine
import app.reup.core.moneyText
import app.reup.core.typedAmount
import app.reup.sync.CATEGORY_FALLBACK
import app.reup.sync.CURRENCY_FALLBACK
import app.reup.sync.Category
import app.reup.sync.ExpenseDraft
import app.reup.sync.IncomeDraft
import app.reup.sync.MoneyEntry
import app.reup.sync.MoneyRepo
import app.reup.sync.MoneySummary
import app.reup.sync.expenseProblems
import app.reup.sync.incomeProblems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

// ─── MoneyFormModel.kt — what the money form is drawn from ───────────────────
//
// Everything the old AddMoneyActivity kept in fields and pushed into views by
// hand, kept here as state the screen reads. The behaviour is the old screen's,
// moved rather than rewritten: the same three checks before a save, the same
// sentences, the same slip rules, the same edit-in-place. What changed is how
// it looks, and two things the old form could not do without a keyboard - the
// date is picked, not typed, and the amount box takes only what can be an
// amount.

/** What the form was opened with, read off the intent once. */
class MoneyFormStart(
    /** Open on the payment side. */
    val incoming: Boolean = false,
    /** One existing row to change, by uid; saving or cancelling goes back. */
    val editUid: String? = null,
    // A reading handed over by the slip reader or a slip that arrived on its
    // own. Strings: what a recogniser read, about to be looked at in a box.
    val amount: String? = null,
    val date: String? = null,
    val note: String? = null,
    /** An ISO code read off a slip, already checked to be three letters. */
    val currency: String? = null,
    /** The slip's money sign is one several currencies share. */
    val currencyUnsure: Boolean = false,
    /** A category a slip's memo named; ignored when it is not one of the chips. */
    val category: String? = null,
    /** The slip's reference, for slip_ref. */
    val slipRef: String? = null,
    /** A slip that arrived on its own, by gallery id; -1 when none. */
    val slipId: Long = -1L,
    /** Why that slip could not be saved with one press, as a sentence. */
    val slipCheck: String? = null,
    /** Money in read off a bank's notification, by its id; saving takes it off the waiting list. */
    val noticeId: String? = null,
)

/**
 * @param done leave the form, with what to say about it where it goes back
 *   to - "บันทึกแล้ว · ข้าวมันไก่ −฿45" - or null when there is nothing to say
 */
class MoneyFormModel(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val start: MoneyFormStart,
    private val done: (said: String?) -> Unit,
) {

    // ── what the boxes hold ─────────────────────────────────────────────────

    var incoming by mutableStateOf(start.incoming)
        private set

    // The three boxes hold their own text, as Compose's text fields want it:
    // typing goes straight in, and what is put in from outside - a slip's
    // reading, a row being edited - lands with the cursor at its end.
    val amount = TextFieldState(start.amount.orEmpty())
    val note = TextFieldState(start.note.orEmpty())

    /** The name on a payment coming in. */
    val source = TextFieldState()

    /** `YYYY-MM-DD`, as stored. */
    var date by mutableStateOf(start.date?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString())
        private set
    var category by mutableStateOf(CATEGORY_FALLBACK)
        private set

    /** `YYYY-MM-DD`, read again whenever the screen comes back. */
    var today by mutableStateOf(LocalDate.now().toString())
        private set

    // ── what the database said ──────────────────────────────────────────────

    var loaded by mutableStateOf(false)
        private set
    var categories by mutableStateOf<List<Category>>(emptyList())
        private set
    var summary by mutableStateOf<MoneySummary?>(null)
        private set
    var recent by mutableStateOf<List<MoneyEntry>>(emptyList())
        private set

    /** The unit the books are kept in, from the computer; null before the first sync. */
    var storedCurrency by mutableStateOf<String?>(null)
        private set

    /**
     * The unit this row is counted in. A slip's own unit wins over the books'
     * for the row being written, because the slip is what the row is a copy of:
     * a receipt in dollars filed under baht is wrong by a factor nobody sees.
     */
    var currency by mutableStateOf(start.currency ?: CURRENCY_FALLBACK)
        private set

    /** False while [currency] is the fallback rather than anything anyone said. */
    var currencyKnown by mutableStateOf(start.currency != null)
        private set

    // ── the slip this came from ─────────────────────────────────────────────

    /** By gallery id, for its picture; -1 when there is none, or once it is dealt with. */
    var slipId by mutableStateOf(start.slipId)
        private set
    var slipCheck by mutableStateOf(start.slipCheck)
        private set
    private var slipUnit by mutableStateOf(start.currency)
    private var slipUnsure by mutableStateOf(start.currencyUnsure)

    /**
     * Written to slip_ref with the row. Its unique index is what stops one slip
     * becoming two rows - scanned by hand and also saved from the shade, say.
     */
    private var slipRef = start.slipRef

    /**
     * The bank's notice of money in this form was opened from, if it was. A
     * payment has no slip_ref, so this is what stops one notice becoming two
     * rows: saved with one press from the money tab while this form was open.
     */
    private var noticeId = start.noticeId

    // ── editing ─────────────────────────────────────────────────────────────

    /** The row being rewritten, or null when this is a new one. */
    var editing by mutableStateOf<MoneyEntry?>(null)
        private set

    /**
     * Opened from the money tab to change one row, rather than to write new
     * ones. Cancelling then goes back to that tab instead of leaving an empty
     * form to fill in, which is not what anybody came here for.
     */
    private var editOnly = false

    /**
     * The form has been told to go. Nothing is pressed after that: the screen
     * takes a moment to slide away, and a second press on save inside that
     * moment would be a second row.
     */
    private var leaving = false

    // ── the screen's own state ──────────────────────────────────────────────

    /** Reading, writing or removing: every button waits. */
    var busy by mutableStateOf(false)
        private set

    /** A save in flight, which the button says in words. */
    var saving by mutableStateOf(false)
        private set

    /** The database would not open. Nothing can be written. */
    var blocked by mutableStateOf(false)
        private set

    var status by mutableStateOf<FormStatus?>(null)
        private set

    /** Bumped when the amount box should take the cursor. */
    var focusAmount by mutableIntStateOf(0)
        private set

    /** The unit the month's totals are in: the books', whatever this row is in. */
    val bookUnit: String
        get() = storedCurrency ?: CURRENCY_FALLBACK

    val yesterday: String
        get() = LocalDate.parse(today).minusDays(1).toString()

    /**
     * The category of the row being edited, when this phone does not list it -
     * hidden or deleted on the computer since. It is offered as a chip of its
     * own and kept on save. Without it the edit would quietly refile the row:
     * a category the phone does not know is saved as `other`, which is right
     * for a new row from a slip's memo and wrong for one somebody filed on
     * purpose.
     */
    val strayCategory: String?
        get() {
            val e = editing ?: return null
            val key = e.tag?.takeIf { it.isNotBlank() } ?: return null
            return if (e.incoming || categories.any { it.key == key }) null else key
        }

    /**
     * Why the unit beside the amount might not be the right one, or null.
     *
     * Printed rather than assumed: a number filed in the wrong unit is not
     * visibly wrong anywhere. It is simply missing from a total that filters by
     * unit, and nothing on any screen goes red.
     */
    val unitNote: String?
        get() {
            val edit = editing != null
            val unit = slipUnit
            val stored = storedCurrency
            return when {
                !edit && unit != null && unit == currency && stored != null && stored != currency ->
                    "สกุลเงินตามสลิป ไม่ใช่ $stored ที่ใช้ปกติ เช็คก่อนบันทึก"
                !edit && slipUnsure ->
                    "สลิปใช้สัญลักษณ์เงินที่หลายประเทศใช้ร่วมกัน บอกไม่ได้ว่าเป็น $currency จริงไหม เช็คก่อนบันทึก"
                !currencyKnown -> UNIT_GUESSED
                else -> null
            }
        }

    // ── opening ─────────────────────────────────────────────────────────────

    /**
     * The categories and the unit, both from the database rather than from a
     * list in this file. A hardcoded set of categories would be a second answer
     * to a question the desktop already answers, and the two would disagree the
     * first time one was renamed. An empty table is a phone before its first
     * sync, and everything files under `other`.
     */
    fun load() {
        busy = true
        scope.launch {
            var target: MoneyEntry? = null
            try {
                val repo = Repo.money(ctx)
                val known = repo.categories()
                categories = known
                category = known.firstOrNull()?.key ?: CATEGORY_FALLBACK
                start.category?.let { k -> if (known.any { it.key == k }) category = k }
                val stored = repo.currency()
                storedCurrency = stored
                currencyKnown = start.currency != null || stored != null
                currency = start.currency ?: stored ?: CURRENCY_FALLBACK
                refresh(repo)
                // The list the tab drew from is the same twenty rows, so the one
                // that was tapped is in it - unless a sync since has pushed it
                // out, or taken it away.
                val wanted = start.editUid
                if (wanted != null) {
                    target = recent.firstOrNull { it.uid == wanted }
                    if (target == null) status = warn(EDIT_GONE)
                }
            } catch (e: Exception) {
                status = warn("เปิดฐานข้อมูลไม่ได้ ${e.message ?: e.toString()}")
                blocked = true
            } finally {
                busy = false
                loaded = true
            }
            val t = target
            if (t != null) {
                editOnly = true
                startEdit(t)
            } else if (!blocked && start.editUid == null && amount.text.isEmpty()) {
                // A blank form is opened to type a number into. One that came
                // filled in is opened to be checked, and a keyboard over it
                // would hide what there is to check.
                focusAmount++
            }
        }
    }

    /** Coming back to the screen: it may be another day. */
    fun resume() {
        today = LocalDate.now().toString()
    }

    /**
     * The month so far and the last entries, always together. One call site,
     * because a screen where the total has moved and the list has not is a
     * screen saying two different things about the same save.
     */
    private suspend fun refresh(repo: MoneyRepo) {
        today = LocalDate.now().toString()
        summary = repo.summary(today.substring(0, 7), bookUnit)
        recent = repo.recent()
    }

    // ── typing and picking ──────────────────────────────────────────────────

    /**
     * Digits and one point, by typedAmount's rule: anything else is refused as
     * it is typed rather than at the save.
     *
     * Runs on what a person types and pastes only. A reading put in from a
     * slip is shown as it came, which is the point of showing it.
     */
    val amountInput: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val typed = asCharSequence().toString()
            val clean = typedAmount(typed)
            if (clean == null) {
                revertAllChanges()
                return
            }
            if (clean != typed) {
                replace(0, length, clean)
                placeCursorAtEnd()
            }
            if (clean != originalText.toString()) settle()
        }
    }

    /** Typing into the note or the name: the line about the form as it was goes. */
    val typing: InputTransformation = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            if (asCharSequence().toString() != originalText.toString()) settle()
        }
    }

    fun pickDate(iso: String) {
        if (iso == date) return
        date = iso
        settle()
    }

    fun pickCategory(key: String) {
        if (key == category) return
        category = key
        settle()
    }

    /**
     * Money out or money in. Not while editing: a row lives in one table or the
     * other, and moving it is a delete and a new row, not an edit - doing it
     * silently would leave the old one behind.
     */
    fun setDirection(value: Boolean) {
        if (editing != null || value == incoming) return
        incoming = value
        // Cleared rather than kept: a complaint left over from the other
        // direction reads as a complaint about this one.
        status = null
    }

    /**
     * A line about the form as it was is not about the form as it is now. The
     * one that stays is the note that a row is being edited, which is still
     * true.
     */
    private fun settle() {
        if (status?.tone != StatusTone.INFO) status = null
    }

    // ── editing a row ───────────────────────────────────────────────────────

    /**
     * Puts a row back into the form.
     *
     * Everything the form needs is already in the list: the amount, the unit,
     * the date, the note and the category or the name on the payment. The unit
     * comes from the row rather than from the books, because the row is what
     * is being rewritten; editing a dollar row on a phone counting in baht
     * should not quietly refile it.
     */
    fun startEdit(e: MoneyEntry) {
        // The same row again would put back what is already on the form, and
        // throw away whatever has been changed in it so far.
        if (busy || blocked || editing?.uid == e.uid) return
        // A slip this form was opened with stays waiting in the money tab, but
        // the form is about another row now, and a save must not carry the
        // slip's reference onto it.
        if (editing == null && !editOnly) detachSlip()
        editing = e
        incoming = e.incoming
        currency = e.currency
        currencyKnown = true
        amount.setTextAndPlaceCursorAtEnd(trimZero(e.amount))
        note.setTextAndPlaceCursorAtEnd(e.note ?: "")
        date = e.date
        if (e.incoming) {
            source.setTextAndPlaceCursorAtEnd(e.tag ?: "")
        } else {
            e.tag?.takeIf { it.isNotBlank() }?.let { category = it }
        }
        status = FormStatus(EDITING, StatusTone.INFO)
    }

    /** Back to writing a new row, leaving the old one exactly as it was. */
    fun cancelEdit() {
        if (editOnly) {
            leave(null)
            return
        }
        clearForm()
        status = null
    }

    /** An empty form for the next new row, in the books' unit. */
    private fun clearForm() {
        editing = null
        amount.clearText()
        note.clearText()
        source.clearText()
        date = LocalDate.now().toString()
        currency = bookUnit
        currencyKnown = storedCurrency != null
        // An edited row's own category may be one with no chip; a new row
        // starts on one that has.
        if (categories.none { it.key == category }) category = categories.firstOrNull()?.key ?: CATEGORY_FALLBACK
    }

    // ── saving ──────────────────────────────────────────────────────────────

    private fun expense() = ExpenseDraft(
        amount = amount.text.toString().trim(),
        currency = currency,
        category = category,
        note = note.text.toString().trim(),
        date = date.trim(),
        // A new row only. An edit never moves a row onto a different slip; see
        // EXPENSE_EDITABLE, which has no slip_ref for that reason.
        slipRef = if (editing == null) slipRef else null,
    )

    private fun income() = IncomeDraft(
        amount = amount.text.toString().trim(),
        source = source.text.toString().trim(),
        note = note.text.toString().trim(),
        date = date.trim(),
        currency = currency,
    )

    fun save() {
        if (leaving || busy || blocked || !loaded) return
        val known = categories.map { it.key } + listOfNotNull(strayCategory)

        // Asked here as well as inside the repository, so the screen can say
        // what is wrong without a round trip. The repository asks again because
        // it is what actually writes, and a check that only exists in front of
        // a screen is a check the next caller skips.
        val problems = if (incoming) incomeProblems(income()) else expenseProblems(expense(), known)
        if (problems.isNotEmpty()) {
            status = warn(problems.joinToString("\n") { sentence(it) })
            return
        }

        busy = true
        saving = true
        scope.launch {
            try {
                val repo = Repo.money(ctx)
                val target = editing
                // Asked before writing, so the answer is a sentence rather than
                // the unique index refusing in words nobody can read.
                val ref = slipRef
                if (target == null && !incoming && ref != null && repo.slipRecorded(ref)) {
                    status = warn(SLIP_ALREADY)
                    forgetSlip()
                    return@launch
                }
                val notice = noticeId
                if (target == null && incoming && notice != null && BankNotices.wasSaved(ctx, notice)) {
                    status = warn(NOTICE_ALREADY)
                    detachSlip()
                    return@launch
                }
                val refused = when {
                    target != null && incoming -> repo.editIncome(target.uid, income())
                    target != null -> repo.editExpense(target.uid, expense(), known)
                    incoming -> repo.addIncome(income())
                    else -> repo.addExpense(expense(), known)
                }
                if (refused.isNotEmpty()) {
                    status = warn(refused.joinToString("\n") { sentence(it) })
                    return@launch
                }
                val said = said(if (target != null) EDITED else SAVED)
                // Whichever way it was saved, the slip it came from is dealt with.
                if (target == null) forgetSlip()
                // Back to where the form was opened from, saying what was saved.
                //
                // It used to stay, with the boxes emptied for the next row, so
                // that a save could not look like a crash: the month line moved
                // and the row appeared in the list underneath. On the phone it
                // looked like neither. It looked like the form starting over,
                // as if the save were the one thing that had not happened. The
                // money tab is where the row is now, and the line along its
                // bottom names it; written down from anywhere else, a toast
                // does.
                leave(said)
            } catch (e: Exception) {
                status = warn("บันทึกไม่ได้ ${e.message ?: e.toString()}")
            } finally {
                // Left busy when leaving, so the button stays "กำลังบันทึก"
                // and pressed-out while the screen slides away.
                if (!leaving) {
                    busy = false
                    saving = false
                }
            }
        }
    }

    /**
     * A tombstone, which is what the desktop writes too.
     *
     * The list is read again afterwards rather than edited in place: a screen
     * that removes the line itself has decided the write worked before
     * anything said so.
     */
    fun delete(e: MoneyEntry) {
        if (leaving || busy || blocked) return
        busy = true
        scope.launch {
            try {
                val repo = Repo.money(ctx)
                repo.delete(e)
                // The row on the form is the one that went: there is nothing
                // left to save it over.
                if (editing?.uid == e.uid) {
                    if (editOnly) {
                        leave(DELETED)
                        return@launch
                    }
                    clearForm()
                }
                status = FormStatus(DELETED, StatusTone.DONE)
                refresh(repo)
            } catch (x: Exception) {
                status = warn("ลบไม่ได้ ${x.message ?: x.toString()}")
            } finally {
                if (!leaving) busy = false
            }
        }
    }

    /**
     * The slip or the notice this form came from is dealt with: off the
     * waiting list, out of the shade, and not attached to whatever is typed
     * in next.
     */
    private fun forgetSlip() {
        val id = slipId
        if (id >= 0L) {
            PendingSlips.remove(ctx, id)
            Notifications.cancelSlip(ctx, id)
        }
        noticeId?.let { BankNotices.savedByForm(ctx, it) }
        detachSlip()
    }

    /** The form stops being about the slip or the notice; either is left where it is. */
    private fun detachSlip() {
        slipId = -1L
        slipRef = null
        slipCheck = null
        slipUnit = null
        slipUnsure = false
        noticeId = null
    }

    /**
     * A code turned into a sentence, here and nowhere else. One list for both
     * directions, which is the visible half of the shared layer answering the
     * same three questions for each: is this a number, is it more than
     * nothing, and what unit and day is it.
     */
    private fun sentence(code: String): String = when (code) {
        "amount-missing" -> "ยังไม่ได้ใส่จำนวนเงิน"
        "amount-not-a-number" -> "จำนวนเงินต้องเป็นตัวเลข"
        "amount-not-positive" -> "จำนวนเงินต้องมากกว่าศูนย์"
        "currency-missing" -> "ยังไม่รู้ว่าเป็นเงินสกุลไหน ซิงก์กับคอมก่อน"
        // The day is picked now, never typed, so these two can only be about a
        // date that arrived from somewhere else - a slip, say.
        "date-missing" -> "ยังไม่ได้เลือกวันที่"
        "date-malformed" -> "วันที่ไม่ถูกต้อง เลือกวันใหม่"
        else -> code
    }

    private fun warn(text: String) = FormStatus(text, StatusTone.WARN)

    private fun leave(said: String?) {
        leaving = true
        done(said)
    }

    /**
     * A save in words, as the row will read on the money tab:
     * "บันทึกแล้ว · ข้าวมันไก่ −฿45", "แก้แล้ว · TELUS +$180.00".
     */
    private fun said(verb: String): String {
        val label = expenseLabel(category, categories.firstOrNull { it.key == category }?.label)
        val tag = if (incoming) source.text.toString().trim() else category
        val line = moneyLine(incoming, tag, note.text.toString(), label)
        val money = amount.text.toString().trim().toDoubleOrNull()?.let { moneyText(it, currency, if (incoming) 1 else -1) }
        return "$verb · ${line.title}" + if (money != null) " $money" else ""
    }

    private companion object {
        /**
         * `60.0` as `60`, because that is what was typed in - and twenty
         * million as 20000000 rather than the 2.0E7 a Double prints itself as.
         */
        fun trimZero(v: Double): String =
            java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

        const val SLIP_ALREADY = "สลิปใบนี้บันทึกไปแล้ว"
        const val NOTICE_ALREADY = "เงินเข้ารายการนี้บันทึกไปแล้ว"
        const val UNIT_GUESSED = "ยังไม่เคยซิงก์สกุลเงินจากคอม อันนี้เป็นค่าตั้งต้น เช็คก่อนบันทึก"
        const val SAVED = "บันทึกแล้ว"
        const val EDITED = "แก้แล้ว"
        const val DELETED = "ลบแล้ว"
        const val EDITING = "กำลังแก้รายการนี้"
        const val EDIT_GONE = "ไม่เจอรายการนี้แล้ว อาจถูกลบไปแล้ว"
    }
}
