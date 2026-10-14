package app.reup.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlin.math.abs
import kotlin.math.roundToLong

// ─── MoneyFace.kt — what the money tab says, as functions of what it was given ─
//
// The same reason Face.kt exists. How an amount is printed is a decision with a
// right answer - whether a dollar row shows its cents, whether a baht row shows
// ".00" on every line, which minus sign - and in an activity it can only be
// checked by looking at a phone. Here it has tests.
//
// Common code, so nothing below may reach for String.format: that is the JVM's,
// and iOS would not have it. The grouping and the cents are done by hand.

/** Units whose cents people actually read, so they are always printed. */
private val ALWAYS_CENTS = setOf("USD", "EUR", "GBP", "CAD", "AUD", "NZD", "SGD", "HKD", "CHF")

/** Units with no minor unit in daily use. */
private val NO_CENTS = setOf("JPY", "KRW", "VND", "IDR")

/**
 * The symbol for a unit, or null when there is not one that only means it.
 *
 * Deliberately short. ¥ is both yen and yuan, and "kr" is four currencies; a
 * symbol that could mean two things is worse than the code, which cannot.
 */
fun currencySymbol(code: String): String? = when (code.uppercase()) {
    "THB" -> "฿"
    "USD" -> "$"
    "EUR" -> "€"
    "GBP" -> "£"
    "JPY" -> "¥"
    "KRW" -> "₩"
    else -> null
}

/**
 * An amount as a row prints it: "−฿60", "+$180.00", "฿4,230", "−1,240 MYR".
 *
 * Baht drops ".00" because a column of "฿60.00 ฿40.00 ฿1,240.00" is mostly
 * zeros; the satang are printed when there are some. A dollar keeps its cents
 * always, because $180 and $180.00 read as different kinds of number to anyone
 * who is paid in them.
 *
 * The minus is U+2212 rather than a hyphen. A hyphen is narrower than a plus,
 * so a column of mixed signs does not line up, and that column is the whole
 * point of tabular digits.
 *
 * @param sign -1 for money out, +1 for money in, 0 for a total with no sign
 */
fun moneyText(amount: Double, currency: String, sign: Int = 0): String {
    val code = currency.trim().uppercase()
    if (!amount.isFinite()) return "—"
    val mark = when {
        sign < 0 -> "−"
        sign > 0 -> "+"
        else -> ""
    }
    val number = if (code in NO_CENTS) {
        grouped(abs(amount).roundToLong())
    } else {
        val cents = (abs(amount) * 100).roundToLong()
        val whole = cents / 100
        val frac = cents % 100
        val showCents = code in ALWAYS_CENTS || frac != 0L
        grouped(whole) + if (showCents) "." + frac.toString().padStart(2, '0') else ""
    }
    val symbol = currencySymbol(code)
    return if (symbol != null) mark + symbol + number else "$mark$number $code"
}

private fun grouped(n: Long): String {
    val s = n.toString()
    val out = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return out.toString()
}

/**
 * The nine categories the desktop seeds, in its Thai.
 *
 * Seeded rows carry no label of their own - the desktop translates the key at
 * draw time - so a screen that prints `label ?: key` prints "food". Mirror of
 * resolveLabel in financeDatabase.ts.
 */
private val BUILT_IN_EXPENSE = mapOf(
    "food" to "อาหาร",
    "transport" to "การเดินทาง",
    "entertainment" to "ความบันเทิง",
    "shopping" to "ช้อปปิ้ง",
    "health" to "สุขภาพ",
    "education" to "การศึกษา",
    "bills" to "ค่าบิล",
    "game" to "เกม/กาชา",
    "other" to "อื่นๆ",
)

/** A category as words: the name somebody gave it, or the built-in one, or the key. */
fun expenseLabel(key: String?, stored: String?): String =
    stored?.trim()?.takeIf { it.isNotEmpty() }
        ?: BUILT_IN_EXPENSE[key]
        ?: key?.trim()?.takeIf { it.isNotEmpty() }
        ?: "อื่นๆ"

/** One of the nine the desktop seeds, which have drawn icons rather than an emoji. */
fun isBuiltInExpense(key: String?): Boolean = key != null && key in BUILT_IN_EXPENSE

/** What a money row says: the thing, then what kind of thing it was. */
data class MoneyLine(val title: String, val detail: String?)

/**
 * The two lines of a money row.
 *
 * Whatever was typed is the title, because "กาแฟ" is what the person remembers
 * buying and "อาหาร" is only the drawer it went in. Nothing typed means the
 * drawer is the title and there is no second line to repeat it.
 *
 * @param label the category as words, from [expenseLabel]; unused for money in
 */
fun moneyLine(incoming: Boolean, tag: String?, note: String?, label: String): MoneyLine {
    val memo = note?.trim().orEmpty()
    if (incoming) {
        val from = tag?.trim().orEmpty()
        return when {
            from.isNotEmpty() -> MoneyLine(from, if (memo.isNotEmpty()) "รายรับ · $memo" else "รายรับ")
            memo.isNotEmpty() -> MoneyLine(memo, "รายรับ")
            else -> MoneyLine("รายรับ", null)
        }
    }
    return if (memo.isNotEmpty()) MoneyLine(memo, label) else MoneyLine(label, null)
}

/**
 * A day of money as a heading: "วันนี้", "เมื่อวาน · พุธ 7 ต.ค.", "จันทร์ 5 ต.ค.".
 *
 * The year appears only when it is not this one, which in a list of the last
 * twenty entries is the first week of January.
 *
 * @param date `YYYY-MM-DD` as stored; anything else is printed as it is
 */
fun moneyDayTitle(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date.take(10)) }.getOrNull() ?: return date
    val words = THAI_DAYS[d.dayOfWeek.isoDayNumber % 7] + " " + d.dayOfMonth + " " +
            THAI_MONTHS_SHORT[d.monthNumber - 1] + if (d.year != today.year) " " + d.year else ""
    return when (today.daysUntil(d)) {
        0 -> "วันนี้"
        -1 -> "เมื่อวาน · $words"
        1 -> "พรุ่งนี้ · $words"
        else -> words
    }
}

/**
 * What the amount box holds after a keystroke or a paste, or null to refuse
 * it and keep what was there.
 *
 * Digits and at most one point, so "not a number" is a sentence nobody gets
 * to see. Commas and spaces are taken out rather than refused: a pasted
 * "1,200.00" is twelve hundred, which in every place this app is used is what
 * it means. Twelve digits before the point and four after are more than any
 * slip carries; past that it is a finger resting on a key.
 *
 * A lone "." is let through, because it is how "0.5" starts being typed. The
 * save refuses it if that is still all there is.
 */
fun typedAmount(typed: String): String? {
    val clean = typed.filterNot { it == ',' || it.isWhitespace() }
    return if (AMOUNT_TYPED.matches(clean)) clean else null
}

/** [0-9] rather than \d, which on some platforms is every script's digits. */
private val AMOUNT_TYPED = Regex("""[0-9]{0,12}(\.[0-9]{0,4})?""")

/** "ตุลาคม", for the line above the title. */
fun monthName(monthNumber: Int): String = THAI_MONTHS.getOrElse(monthNumber - 1) { "" }

/**
 * The line under the month's totals when some rows were left out of them.
 *
 * A count rather than a list of units, because that is what the query returns;
 * the rows themselves are in the list below with their units printed.
 */
fun otherUnitsLine(rows: Int): String? =
    if (rows <= 0) null else "อีก $rows รายการเป็นสกุลเงินอื่น ไม่ได้รวมในยอดนี้"
