// shared/src/commonMain/kotlin/app/reup/sync/SlipText.kt
//
// Turning the text off a slip into the fields of a form.
//
// ─── WHY THIS IS THE HALF THAT EXISTS FIRST ──────────────────────────────────
//
// The desktop reads slips by sending the picture to whichever model is
// configured. That cannot be copied to the phone, and not for a technical
// reason: the phone has no API key, on purpose, because keys are the one thing
// this project has always refused to sync. A slip also carries an account
// number and a full name — slipScanner.ts says so in its own header — which
// makes it the single worst thing in the app to put in an outgoing request.
//
// So the phone reads slips on the device, and the picture never leaves.
//
// The first version of this comment said that was ML Kit. It is not: ML Kit's
// text recognition covers Latin, Chinese, Devanagari, Japanese and Korean, and
// Thai is not one of them. It would have read the digits on a slip and none of
// the words around them, which is worse than useless here, because the words
// are what tells จำนวนเงิน apart from ค่าธรรมเนียม.
//
// Tesseract with tha.traineddata does read Thai, offline, and that is what the
// app module uses. The claim was written from memory and checked one round
// later, which is one round later than it should have been.
//
// Either way the decision is reversible in exactly one place: whatever produces
// the text calls this, and this does not care where the text came from.
//
// Which splits the work in two. The part that needs a library nobody here can
// compile is the part that turns a photograph into lines of text. The part that
// decides what those lines MEAN is ordinary logic, and it is written first
// because it is the part that can be proved before it ships.
//
// ─── WHAT IS AND IS NOT KNOWN ────────────────────────────────────────────────
//
// The field shapes below are not invented. They are the ones the desktop prompt
// spells out, written against real slips: a K PLUS transfer, a Krungthai bill
// payment. Buddhist years, the abbreviated Thai months, fees listed separately,
// the reference under เลขที่รายการ.
//
// What is NOT known is the exact text the recogniser hands back for a real
// slip — the line order, what it does with two-column layouts, whether it keeps
// the ฿, how badly Thai comes out at slip resolution.
// Nothing here leans on line order for that reason, and the tests are written
// against text assembled by hand rather than captured from a phone.
//
// That is the one unvalidated assumption in this file and it is worth saying
// plainly, because the last thing designed in a conversation and never run was
// notify_before_min, which spent a month computing a lead time from a column
// that did not exist.
//
// ─── AND WHY IT FAILS CLOSED ─────────────────────────────────────────────────
//
// Every rule here answers null rather than guessing. The prompt on the desktop
// puts it better than a comment can: a wrong number that looks right is worse
// than a blank somebody fills in. A blank costs four taps. A wrong amount is a
// month total that is quietly off and nothing on any screen that says so.

package app.reup.sync

/** What could be read off a slip. Anything unreadable is null, never a guess. */
data class SlipReading(
    val amount: Double? = null,
    val currency: String? = null,
    /** YYYY-MM-DD, Gregorian, converted from whatever calendar was printed. */
    val date: String? = null,
    val reference: String? = null,
    /**
     * Why a field is null, in codes a screen can turn into a sentence.
     *
     * A list rather than a single reason: two fields can fail for two different
     * causes on the same slip, and the screen shows both boxes.
     */
    val problems: List<String> = emptyList(),
)

// ─── what a recogniser actually hands back ───────────────────────────────────
//
// From a real K PLUS slip, read on the phone:
//
//   27 ส.ค. 69 17:04 น.
//   2оо-%-%8762-%
//   006-ooതooത3650
//   016239170445BPM19370
//   ค่าธรรมเนียม: ร ง Sizes [ต] ฟะเช x
//   ENS            0.00 บาท
//
// Three things in that which were not guessed at beforehand.
//
// THAI DIGITS COME BACK AS THAI DIGITS. ๐ ๒ ๓ ๕ appear mixed into otherwise
// Latin numbers, because the font renders them and tha.traineddata knows them.
// A number regex over Arabic digits alone sees `๕5` as `5`.
//
// ZEROS COME BACK AS THE LETTER o. `2оо-%-%8762` is an account number, and
// `006-ooതooത3650` is a phone number. Round glyphs at slip resolution are a
// coin toss.
//
// LABELS AND THEIR VALUES LAND ON DIFFERENT LINES. `ค่าธรรมเนียม:` came back
// with no number on it at all, and its 0.00 arrived on the line below next to a
// word that is not a word. Anything that excludes a line by its label has to
// look one line further, or a fee of two hundred becomes the amount.

