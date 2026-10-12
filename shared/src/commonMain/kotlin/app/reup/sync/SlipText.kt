// shared/src/commonMain/kotlin/app/reup/sync/SlipText.kt
//
// Turning the text off a slip into the fields of a form.
//
// ─── WHERE THE TEXT COMES FROM ───────────────────────────────────────────────
//
// The phone reads slips on the device and the picture never leaves it. A slip
// carries an account number and a full name, which makes it the worst thing in
// the app to put in an outgoing request, and the phone has no API key on
// purpose. Tesseract does the reading (SlipOcr.kt, after SlipImage.kt has
// turned the picture black and white). This file decides what the lines MEAN,
// and does not care where they came from.
//
// ─── WHY IT FAILS CLOSED ─────────────────────────────────────────────────────
//
// Every rule here answers null rather than guessing. A wrong number that looks
// right is worse than a blank somebody fills in. A blank costs four taps. A
// wrong amount is a month total that is quietly off, with nothing on any screen
// that says so.
//
// Five slips read on one evening are why that sentence is not decoration. Read
// the way the app read them until this round, a seventeen baht top-up came back
// as 170 and a two hundred and seventy baht one as 7, both with "problems none".
// The picture was the main cause and SlipImage.kt fixes it. But the rules let
// both through, and SlipTextTest keeps the exact text that fooled them, so a
// worse picture can never do it again.
//
// Two independent reviews then threw a few hundred slips at these rules, real
// layouts and hostile ones, looking for a wrong answer that looks right and
// for ordinary slips refused for no reason. What they found is fixed, and is
// in SlipTextTest under their own headings, so it stays fixed.
//
// ─── WHY IT IS NOT A THAI-ONLY FILE ANY MORE ─────────────────────────────────
//
// The first version knew one bank: baht, Buddhist years, จำนวนเงิน and
// ค่าธรรมเนียม. The app is meant to work wherever it is installed, so the same
// rules now run on tables instead of constants:
//
//   • money signs     ฿ บาท THB · $ S$ US$ HK$ · € £ ¥ 円 元 ₩ 원 ₫ Rp RM ₱ ₹ kr zł …
//   • numbers         1,234.56 · 1.234,56 · 1 234,56 · 1'234.56 · 1,23,456.00
//   • dates           8 ต.ค. 69 · 8 Oct 2026 · Oct 8, 2026 · 2026-10-08 · 08/10/2026
//                     8 de octubre de 2026 · 2026年10月8日 · ngày 8 tháng 10 năm 2026
//   • what lines are called, in Thai, English, Indonesian, Malay, Vietnamese,
//     Spanish, Portuguese, French, German, Italian, Dutch, Swedish, Hungarian,
//     Turkish, Polish, Czech, Russian, Japanese, Chinese and Korean
//
// What tables cannot fix is the recogniser. The language data in the APK is
// Thai and English. English reads every language written in Latin letters,
// with most accents lost on the way — which is why labels are matched with
// accents folded away (Gebühr and Gebuhr are the same word here). It reads
// nothing in any other script: a Japanese receipt needs jpn.traineddata in
// assets/tessdata, and SlipOcr picks up whatever is there. The words for it are
// already in this file.

package app.reup.sync

import app.reup.core.THAI_MONTHS
import app.reup.core.THAI_MONTHS_SHORT
import kotlin.math.abs
import kotlin.math.roundToLong

/** What could be read off a slip. Anything unreadable is null, never a guess. */
data class SlipReading(
    val amount: Double? = null,
    /**
     * An ISO 4217 code: THB, USD, JPY. Null when the slip does not say, and also
     * when it says something several currencies share — `$` on a phone that
     * counts in baht is not "dollars", it is "a dollar, nobody can say whose".
     */
    val currency: String? = null,
    /** YYYY-MM-DD, Gregorian, converted from whatever calendar was printed. */
    val date: String? = null,
    val reference: String? = null,
    /**
     * Why a field is null, in codes a screen can turn into a sentence.
     *
     * A list rather than a single reason: two fields can fail for two different
     * causes on the same slip, and the screen shows both boxes.
     *
     *   no-amount           nothing on the page is labelled or printed as money
     *   amount-ambiguous    more than one amount, and nothing to choose by
     *   amount-unsure       an amount was found and does not look whole: a
     *                       decimal point that went missing, or a number broken
     *                       in two by a space
     *   no-currency         no money sign anywhere
     *   currency-ambiguous  a sign several currencies share ($ ¥ kr Rs), or two
     *                       different currencies on one slip
     *   no-date / date-ambiguous
     *   no-reference        no reference under any reference label
     *   reference-cut       the slip itself prints the reference shortened, with
     *                       "…" — there is nothing whole to copy
     */
    val problems: List<String> = emptyList(),
    /**
     * What the payer typed into the bank app before pressing transfer — K PLUS
     * and Krungthai NEXT both print it on the slip as บันทึกช่วยจำ — or null.
     *
     * Not a field that can be wrong in the way the others can. It is a note, it
     * goes into the note box, and the person reads it there. So it has no
     * problem code: a memo that did not come out is a note left empty, which is
     * what a slip without one gives anyway.
     */
    val memo: String? = null,
)

/**
 * What the phone knows that a slip might not say.
 *
 * Neither of these is a default for a missing field. They settle readings that
 * could go two ways, and nothing else.
 */
data class SlipHints(
    /**
     * The currency the books are kept in, as an ISO code.
     *
     * Used to settle a sign that several currencies share: `$` on a phone that
     * counts in SGD is SGD. On a phone that counts in baht it stays unknown.
     */
    val homeCurrency: String? = null,
    /**
     * Whether 03/04/2026 means the fourth of March where the phone is.
     *
     * Only asked when a date really can be read both ways, and never for a slip
     * with Thai on it, which is day first whatever language the phone is in. A
     * Thai phone set to American English is common, and reading every Thai slip
     * under it month first would move most of them to the wrong month.
     */
    val monthFirst: Boolean = false,
)

/**
 * Read a slip.
 *
 * [raw] is whatever the recogniser produced, newlines and all. Line order is
 * only trusted for one thing — a label above its value — because a recogniser
 * reading a two-column slip may hand back the right column first.
 */
fun readSlip(raw: String, hints: SlipHints = SlipHints()): SlipReading {
    val page = Page.read(raw, hints)
    val problems = mutableListOf<String>()
    val pick = readAmount(page, problems)
    val currency = readCurrency(page, pick, problems)
    val date = readDate(page, problems)
    // Reported like the other three. Without it a slip whose reference never
    // made it out of the recogniser reads as `reference —` next to `problems
    // none`, which says the field is empty and nothing is wrong at the same time.
    val reference = readReference(page, problems)
    return SlipReading(
        amount = pick?.value,
        currency = currency,
        date = date,
        reference = reference,
        problems = problems,
        memo = readMemo(page),
    )
}

// ─── what a recogniser actually hands back ───────────────────────────────────
//
// From a real K PLUS slip, read on the phone:
//
//   27 ส.ค. 69 17:04 น.
//   2оо-%-%4410-%
//   006-ooതooത1207
//   ค่าธรรมเนียม: ร ง Sizes [ต] ฟะเช x
//   ENS            0.00 บาท
//
// THAI DIGITS COME BACK AS THAI DIGITS, mixed into Latin numbers. Every other
// script's digits get the same treatment now: Arabic-Indic, Devanagari,
// fullwidth. ZEROS COME BACK AS THE LETTER o. LABELS AND THEIR VALUES LAND ON
// DIFFERENT LINES, sometimes six lines apart with rubbish in between.
//
// And from the same bank a month later, read in colour: `ต.ุค.` for ต.ค., with a
// vowel mark the slip never printed; `คําธรรม เนียม` for ค่าธรรมเนียม; `17.00`
// as `1700`. Each of those has a rule below, named where it is used.

/**
 * The text the rules read: one kind of digit, one kind of space, one kind of
 * dash, and Thai marks in one order with สระอำ spelled one way.
 */
private fun normalize(raw: String): String {
    val sb = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        val next = if (i + 1 < raw.length) raw[i + 1] else ' '
        when {
            ch == '\r' -> {
                sb.append('\n')
                if (next == '\n') i++
            }
            ch.isDigit() && ch !in '0'..'9' -> sb.append('0' + ch.digitToInt())
            // Fullwidth forms, which CJK receipts use for everything: ＄１２．５０
            ch in '\uFF01'..'\uFF5E' -> sb.append((ch.code - 0xFEE0).toChar())
            ch == '\uFFE5' -> sb.append('\u00A5')
            ch == '\uFFE6' -> sb.append('\u20A9')
            ch == '\uFFE1' -> sb.append('\u00A3')
            ch == '\t' || ch == '\u00A0' || ch == '\u2007' || ch == '\u2009' ||
                ch == '\u202F' || ch == '\u3000' -> sb.append(' ')
            ch in '\u2010'..'\u2015' || ch == '\u2212' || ch == '\uFE63' -> sb.append('-')
            ch == '\u2018' || ch == '\u2019' || ch == '\u02BC' -> sb.append('\'')
            ch == '\u2026' -> sb.append("...")
            else -> sb.append(ch)
        }
        i++
    }
    return repairZeros(thaiMarks(sb.toString()))
}

/**
 * Each Thai letter's marks once, in the order a keyboard types them, and สระอำ
 * as one letter.
 *
 * A recogniser hands a letter's marks back in whatever order it found them, and
 * now and then twice. น้ำ has come back as น ้ ํ ้ า and as น ํ ้ า — neither of
 * which is how anybody types it, and both of which end up in the note. So in
 * each run of marks a repeat is dropped and the rest are put in typing order:
 * the vowel above or below, then the tone, then the rest. A นิคหิต at the end
 * of a run with สระอา after it is the สระอำ it was split from — which is how a
 * recogniser writes สระอำ more often than not, on every Thai slip read here.
 *
 * Only marks move. The letters, and how many of them there are, stay exactly
 * as they were read.
 */
internal fun thaiMarks(text: String): String {
    if (text.none { thaiMark(it) }) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (!thaiMark(text[i])) {
            sb.append(text[i])
            i++
            continue
        }
        var end = i
        while (end < text.length && thaiMark(text[end])) end++
        val run = text.substring(i, end).toList().distinct().sortedBy { markRank(it) }
        val am = run.last() == '\u0E4D' && text.getOrNull(end) == '\u0E32'
        for (m in run) if (!(am && m == '\u0E4D')) sb.append(m)
        if (am) {
            sb.append('\u0E33')
            end++
        }
        i = end
    }
    return sb.toString()
}

/** A Thai vowel or tone mark, written over or under the letter before it. */
internal fun thaiMark(c: Char): Boolean = c == '\u0E31' || c in '\u0E34'..'\u0E3A' || c in '\u0E47'..'\u0E4E'

/** Typing order: the vowel above or below, the tone, การันต์, นิคหิต. */
private fun markRank(c: Char): Int = when (c) {
    '\u0E31', in '\u0E34'..'\u0E3A', '\u0E47' -> 0
    in '\u0E48'..'\u0E4B' -> 1
    '\u0E4C', '\u0E4E' -> 2
    else -> 3
}

/**
 * `o` and `O` back to zero, but only inside something already mostly digits.
 *
 * Doing it everywhere would turn every English label into gibberish. Doing it
 * nowhere loses a phone number, an account number and — the one that matters —
 * any amount whose zeros were read as letters.
 */
private fun repairZeros(text: String): String =
    Regex("""[0-9oO][0-9oO,.\-]{2,}""").replace(text) { m ->
        val token = m.value
        // One real digit is enough. `1oo.oo` is a hundred baht with four
        // letters to one digit, and the pattern already refuses anything
        // holding a letter other than o, so a word cannot get in here.
        if (token.none { it.isDigit() }) return@replace token
        // Except at the edges: an o touching a letter is the start or the
        // end of a word. `08-Oct-2026` is a date, and `08-0ct` is not.
        val before = text.getOrNull(m.range.first - 1)
        val after = text.getOrNull(m.range.last + 1)
        val chars = token.toCharArray()
        for (k in chars.indices) {
            if (chars[k] != 'o' && chars[k] != 'O') continue
            val word = (k == 0 && before != null && before.isLatin()) ||
                (k == chars.lastIndex && after != null && after.isLatin())
            if (!word) chars[k] = '0'
        }
        chars.concatToString()
    }

/**
 * A line in lower case with Latin accents taken off: Số tiền → so tien,
 * Gebühr → gebuhr, Référence → reference. And ฮ read as อ — see [FOLD].
 *
 * One character in, one character out, always — the labels found in this copy
 * are matched back against numbers found in the original by position.
 */
private fun fold(s: String): String {
    val sb = StringBuilder(s.length)
    for (ch in s) {
        val lower = ch.lowercaseChar()
        sb.append(FOLD[lower] ?: lower)
    }
    return sb.toString()
}

private val FOLD: Map<Char, Char> = buildMap {
    for (group in listOf(
        "aàáâãäåæāăąǎǟǡǻȁȃȧḁạảấầẩẫậắằẳẵặ",
        "bƀḃḅḇ",
        "cçćĉċčḉ",
        "dðďđḋḍḏḑḓ",
        "eèéêëēĕėęěȅȇȩḕḗḙḛḝẹẻẽếềểễệ",
        "fḟ",
        "gĝğġģǧǵḡ",
        "hĥħȟḣḥḧḩḫẖ",
        "iìíîïĩīĭįıǐȉȋḭḯỉịɨ",
        "jĵǰ",
        "kķĸǩḱḳḵ",
        "lĺļľŀłḷḹḻḽ",
        "mḿṁṃ",
        "nñńņňŋǹṅṇṉṋ",
        "oòóôõöøōŏőœơǒǫǭȍȏȫȭȯȱṍṏṑṓọỏốồổỗộớờởỡợ",
        "pṕṗ",
        "rŕŗřȑȓṙṛṝṟ",
        "sßśŝşšſșṡṣṥṧṩ",
        "tþţťŧțṫṭṯṱẗ",
        "uùúûüũūŭůűųưǔǖǘǚǜȕȗṳṵṷṹṻụủứừửữựʉ",
        "vṽṿ",
        "wŵẁẃẅẇẉẘ",
        "xẋẍ",
        "yýÿŷȳẏẙỳỵỷỹ",
        "zźżžẑẓẕ",
    )) {
        for (c in group.drop(1)) put(c, group[0])
    }
    // Krungthai NEXT's loopless อ comes back as ฮ often enough that a label
    // read for ฮ instead of อ is still the label: รหัสฮ้างฮิง is รหัสอ้างอิง. No
    // label anywhere in this file is spelt with ฮ, so nothing is lost by it.
    put('\u0E2E', '\u0E2D')
}

