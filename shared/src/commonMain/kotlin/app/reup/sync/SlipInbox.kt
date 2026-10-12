// shared/src/commonMain/kotlin/app/reup/sync/SlipInbox.kt
//
// A slip that arrived on its own, turned into something a person can say yes to.
//
// ─── WHERE THESE COME FROM ───────────────────────────────────────────────────
//
// K PLUS and Krungthai NEXT save every slip into an album on the phone the
// moment a transfer goes through, with whatever was typed into บันทึกช่วยจำ
// printed at the bottom. The phone side watches the albums the person picked,
// reads each new picture with the same recogniser and the same rules as the
// scan screen, and hands the result to this file. What leaves here is a draft
// and two lines of words, never a row. Writing the row is still somebody's
// press; the only thing that changed is that the press can be on the
// notification instead of on the form.
//
// ─── WHY THE FILE'S CLOCK IS TRUSTED, AND HOW FAR ────────────────────────────
//
// A slip in a bank's album was saved by the bank's app when the money went, so
// the moment the picture was saved is the moment of the transfer. That settles
// the date on slips the recogniser cannot read a date off — on the first two
// tried, K PLUS's 11 came back as `Ti` and Krungthai's ต.ค. as `a.A.` — but
// only when the slip agrees: a time of day printed on it within a few minutes
// of the file's. A slip saved again from the bank's history a week later has a
// file a week newer than the money, its printed time will not match, and it
// waits for a person instead.
//
// When the slip has a date of its own, that one is used and the file only has
// to be within a day of it — a transfer at 23:59 saved at 00:01 is still the
// same transfer. Further apart than that is a slip saved long after the fact.
//
// ─── WHAT GETS ONE PRESS AND WHAT DOES NOT ───────────────────────────────────
//
// One press saves only what has nothing left to check: an amount the rules
// were sure of, a unit, a date that agrees with the file, and money that went
// to somebody else. Anything short of that opens the form already filled in,
// with the reason written on it. A wrong row that looks right is the one thing
// this feature could add to the ledger, and the button is missing exactly
// where that could happen.
//
// ─── THE CATEGORY IS TYPED AT THE BANK ───────────────────────────────────────
//
// The memo is the only thing on the slip the person chose, so it is where the
// category comes from: "อาหาร ข้าวมันไก่" is food, with ข้าวมันไก่ as the note.
// No space is needed — "อาหารทดสอบ" is what was actually typed the first time.
// A memo that names no category is looked up against the notes already in the
// books, so ข้าวมันไก่ goes wherever ข้าวมันไก่ went last time.

package app.reup.sync

import app.reup.core.expenseLabel
import app.reup.core.moneyDayTitle
import app.reup.core.moneyText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.math.abs

// ─── the memo ────────────────────────────────────────────────────────────────

/** A memo, split into the category its first word named and the rest of it. */
data class MemoSplit(
    /** A category key, or null when the memo does not start with one. */
    val category: String?,
    /** What is left to go in the note. The whole memo when no category was named. */
    val note: String,
)

/**
 * Which category a memo starts with, by its name on screen or by its key.
 *
 * The longest name wins, so a category called ค่าบิลมือถือ beats ค่าบิล. A name
 * of one character never matches anything — every memo starts with some
 * letter. And a match that would end in the middle of a Thai syllable, with a
 * vowel or tone mark still to come, is not a match: the name has to finish
 * where a letter could start.
 *
 * The names are the ones the screen shows: a built-in category's Thai label
 * (อาหาร for food), anything somebody named themselves, and each half of a name
 * like เกม/กาชา. Keys count when they are words, which is what lets an
 * English memo say "food lunch".
 */
fun memoCategory(memo: String?, categories: List<Category>): MemoSplit {
    val text = sameAm(memo?.trim().orEmpty()).replace(Regex("""\s+"""), " ")
    if (text.isEmpty()) return MemoSplit(null, "")
    val lower = text.lowercase()
    // Positions are carried from one to the other, so they must line up.
    if (lower.length != text.length) return MemoSplit(null, text)
    var best: String? = null
    var bestLength = 0
    for (c in categories) {
        for (name in namesOf(c)) {
            val word = sameAm(name.trim()).lowercase()
            if (word.length < 2 || word.length <= bestLength) continue
            if (!lower.startsWith(word) || !endsWord(lower, word.length)) continue
            best = c.key
            bestLength = word.length
        }
    }
    if (best == null) return MemoSplit(null, text)
    val rest = text.substring(bestLength).trimStart(' ', '-', ':', ',', '.', '/', '|', '\u00B7').trim()
    return MemoSplit(best, rest)
}

private val KEY_WORD = Regex("""[a-z]+(?:[ _-][a-z]+)*""")

private fun namesOf(c: Category): List<String> {
    val label = expenseLabel(c.key, c.label).trim()
    val out = mutableListOf(label)
    if (label.contains('/')) out += label.split('/').map { it.trim() }
    if (KEY_WORD.matches(c.key)) out += c.key.replace('_', ' ')
    return out.filter { it.isNotEmpty() }
}