/** Thai digits to Arabic. Everything else is left exactly as it was. */
private fun arabicDigits(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
        sb.append(if (ch in '\u0E50'..'\u0E59') ('0' + (ch - '\u0E50')) else ch)
    }
    return sb.toString()
}

/**
 * `o` and `O` back to zero, but only inside something already mostly digits.
 *
 * Doing it everywhere would turn บาท-adjacent words and every English label
 * into gibberish. Doing it nowhere loses a phone number, an account number and
 * — the one that matters — any amount whose zeros were read as letters.
 */
private fun repairZeros(text: String): String =
    Regex("""[0-9oO][0-9oO,.\-]{2,}""").replace(text) { m ->
        val token = m.value
        // One real digit is enough. `1oo.oo` is a hundred baht and has four
        // letters to one digit, so counting them against each other loses the
        // case this exists for. The pattern already refuses anything holding a
        // letter other than o, so a word cannot get in here.
        if (token.any { it.isDigit() }) {
            token.replace('o', '0').replace('O', '0')
        } else {
            token
        }
    }

// A number as banks print it: grouped with commas, two decimals, sometimes not.
private val NUMBER = Regex("""\d{1,3}(?:,\d{3})*(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?""")

// Lines whose number is not the amount that left the account.
private val NOT_THE_AMOUNT = Regex(
    "ค่าธรรมเนียม|ธรรมเนียม|คงเหลือ|ยอดคงเหลือ|ใช้ได้|" +
            "fee|charge|balance|available|remaining",
    RegexOption.IGNORE_CASE,
)

// Lines that say "this is the amount" in so many words.
private val IS_THE_AMOUNT = Regex(
    "จำนวนเงิน|จํานวนเงิน|จำนวน|จํานวน|ยอดเงิน|ยอดชำระ|ยอดชําระ|amount|total",
    RegexOption.IGNORE_CASE,
)

private val THAI_MONTHS = listOf(
    "ม.ค." to 1, "ก.พ." to 2, "มี.ค." to 3, "เม.ย." to 4,
    "พ.ค." to 5, "มิ.ย." to 6, "ก.ค." to 7, "ส.ค." to 8,
    "ก.ย." to 9, "ต.ค." to 10, "พ.ย." to 11, "ธ.ค." to 12,
)

private val REFERENCE_LABEL = Regex(
    "เลขที่รายการ|รหัสอ้างอิง|เลขอ้างอิง|reference|ref\\.?\\s*no|transaction\\s*id",
    RegexOption.IGNORE_CASE,
)

/**
 * Read a slip.
 *
 * [text] is whatever the recogniser produced, newlines and all. Nothing here
 * assumes an order: every rule looks at the whole text or at the line a match
 * happens to be on, because a recogniser reading a two-column slip may hand
 * back the right column first and there is no way to know from here.
 */
fun readSlip(raw: String): SlipReading {
    // Normalised once, at the door. Every rule below reads the repaired text,
    // so none of them has to know that a recogniser confuses o with 0.
    val text = repairZeros(arabicDigits(raw))
    val lines = text.split("\n", "\r").map { it.trim() }.filter { it.isNotEmpty() }
    val problems = mutableListOf<String>()

    val amount = readAmount(lines, problems)
    val currency = if (Regex("฿|บาท|THB").containsMatchIn(text)) "THB" else null
    if (currency == null) problems += "no-currency"
    val date = readDate(text) ?: run { problems += "no-date"; null }
    val reference = readReference(lines)

    return SlipReading(
        amount = amount,
        currency = currency,
        date = date,
        reference = reference,
        problems = problems,
    )
}

/**
 * The amount that left the account.
 *
 * Labelled wins. With nothing labelled, one candidate is the answer and several
 * is a refusal — the alternative is picking the largest, which is right on most
 * slips and silently wrong on the ones that also print a daily limit or a
 * previous balance under a name this list has not heard of.
 */