/** A Latin letter, accented or not — the kind that makes a word longer. */
private fun Char.isLatin(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || (this in '\u00C0'..'\u024F' && isLetter()) ||
        (this in '\u1E00'..'\u1EFF' && isLetter())

// ─── money signs ─────────────────────────────────────────────────────────────

private enum class Side { BEFORE, AFTER, EITHER }

/**
 * One way of writing a currency next to a number.
 *
 * [fallback] is what the sign means when the home currency does not settle it,
 * and is null when nobody could say: `$` is two dozen currencies, `¥` is two,
 * `kr` is four. `£` falls back to sterling, because the others that use it are
 * rare enough that sterling is the reading, not a guess.
 */
private class Sign(
    val text: String,
    val codes: List<String>,
    val fallback: String?,
    val side: Side,
    /** Counts as the page's currency even with no number beside it. */
    val strong: Boolean,
) {
    /** Made of Latin letters, so it must not be the middle of a longer word. */
    val word: Boolean = text.any { it.isLatin() }
}

private fun sign(
    text: String,
    codes: List<String>,
    side: Side = Side.EITHER,
    strong: Boolean = true,
    fallback: String? = codes.singleOrNull(),
) = Sign(text, codes, fallback, side, strong)

/** A word that only means money when a number is right beside it. */
private fun word(text: String, codes: List<String>, side: Side = Side.AFTER, fallback: String? = codes.singleOrNull()) =
    Sign(text, codes, fallback, side, strong = false)

private val DOLLARS = listOf(
    "USD", "SGD", "AUD", "CAD", "HKD", "TWD", "NZD", "MXN", "ARS", "CLP", "COP", "BND",
    "BSD", "BBD", "BMD", "BZD", "FJD", "JMD", "LRD", "NAD", "SBD", "SRD", "TTD", "XCD",
    "KYD", "GYD", "UYU", "DOP",
)
private val RUPEES = listOf("INR", "PKR", "LKR", "NPR", "MUR", "SCR")
private val CROWNS = listOf("SEK", "NOK", "DKK", "ISK")
private val PESOS = listOf("PHP", "MXN", "ARS", "CLP", "COP", "UYU", "DOP")

/**
 * Codes that are also ordinary words or common abbreviations are left out on
 * purpose: ALL, TOP, CUP, PEN, MAD, BOB, AMD. "TOP UP" is printed on half the
 * wallet slips in Asia and is not a currency from Tonga.
 *
 * And the ones that are left only count beside a number. PLN is the złoty and
 * also Indonesia's electricity company, printed in capitals on every
 * electricity bill there; "TRY OUR NEW APP" is printed under receipts in
 * America. Neither is a currency until a number stands next to it.
 */
private val ISO_CODES = listOf(
    "THB", "USD", "EUR", "GBP", "JPY", "CNY", "KRW", "VND", "IDR", "MYR", "SGD", "PHP",
    "INR", "HKD", "TWD", "AUD", "NZD", "CAD", "CHF", "SEK", "NOK", "DKK", "ISK", "PLN",
    "CZK", "HUF", "RON", "BGN", "TRY", "RUB", "UAH", "KZT", "BRL", "MXN", "ARS", "CLP",
    "COP", "UYU", "ZAR", "NGN", "KES", "EGP", "AED", "SAR", "QAR", "KWD", "BHD", "OMR",
    "JOD", "ILS", "PKR", "BDT", "LKR", "NPR", "MMK", "KHR", "LAK", "MOP", "BND", "MNT",
)

private val SIGNS: List<Sign> = buildList {
    for (code in ISO_CODES) add(word(code, listOf(code), Side.EITHER))
    add(sign("RMB", listOf("CNY")))
    add(sign("NTD", listOf("TWD")))
    add(sign("VNĐ", listOf("VND")))
    add(sign("VNđ", listOf("VND")))
    // The dollars that say whose they are, before the one that does not.
    for ((prefix, code) in listOf(
        "US" to "USD", "S" to "SGD", "SG" to "SGD", "A" to "AUD", "AU" to "AUD",
        "C" to "CAD", "CA" to "CAD", "HK" to "HKD", "NT" to "TWD", "NZ" to "NZD",
        "R" to "BRL", "MX" to "MXN", "B" to "BND", "MOP" to "MOP", "RD" to "DOP",
        "J" to "JMD", "TT" to "TTD", "BZ" to "BZD", "FJ" to "FJD", "COL" to "COP",
    )) add(sign(prefix + "$", listOf(code), Side.BEFORE))
    add(sign("$", DOLLARS))
    add(sign("฿", listOf("THB")))
    add(sign("บาท", listOf("THB"), Side.AFTER))
    add(sign("€", listOf("EUR")))
    add(sign("£", listOf("GBP", "EGP", "LBP", "SYP", "GIP", "FKP", "SHP"), fallback = "GBP"))
    add(sign("¥", listOf("JPY", "CNY")))
    add(sign("円", listOf("JPY"), Side.AFTER))
    // 元 is the yuan in China and the dollar in Taiwan. The yuan is the
    // reading unless the phone itself counts in one of the others.
    add(sign("元", listOf("CNY", "TWD", "HKD", "MOP"), Side.AFTER, fallback = "CNY"))
    add(sign("₩", listOf("KRW")))
    add(sign("원", listOf("KRW"), Side.AFTER))
    add(sign("₫", listOf("VND")))
    add(sign("Rp", listOf("IDR"), Side.BEFORE))
    add(word("RP", listOf("IDR"), Side.BEFORE))
    add(sign("RM", listOf("MYR"), Side.BEFORE))
    add(sign("₱", listOf("PHP")))
    add(sign("₹", listOf("INR")))
    add(sign("₨", RUPEES))
    add(sign("₺", listOf("TRY")))
    add(sign("₽", listOf("RUB")))
    add(sign("₪", listOf("ILS")))
    add(sign("₦", listOf("NGN")))
    add(sign("₴", listOf("UAH")))
    add(sign("₸", listOf("KZT")))
    add(sign("₮", listOf("MNT")))
    add(sign("₭", listOf("LAK")))
    add(sign("៛", listOf("KHR")))
    add(sign("৳", listOf("BDT")))
    // Words and short letters: money beside a number, and otherwise just
    // letters. "won" is a word in English, "kr" an abbreviation of anything.
    add(word("Rs.", RUPEES, Side.BEFORE))
    add(word("Rs", RUPEES, Side.BEFORE))
    add(word("Php", listOf("PHP"), Side.BEFORE))
    add(word("PhP", listOf("PHP"), Side.BEFORE))
    add(word("KSh", listOf("KES"), Side.BEFORE))
    add(word("Ksh", listOf("KES"), Side.BEFORE))
    add(word("kr.", CROWNS, Side.EITHER))
    add(word("kr", CROWNS, Side.EITHER))
    add(word("Kr", CROWNS, Side.EITHER))
    add(word("zł", listOf("PLN")))
    add(word("Kč", listOf("CZK")))
    add(word("Kc", listOf("CZK")))
    add(word("Ft", listOf("HUF")))
    add(word("lei", listOf("RON")))
    add(word("TL", listOf("TRY"), Side.EITHER))
    add(word("руб.", listOf("RUB")))
    add(word("руб", listOf("RUB")))
    add(word("грн", listOf("UAH")))
    add(word("лв", listOf("BGN")))
    add(word("Tk", listOf("BDT"), Side.EITHER))
    add(word("Ks", listOf("MMK")))
    // The Gulf dinars, which are also the three-decimal currencies.
    add(word("KD", listOf("KWD"), Side.BEFORE))
    add(word("BD", listOf("BHD"), Side.BEFORE))
    add(word("JD", listOf("JOD"), Side.BEFORE))
    add(word("đ", listOf("VND")))
    add(word("vnd", listOf("VND"), Side.EITHER))
    add(word("Vnd", listOf("VND"), Side.EITHER))
    add(word("Baht", listOf("THB")))
    add(word("baht", listOf("THB")))
    add(word("BAHT", listOf("THB")))
    add(word("dollars", DOLLARS))
    add(word("dollar", DOLLARS))
    add(word("euros", listOf("EUR")))
    add(word("euro", listOf("EUR")))
    add(word("Euro", listOf("EUR")))
    add(word("yen", listOf("JPY")))
    add(word("won", listOf("KRW")))
    add(word("dong", listOf("VND")))
    add(word("rupiah", listOf("IDR")))
    add(word("ringgit", listOf("MYR")))
    add(word("pesos", PESOS))
    add(word("peso", PESOS))
    add(word("rupees", RUPEES))
    add(word("rupee", RUPEES))
    // Thai names for other people's money, which Thai bank slips use for
    // transfers abroad and a Thai recogniser reads perfectly well.
    add(word("ดอลลาร์สหรัฐ", listOf("USD")))
    add(word("ดอลลาร์สิงคโปร์", listOf("SGD")))
    add(word("ดอลลาร์ฮ่องกง", listOf("HKD")))
    add(word("ดอลลาร์ออสเตรเลีย", listOf("AUD")))
    add(word("ดอลลาร์ไต้หวัน", listOf("TWD")))
    add(word("ดอลลาร์", DOLLARS))
    add(word("ยูโร", listOf("EUR")))
    add(word("เยน", listOf("JPY")))
    add(word("หยวน", listOf("CNY")))
    add(word("วอน", listOf("KRW")))
    add(word("ปอนด์", listOf("GBP")))
    add(word("ริงกิต", listOf("MYR")))
    add(word("รูเปียห์", listOf("IDR")))
    add(word("ด่ง", listOf("VND")))
    add(word("กีบ", listOf("LAK")))
    add(word("จ๊าด", listOf("MMK")))
    add(word("รูปี", RUPEES))
    add(word("เปโซ", PESOS))
}.sortedByDescending { it.text.length }

/** Where a sign was found in a line. */
private class SignHit(val start: Int, val end: Int, val sign: Sign)

private fun findSigns(line: String): List<SignHit> {
    val taken = BooleanArray(line.length)
    val out = mutableListOf<SignHit>()
    // Longest first, so S$ is found as S$ and never as an S beside a $.
    for (s in SIGNS) {
        var from = 0
        while (true) {
            val at = line.indexOf(s.text, from)
            if (at < 0) break
            from = at + 1
            val end = at + s.text.length
            if ((at until end).any { taken[it] }) continue
            if (s.word) {
                val before = line.getOrNull(at - 1)
                val after = line.getOrNull(end)
                if (s.text.first().isLatin() && before != null && before.isLatin()) continue
                if (s.text.last().isLatin() && after != null && after.isLatin()) continue
            }
            for (k in at until end) taken[k] = true
            out += SignHit(at, end, s)
        }
    }
    return out.sortedBy { it.start }
}

/**
 * Currencies with no minor unit to speak of, where a ten digit amount is an
 * ordinary afternoon. Everywhere else ten million is past anything typed into
 * this app, and a number that big beside a currency word is an account number.
 */
private val LARGE_UNITS = setOf(
    "VND", "IDR", "KRW", "LAK", "KHR", "MMK", "IRR", "UZS", "COP", "PYG", "UGX", "TZS",
    "LBP", "GNF", "MGA", "SLL",
)
private val MEDIUM_UNITS = setOf("JPY", "HUF", "CLP", "ISK", "KZT", "NGN", "PKR", "LKR", "MNT", "XOF", "XAF", "TWD")

private fun capOf(code: String?): Double = when (code) {
    in LARGE_UNITS -> 1e11
    in MEDIUM_UNITS -> 1e9
    else -> 1e7
}

/** Where a space between digit groups is a thousands separator: 1 234,56 €. */
private val SPACE_GROUPED = setOf(
    "EUR", "PLN", "CZK", "HUF", "SEK", "NOK", "RUB", "UAH", "KZT", "BGN", "ZAR", "CAD",
    "BYN", "UZS", "KGS", "GEL", "AZN",
)

/**
 * Where money is written 1,234.56 and never 1.234,56. On a slip in one of
 * these, a dot followed by three digits is not a thousands separator: on a
 * Thai slip `52.001 บาท` is 52.00 with a digit of rubbish on the end, and
 * `250.000` is 250.00 with one too many zeros — never two hundred and fifty
 * thousand.
 */
private val DOT_DECIMAL = setOf(
    "THB", "USD", "SGD", "MYR", "PHP", "HKD", "CNY", "TWD", "KRW", "JPY", "INR", "PKR", "LKR",
    "NPR", "BDT", "AUD", "NZD", "GBP", "MXN", "ILS", "AED", "SAR", "QAR", "EGP", "NGN", "KES",
)

/** Where a whole amount is grouped with spaces and printed without decimals: 12 500 Ft, 1 250 kr. */
private val WHOLE_SPACE_GROUPED = setOf("HUF", "SEK", "NOK", "ISK", "CZK")

/** Three decimal places: 12.345 is twelve dinars, not twelve thousand. */
private val THREE_DECIMALS = setOf("KWD", "BHD", "OMR", "JOD", "TND", "LYD", "IQD")


// ─── numbers ─────────────────────────────────────────────────────────────────

/** A number found in a line, with how it was printed. */
private class Num(
    val start: Int,
    val end: Int,
    val value: Double,
    /** Digits after the decimal separator. */
    val decimals: Int,
    /** Digits before the decimal separator. */
    val intDigits: Int,
    /** The thousands separator it was printed with, if any. */
    val groupSep: Char?,
    /** The decimal separator it was printed with, if any. */
    val decimalSep: Char?,
    /** `006`, `0123`: a piece of a phone or an account number, never an amount. */
    val idLike: Boolean,
    /** Printed with a minus in front of it: a discount, a refund, a debit. */
    val negative: Boolean,
    /** Behind a lone P, B, W or R: a ₱ ฿ ₩ ₹ the recogniser could not read. */
    val unreadSign: Boolean = false,
) {
    /** Half of something a space broke in two. Never an amount. */
    var fragment = false

    /** The sign printed beside it, if one is. */
    var sign: SignHit? = null

    /** Printed with no separator at all. */
    val bare: Boolean get() = groupSep == null && decimalSep == null
}

private class Shape(
    val value: Double,
    val decimals: Int,
    val intDigits: Int,
    val groupSep: Char?,
    val decimalSep: Char?,
    val idLike: Boolean,
) {
    /** Grouped with a dot where a dot is the decimal point: see [DOT_DECIMAL]. */
    var suspect = false
}

/**
 * Digit groups and their separators as one number, or null when no way of
 * writing money produces this shape.
 *
 * The rule that matters most is the one about three digits after a single
 * separator: 50.000 is fifty thousand rupiah and 1,234 is one thousand two
 * hundred dollars, so three digits are a thousands group — except where the
 * currency itself has three decimals, which is what [threeDecimals] says.
 * Whether that reading fits the rest of the page is checked later, against
 * how the page prints its other money.
 */
private fun shapeOf(run: String, threeDecimals: Boolean): Shape? {
    val groups = mutableListOf<String>()
    val seps = mutableListOf<Char>()
    val cur = StringBuilder()
    for (c in run) {
        if (c in '0'..'9') {
            cur.append(c)
        } else {
            groups += cur.toString()
            seps += c
            cur.clear()
        }
    }
    groups += cur.toString()
    if (seps.isEmpty()) {
        return Shape(run.toDouble(), 0, run.length, null, null, run.length > 1 && run[0] == '0')
    }
    val last = seps.last()
    val tail = groups.last()
    val decimal: Char? = when {
        last == '\'' || last == ' ' -> null
        tail.length in 1..2 -> last
        tail.length == 3 && threeDecimals && seps.dropLast(1).none { it == last } -> last
        else -> null
    }
    val intGroups = if (decimal != null) groups.dropLast(1) else groups
    val groupSeps = if (decimal != null) seps.dropLast(1) else seps
    var group: Char? = null
    if (groupSeps.isNotEmpty()) {
        val g = groupSeps[0]
        if (groupSeps.any { it != g } || g == decimal) return null
        if (!validGroups(intGroups, g)) return null
        group = g
    }
    val whole = intGroups.joinToString("")
    val fraction = if (decimal != null) tail else ""
    val value = (if (fraction.isEmpty()) whole else "$whole.$fraction").toDoubleOrNull() ?: return null
    return Shape(value, fraction.length, whole.length, group, decimal, whole.length > 1 && whole[0] == '0')
}

private fun validGroups(groups: List<String>, sep: Char): Boolean {
    if (groups.size < 2) return true
    val first = groups[0]
    if (first.length !in 1..3) return false
    val rest = groups.drop(1)
    if (rest.all { it.length == 3 }) return true
    // India and its neighbours: 1,23,45,678 — pairs, then a final three.
    return sep == ',' && first.length in 1..2 && rest.last().length == 3 &&
        rest.dropLast(1).all { it.length == 2 }
}

/**
 * Words that make the number before them a count, a weight or a time.
 *
 * `18.10 น.` is ten past six in the evening on a Thai slip, and under a title
 * that said ชำระ it was once read as 18.10 baht. `2 x 50.00` is two of
 * something. Thai words are matched as prefixes, because Thai is not spaced.
 */
private val UNITS_LATIN = setOf(
    "kg", "g", "gm", "gr", "mg", "ml", "l", "lt", "ltr", "ltrs", "liter", "liters", "litre", "litres",
    "lb", "lbs", "oz", "gal", "km", "pcs", "pc", "pce", "ea", "x", "item", "items", "hrs", "hr", "h",
    "min", "mins", "am", "pm", "nights", "night",
)
private val UNITS_THAI = listOf(
    "น.", "นาฬิกา", "นาที", "ชั่วโมง", "ชม.", "กก", "กรัม", "ลิตร", "มล", "ชิ้น", "ขวด", "กล่อง",
    "แก้ว", "จาน", "ห่อ", "ถุง", "ซอง", "รายการ", "ครั้ง", "หน่วย", "เม็ด", "คืน",
    // "ซื้อครบ 1,000 บาท ขึ้นไป": a condition in an advert, not a payment.
    "ขึ้นไป",
)

private val CONDITION = Regex("""^\S{0,4}\s*(?:ขึ้นไป|or more|and above|minimum)""")

private fun unitAfter(line: String, end: Int): Boolean {
    var k = end
    while (k < line.length && k - end < 2 && line[k] == ' ') k++
    if (k >= line.length) return false
    if (line[k] == '%') return true
    if (UNITS_THAI.any { line.startsWith(it, k) }) return true
    // A condition in an advert, with the currency in between: "ยอดรวม 5,000
    // บาท ขึ้นไป ผ่อน 0%" is an offer printed on the slip, not a total.
    val rest = line.substring(k, minOf(line.length, k + 16))
    if (CONDITION.containsMatchIn(rest)) return true
    // A price per something: 1,859 EUR/l, $3.459/gal, 28.18 บาท/ลิตร. What a
    // litre costs is not what was paid.
    var p = k
    while (p < line.length && p - k < 4 && line[p] != '/' && line[p] != ' ' && line[p] !in '0'..'9') p++
    while (p < line.length && p - k < 6 && line[p] == ' ') p++
    if (p + 1 < line.length && line[p] == '/' && line[p + 1].isLetter()) return true
    var e = k
    while (e < line.length && (line[e] in 'a'..'z' || line[e] in 'A'..'Z')) e++
    if (e == k) return false
    return line.substring(k, e).lowercase() in UNITS_LATIN
}

/**
 * The numbers in a line that could be money, each with the sign beside it.
 *
 * Digits glued to letters are part of something else — `APM09146` is a
 * reference, `x4410` a masked account — and so are times, dates, percentages,
 * counts and masked card numbers. They are left out here rather than argued
 * about further down.
 */
private fun scanNumbers(
    line: String,
    signs: List<SignHit>,
    labels: List<LabelHit>,
    page: PageContext,
    labelledAbove: Boolean,
): List<Num> {
    val inSign = BooleanArray(line.length)
    for (s in signs) for (k in s.start until s.end) inSign[k] = true
    fun letter(i: Int) = i in line.indices && !inSign[i] && line[i].isLatin()
    fun digit(i: Int) = i in line.indices && line[i] in '0'..'9'

    val three = page.guess in THREE_DECIMALS
    val dotDecimal = page.dotDecimal
    val found = mutableListOf<Num>()
    var i = 0
    while (i < line.length) {
        if (!digit(i)) {
            i++
            continue
        }
        var j = i
        while (j < line.length) {
            j = when {
                digit(j) -> j + 1
                line[j] in ".,'" && digit(j - 1) && digit(j + 1) -> j + 1
                else -> break
            }
        }
        val start = i
        val end = j
        i = j
        val before = line.getOrNull(start - 1)
        val after = line.getOrNull(end)
        // ₱ ฿ ₩ ₹ are not in the recogniser's alphabet, and come back as the
        // nearest letter it has: P500.00, B52.00. One such letter standing on
        // its own in front of a number printed as money is a sign it could not
        // read, not the end of a word — the number is kept, and nothing is
        // concluded about its currency.
        val unreadSign = before != null && before in "PBWR" && !inSign[start - 1] &&
            (start < 2 || !line[start - 2].isLatin())
        val glued = (letter(start - 1) && !unreadSign) || letter(end) ||
            // 18:10, 08/10, 2026-10-08, 006-xxx, 60/40
            (before != null && before in ":/-" && digit(start - 2)) ||
            (after != null && after in ":/-" && digit(end + 1)) ||
            after == '%' || before == '*' || before == '#' || unitAfter(line, end)
        if (glued) continue
        val shape = shapeOf(line.substring(start, end), three) ?: continue
        if (dotDecimal && shape.groupSep == '.') shape.suspect = true
        // 250.- and 250,- : a whole amount, written the way Thai and Swiss
        // receipts write one. Money, with nothing after the point.
        val dash = line.startsWith(".-", end) || line.startsWith(",-", end)
        val n = Num(
            start, if (dash) end + 2 else end, shape.value, shape.decimals, shape.intDigits,
            shape.groupSep, if (dash) line[end] else shape.decimalSep, shape.idLike,
            negative = minusBefore(line, start, inSign), unreadSign = unreadSign,
        )
        // Not read, and not forgotten either: where it matters the answer
        // is "unsure", exactly as for a number broken by a space.
        if (shape.suspect) n.fragment = true
        found += n
    }
    return chains(line, found, signs, labels, page, labelledAbove)
}

/** A minus sign in front of a number, across a money sign: -78, -฿52.00, - THB 5. */
private fun minusBefore(line: String, start: Int, inSign: BooleanArray): Boolean {
    var k = start - 1
    while (k >= 0 && (line[k] == ' ' || inSign[k])) k--
    if (k < 0 || line[k] != '-') return false
    val prev = line.getOrNull(k - 1)
    // A hyphen joining two things is not a minus: 006-1207, G-Wallet.
    if (prev != null && prev.isLetterOrDigit()) return false
    // Nor is a dash between a date and its time: 08 ต.ค. 2569 - 18.10.
    var q = k - 1
    while (q >= 0 && line[q] == ' ') q--
    return q < 0 || line[q] !in '0'..'9'
}

/**
 * Numbers a single space apart.
 *
 * `1 234,56 €` is one number in Paris. Everywhere else a space inside what
 * looks like one number means the recogniser broke it: `52 001 บาท` is what
 * it made of 52.00 on a real slip, and `17 50 บาท` is what it makes of 17.50
 * when the point goes missing. Neither half is the amount, so both halves
 * are marked as fragments, which the amount rules refuse rather than read.
 *
 * Joined only when it is unmistakably one number: a currency that groups
 * thousands with a space, the shape of a thousands group with decimals at the
 * end, and an amount label right in front of it. `Tisch 4 125,00 €` is table
 * four and a price, and stays two numbers.
 */
private fun chains(
    line: String,
    nums: List<Num>,
    signs: List<SignHit>,
    labels: List<LabelHit>,
    page: PageContext,
    labelledAbove: Boolean,
): List<Num> {
    val out = mutableListOf<Num>()
    var i = 0
    while (i < nums.size) {
        var j = i
        while (j + 1 < nums.size && nums[j + 1].start == nums[j].end + 1 && line[nums[j].end] == ' ') j++
        if (j == i) {
            out += nums[i]
            i++
            continue
        }
        val run = nums.subList(i, j + 1)
        val near = signNear(line, signs, run.first().start, run.last().end)
        val code = near?.let { page.resolve(it.sign) } ?: page.guess
        val inline = labels.lastOrNull { it.end <= run.first().start }
        val labelled = if (inline != null) {
            inline.kind in AMOUNT_KINDS && line.substring(inline.end, run.first().start).none { it in '0'..'9' }
        } else {
            labelledAbove
        }
        val joined = if (labelled && code in SPACE_GROUPED && spaceGrouped(run, whole = code in WHOLE_SPACE_GROUPED)) {
            shapeOf(line.substring(run.first().start, run.last().end).replace(" ", ""), false)
        } else {
            null
        }
        if (joined != null) {
            out += Num(
                run.first().start, run.last().end, joined.value, joined.decimals, joined.intDigits,
                ' ', joined.decimalSep, joined.idLike, run.first().negative,
            )
        } else {
            for (n in run) {
                n.fragment = true
                out += n
            }
        }
        i = j + 1
    }
    return out
}

/**
 * 1 234 567,89: a first group of one to three, then threes, decimals at the
 * end — or no decimals, where the currency [whole] prints none.
 */
private fun spaceGrouped(run: List<Num>, whole: Boolean): Boolean {
    if (run.dropLast(1).any { !it.bare }) return false
    if (run.first().intDigits !in 1..3) return false
    if (run.drop(1).dropLast(1).any { it.intDigits != 3 }) return false
    val last = run.last()
    return last.intDigits == 3 && last.groupSep == null && (last.decimals > 0 || (whole && last.bare))
}

/** The sign right beside a stretch of a line, if there is one. */
private fun signNear(line: String, signs: List<SignHit>, start: Int, end: Int): SignHit? =
    signs.firstOrNull { it.end <= start && gap(line, it.end, start) } ?: signs.firstOrNull { it.start >= end && gap(line, end, it.start) }

/** Nothing but a few spaces (or a sign of the number) between two things. */
private fun gap(line: String, from: Int, to: Int): Boolean =
    to - from in 0..4 && (from until to).all { line[it] == ' ' || line[it] == '-' || line[it] == '+' }

/** A sign with a digit right beside it, judged before any number is scanned. */
private fun besideDigit(line: String, s: SignHit): Boolean {
    var k = s.end
    while (k < line.length && k - s.end < 2 && line[k] == ' ') k++
    if (k < line.length && line[k] in '0'..'9') return true
    k = s.start - 1
    while (k >= 0 && s.start - 1 - k < 2 && line[k] == ' ') k--
    return k >= 0 && line[k] in '0'..'9'
}

/**
 * Each sign to the number it belongs to.
 *
 * Left to right, and a number once claimed stays claimed. That is what makes
 * `USD 28.50 THB 1,000.00` come out as two pairs rather than THB claiming the
 * 28.50 on its left, and `0.00 บาท 7` come out as 0.00 baht and a stray 7 —
 * บาท is only ever written after its number.
 */
private fun attach(line: String, nums: List<Num>, signs: List<SignHit>) {
    for (s in signs) {
        val left = nums.lastOrNull { it.end <= s.start }?.takeIf { it.sign == null && gap(line, it.end, s.start) }
        val right = nums.firstOrNull { it.start >= s.end }?.takeIf { it.sign == null && gap(line, s.end, it.start) }
        val target = when (s.sign.side) {
            Side.AFTER -> left
            Side.BEFORE -> right
            Side.EITHER -> left ?: right
        }
        target?.sign = s
    }
}

// ─── labels ──────────────────────────────────────────────────────────────────

/**
 * What a label says about the numbers after it.
 *
 * Three kinds of amount, ranked. A slip that prints an amount, a fee and a
 * total has left the account with the total, so TOTAL outranks AMOUNT; one
 * that says what was paid outright outranks both. Everything else is a reason
 * a number is NOT the amount, and says which reason, because they differ in
 * what else they rule out.
 */
private enum class Kind {
    PAID, TOTAL, AMOUNT,
    /** A fee, a balance, a discount, a tax, a subtotal, a price, a quantity. */
    NOT,
    /** Cash handed over and change. Owns a number beside it, not below: those words are also titles. */
    NOT_HERE,
    /** An exchange rate, or what arrived on the other side of one. Not an amount, and not the slip's currency. */
    RATE,
    /** An account, a card, a phone, a tax id: somebody's number, never money. */
    IDENT,
    /** What a person typed — a memo, a note. Never an amount, and the words in it are not labels. */
    MEMO,
    REFERENCE, DATE,
}

private val AMOUNT_KINDS = setOf(Kind.PAID, Kind.TOTAL, Kind.AMOUNT)

/** Labels that, stacked above another label, mean the values are in a separate column. */
private val STACKING = AMOUNT_KINDS + setOf(Kind.NOT, Kind.NOT_HERE, Kind.RATE)

/** Owners a number printed as money can legitimately have. */
private val MONEY_OWNERS = AMOUNT_KINDS + setOf(Kind.NOT, Kind.NOT_HERE)

private class LabelHit(val start: Int, val end: Int, val kind: Kind)

// ชำระ alone is not a label: it is in ชำระบิล and ชำระเงินสำเร็จ, which are
// headlines, and on a Counter Service receipt ยอดชำระ is the bill while the
// total with the fee is printed under it. So ยอดชำระ is an amount, and only
// the phrases that say what left the wallet are here.
private val PAID = listOf(
    "จำนวนเงินที่ชำระ", "จำนวนเงินที่จ่าย", "ชำระแล้ว",
    "ยอดเงินสุทธิ", "ยอดสุทธิ", "รวมสุทธิ", "จ่ายสุทธิ", "ชำระสุทธิ", "รวมทั้งสิ้น",
    "grand total", "total paid", "amount paid", "paid amount", "you paid", "you sent", "you received",
    "you pay", "amount due", "total due", "balance due", "net amount", "net total", "net payable",
    "amount payable", "total payable", "total charged", "amount charged", "amount debited",
    "debit amount", "total to pay",
    "total bayar", "jumlah bayar", "total pembayaran", "jumlah pembayaran", "total tagihan",
    "jumlah dibayar", "amaun dibayar", "jumlah keseluruhan",
    "tong thanh toan", "so tien thanh toan", "tong tien thanh toan", "thanh toan",
    "total a pagar", "importe pagado", "monto pagado", "total pagado", "importe a pagar", "monto a pagar",
    "valor pago", "total pago", "valor a pagar",
    "net a payer", "montant paye", "total a payer", "a payer", "montant regle", "total paye",
    "zu zahlen", "gesamtbetrag", "endbetrag", "zahlbetrag", "bezahlt", "gezahlt",
    "att betala", "at betale", "fizetendo",
    "da pagare", "importo pagato", "totale pagato", "totale da pagare",
    "te betalen", "totaal betaald", "betaald",
    "odenen tutar", "odenecek tutar", "toplam odenen", "odenen",
    "do zaplaty", "zaplacono", "k uhrade", "zaplaceno",
    "итого к оплате", "к оплате", "оплачено",
    "お支払い金額", "お支払金額", "支払金額", "お支払", "ご請求金額", "ご利用金額", "決済金額", "お会計",
    "实付金额", "實付金額", "实付", "實付", "支付金额", "支付金額", "付款金额", "付款金額", "应付金额", "應付金額",
    "최종결제금액", "총결제금액", "결제금액", "결제 금액",
)

private val TOTAL = listOf(
    "จำนวนเงินรวม", "ยอดเงินรวม", "ยอดรวม", "รวมเงิน", "รวมทั้งหมด", "ราคารวม", "ราคาสุทธิ", "ทั้งหมด",
    "total amount", "amount total", "total price", "total cost", "total", "sum",
    "jumlah total", "total keseluruhan",
    "tong so tien", "tong tien", "tong cong", "tong",
    "importe total", "monto total", "valor total", "montant total", "importo totale",
    "suma", "soma", "somme", "gesamtsumme", "gesamt", "summe", "totale", "totaalbedrag", "totaal",
    "genel toplam", "toplam", "razem", "ogolem", "celkova castka", "celkem",
    "totalt", "summa", "i alt", "yhteensa", "osszesen",
    "итого", "всего",
    "合計金額", "税込合計", "総合計", "合計", "総額", "合计", "总计", "總計", "总金额", "總金額", "总额", "總額",
    "합계", "총금액", "총 금액", "총액",
)

private val AMOUNT = listOf(
    "จำนวนเงินโอน", "จำนวนเงิน", "จำนวน", "ยอดเงินโอน", "ยอดเงิน", "ยอดโอน", "ยอดเติมเงิน", "ยอดถอน", "ยอดฝาก",
    "ยอดที่ต้องชำระ", "ยอดที่ชำระ", "ยอดชำระ",
    "transfer amount", "payment amount", "transaction amount", "amount", "amt",
    "jumlah transfer", "nilai transaksi", "jumlah", "nominal", "nilai", "amaun",
    "so tien", "tien",
    "importe", "monto", "cantidad", "valor", "quantia", "montante", "montant",
    "uberweisungsbetrag", "zahlungsbetrag", "betrag", "importo", "bedrag",
    "islem tutari", "tutar", "kwota", "castka", "сумма",
    "送金額", "取引金額", "振込金額", "入金額", "出金額", "金額",
    "转账金额", "轉帳金額", "交易金额", "交易金額", "金额",
    "이체금액", "송금액", "거래금액", "금액",
    "राशि", "المبلغ",
)

private val NOT = listOf(
    // fees
    "ค่าธรรมเนียม", "ธรรมเนียม", "ธรรม", "เนียม", "ค่าบริการ", "ค่าจัดส่ง", "ค่าส่ง",
    "service fee", "service charge", "delivery fee", "convenience fee", "processing fee",
    "surcharge", "charges", "charge", "fees", "fee", "shipping",
    "biaya admin", "biaya layanan", "biaya", "caj",
    "phi giao dich", "phi dich vu", "phi",
    "comision", "tarifa", "cargos", "cargo", "taxa", "frais", "commission",
    "gebuhren", "gebuhr", "entgelt", "commissione", "spese", "transactiekosten", "kosten",
    "ucret", "masraf", "komisyon", "oplata", "prowizja", "poplatek", "комиссия",
    "手数料", "手续费", "手續費", "服务费", "服務費", "수수료",
    // balances, limits, what is still owed
    "ยอดเงินคงเหลือ", "ยอดคงเหลือ", "คงเหลือ", "ใช้ได้", "วงเงิน", "ยอดยกมา", "ค้างชำระ", "ยอดค้าง",
    "ขั้นต่ำ",
    "previous balance", "new balance", "credit limit", "minimum amount due", "minimum payment",
    "minimum due", "outstanding", "overdue", "balance", "available", "remaining", "limit",
    "sisa saldo", "saldo", "baki", "so du", "solde", "kontostand", "disponible", "disponivel",
    "bakiye", "остаток", "残高", "余额", "餘額", "잔액",
    // discounts, subsidies, refunds
    "ส่วนลด", "สิทธิ", "คูปอง", "เงินคืน",
    "discount", "coupon", "voucher", "promotion", "promo", "you saved", "savings", "saving",
    "cashback", "refund", "rebate",
    "diskon", "potongan", "giam gia", "khuyen mai", "hoan tien", "descuento", "desconto", "remise",
    "reduction", "rabatt", "sconto", "korting", "indirim", "rabat", "sleva", "скидка",
    "割引", "値引", "折扣", "优惠", "優惠", "할인",
    // tax and tips, and what was before them
    "ภาษี", "ไม่รวม", "ก่อนภาษี", "vat", "tax", "gst", "sst", "pajak", "ppn", "thue", "iva", "tva",
    "mwst", "ust", "btw", "kdv", "ндс", "消費税", "税额", "税", "稅", "부가세", "세금",
    "excl. vat", "excluding vat", "excl. tax", "before tax", "pre-tax",
    "tips", "tip", "gratuity", "ทิป",
    // subtotals, prices, quantities, weights
    "ค่าสินค้า", "ราคาสินค้า", "ราคาปกติ", "ราคา", "น้ำหนักสุทธิ", "น้ำหนัก", "ปริมาณสุทธิ",
    "subtotal", "sub total", "items total", "unit price", "price", "quantity", "qty",
    "net weight", "net wt", "weight",
    "zwischensumme", "sous total", "subtotale", "subtotaal", "ara toplam", "小計", "小计", "소계",
    // points, and the other side of a conversion
    "คะแนน", "แต้ม", "points", "poin", "diem", "punkte", "puntos", "pontos",
    "ポイント", "积分", "積分", "포인트",
)

private val NOT_HERE = listOf(
    "เงินทอน", "เงินสด", "cash tendered", "tendered", "change", "cash",
    "kembalian", "tunai", "tien thua", "tien mat", "cambio", "vuelto", "efectivo", "troco",
    "dinheiro", "rendu", "especes", "wechselgeld", "ruckgeld", "resto", "contanti",
    "wisselgeld", "contant", "para ustu", "nakit", "reszta", "gotowka",
    "お釣り", "おつり", "お預かり", "お預り", "現金", "找零", "现金", "거스름돈", "현금",
)

private val RATE = listOf(
    "อัตราแลกเปลี่ยน", "exchange rate", "conversion rate", "fx rate", "rate", "kurs", "ty gia",
    "tipo de cambio", "taux", "為替", "汇率", "환율",
    // The other side of a conversion: what arrived, in their money.
    "recipient gets", "recipient receives", "they receive", "ผู้รับได้รับ",
)

private val IDENT = listOf(
    "เลขที่บัญชี", "บัญชี", "บัตร", "เบอร์โทร", "เบอร์", "โทร", "เลขประจำตัวผู้เสียภาษี",
    "เลขผู้เสียภาษี", "เลขประจำตัวประชาชน", "รหัสลูกค้า", "หมายเลขลูกค้า", "สมาชิก",
    "account no", "account", "acct", "a/c", "card no", "card", "tel", "phone", "mobile",
    "tax id", "member", "wallet id", "user id", "customer id",
    "no. rekening", "rekening", "so tai khoan", "cuenta", "conta", "compte", "konto", "conto",
)

// บันทึกชวยจำ is Krungthai NEXT's บันทึกช่วยจำ as the recogniser reads it: its
// loopless font loses the tone mark. Without the long form here the label is
// matched as บันทึก alone, and the rest of the word would be taken for the memo.
private val MEMO = listOf(
    "รายละเอียด", "รายละเอยด", "ละเอียด", "ละเอยด", "บันทึกช่วยจำ", "บันทึกชวยจำ", "หมายเหตุ", "ข้อความ", "บันทึก",
    "notes", "note", "memo", "message", "remarks", "remark", "description", "purpose",
    "keterangan", "catatan", "berita", "noi dung", "loi nhan", "concepto", "motivo", "descripcion",
    "mensagem", "descricao", "libelle", "verwendungszweck", "betreff", "causale",
    "omschrijving", "aciklama", "tytul",
    "备注", "附言", "留言", "備註", "メモ", "摘要", "메모", "비고",
)

// Krungthai NEXT's loopless อ comes back as ฮ — รหัสฮ้างอิง, รหัสฮ้างฮิง — and
// its reference, Ac1f9… in letters and digits, sat under a label nothing
// matched. The ฮ spellings are no longer listed: fold() reads ฮ as อ before
// any label is looked for.
private val REFERENCE = listOf(
    "เลขที่รายการ", "รหัสอ้างอิง", "เลขที่อ้างอิง", "เลขอ้างอิง", "หมายเลขอ้างอิง", "รหัสรายการ",
    "หมายเลขรายการ", "รหัสธุรกรรม", "เลขที่ธุรกรรม", "เลขที่ใบเสร็จ",
    "reference number", "reference no", "reference", "ref. no", "ref no", "ref id", "ref",
    "transaction id", "transaction no", "transaction number", "transaction ref", "txn id",
    "txn no", "trans id", "trace no", "upi transaction id", "upi ref", "utr", "receipt no",
    "order id", "order no",
    "no. referensi", "referensi", "no. ref", "id transaksi", "nomor transaksi", "kode transaksi",
    "no. transaksi", "no. rujukan", "rujukan",
    "ma giao dich", "ma gd", "so tham chieu", "ma tham chieu", "so giao dich",
    "referencia", "numero de operacion", "n° de operacion", "folio", "id de transaccion",
    "numero de transaccion", "id da transacao", "codigo da transacao", "autenticacao",
    "numero de transaction", "n° de transaction", "referenz", "transaktions-id",
    "buchungsnummer", "riferimento", "id transazione", "numero operazione",
    "referentie", "kenmerk", "transactienummer", "referans", "islem no", "dekont no",
    "取引番号", "受付番号", "照会番号", "取引id",
    "交易单号", "交易單號", "交易号", "流水号", "订单号", "訂單編號", "参考号", "參考編號",
    "交易编号", "交易編號",
    "거래번호", "승인번호", "참조번호",
)

private val DATE = listOf(
    "วันที่ทำรายการ", "วัน/เวลา", "วันเวลา", "วันที่",
    "transaction date", "date/time", "paid on", "date",
    "tanggal", "tgl", "ngay giao dich", "ngay",
    "fecha", "data", "datum", "tarih",
    "日付", "取引日", "日時", "日期", "交易时间", "交易時間", "날짜", "거래일시", "일시",
)

/**
 * The headline of a slip: "transfer successful". A line that says this owns
 * nothing under it. ชำระเงินสำเร็จ has a word for "paid" in it, and the line
 * under it is the time — which, written 18.10, was once read as the amount.
 */
private val TITLE = phrases(listOf(
    "สำเร็จ", "สำเรจ", "สาเร็จ", "สาเรจ", "successfully", "successful", "success", "succeeded", "completed", "complete", "approved",
    "berhasil", "sukses", "thanh cong", "exitoso", "exitosa", "realizado", "concluido", "reussi",
    "effectue", "erfolgreich", "riuscito", "completato", "gelukt", "basarili", "pomyslnie", "uspesne",
    "成功", "完了", "완료", "성공",
))

/** Words for the time of day, which make 18.10.25 a time rather than a date. */
private val TIME_WORDS = phrases(listOf(
    "เวลา", "time", "waktu", "jam", "pukul", "thoi gian", "gio", "hora", "heure", "uhrzeit", "ora",
    "tijd", "saat", "時間", "时间", "시간",
))

/** Words on a line that make its date somebody else's: due, expiry, birth. */
private val NOT_THE_DAY = phrases(listOf(
    "ครบกำหนด", "หมดอายุ", "ภายในวันที่", "ถึงวันที่", "วันเกิด", "มีผล",
    "due", "expires", "expire", "expiry", "exp", "valid", "until", "birth", "dob", "effective",
    "value date",
    "jatuh tempo", "berlaku", "han thanh toan", "het han", "vencimiento", "vence",
    "vencimento", "validade", "echeance", "expiration", "fallig", "gultig", "scadenza",
    "valido", "vervaldatum", "geldig", "son odeme", "termin",
    "期限", "有効期限", "到期", "截止", "만료", "기한",
))

/**
 * Words that put a tax or a fee inside a total rather than on a line of its
 * own: "Total (incl. VAT)", "Total Bayar (termasuk PPN)", "合計(税込)",
 * ยอดรวมภาษี. Blanked before labels are looked for, keeping the first [Int]
 * characters — the รวม of รวมภาษี is still a total.
 */
private val INCLUDED_TAX: List<Pair<Regex, Int>> = listOf(
    Regex(
        """(?:incl\.?|inc\.?|including|inkl\.?|termasuk|bao gom|incluye|incluido|inclus|compris)\s*""" +
            """(?:vat|tax|gst|sst|ppn|pajak|mwst|ust|iva|tva|thue|fees?|service\s*charges?|charges?|ภาษี)""",
    ) to 0,
    Regex("""(?:vat|tax|gst|sst|ppn|iva|tva|fees?)\s*(?:incl\.?|included|incluido|incluso|incluse|termasuk)""") to 0,
    Regex("""税込|税抜""") to 0,
    Regex("""รวม\s*(?:vat|ภาษีมูลค่าเพิ่ม|ภาษี)""") to 3,
)

private class Phrases(val regex: Regex)

/** A list of phrases as one pattern, longest first, spaces optional. */
private fun phrases(list: List<String>): Phrases {
    val body = list.sortedByDescending { it.length }.joinToString("|") { p ->
        val sb = StringBuilder()
        for (c in p) {
            when (c) {
                ' ' -> sb.append("""\s*""")
                '.' -> sb.append("""\.?""")
                '-' -> sb.append("""[-\s]?""")
                '/' -> sb.append("""\s*/\s*""")
                in "\\^$|?*+()[]{}" -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.toString()
    }
    return Phrases(Regex(body))
}

private val LABELS: List<Pair<Kind, Phrases>> = listOf(
    Kind.PAID to phrases(PAID),
    Kind.TOTAL to phrases(TOTAL),
    Kind.AMOUNT to phrases(AMOUNT),
    Kind.NOT to phrases(NOT),
    Kind.NOT_HERE to phrases(NOT_HERE),
    Kind.RATE to phrases(RATE),
    Kind.IDENT to phrases(IDENT),
    Kind.MEMO to phrases(MEMO),
    Kind.REFERENCE to phrases(REFERENCE),
    Kind.DATE to phrases(DATE),
)

/**
 * Every match of [p] in [text], with Latin words held to whole words: `total`
 * is not in `subtotal`, `ref` is not in `prefer`.
 */
private fun matches(p: Phrases, text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var from = 0
    while (from < text.length) {
        val m = p.regex.find(text, from) ?: break
        val r = m.range
        if (r.isEmpty()) {
            from = r.first + 1
            continue
        }
        val first = text[r.first]
        val last = text[r.last]
        val okBefore = first !in 'a'..'z' || r.first == 0 || text[r.first - 1] !in 'a'..'z'
        val okAfter = !(last in 'a'..'z' || last == '.') || r.last + 1 >= text.length || text[r.last + 1] !in 'a'..'z'
        if (okBefore && okAfter) {
            out += r
            from = r.last + 1
        } else {
            from = r.first + 1
        }
    }
    return out
}

/** The labels on a line, and whether the line is a headline. */
private class Labels(val hits: List<LabelHit>, val title: Boolean)

private fun findLabels(folded: String): Labels {
    // Tax inside a total is not a tax line.
    val chars = folded.toCharArray()
    for ((rx, keep) in INCLUDED_TAX) {
        for (m in rx.findAll(folded)) {
            for (k in m.range.first + keep..m.range.last) chars[k] = ' '
        }
    }
    val text = chars.concatToString()
    if (matches(TITLE, text).isNotEmpty()) return Labels(emptyList(), title = true)

    val raw = mutableListOf<LabelHit>()
    for ((kind, p) in LABELS) for (r in matches(p, text)) raw += LabelHit(r.first, r.last + 1, kind)
    // รวม on its own is "total" at the start of a line and part of other
    // words anywhere else: ผัดผักรวม is a dish, ไม่รวม is "not including".
    if (text.startsWith("รวม") && text.getOrNull(3).let { it == null || it !in '\u0E01'..'\u0E4E' }) {
        raw += LabelHit(0, 3, Kind.TOTAL)
    }
    // Earlier first, then longer: "sub total" keeps its "total", "total paid"
    // is not also a "total".
    val kept = mutableListOf<LabelHit>()
    for (h in raw.sortedWith(compareBy<LabelHit> { it.start }.thenByDescending { it.end - it.start })) {
        if (kept.none { it.start < h.end && h.start < it.end }) kept += h
    }
    // Words that make one label between them: "Fee Amount", "Total Amount",
    // ค่าธรรมเนียมการชำระ. Joined when nothing but a short run of letters or
    // punctuation separates them — never across a number, and never across the
    // wide gap that means two columns.
    //
    // And never onto a memo label. What follows one is somebody's words, and
    // somebody's words are full of labels: บันทึกช่วยจำ: จ่ายบัตร has บัตร in it,
    // ค่าโทรศัพท์ has โทร. Joined, the memo label swallowed them and the memo
    // came back as ศัพท์, or as nothing at all.
    val merged = mutableListOf<LabelHit>()
    for (h in kept) {
        val prev = merged.lastOrNull()
        if (prev != null && prev.kind == Kind.MEMO) break
        if (prev != null && joins(text, prev.end, h.start)) {
            merged[merged.size - 1] = LabelHit(prev.start, h.end, stronger(prev.kind, h.kind))
        } else {
            merged += h
        }
    }
    // Everything after a memo label is what somebody typed. "รายละเอียด:
    // ค่าเช่ารวม 3,500" is a note about rent, not a total of 3,500.
    val memo = merged.indexOfFirst { it.kind == Kind.MEMO }
    return Labels(if (memo >= 0) merged.subList(0, memo + 1).toList() else merged, title = false)
}

private fun joins(text: String, from: Int, to: Int): Boolean {
    if (to - from > 12) return false
    val between = text.substring(from, to)
    return between.none { it in '0'..'9' } && !between.contains("  ")
}

/** Two labels as one: a reason it is not the amount wins, then the higher rank. */
private fun stronger(a: Kind, b: Kind): Kind {
    for (k in listOf(Kind.MEMO, Kind.RATE, Kind.IDENT, Kind.NOT, Kind.NOT_HERE, Kind.REFERENCE, Kind.DATE)) {
        if (a == k || b == k) return k
    }
    return if (a.ordinal <= b.ordinal) a else b
}

// ─── the page ────────────────────────────────────────────────────────────────

private class Line(
    val text: String,
    val folded: String,
    val labels: List<LabelHit>,
    val title: Boolean,
    val signs: List<SignHit>,
    val nums: List<Num>,
) {
    val hasDigit: Boolean = text.any { it in '0'..'9' }

    /** Nothing on it: no label, no digit, not a headline. Recogniser rubbish, or a name. */
    val noise: Boolean get() = labels.isEmpty() && !hasDigit && !title

    /**
     * Rubbish and nothing else: a few stray letters, `oom`, `=.`, `ซะ`. A name
     * or a bank's account type is noise to the amount rules but not junk —
     * it is a line of the slip, and two labels with it between them are not
     * a column of labels.
     */
    val junk: Boolean get() = noise && text.count { it.isLetter() } <= 4

    /** Every label on it is the memo's. */
    val memo: Boolean get() = labels.any { it.kind == Kind.MEMO }

    /** Numbers on it printed the way money is printed. */
    val money: List<Num> get() = nums.filter { !it.fragment && !it.idLike && (it.sign != null || !it.bare) }

    /** The dates on it, filled in once the page knows whether it is Thai. */
    var dates: List<Pair<String, Boolean>> = emptyList()
}

/** What the page says about money before any one number is looked at. */
private class PageContext(val home: String?) {
    /** The page's currency, when every sign on it says the same one. */
    var guess: String? = null

    /** Whether a dot on this page is a decimal point and never a thousands separator. */
    var dotDecimal = false

    fun resolve(s: Sign): String? = when {
        s.codes.size == 1 -> s.codes[0]
        home != null && home in s.codes -> home
        else -> s.fallback
    }
}

private class Page(val lines: List<Line>, val context: PageContext, val thai: Boolean) {

    fun capFor(n: Num): Double = capOf(n.sign?.let { context.resolve(it.sign) } ?: context.guess)

    /**
     * Answers already worked out. [ownerOf] looks at the line above a label to
     * check it is not a caption, which asks the owner of the numbers there,
     * and without this a long receipt asked the same question exponentially
     * many times — seconds on a laptop, longer on a phone.
     */
    private val owners = HashMap<Num, Kind?>()

    /**
     * The label a number belongs to, or null when it has none.
     *
     * A label on its own line owns the next number below it. On the first real
     * slip the fee label came back with no digits and its 0.00 arrived on the
     * line underneath; on later ones five or six lines of recogniser rubbish
     * sat in between. So the owner of a number with no label beside it is the
     * nearest label above, reached across noise but never across another
     * number — a line with its own digits owns them, and the chain stops.
     *
     * Two layouts make "the label above" the wrong answer, and both are
     * refused rather than read:
     *
     *  • labels stacked in one column and values in another, which a
     *    recogniser hands back as Subtotal / Tax / Total / 20.00 / 1.80 /
     *    21.80 — the first value is not the total's;
     *  • a caption under its value: ฿52.00 / จำนวนเงิน / ฿5.00 / ค่าธรรมเนียม,
     *    where the label above 5.00 is the caption of the 52.00.
     *
     * An account, card or phone label never owns a number printed as money:
     * "Mobile Top-up P100.00" is a top-up, not a phone number.
     */
    fun ownerOf(i: Int, n: Num): Kind? {
        if (n in owners) return owners[n]
        var kind = rawOwner(i, n)
        if (kind == Kind.IDENT && (n.sign != null || n.decimals == 2)) kind = null
        owners[n] = kind
        return kind
    }

    private fun rawOwner(i: Int, n: Num): Kind? {
        lines[i].labels.lastOrNull { it.end <= n.start }?.let { return it.kind }
        val (j, kind) = labelAbove(i) ?: return null
        if (kind == Kind.NOT_HERE || kind == Kind.REFERENCE || kind == Kind.DATE) return null
        // What sits above the label line, past any rubbish.
        var m = j - 1
        while (m >= 0 && j - m <= 8 && lines[m].junk) m--
        val above = lines.getOrNull(m) ?: return kind
        if (above.labels.isNotEmpty() && !above.hasDigit) {
            val other = above.labels.last().kind
            // Two amount labels in a row, one Thai and one not, are one label
            // in two languages. In one language they are a column.
            if (kind in AMOUNT_KINDS && other in AMOUNT_KINDS && thaiIn(lines[j].text) != thaiIn(above.text)) {
                return if (other.ordinal < kind.ordinal) other else kind
            }
            // A reference or a date label whose own value went missing is
            // not a column of labels.
            if (other !in STACKING) return kind
            // Nor is a label whose value is printed just above it, which is
            // how a recogniser hands back a row whose value sat a little
            // higher than its label: $3.00 / Tip / Total / $22.92.
            var p = m - 1
            while (p >= 0 && m - p <= 8 && lines[p].junk) p--
            val ownValue = lines.getOrNull(p)
            if (ownValue != null && ownValue.money.any { ownerOf(p, it) == null }) return kind
            return null
        }
        if (above.money.any { ownerOf(m, it) !in MONEY_OWNERS }) return null
        return kind
    }

    /** The label line owning the start of line [i]: its index and kind. */
    fun labelAbove(i: Int): Pair<Int, Kind>? = labelAbove(lines, i)

    /** Under a date label, on its line or as the line below a label of its own. */
    fun dateLabelled(i: Int): Boolean =
        lines[i].labels.any { it.kind == Kind.DATE } || labelAbove(i)?.second == Kind.DATE

    companion object {
        fun read(raw: String, hints: SlipHints): Page {
            val texts = normalize(raw).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val home = hints.homeCurrency?.trim()?.uppercase()?.takeIf { c -> c.length == 3 && c.all { it in 'A'..'Z' } }
            val context = PageContext(home)
            val signs = texts.map { findSigns(it) }
            // The page's currency, if every sign on it names the same one —
            // used to read 12.345 dinars and 1 234,56 euros, nothing else.
            val named = texts.indices.flatMap { i -> signs[i].filter { it.sign.strong || besideDigit(texts[i], it) } }
                .map { context.resolve(it.sign) }
            context.guess = named.filterNotNull().distinct().singleOrNull()?.takeIf { null !in named } ?: home
            // Thai enough to be a Thai slip: a few Thai words, or baht on it.
            // One stray Thai letter in an English receipt is the recogniser,
            // not the bank.
            val thaiLetters = texts.sumOf { t -> t.count { it in '\u0E01'..'\u0E4E' } }
            val baht = signs.flatten().any { context.resolve(it.sign) == "THB" }
            val thai = thaiLetters >= 8 || baht
            context.dotDecimal = thai || context.guess in DOT_DECIMAL

            val lines = mutableListOf<Line>()
            for ((i, t) in texts.withIndex()) {
                val folded = fold(t)
                val found = findLabels(folded)
                // The line under a memo label with nothing after it is the memo
                // itself: "รายละเอียด:" then "ชำระหนี้งวดสุดท้าย 5,000". Its words
                // are not labels and its numbers are not money.
                val previous = lines.lastOrNull()
                val underMemo = previous != null && previous.labels.lastOrNull()?.let { l ->
                    l.kind == Kind.MEMO && previous.text.substring(l.end).none { it.isLetterOrDigit() }
                } == true
                val labels = if (underMemo) listOf(LabelHit(0, 0, Kind.MEMO)) else found.hits
                val title = !underMemo && found.title
                val above = labelAbove(lines, lines.size)
                val labelledAbove = above != null && above.second in AMOUNT_KINDS
                val nums = scanNumbers(t, signs[i], labels, context, labelledAbove)
                attach(t, nums, signs[i])
                lines += Line(t, folded, labels, title, signs[i], nums)
            }
            for (line in lines) line.dates = datesIn(line, thai, hints.monthFirst)
            return Page(lines, context, thai)
        }
    }
}

/** Whether a stretch of text is written in Thai. */
private fun thaiIn(text: String): Boolean = text.any { it in '\u0E01'..'\u0E4E' }

/**
 * The label line owning the start of line [i] in [lines], as its index and
 * kind: the nearest line above with a label and no digits after it, reached
 * across noise and never across a number or a headline.
 */
private fun labelAbove(lines: List<Line>, i: Int): Pair<Int, Kind>? {
    for (k in 1..8) {
        val j = i - k
        val above = lines.getOrNull(j) ?: return null
        if (above.title) return null
        val last = above.labels.lastOrNull()
        if (last != null) {
            if (above.text.substring(last.end).any { it in '0'..'9' }) return null
            return j to last.kind
        }
        if (above.hasDigit) return null
    }
    return null
}

// ─── the amount ──────────────────────────────────────────────────────────────

private class Pick(val value: Double, val num: Num)

/**
 * The amount that left the account.
 *
 * Ranked labels first — what was paid, then the total, then the amount — and
 * only then a number that merely has a currency beside it. Within the rank
 * that has anything, one value is the answer and two are a refusal: the
 * alternative is picking the largest, which is right on most slips and
 * silently wrong on the ones that print a daily limit under a name these
 * tables have never heard of.
 */
private fun readAmount(page: Page, problems: MutableList<String>): Pick? {
    val ranks = List(4) { mutableListOf<Pick>() }
    val negatives = mutableListOf<Pick>()
    var brokenRank = Int.MAX_VALUE
    val printedAsMoney = mutableListOf<Num>()
    // Whether anything on the page shows it prints money with a decimal point:
    // 0.00, 0.0, or a 000 that is what 0.00 becomes when the point goes.
    var printsDecimals = false

    for ((i, line) in page.lines.withIndex()) {
        for (n in line.nums) {
            val owner = page.ownerOf(i, n)
            val signed = n.sign != null
            val rank = when (owner) {
                Kind.PAID -> 0
                Kind.TOTAL -> 1
                Kind.AMOUNT -> 2
                null -> 3
                else -> -1
            }
            // Half of a broken number where an amount should be. Not read, and
            // remembered, so the answer is "unsure" rather than "nothing here".
            if (n.fragment) {
                if (rank in 0..2 || (rank == 3 && signed)) brokenRank = minOf(brokenRank, rank)
                continue
            }
            val counted = signed || rank in 0..2 || owner == Kind.NOT || owner == Kind.NOT_HERE
            if (counted && (n.decimals in 1..2 || (n.bare && n.intDigits >= 3 && n.value == 0.0))) printsDecimals = true
            if (n.idLike) continue
            if (counted) printedAsMoney += n
            if (rank < 0) continue
            // The time, on the line with the date: 08 ต.ค. 2569 - 18.10.
            if (line.dates.isNotEmpty() && clockLike(n)) continue
            // Nothing says this is money: no label, no sign. A ₱ or ฿ the
            // recogniser could only read as P or B is still a sign in front of
            // a number printed to the cent: Maya's P500.00, TrueMoney's B100.00.
            if (rank == 3 && !signed && !(n.unreadSign && n.decimals == 2)) continue
            // A bare digit under an amount label, with no sign beside it, is a
            // digit found in noise. On a real slip 70.60 came back as `จ9`
            // under จำนวน: and was offered to the ledger as nine baht with
            // "problems none". Money is printed with a separator or a sign;
            // this is neither. (A ฿ read as B is a sign, even unread.)
            if (rank < 3 && !signed && !n.unreadSign && n.bare) continue
            if (n.value <= 0.0 || n.value >= page.capFor(n)) continue
            // A discount or a refund, with nothing calling it the amount, is
            // only the answer when nothing positive is: Revolut and MoMo print
            // what left as -£12.50 and nothing else.
            if (rank == 3 && n.negative) {
                negatives += Pick(n.value, n)
                continue
            }
            ranks[rank] += Pick(n.value, n)
        }
    }
    // Only when nothing else on the page is money either: a price of 130 and a
    // -78 whose label was lost are a price and a discount, not a payment of 78.
    if (ranks[3].isEmpty() && printedAsMoney.none { !it.negative && it.value != 0.0 }) ranks[3] += negatives

    for ((rank, picks) in ranks.withIndex()) {
        if (picks.isEmpty()) continue
        if (brokenRank <= rank) {
            problems += "amount-unsure"
            return null
        }
        val values = picks.distinctBy { (it.value * 1000).roundToLong() }
        if (values.size > 1) {
            problems += "amount-ambiguous"
            return null
        }
        val pick = values[0]
        if (lostItsPoint(pick.num, printsDecimals) || separatorsDisagree(pick.num, printedAsMoney)) {
            problems += "amount-unsure"
            return null
        }
        return pick
    }
    problems += if (brokenRank != Int.MAX_VALUE) "amount-unsure" else "no-amount"
    return null
}

/**
 * Whether a whole number is a decimal number with its point rubbed out.
 *
 * A real slip printed 17.00 and the recogniser returned `1700 บาท`. Nothing
 * about 1700 is wrong on its own. What gives it away is the rest of the page:
 * the fee under it is printed 0.00, and a slip prints all its money one way.
 * So when anything else on the page has two decimals, a bare number of three
 * digits or more — which is what losing the point from d.dd produces — is not
 * trusted. Pao Tang prints 130, -78 and 52 with no decimals at all, and is read
 * exactly as printed.
 */
private fun lostItsPoint(n: Num, printsDecimals: Boolean): Boolean =
    n.bare && n.intDigits >= 3 && printsDecimals

/**
 * Whether a number is the time of day: 18.10 is ten past six, on the line
 * that also has the date. A Thai bank writes the time with a dot as often as
 * with a colon, and under a headline that said ชำระ the time was once read as
 * eighteen baht ten.
 */
private fun clockLike(n: Num): Boolean {
    if (n.sign != null || n.decimals != 2 || n.intDigits > 2) return false
    val hours = n.value.toInt()
    val minutes = ((n.value - hours) * 100).roundToLong()
    return hours <= 23 && minutes <= 59
}

/**
 * Whether the amount uses as a thousands separator the character the rest of
 * the page uses as a decimal point, or the other way round.
 *
 * `52.001 บาท` over a fee of `0.00 บาท` is 52.00 with a digit of rubbish on
 * the end, not fifty-two thousand and one: on that page `.` is the decimal
 * point. The rupiah slip that prints Rp 150.000 prints its fee Rp 2.500, and
 * agrees with itself.
 */
private fun separatorsDisagree(n: Num, printedAsMoney: List<Num>): Boolean {
    // A fee of nothing says little: 0,00 under 1,250.00 is the recogniser
    // making a comma of a dot, not the page changing its mind.
    val others = printedAsMoney.filter { it !== n && it.value != 0.0 }
    val g = n.groupSep
    val d = n.decimalSep
    return (g != null && others.any { it.decimalSep == g }) || (d != null && others.any { it.groupSep == d })
}

// ─── the currency ────────────────────────────────────────────────────────────

/**
 * The amount's own sign first.
 *
 * Without one, the currency is borrowed only from the amount's own company: a
 * fee, a discount, cash, change, or a sign standing on its own. Not from a
 * number in a different role — "Recipient gets 3,550.00 THB" under "Total
 * paid 100.00" says nothing about what the 100 was in, and neither does the
 * USD of "Rate 1 USD = 35.50".
 */
private fun readCurrency(page: Page, pick: Pick?, problems: MutableList<String>): String? {
    val own = pick?.num?.sign
    if (own != null) {
        val code = page.context.resolve(own.sign)
        if (code == null) problems += "currency-ambiguous"
        return code
    }
    val signs = mutableListOf<SignHit>()
    for ((i, line) in page.lines.withIndex()) {
        val attached = line.nums.mapNotNull { it.sign }
        if (pick == null) {
            // Nothing was picked, so there is no amount to be wrong about;
            // what the page is in is still worth showing.
            signs += attached
        } else {
            for (n in line.nums) {
                val s = n.sign ?: continue
                val owner = page.ownerOf(i, n)
                if (owner == Kind.NOT || owner == Kind.NOT_HERE) signs += s
            }
        }
        val owners = line.labels.map { it.kind }
        if (Kind.RATE in owners || Kind.MEMO in owners) continue
        // A sign with no number beside it counts only where it can only be
        // the unit: on the amount's label line ("Amount (THB)", "จำนวนเงิน
        // (บาท)"), as a Thai word, or as a code standing on a line of its
        // own. A lone £ or RM on a line of rubbish is rubbish.
        val unitLine = owners.any { it in AMOUNT_KINDS }
        val bare = line.text.trim().trim('(', ')', '[', ']', ':', '.', ' ')
        for (hit in line.signs) {
            if (hit in attached) continue
            val text = hit.sign.text
            if (unitLine || thaiIn(text) || (bare == text && text.length >= 3)) signs += hit
        }
    }
    if (signs.isEmpty()) {
        problems += "no-currency"
        return null
    }
    val codes = signs.map { page.context.resolve(it.sign) }
    val distinct = codes.filterNotNull().distinct()
    if (distinct.size == 1 && null !in codes) return distinct[0]
    problems += "currency-ambiguous"
    return null
}

// ─── the date ────────────────────────────────────────────────────────────────

/**
 * The date, as Gregorian.
 *
 * Buddhist years are subtracted whether written in full or in two digits: a
 * Thai slip printed 2569 is this year, and reading it as the year 2569 files
 * the row five centuries out with nothing on screen to notice by.
 *
 * Every date on the page is collected, and the ones on a line that says due,
 * expiry or birth are set aside first — a slip whose own date was destroyed
 * still prints when its points expire, and that is not the day it was paid.
 * One date is the answer. Several are a refusal, unless exactly one of them
 * sits under a date label.
 */
private fun readDate(page: Page, problems: MutableList<String>): String? {
    val found = mutableListOf<Pair<String, Int>>()
    val weak = mutableListOf<Pair<String, Int>>()
    for ((i, line) in page.lines.withIndex()) {
        // Due dates, expiry dates and what somebody typed in the memo.
        if (line.memo || matches(NOT_THE_DAY, line.folded).isNotEmpty()) continue
        for ((d, isWeak) in line.dates) if (isWeak) weak += d to i else found += d to i
    }
    // 18.10.25 is a date or the time to the second. It is only read as a date
    // under a date label, on a line that says nothing about time, and only
    // when nothing else on the page is a date.
    if (found.isEmpty()) {
        found += weak.filter { (_, i) -> page.dateLabelled(i) && matches(TIME_WORDS, page.lines[i].folded).isEmpty() }
    }
    val all = found.map { it.first }.distinct()
    if (all.size == 1) return all[0]
    if (all.isEmpty()) {
        problems += "no-date"
        return null
    }
    val labelled = found.filter { (_, i) -> page.dateLabelled(i) }.map { it.first }.distinct()
    if (labelled.size == 1) return labelled[0]
    problems += "date-ambiguous"
    return null
}

/**
 * Thai vowel and tone marks that are not part of any month's abbreviation.
 *
 * Taken out of a line before months are looked for, because a recogniser adds
 * them where the slip has none: the October slips came back as `8 ต.ุค. 69`.
 * ิ and ี stay — they are what tells มี.ค. and มิ.ย. from ม.ค.
 */
private fun thaiSkeleton(s: String): String =
    s.filterNot { it == '\u0E31' || it in '\u0E36'..'\u0E3A' || it in '\u0E47'..'\u0E4E' }

/** A year is never followed by a decimal part: in `1 SET 99.00`, 99 is a price. */
private const val YEAR_END = """(?![\d])(?![.,]\d)"""

/** The twelve abbreviations Face.kt prints, read in the other direction. */
private val THAI_SHORT: List<Regex> = THAI_MONTHS_SHORT.map { label ->
    val parts = thaiSkeleton(label).split('.').filter { it.isNotEmpty() }
    Regex(
        """(\d{1,2})\s*""" + parts.joinToString("""\s*[.,]\s*""") +
            """\s*[.,]?\s*(?:พ\s*\.?\s*ศ\s*\.?\s*)?(\d{4}|\d{2})""" + YEAR_END,
    )
}

private val THAI_LONG: List<Regex> = THAI_MONTHS.map { name ->
    Regex("""(\d{1,2})\s*""" + thaiSkeleton(name) + """\s*(?:พ\s*\.?\s*ศ\s*\.?\s*)?(\d{4}|\d{2})""" + YEAR_END)
}

/** Month names and their usual abbreviations, folded, in the languages above. */
private val MONTH_WORDS: Map<String, Int> = buildMap {
    val table = listOf(
        "january jan januari janvier janv januar janner enero ene janeiro gennaio gen ocak",
        "february feb februari fevrier fevr februar febrero fevereiro fev febbraio subat pebrero",
        "march mar maret mac mars marz marzo marco maart mrt mart marso",
        "april apr avril abril abr aprile nisan",
        "may mei mai mayo maio maggio mag mayis",
        "june jun juni juin junio junho giugno giu haziran hunyo",
        "july jul juli julai juillet juil julio julho luglio lug temmuz hulyo",
        "august aug agustus agu agt ogos aout agosto ago augustus agustos",
        "september sep sept setembro set settembre septiembre setiembre septembre eylul setyembre",
        "october oct oktober okt octobre octubre outubro out ottobre ott ekim oktubre",
        "november nov novembre noviembre novembro kasim nobyembre",
        "december dec desember des disember decembre dezember dez diciembre dic dezembro dicembre aralik disyembre",
    )
    for ((i, words) in table.withIndex()) for (w in words.split(' ')) put(w, i + 1)
}

private val LATIN_DMY = Regex("""(\d{1,2})(?:st|nd|rd|th|er)?[\s.\-/,]*(?:de\s+)?([a-z]{3,10})\.?[\s.\-/,]*(?:de\s+|del\s+)?(\d{4}|\d{2})""" + YEAR_END)
private val LATIN_MDY = Regex("""([a-z]{3,10})\.?[\s.\-]*(\d{1,2})(?:st|nd|rd|th)?\s*,?\s*(\d{4})""" + YEAR_END)
private val CJK_YMD = Regex("""(\d{4})\s*[年년]\s*(\d{1,2})\s*[月월]\s*(\d{1,2})""")
private val VIET = Regex("""ngay\s*(\d{1,2})\s*thang\s*(\d{1,2})\s*nam\s*(\d{4})""")
private val YMD = Regex("""(\d{4})([./\-])(\d{1,2})\2(\d{1,2})""" + YEAR_END)
private val DMY = Regex("""(\d{1,2})([./\-])(\d{1,2})\2(\d{4}|\d{2})""" + YEAR_END)

/** A time right before a dash: the 18:10 of 18:10-08/10/2026. */
private val CLOCK_BEFORE = Regex("""\d{1,2}:\d{2}\s*$""")

/**
 * [regex] in [text], skipping matches that start inside something else: a
 * number, a word, or a code like INV-10-12-25.
 */
private fun Regex.starts(text: String): List<MatchResult> {
    val out = mutableListOf<MatchResult>()
    var from = 0
    while (from < text.length) {
        val m = find(text, from) ?: break
        val b = text.getOrNull(m.range.first - 1)
        val bb = text.getOrNull(m.range.first - 2)
        val afterClock = b == '-' && CLOCK_BEFORE.containsMatchIn(text.substring(0, m.range.first - 1))
        val inside = !afterClock && b != null &&
            (b.isLatin() || b in '0'..'9' || (b in "-/._" && bb != null && (bb.isLatin() || bb in '0'..'9')))
        if (!inside) {
            out += m
            from = m.range.last + 1
        } else {
            from = m.range.first + 1
        }
    }
    return out
}

/**
 * Month words that are also ordinary words somewhere: "1 SET 50" is a set
 * meal, not the first of September 2050. Read as months only with a four-digit
 * year or with the dashes and slashes of a printed date around them.
 */
private val LOOSE_MONTH_WORDS = setOf("set", "mar", "may", "out", "ago", "gen", "mag", "des", "ene", "mai")

private fun monthWordOk(word: String, whole: String, year: String): Boolean =
    word !in LOOSE_MONTH_WORDS || year.length == 4 || whole.contains('-') || whole.contains('/')

/** Every date on a line, each with whether it could also be a time of day. */
private fun datesIn(line: Line, thai: Boolean, monthFirst: Boolean): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    fun add(d: String?, weak: Boolean = false) {
        if (d != null) out += d to weak
    }
    val skeleton = thaiSkeleton(line.text)
    for ((i, rx) in THAI_SHORT.withIndex()) {
        for (m in rx.starts(skeleton)) add(date(m.groupValues[2], i + 1, m.groupValues[1].toInt()))
    }
    for ((i, rx) in THAI_LONG.withIndex()) {
        for (m in rx.starts(skeleton)) add(date(m.groupValues[2], i + 1, m.groupValues[1].toInt()))
    }
    for (m in LATIN_DMY.starts(line.folded)) {
        val word = m.groupValues[2]
        val month = MONTH_WORDS[word] ?: continue
        if (!monthWordOk(word, m.value, m.groupValues[3])) continue
        add(date(m.groupValues[3], month, m.groupValues[1].toInt()))
    }
    for (m in LATIN_MDY.starts(line.folded)) {
        val month = MONTH_WORDS[m.groupValues[1]] ?: continue
        add(date(m.groupValues[3], month, m.groupValues[2].toInt()))
    }
    for (m in CJK_YMD.starts(line.text)) {
        add(date(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt()))
    }
    for (m in VIET.starts(line.folded)) {
        add(date(m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1].toInt()))
    }
    for (m in YMD.starts(line.text)) {
        add(date(m.groupValues[1], m.groupValues[3].toInt(), m.groupValues[4].toInt()))
    }
    for (m in DMY.starts(line.text)) {
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[3].toInt()
        val rawYear = m.groupValues[4]
        // Day first, unless the numbers say otherwise or the phone does. A
        // Buddhist year or a Thai slip settles it: Thai slips are day first.
        val buddhist = rawYear.toInt() >= 2400 || (rawYear.length == 2 && rawYear.toInt() in 60..99)
        val dayFirst = when {
            a > 12 -> true
            b > 12 -> false
            thai || buddhist -> true
            else -> !monthFirst
        }
        // 18.10.25 written with dots is as much ten past six and twenty-five
        // seconds as it is a day in 2025.
        val clock = m.groupValues[2] == "." && rawYear.length == 2 && a <= 23 && b <= 59 && rawYear.toInt() <= 59
        add(if (dayFirst) date(rawYear, b, a) else date(rawYear, a, b), weak = clock)
    }
    return out.distinctBy { it.first }
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

/**
 * A date from the year as printed, or null for one no slip has.
 *
 * A two-digit year has to land within ten years or so of now. `26/10/08`
 * from a Korean receipt, read the Thai way round, is the eighth of an October
 * in 2008; refusing it is better than filing it eighteen years ago.
 */
private fun date(year: String, month: Int, day: Int): String? {
    val y = gregorian(year.toInt())
    if (year.length <= 2 && y !in 2015..2045) return null
    return iso(y, month, day)
}

/** YYYY-MM-DD, or null for a day no calendar has. */
private fun iso(year: Int, month: Int, day: Int): String? {
    if (year !in 2000..2100 || month !in 1..12) return null
    val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    val days = when (month) {
        2 -> if (leap) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    if (day !in 1..days) return null
    return year.toString().padStart(4, '0') + "-" +
        month.toString().padStart(2, '0') + "-" +
        day.toString().padStart(2, '0')
}

// ─── the reference ───────────────────────────────────────────────────────────

private sealed interface Ref {
    data class Found(val value: String) : Ref

    /** Printed shortened, broken in two, or wrapped onto the next line. */
    data object Cut : Ref

    /** A number is here and it is not a reference: stop looking under this label. */
    data object Other : Ref

    /** Nothing here. Keep looking. */
    data object None : Ref
}

/**
 * A bill's Ref 1 and Ref 2 are the customer's numbers at the biller — often a
 * national ID or a phone number — and never the transaction's. Thai bill slips
 * print both above เลขที่รายการ, which is the one wanted.
 */
private val BILL_REF = Regex("""^\s*[1-3]\s*(?:[:.)]|\s|$)""")

/**
 * The reference number.
 *
 * Taken from under a reference label only. A slip is covered in long digit
 * strings — account numbers, phone numbers, the QR payload — and picking the
 * longest one on the page would copy an account number into a note field about
 * as often as it would find a reference.
 *
 * The label's own line first. If something number-like is there and it is not
 * a reference — `Receipt No: 001234` — that was the label's answer, and the
 * lines below are not searched for a better one. Otherwise the next few lines,
 * past noise, stopping at any line with a label of its own: on the second real
 * slip four lines of rubbish sat between เลขที่รายการ and its number.
 */
private fun readReference(page: Page, problems: MutableList<String>): String? {
    var cut = false
    for ((i, line) in page.lines.withIndex()) {
        for (label in line.labels) {
            if (label.kind != Kind.REFERENCE) continue
            val rest = line.text.substring(label.end)
            if (BILL_REF.containsMatchIn(rest)) continue
            when (val own = referenceIn(rest, sameLine = true, next = page.lines.getOrNull(i + 1), beside = true)) {
                is Ref.Found -> return own.value
                Ref.Cut -> {
                    cut = true
                    continue
                }
                Ref.Other -> continue
                Ref.None -> {}
            }
            var first = true
            for (j in i + 1..minOf(i + 6, page.lines.lastIndex)) {
                val here = page.lines[j]
                if (here.labels.isNotEmpty() || here.title) break
                val r = referenceIn(here.text, sameLine = first, next = page.lines.getOrNull(j + 1))
                if (!here.noise) first = false
                when (r) {
                    is Ref.Found -> return r.value
                    Ref.Cut -> {
                        cut = true
                        break
                    }
                    Ref.Other -> break
                    Ref.None -> {}
                }
            }
        }
    }
    problems += if (cut) "reference-cut" else "no-reference"
    return null
}

private val CHUNK = Regex("""[A-Za-z0-9][A-Za-z0-9\-]*""")

/**
 * A whole reference in a stretch of text, if there is one.
 *
 * Twelve characters at least, mostly digits. Twelve because India's UPI
 * reference is twelve digits; not fewer, because on one slip the recogniser
 * split a twenty-character K PLUS reference in two and an eleven-character
 * half came back looking exactly as trustworthy as a whole one. Right
 * [beside] the label, letters may outnumber the digits — see [lettered].
 *
 * Digit groups one space apart are joined right beside the label or on the
 * first line under it — GCash prints `Ref No. 9012 345 678901` on purpose —
 * and only when there are twelve digits or more. Further down, under a label
 * that lost its value, the next spaced digits are as likely to be a phone
 * number, and a phone number with its country code is eleven. Pieces with
 * letters in them are a reference the recogniser broke, and half of it is not
 * a shorter reference, it is a different one.
 */
private fun referenceIn(text: String, sameLine: Boolean, next: Line?, beside: Boolean = false): Ref {
    val chunks = CHUNK.findAll(text).toList()
    var best: String? = null
    var cut = false
    var other = false
    var i = 0
    while (i < chunks.size) {
        if (chunks[i].value.none { it.isDigit() }) {
            i++
            continue
        }
        var j = i
        while (j + 1 < chunks.size &&
            chunks[j + 1].range.first == chunks[j].range.last + 2 &&
            text[chunks[j].range.last + 1] == ' ' &&
            chunks[j + 1].value.any { it.isDigit() }
        ) j++
        val run = chunks.subList(i, j + 1).map { it.value }
        val after = text.substring(chunks[j].range.last + 1)
        val shortened = after.trimStart().startsWith("..")
        if (run.size > 1) {
            val digitsOnly = run.all { c -> c.all { it.isDigit() } }
            when {
                digitsOnly && sameLine && run.all { it.length in 2..6 } -> {
                    val joined = run.joinToString("")
                    when {
                        joined.length !in 12..30 || thaiCitizenId(joined) || phoneLike(joined) -> other = true
                        shortened -> cut = true
                        else -> best = longer(best, joined)
                    }
                }
                // Spaced digits further down, or a reference with letters
                // broken in two: not a reference.
                else -> other = true
            }
        } else {
            val c = run[0]
            when {
                masked(c) || thaiCitizenId(c.filter { it != '-' }) || THAI_ID_PRINTED.matches(c) ||
                    phoneLike(c) -> other = true
                uuidShape(c) == UuidShape.WHOLE -> best = longer(best, c.replace('O', '0').replace('o', '0'))
                uuidShape(c) == UuidShape.CUT -> cut = true
                looksLikeReference(c) || (beside && lettered(c)) -> when {
                    shortened -> cut = true
                    after.isBlank() && wraps(next) -> other = true
                    else -> best = longer(best, c)
                }
                c.count { it.isDigit() } >= 4 -> other = true
            }
        }
        i = j + 1
    }
    return when {
        best != null -> Ref.Found(best)
        cut -> Ref.Cut
        other -> Ref.Other
        else -> Ref.None
    }
}

private fun longer(a: String?, b: String): String = if (a == null || b.length > a.length) b else a

private fun looksLikeReference(c: String): Boolean {
    val body = c.filter { it != '-' }
    return body.length in 12..40 && body.count { it.isDigit() } * 2 >= body.length
}

/**
 * A reference written in letters as much as digits, which only counts right
 * beside its label.
 *
 * Krungthai NEXT's is Ac and fifteen hex digits — Ac1f9e07d3b52c8a4 — which is
 * as often under half digits as over, and the recogniser makes it fewer still:
 * 5 comes back as S or h, 0 as O. Beside the label nothing else is printed, so
 * a long run of letters with a few digits in it is the reference, misread or
 * not. A misread one is no worse at its only job, which is to be the same
 * every time the same picture is read.
 */
private fun lettered(c: String): Boolean {
    val body = c.filter { it != '-' }
    return body.length in 12..40 && body.count { it.isDigit() } >= 3 && body.count { it.isLetter() } >= 3
}

/**
 * A Thai national ID: thirteen digits whose last is the check digit of the
 * other twelve. Bill slips print the payer's under a reference label often
 * enough, and it is the one number on a slip that must never be copied into
 * a note. One genuine thirteen-digit reference in ten also passes the check
 * and is refused with it; that is the right way round for this mistake.
 */
private fun thaiCitizenId(digits: String): Boolean {
    if (digits.length != 13 || digits.any { it !in '0'..'9' } || digits[0] == '0' || digits[0] == '9') return false
    var sum = 0
    for (k in 0 until 12) sum += (digits[k] - '0') * (13 - k)
    return (11 - sum % 11) % 10 == digits[12] - '0'
}

/** 1-2345-67890-12-3: how a Thai ID card prints its number. Whatever the check digit says. */
private val THAI_ID_PRINTED = Regex("""\d-\d{4}-\d{5}-\d{2}-\d""")

/**
 * A mobile number with its country code, which is what sits under a
 * reference label whose own value was lost often enough: 63 917…, 91 98…,
 * 62 812…, 60 12…, 66 81…, 84 9…. Twelve digits like a UPI reference, and
 * not one.
 */
private fun phoneLike(c: String): Boolean {
    if (c.any { it !in '0'..'9' }) return false
    val n = c.length
    return when {
        c.startsWith("63") -> n == 12 && c[2] == '9'
        c.startsWith("91") -> n == 12 && c[2] in "6789"
        c.startsWith("62") -> n in 11..14 && c[2] == '8'
        c.startsWith("60") -> n in 11..12 && c[2] == '1'
        c.startsWith("66") -> n == 11 && c[2] in "689"
        c.startsWith("84") -> n in 11..12 && c[2] in "35789"
        c.startsWith("65") -> n == 10 && c[2] in "89"
        else -> false
    }
}

/** 4111-XXXX-XXXX-1111: a card or an account with the middle hidden. */
private fun masked(c: String): Boolean = c.contains("XXX") || c.contains("xxx")

/**
 * Whether the line after a reference is the rest of it: `016281180000` on one
 * line and `APM01111` on the next is one reference wrapped, and the first half
 * alone is not it.
 */
private fun wraps(next: Line?): Boolean {
    if (next == null) return false
    val t = next.text.trim()
    return t.length >= 4 && CHUNK.matchEntire(t) != null && t.any { it.isDigit() } && t.any { it.isLetter() }
}

private enum class UuidShape { WHOLE, CUT, NOT }

/**
 * Pao Tang prints its reference as a UUID and cuts it with "…" to fit the
 * line: ac0caffa-b27d-4253-84a0-969480318… The recogniser drops the dots as
 * often as it keeps them, so the shape itself is checked — a UUID's last group
 * is twelve long, and nine is a cut one.
 */
private fun uuidShape(c: String): UuidShape {
    val parts = c.split('-')
    if (parts.size < 2 || parts.size > 5) return UuidShape.NOT
    val hex = parts.all { p -> p.isNotEmpty() && p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == 'o' || it == 'O' } }
    if (!hex || parts[0].length != 8) return UuidShape.NOT
    val want = listOf(8, 4, 4, 4, 12)
    for (k in 0 until parts.size - 1) if (parts[k].length != want[k]) return UuidShape.NOT
    return when {
        parts.size == 5 && parts[4].length == 12 -> UuidShape.WHOLE
        parts.last().length <= want[parts.size - 1] -> UuidShape.CUT
        else -> UuidShape.NOT
    }
}

// ─── the memo ────────────────────────────────────────────────────────────────

/**
 * Labels that only ever stand in front of what the payer typed.
 *
 * A subset of [MEMO] on purpose. To the amount rules everything after any memo
 * label is somebody's words and not money, and that is right for all of them.
 * But รายละเอียด, ข้อความ and description also head sections a bank prints
 * itself — รายละเอียดการโอน, "ดูรายละเอียดที่ K PLUS" — and a bank's own
 * heading copied into the note is a note nobody typed.
 */
private val TYPED = phrases(listOf("บันทึกช่วยจำ", "บันทึกชวยจำ", "memo", "note", "notes"))

/** Long enough for any memo a bank lets somebody type. */
private const val MEMO_MAX = 80

/**
 * What the payer typed, from beside the memo label or from the line under it.
 *
 * Under it is how Krungthai NEXT comes back: the label on the left, the memo
 * right-aligned on the same row, and the recogniser hands them over as two
 * lines. Page.read has already marked that second line as the memo's — the same
 * mark that keeps its numbers out of the amount — so it is read from there
 * rather than worked out a second time.
 */
private fun readMemo(page: Page): String? {
    for ((i, line) in page.lines.withIndex()) {
        val label = line.labels.firstOrNull { it.kind == Kind.MEMO } ?: continue
        // The mark Page.read puts on the line under a memo label is zero wide.
        if (label.end <= label.start) continue
        if (matches(TYPED, line.folded.substring(label.start, label.end)).isEmpty()) continue
        typed(line.text.substring(label.end))?.let { return it }
        val next = page.lines.getOrNull(i + 1) ?: return null
        val under = next.labels.singleOrNull()?.let { it.kind == Kind.MEMO && it.end == 0 } == true
        return if (under) typed(next.text) else null
    }
    return null
}

/** Somebody's words without the colon in front of them, or null when there are none. */
private fun typed(text: String): String? {
    val t = text.trim().trimStart(':', '-', '.', '\u00B7').trim().replace(Regex("""\s+"""), " ")
    if (t.none { it.isLetterOrDigit() }) return null
    return if (t.length > MEMO_MAX) t.take(MEMO_MAX).trimEnd() else t
}

// ─── the memo, read a second time ────────────────────────────────────────────
//
// The page is turned black and white by one threshold, picked for the whole
// slip, and a memo is the one line on it that no rule can check. On a real
// Krungthai slip that threshold left the loopless ท of ทดสอบ101 looking like
// เท, and the note said เทดสอบ101. The same line cut out of the picture and
// thresholded on its own came back ทดสอบ101.
//
// One slip is not a reason, so before this was settled 400 were made: the
// Krungthai slip and a K PLUS one with forty memos typed into them — ค่าข้าว,
// ทรายแมว, ค่าน้ำค่าไฟ, Shopee — in five loopless fonts, and read the way the
// phone reads them. The page alone got 281 of the memos exactly right; with the
// line read a second time, 293. Not on every slip: the K PLUS ones went from
// 123 to 138 and the Krungthai ones from 158 to 155, in fonts only a little
// like Krungthai's own — which, on the real slip, went the other way. Neither
// reading is right every time the other is wrong, so the second wins unless it
// is clearly the worse one.

/** One reading of a memo, and how sure the recogniser said it was, 0 to 100. */
data class MemoRead(val text: String, val confidence: Float)

/**
 * How much surer the page has to be before its memo beats the second reading.
 *
 * The recogniser's own confidence was barely a signal: on the 400, readings it
 * got wrong were on average three points less sure than readings it got right.
 * Ten points is past that noise. Margins from five to twenty all came within
 * two memos of each other.
 */
private const val MEMO_SURER = 10f

/**
 * The memo to keep, out of the page's reading of it and a reading of its line
 * on its own.
 *
 * The second reading is passed over when it is empty, when it is a third
 * longer or shorter than the first — a label or a neighbouring line got into
 * the cut — or when the page was much surer of its own.
 */
fun betterMemo(page: MemoRead, again: MemoRead?): String? {
    val first = memoOnLine(page.text) ?: return null
    val second = again?.let { memoOnLine(it.text) } ?: return first
    val a = memoKey(first)
    val b = memoKey(second)
    if (a == b) return first
    if (abs(a.length - b.length) > maxOf(2, a.length / 3)) return first
    if (page.confidence > again.confidence + MEMO_SURER) return first
    return second
}

/**
 * What follows a memo label on [line], or the whole line when it has none.
 *
 * A line read again on its own is cut from where its first word starts, with
 * a little room around it, and on K PLUS that room reaches back into the
 * label: the จำ: in front of the memo came back as `]:` and `1:`. So whatever
 * is left of a label — a character or two and a colon — goes too, and so does
 * anything in front that is not a letter or a digit.
 */
fun memoOnLine(line: String): String? {
    var text = normalize(line).replace('\n', ' ').trim()
    val hit = matches(TYPED, fold(text)).firstOrNull()
    if (hit != null && hit.first <= 1) text = text.substring(hit.last + 1)
    text = LABEL_END.replace(text.trim(), "").trimStart { !it.isLetterOrDigit() }
    return typed(text)
}

/** The end of a label caught at the start of a line: `]:`, `1:`, `ำ:`, a bare colon. */
private val LABEL_END = Regex("""^[^\s:;]{0,2}\s*[:;]\s*""")

/** A memo the way two readings of it are compared: marks in order, no spaces, no colon in front. */
fun memoKey(text: String): String =
    normalize(text).filterNot { it.isWhitespace() }.trimStart(':', '-', '.', '\u00B7')

/**
 * Where on a line the memo starts: the index of its first word, or -1.
 *
 * [words] are one line's words as the recogniser split them, which for Thai is
 * anywhere at all — `เท ด ส อ บ 101`. The memo is everything after its label,
 * so it is the line's last few words; this is the fewest of them that still
 * spell it, which leaves out a colon the recogniser made a word of its own —
 * the cut starts at the memo, not at its label. Under Krungthai's layout the
 * memo is the whole line.
 */
fun memoStart(words: List<String>, memo: String): Int {
    val want = memoKey(memo)
    if (want.isEmpty()) return -1
    var start = -1
    for (k in words.indices) {
        val tail = memoKey(words.subList(k, words.size).joinToString(""))
        if (tail == want) start = k
        if (tail.length < want.length) break
    }
    return start
}

/**
 * Where to cut a memo's line out of the picture: left, top, right, bottom.
 *
 * [words] are the boxes of the memo's words, each left, top, right, bottom.
 * [before] is the box of the word in front of the memo on the same line, and
 * [beforeText] what it says — the label, or its colon — both null when the
 * memo has the line to itself.
 *
 * The recogniser's boxes for Thai letters are loose about where a letter
 * starts. On a K PLUS test slip it put กาแฟ's ก five pixels wide when it is
 * fifteen, and a cut made at the box read the half that was left as า; on the
 * real K PLUS slip the colon's box ran fifteen pixels into อาหาร. So the cut
 * reaches a whole line's height to either side, but not into a word in front:
 * a colon is let in whole, since what is left of a label at the start of the
 * second reading is taken off by [memoOnLine]; anything longer stops the cut at
 * its edge.
 */
fun memoCut(words: List<IntArray>, before: IntArray?, beforeText: String?, width: Int, height: Int): IntArray {
    val l = words.minOf { it[0] }
    val t = words.minOf { it[1] }
    val r = words.maxOf { it[2] }
    val b = words.maxOf { it[3] }
    val h = b - t
    val padX = maxOf(8, h)
    val padY = maxOf(4, h * 3 / 10)
    var x0 = maxOf(0, l - padX)
    if (before != null) {
        val mark = beforeText != null && beforeText.none { it.isLetterOrDigit() }
        x0 = maxOf(x0, minOf(if (mark) before[0] else before[2], l))
    }
    return intArrayOf(x0, maxOf(0, t - padY), minOf(width, r + padX), minOf(height, b + padY))
}

// ─── the time of day ─────────────────────────────────────────────────────────

private val CLOCK = Regex("""(\d{1,2})([:.])(\d{2})(?::(\d{2}))?""")

/**
 * The times of day printed on a slip, as minutes after midnight.
 *
 * Not one of the fields. A slip's time has no box on the form, and nothing here
 * is decided by it. It exists for a caller that has a second clock to hold it
 * against: a slip that arrived in a bank's album, whose file says when it was
 * saved. When the slip says 20:38 and the file says 20:38, the file's date is
 * the slip's date even when the recogniser made nothing of the date itself —
 * K PLUS's 11 came back as `Ti` on the first one tried.
 *
 * 20:38 and 20:38:05 anywhere. 20.38 only right before น or after เวลา, because
 * 20.38 on its own is as likely to be twenty baht. 8:05 PM is 20:05.
 */
internal fun clocksOn(raw: String): List<Int> {
    val out = mutableListOf<Int>()
    for (line in normalize(raw).split('\n')) {
        for (m in CLOCK.findAll(line)) {
            val before = line.getOrNull(m.range.first - 1)
            val after = line.getOrNull(m.range.last + 1)
            if (before != null && (before in '0'..'9' || before == '.' || before == ',' || before == ':')) continue
            if (after != null && after in '0'..'9') continue
            val rest = line.substring(m.range.last + 1).trimStart().lowercase()
            if (m.groupValues[2] == ".") {
                val head = line.substring(0, m.range.first).lowercase()
                val named = rest.startsWith("น") || head.trimEnd().endsWith("เวลา") ||
                    head.trimEnd().endsWith("time")
                if (!named) continue
            }
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[3].toInt()
            if (minute > 59) continue
            val pm = rest.startsWith("pm") || rest.startsWith("p.m")
            val am = rest.startsWith("am") || rest.startsWith("a.m")
            if (pm || am) {
                if (hour !in 1..12) continue
                hour = hour % 12 + if (pm) 12 else 0
            }
            if (hour > 23) continue
            out += hour * 60 + minute
        }
    }
    return out
}