/** Whether a match of length [n] ends where a word may end. */
private fun endsWord(text: String, n: Int): Boolean {
    val next = text.getOrNull(n) ?: return true
    val last = text[n - 1]
    if (last in 'a'..'z' && next in 'a'..'z') return false
    return !thaiMark(next)
}

/** A Thai vowel or tone mark, which belongs to the letter before it. */
private fun thaiMark(c: Char): Boolean =
    c == '\u0E31' || c in '\u0E34'..'\u0E3A' || c in '\u0E47'..'\u0E4E'

/** สระอำ written one way, as SlipText spells it: นิคหิต + สระอา is how it is often typed. */
private fun sameAm(s: String): String = s.replace("\u0E4D\u0E32", "\u0E33")

// ─── who it went to ──────────────────────────────────────────────────────────

/** Titles a Thai slip puts in front of a person's name, longest first. */
private val TITLES = listOf(
    "นางสาว", "น.ส.", "นาง", "นาย", "ด.ช.", "ด.ญ.",
    "mrs.", "mrs", "miss", "mr.", "mr", "ms.", "ms",
)

/** Words a slip may print on the same line before a name. */
private val SIDES = listOf("จาก", "ไปยัง", "ถึง", "ผู้โอน", "ผู้รับ", "from", "to")

private class Person(val joined: String, val words: Int)

/** The person a line names, or null when it does not start with a title. */
private fun personOn(line: String): Person? {
    var t = line.trim().lowercase()
    for (s in SIDES) {
        if (t.startsWith("$s ") || t.startsWith("$s:")) {
            t = t.substring(s.length).trimStart(' ', ':')
            break
        }
    }
    val title = TITLES.firstOrNull { t.startsWith(it) } ?: return null
    val rest = t.substring(title.length)
    // A title glued to a word that carries on is not a title: นายหน้า, mrt.
    val first = rest.firstOrNull() ?: return null
    if (title.last() != '.' && first != ' ' && title.last() in 'a'..'z') return null
    val words = rest.replace('*', ' ').trim().trimEnd('.').split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return Person(title + words.joinToString(""), words.size)
}

/**
 * Whether the money went to the person who sent it: from one of their own
 * accounts to another, or into their own wallet.
 *
 * That is not spending — the money is still theirs, it only moved — and a
 * ledger that counted every top-up as an expense would count the same baht
 * twice, once going into the wallet and once going out of it.
 *
 * A Thai slip prints the payer first and the payee second, each behind a
 * title, and hides part of the payer's name: K PLUS cuts it after the first
 * letter of the surname, Krungthai prints stars. So the payee's full name has
 * to begin with everything that is left of the payer's. A payer with no
 * surname left at all proves nothing — two people share a first name all the
 * time — and is not counted.
 */
fun paidToSelf(raw: String): Boolean {
    val people = raw.split('\n').mapNotNull { personOn(it) }
    if (people.size < 2) return false
    val payer = people[0]
    val payee = people[1]
    if (payer.words < 2 || payee.words < 2) return false
    return payee.joined.startsWith(payer.joined)
}

// ─── the draft ───────────────────────────────────────────────────────────────

/** Minutes either side of the file's time that a slip's printed time may be. */
private const val CLOCK_SLACK = 5

/**
 * Problems that keep the one-press save off a slip.
 *
 * no-reference and reference-cut are not here. A reference is how the same
 * slip is kept from being written twice, and a slip without one is still a
 * slip — it just cannot be told apart from a second copy of itself.
 */
private val BLOCKING = setOf(
    "no-amount", "amount-ambiguous", "amount-unsure",
    "no-currency", "currency-ambiguous",
    "date-ambiguous", "date-from-file", "date-not-file-day",
)

/**
 * A slip as the notification and the form need it.
 *
 * Problems are the reading's, without no-date when the file settled it, plus
 * two of this file's own:
 *
 *   date-from-file     the slip has no date that could be read and no time
 *                      that matches the file, so the date is the file's and
 *                      nothing confirms it
 *   date-not-file-day  the slip's date is more than a day away from when the
 *                      picture was saved: a slip saved again long afterwards
 */
data class SlipDraft(
    val amount: Double?,
    val currency: String?,
    /** YYYY-MM-DD. Always filled in; [problems] says when it is a guess. */
    val date: String,
    /** HH:MM, the slip's own when it could be read, the file's when not. */
    val clock: String,
    val reference: String?,
    /** What was typed at the bank, exactly as it was read. */
    val memo: String?,
    /** The memo with the category word taken off, for the note box. */
    val note: String,
    /** A category key, or null for whatever the form starts on. */
    val category: String?,
    /** Money moved between the payer's own accounts. See [paidToSelf]. */
    val toSelf: Boolean,
    val problems: List<String>,
) {
    /** Nothing left to check: one press may write it. */
    val oneTap: Boolean
        get() = amount != null && amount > 0.0 && currency != null && !toSelf &&
            problems.none { it in BLOCKING }
}

/**
 * The draft for one slip.
 *
 * @param raw the recogniser's text, which the reading was made from
 * @param savedAt when the picture was saved, on the phone's clock, in the
 *        phone's zone
 * @param split the memo already matched to a category, which takes the
 *        category list and the books, neither of which this file has
 */