private fun readAmount(lines: List<String>, problems: MutableList<String>): Double? {
    data class Candidate(val value: Double, val labelled: Boolean)

    val found = mutableListOf<Candidate>()
    for ((i, line) in lines.withIndex()) {
        if (NOT_THE_AMOUNT.containsMatchIn(line)) continue
        // A label whose own line carries no number has its value on the next
        // one. Seen on a real slip: the fee label came back with no digits and
        // its 0.00 arrived underneath. Without this, a fee that is not zero is
        // a candidate for being the amount.
        val above = lines.getOrNull(i - 1)
        if (above != null &&
            NOT_THE_AMOUNT.containsMatchIn(above) &&
            !NUMBER.containsMatchIn(above)
        ) continue
        val labelled = IS_THE_AMOUNT.containsMatchIn(line)
        val money = Regex("฿|บาท|THB").containsMatchIn(line)
        if (!labelled && !money) continue
        for (m in NUMBER.findAll(line)) {
            val v = m.value.replace(",", "").toDoubleOrNull() ?: continue
            // A year is not an amount, and neither is an account number that
            // happens to sit on the same line as the word baht.
            if (v <= 0.0 || v >= 10_000_000.0) continue
            found += Candidate(v, labelled)
        }
    }

    val labelled = found.filter { it.labelled }.map { it.value }.distinct()
    if (labelled.size == 1) return labelled[0]
    if (labelled.size > 1) { problems += "amount-ambiguous"; return null }

    val plain = found.map { it.value }.distinct()
    if (plain.size == 1) return plain[0]
    problems += if (plain.isEmpty()) "no-amount" else "amount-ambiguous"
    return null
}

/**
 * The date, as Gregorian.
 *
 * Buddhist years are subtracted whether written in full or in two digits, which
 * is the rule the desktop prompt gives and the one that matters here: a Thai
 * slip printed 2569 is this year, and reading it as the year 2569 would file
 * the row five centuries out with nothing on screen to notice by.
 */
private fun readDate(text: String): String? {
    for ((label, month) in THAI_MONTHS) {
        val m = Regex("""(\d{1,2})\s*""" + Regex.escape(label) + """\s*(\d{2,4})""").find(text)
        if (m != null) {
            val day = m.groupValues[1].toInt()
            val year = gregorian(m.groupValues[2].toInt())
            return iso(year, month, day)
        }
    }

    val m = Regex("""(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2,4})""").find(text)
    if (m != null) {
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        // Day-first, because that is how it is printed everywhere this app is
        // used. A slip from the United States is not the case being served.
        if (month in 1..12 && day in 1..31) {
            return iso(gregorian(m.groupValues[3].toInt()), month, day)
        }
    }
    return null
}

/**
 * Buddhist to Gregorian, for the years that can only be one of the two.
 *
 * Two digits are ambiguous by nature: 69 is 2569 on a Thai slip and 2069 on
 * nothing anybody is holding. Treated as Buddhist, which is the reading that is
 * right on every slip this will ever see.
 */
private fun gregorian(year: Int): Int = when {
    // 2569 → 2026.
    year >= 2400 -> year - 543
    // 69 → 2569 → 2026. Written the long way round because 2500 + 69 - 543 is
    // two facts about calendars, and any shorter arithmetic here is a constant
    // nobody can check.
    year in 60..99 -> 2500 + year - 543
    // 26 → 2026. A Gregorian two-digit year, which slips do print.
    year in 0..59 -> 2000 + year
    else -> year
}

private fun iso(year: Int, month: Int, day: Int): String =
    year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" +
            day.toString().padStart(2, '0')

/**
 * The reference number.
 *
 * Taken from the labelled line only. A slip is covered in long digit strings —
 * account numbers, phone numbers, the QR payload — and picking the longest one
 * on the page would copy an account number into a note field roughly as often
 * as it would find a reference.
 */
private fun readReference(lines: List<String>): String? {
    for ((i, line) in lines.withIndex()) {
        if (!REFERENCE_LABEL.containsMatchIn(line)) continue
        // The label's own line first, then the next few. On a real slip the
        // label was alone on its line and the number arrived two lines later,
        // with a line of recogniser noise in between.
        for (j in i..minOf(i + 3, lines.lastIndex)) {
            val here = lines[j]
            if (j != i && REFERENCE_LABEL.containsMatchIn(here)) break
            val token = Regex("""[A-Za-z0-9]{10,}""").findAll(here)
                .map { it.value }
                // Mostly digits, which is what a reference is and what the
                // words around it are not. `สแกนตรวจสอบสลิป` is long too.
                .filter { t -> t.count { it.isDigit() } * 2 >= t.length }
                .toList()
            if (token.isNotEmpty()) return token.maxByOrNull { it.length }
        }
    }
    return null
}