fun slipDraft(reading: SlipReading, raw: String, savedAt: LocalDateTime, split: MemoSplit): SlipDraft {
    val saved = savedAt.date
    val savedMinute = savedAt.hour * 60 + savedAt.minute
    val clocks = clocksOn(raw)
    // Signed, and wrapped at midnight: 23:59 printed and 00:01 saved is two
    // minutes before, on the day before.
    fun apart(m: Int): Int {
        var d = m - savedMinute
        if (d > 720) d -= 1440
        if (d < -720) d += 1440
        return d
    }
    val near = clocks.firstOrNull { abs(apart(it)) <= CLOCK_SLACK }
    val problems = reading.problems.filter { it != "no-date" }.toMutableList()
    val printed = reading.date?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
    val date = when {
        printed != null -> {
            if (abs(saved.daysUntil(printed)) > 1) problems += "date-not-file-day"
            iso(printed)
        }
        near != null -> {
            val shift = when {
                near - savedMinute > 720 -> -1
                near - savedMinute < -720 -> 1
                else -> 0
            }
            iso(saved.plus(shift, DateTimeUnit.DAY))
        }
        else -> {
            if ("date-ambiguous" !in problems) problems += "date-from-file"
            iso(saved)
        }
    }
    return SlipDraft(
        amount = reading.amount,
        currency = reading.currency,
        date = date,
        clock = hhmm(near ?: clocks.singleOrNull() ?: savedMinute),
        reference = reading.reference,
        memo = reading.memo,
        note = split.note,
        category = split.category,
        toSelf = paidToSelf(raw),
        problems = problems,
    )
}

/**
 * The row one press writes, through the same validation as the form.
 *
 * The reference goes into slip_ref, where a unique index refuses a second row
 * from the same slip, rather than into the note where it used to be put by the
 * scan screen — a number nobody reads, in the one box people do.
 */
fun slipExpense(d: SlipDraft): ExpenseDraft = ExpenseDraft(
    amount = d.amount?.let { a -> if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString() },
    currency = d.currency,
    category = d.category,
    note = d.note,
    date = d.date,
    slipRef = d.reference,
)

// ─── the words ───────────────────────────────────────────────────────────────

/** Two lines: what it is and how much, then the category and when — or what to check. */
data class SlipFace(val title: String, val text: String)

/**
 * Why a slip cannot be saved with one press, as a sentence, or null when it can.
 *
 * One sentence, the most important reason first. A list of every code would be
 * accurate and unread; the form it opens shows each box anyway.
 */
fun slipCheck(d: SlipDraft): String? = when {
    d.toSelf -> "โอนเข้าบัญชีตัวเอง ปกติไม่นับเป็นรายจ่าย"
    d.amount == null || "no-amount" in d.problems -> "อ่านยอดเงินไม่ออก"
    "amount-ambiguous" in d.problems || "amount-unsure" in d.problems -> "อ่านยอดเงินไม่ชัด เช็คจำนวนก่อนบันทึก"
    d.currency == null || "no-currency" in d.problems || "currency-ambiguous" in d.problems ->
        "ไม่แน่ใจว่าเป็นเงินสกุลไหน เช็คก่อนบันทึก"
    "date-not-file-day" in d.problems -> "วันที่บนสลิปไม่ตรงกับวันที่เซฟรูป เช็ควันที่ก่อนบันทึก"
    "date-ambiguous" in d.problems -> "วันที่อ่านได้สองแบบ เช็ควันที่ก่อนบันทึก"
    "date-from-file" in d.problems -> "อ่านวันที่บนสลิปไม่ได้ ใส่วันที่เซฟรูปไว้ เช็คก่อนบันทึก"
    else -> null
}

/**
 * The two lines of a slip's notification, and of its row in the money tab.
 *
 * @param label the category as words, from expenseLabel; "อื่นๆ" when none
 */
fun slipFace(d: SlipDraft, label: String, today: LocalDate): SlipFace {
    val money = d.amount?.let { a ->
        val c = d.currency
        if (c == null) plain(a) else moneyText(a, c, if (d.toSelf) 0 else -1)
    } ?: "?"
    val day = moneyDayTitle(d.date, today).substringBefore(" · ")
    val time = "$day ${d.clock}"
    if (d.toSelf) return SlipFace("โอนเข้าบัญชีตัวเอง · $money", "ไม่นับเป็นรายจ่าย แตะถ้าจะบันทึกอยู่ดี · $time")
    val what = d.note.ifEmpty { label }
    val check = slipCheck(d)
    val text = when {
        check != null -> check
        d.note.isNotEmpty() -> "$label · $time"
        else -> time
    }
    return SlipFace("$what · $money", text)
}

private fun plain(a: Double): String = if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString()

private fun pad(n: Int): String = if (n < 10) "0$n" else n.toString()

private fun hhmm(minutes: Int): String = pad(minutes / 60) + ":" + pad(minutes % 60)

private fun iso(d: LocalDate): String =
    d.year.toString().padStart(4, '0') + "-" + pad(d.monthNumber) + "-" + pad(d.dayOfMonth)
