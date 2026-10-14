// shared/src/commonMain/kotlin/app/reup/sync/BankNotice.kt
//
// Money coming in, read off the notification a bank's app posts.
//
// ─── WHY NOTIFICATIONS ───────────────────────────────────────────────────────
//
// A bank's app saves a slip for money going out, which is what SlipInbox
// reads, and nothing at all for money coming in. What it does post, both ways,
// is a notification: K PLUS says รายการเงินเข้า with the account, the amount
// and the minute, and Krungthai NEXT says รับเงินสำเร็จ with the amount and
// the account the money came from. Reading those needs no bank to agree to
// anything and no login - only the person's say-so, given once in the phone's
// own settings.
//
// ─── WHAT IS READ, AND WHAT IS KEPT ──────────────────────────────────────────
//
// Two apps, by package, and from those only the titles below. The banks' LINE
// accounts post the same news and are not read: LINE is a chat app, and the
// phone side asks Android never to send it conversations at all. A notice
// becomes an amount, a minute, the banks on either side and the masked account
// numbers it printed. No names - neither notice prints one - and none of the
// text.
//
// ─── MONEY THAT IS ONLY MOVING ───────────────────────────────────────────────
//
// Plenty of what arrives in one of somebody's accounts left another of their
// accounts a moment before. That is not income, and a ledger that counted it
// would count the same baht twice. Three things here say so, surest first:
//
//  1. Krungthai prints the account the money came from, masked. When it is
//     one of the person's own - K PLUS prints those on every notice - the
//     money only moved.
//  2. A slip whose payee was its payer (paidToSelf), for the same amount, a
//     few minutes either side.
//  3. A notice of money leaving another account, for the same amount, a few
//     minutes either side. Only a "maybe": that notice does not say where the
//     money went.
//
// The first two hide the arrival. The third leaves it in the list without its
// one-press save, with a line saying why.

package app.reup.sync

import app.reup.core.THAI_MONTHS_SHORT
import app.reup.core.moneyDayTitle
import app.reup.core.moneyText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlin.math.abs
import kotlin.math.round

// ─── the banks ───────────────────────────────────────────────────────────────

/** Which way money went. */
enum class Flow { IN, OUT }

/**
 * The apps whose notifications are read, by package, and the bank each one is.
 * A notification from any other package is not looked at, not even its title.
 */
val BANK_APPS: Map<String, String> = mapOf(
    "com.kasikorn.retail.mbanking.wap" to "kbank",
    "ktbcs.netbank" to "ktb",
)

/** The app's name as the phone shows it: "K PLUS". */
fun bankAppName(bank: String): String = when (bank) {
    "kbank" -> "K PLUS"
    "ktb" -> "Krungthai NEXT"
    else -> bank
}

/** The bank's own name, as a Thai slip prints it: "กสิกรไทย". */
fun bankName(bank: String): String = BANK_NAMES[bank] ?: bank

private val BANK_NAMES = mapOf(
    "kbank" to "กสิกรไทย",
    "ktb" to "กรุงไทย",
    "scb" to "ไทยพาณิชย์",
    "bbl" to "กรุงเทพ",
    "bay" to "กรุงศรี",
    "ttb" to "ทีทีบี",
    "gsb" to "ออมสิน",
)

/**
 * Words that name a bank, as a notice prints it or an album is called. The
 * first that appears is the answer.
 */
private val BANK_WORDS: List<Pair<String, String>> = listOf(
    "กสิกร" to "kbank", "kasikorn" to "kbank", "kbank" to "kbank", "kplus" to "kbank", "k+" to "kbank",
    "กรุงไทย" to "ktb", "krungthai" to "ktb", "ktb" to "ktb",
    "ไทยพาณิชย์" to "scb", "scb" to "scb",
    "กรุงเทพ" to "bbl", "bangkokbank" to "bbl", "bualuang" to "bbl",
    "กรุงศรี" to "bay", "krungsri" to "bay",
    "ทหารไทย" to "ttb", "ทีทีบี" to "ttb", "ttb" to "ttb",
    "ออมสิน" to "gsb", "mymo" to "gsb",
    // Last, being an ordinary word: an album called NEXT is Krungthai's.
    "next" to "ktb",
)

/**
 * The bank a printed name or an album's name means - "กสิกรไทย",
 * "ธ.กรุงไทย", "Krungthai NEXT", "K PLUS" - or null.
 */
fun bankKey(name: String?): String? {
    val n = name?.lowercase()?.filterNot { it.isWhitespace() || it == '_' || it == '-' || it == '.' } ?: return null
    if (n.isEmpty()) return null
    return BANK_WORDS.firstOrNull { (word, _) -> n.contains(word) }?.second
}

// ─── account numbers, masked ─────────────────────────────────────────────────

/**
 * Whether two masked account numbers can be the same account: the same
 * length, grouped the same way, and every digit both of them show the same -
 * at least three of them.
 *
 * Each bank hides a different part. K PLUS shows the four digits before the
 * last, xxx-x-x4410-x; Krungthai, printing where money came from, shows the
 * last four, XXX-X-XX410-7. Three digits overlap. Fewer than three agreeing
 * cannot tell one person's account from a stranger's.
 */
fun sameAccount(a: String?, b: String?): Boolean {
    val x = maskOf(a) ?: return false
    val y = maskOf(b) ?: return false
    if (x.digits.length != y.digits.length) return false
    if (x.groups != null && y.groups != null && x.groups != y.groups) return false
    var shared = 0
    for (i in x.digits.indices) {
        val p = x.digits[i]
        val q = y.digits[i]
        if (p == 'X' || q == 'X') continue
        if (p != q) return false
        shared++
    }
    return shared >= 3
}

/** A mask as compared: its digits with every hidden one as X, and its groups' lengths when it has groups. */
private class Mask(val digits: String, val groups: List<Int>?)

private fun maskOf(s: String?): Mask? {
    val t = s?.trim()?.map { c -> if (c == 'x' || c == '*' || c == '•') 'X' else c }?.joinToString("") ?: return null
    if (t.isEmpty() || t.any { it !in '0'..'9' && it != 'X' && it != '-' && it != ' ' }) return null
    val groups = t.split('-', ' ').filter { it.isNotEmpty() }
    val digits = groups.joinToString("")
    if (digits.isEmpty()) return null
    return Mask(digits, if (groups.size > 1) groups.map { it.length } else null)
}

/** A run that can be a masked account: something hidden in it and something shown. */
private fun masked(s: String): Boolean =
    s.any { it == 'x' || it == 'X' || it == '*' } && s.any { it in '0'..'9' }

// ─── reading a notice ────────────────────────────────────────────────────────

/** One notice from a bank's app, as far as anything here needs it. */
data class BankNotice(
    /** Which bank's app posted it: one of [BANK_APPS]'s values. */
    val bank: String,
    val flow: Flow,
    /** Baht, and always more than nothing. */
    val amount: Double,
    /** The person's own account it is about, masked as printed; null when it does not say. */
    val account: String?,
    /** The bank the money came from, when the notice names one the rules know. */
    val fromBank: String?,
    /** The account it came from, masked as printed. */
    val fromAccount: String?,
    /** The notice's own date and minute when it prints them; when it was posted when not. */
    val at: LocalDateTime,
)

private const val MASK = "[xX*0-9][xX*0-9-]{4,}[xX*0-9]"
private const val AMOUNT = "[0-9][0-9,]*(?:\\.[0-9]{1,2})?"

/**
 * K PLUS: "บัญชี xxx-x-x4410-x จำนวนเงิน 1.00 บาท วันที่ 11 ต.ค. 69 20:39 น.",
 * the same words under รายการเงินเข้า and รายการโอน/ถอน.
 */
private val KPLUS_BODY = Regex(
    "บัญชี[^0-9xX*]{0,30}?($MASK)\\s*จำนวนเงิน\\s*:?\\s*($AMOUNT)\\s*บาท" +
        "(?:\\s*วันที่\\s*([0-9]{1,2})\\s*([^\\s0-9]+)\\s*([0-9]{2,4})\\s*(?:เวลา\\s*)?([0-9]{1,2})[:.]([0-9]{2}))?",
)

/** Krungthai NEXT: "ได้รับ +1.00 บาท เข้าพร้อมเพย์ จากบัญชี กสิกรไทย XXX-X-XX410-7". */
private val KTB_AMOUNT = Regex("ได้รับ\\s*\\+?\\s*($AMOUNT)\\s*บาท")
private val KTB_FROM = Regex("จาก(?:บัญชี)?\\s*([^0-9xX*]{0,40}?)\\s*($MASK)")

/**
 * A bank app's notification as a [BankNotice], or null when it is not one of
 * the kinds this reads: another app, another title, a body in a shape the
 * rules have not seen.
 *
 * @param app the package that posted it
 * @param postedAt when it was posted, on the phone's clock and in its zone;
 *        the time of a notice that prints none, and the check on one that does
 */
fun bankNotice(app: String, title: String?, text: String?, postedAt: LocalDateTime): BankNotice? {
    val bank = BANK_APPS[app] ?: return null
    val head = flat(title)
    val body = flat(text)
    if (body.isEmpty()) return null
    return when (bank) {
        "kbank" -> kplus(head, body, postedAt)
        "ktb" -> krungthai(head, body, postedAt)
        else -> null
    }
}

private fun kplus(title: String, body: String, postedAt: LocalDateTime): BankNotice? {
    val flow = flowOf(title) ?: return null
    val m = KPLUS_BODY.find(body) ?: return null
    val account = m.groupValues[1].takeIf { masked(it) }
    val amount = amountOf(m.groupValues[2]) ?: return null
    val printed = printedAt(m.groupValues, postedAt)
    return BankNotice("kbank", flow, amount, account, null, null, printed ?: postedAt)
}

private fun krungthai(title: String, body: String, postedAt: LocalDateTime): BankNotice? {
    // Money in only. Krungthai's own app was not seen to post anything for
    // money going out; its LINE account does, and LINE is not read.
    if (flowOf(title) != Flow.IN) return null
    val amount = KTB_AMOUNT.find(body)?.let { amountOf(it.groupValues[1]) } ?: return null
    val from = KTB_FROM.find(body)
    val fromAccount = from?.groupValues?.get(2)?.takeIf { masked(it) }
    val fromBank = if (from != null && fromAccount != null) bankKey(from.groupValues[1]) else null
    return BankNotice("ktb", Flow.IN, amount, null, fromBank, fromAccount, postedAt)
}

/**
 * Whether a notification says money came in, by its app and its title alone:
 * true for one whose body the rules then could not read, which is how a bank
 * changing its wording is noticed rather than missed in silence.
 */
fun looksLikeMoneyIn(app: String, title: String?): Boolean =
    app in BANK_APPS && flowOf(flat(title)) == Flow.IN

/**
 * Which way a title says the money went, or null for a title this does not
 * read. Money in is decided only by the words for it, so that a title nobody
 * has seen yet can never turn spending into income.
 */
private fun flowOf(title: String): Flow? = when {
    "เงินเข้า" in title || "รับเงิน" in title || "รับโอน" in title -> Flow.IN
    "เข้า" in title || "รับ" in title -> null
    "โอน" in title || "ถอน" in title || "ชำระ" in title || "จ่าย" in title -> Flow.OUT
    else -> null
}

/**
 * The date and minute K PLUS printed, or null when it printed none, or one
 * further than a day from when the notice was posted - a date read wrong is
 * worse than the post's own.
 */
private fun printedAt(g: List<String>, postedAt: LocalDateTime): LocalDateTime? {
    if (g[3].isEmpty()) return null
    val day = g[3].toIntOrNull() ?: return null
    val token = g[4].replace(".", "")
    val month = THAI_MONTHS_SHORT.indexOfFirst { it.replace(".", "") == token } + 1
    if (month == 0) return null
    val year = g[5].toIntOrNull()?.let { ceYear(it, g[5].length) } ?: return null
    val hour = g[6].toIntOrNull() ?: return null
    val minute = g[7].toIntOrNull() ?: return null
    val at = runCatching { LocalDateTime(year, month, day, hour, minute) }.getOrNull() ?: return null
    return if (abs(postedAt.date.daysUntil(at.date)) <= 1) at else null
}

/** A Thai year as printed: "69" and "2569" are 2026, and a year already in the Common Era stays. */
private fun ceYear(printed: Int, digits: Int): Int = when {
    digits <= 2 -> printed + 2500 - 543
    printed > 2400 -> printed - 543
    else -> printed
}

private fun amountOf(s: String): Double? =
    s.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** Spaces of every kind as one, and the invisible ones gone, so a pattern sees words. */
private fun flat(s: String?): String =
    s.orEmpty().replace(INVISIBLE, "").replace(SPACES, " ").trim()

private val INVISIBLE = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")
private val SPACES = Regex("[\\s\\u00A0\\u2007\\u202F]+")

/**
 * What makes two notices the same one, posted again when the phone reconnects
 * to the listener or updated in the shade: the bank, the way, the amount, the
 * accounts and the time the notice is about.
 */
fun noticeKey(n: BankNotice): String = listOf(
    n.bank,
    n.flow.name,
    round(n.amount * 100).toLong().toString(),
    n.account.orEmpty().uppercase(),
    n.fromBank.orEmpty(),
    n.fromAccount.orEmpty().uppercase(),
    iso(n.at.date) + "T" + pad(n.at.hour) + ":" + pad(n.at.minute) + ":" + pad(n.at.second),
).joinToString("|")

/**
 * [noticeKey] as an id that can be kept without the account numbers in it:
 * "n" and a 64-bit FNV-1a of the key, in hex.
 */
fun noticeId(n: BankNotice): String {
    var h = -0x340d631b7bdddcdbL
    for (c in noticeKey(n)) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return "n" + h.toULong().toString(16)
}

// ─── money in, money out, and which is which ─────────────────────────────────

/** Money that left one of the person's accounts: a bank's notice of it, or a slip. */
data class Movement(
    val id: String,
    /** Epoch milliseconds: when the notice was posted, or when the slip was saved. */
    val at: Long,
    val amount: Double,
    /** The bank it left, when known. */
    val bank: String?,
    /** The account it left, masked, when known. */
    val account: String?,
    /** A slip whose payee was its payer. See [paidToSelf]. */
    val toSelf: Boolean,
)

/** Money that arrived, as the waiting list keeps it. */
data class Arrival(
    val id: String,
    /** Epoch milliseconds, when the notice was posted: what a [Movement] is compared with. */
    val at: Long,
    /** `YYYY-MM-DD` and `HH:MM`, the notice's own when it printed them: what the row says and saves. */
    val date: String,
    val clock: String,
    val amount: Double,
    val bank: String,
    val account: String?,
    val fromBank: String?,
    val fromAccount: String?,
)

/** What the rules make of an arrival. */
sealed interface Verdict {
    /** Money from somebody else, as far as anything here can tell. One press may save it. */
    data object Income : Verdict

    /** Money from one of the person's own accounts. Not income, and not shown. */
    data object Own : Verdict

    /** Money that may be the person's own: [movement] left another account at the same moment. */
    data class Maybe(val movement: String) : Verdict
}

/** Minutes either side of a slip to oneself that an arrival of the same amount is that slip's money. */
const val SELF_SLIP_MINUTES = 15

/** Minutes either side of money leaving another account that an arrival of the same amount may be it. */
const val NEAR_MINUTES = 10

fun arrivalOf(n: BankNotice, postedAt: Long): Arrival = Arrival(
    id = noticeId(n),
    at = postedAt,
    date = iso(n.at.date),
    clock = pad(n.at.hour) + ":" + pad(n.at.minute),
    amount = n.amount,
    bank = n.bank,
    account = n.account,
    fromBank = n.fromBank,
    fromAccount = n.fromAccount,
)

fun movementOf(n: BankNotice, postedAt: Long): Movement =
    Movement(noticeId(n), postedAt, n.amount, n.bank, n.account, toSelf = false)

/**
 * A slip that arrived on its own, as money leaving, or null when it cannot be
 * compared with a notice: no amount, or not baht. The bank is the album's,
 * which the bank's own app named.
 */
fun slipMovement(id: Long, savedAt: Long, album: String, d: SlipDraft): Movement? {
    val amount = d.amount?.takeIf { it > 0.0 } ?: return null
    if (d.currency != BAHT) return null
    return Movement("s$id", savedAt, amount, bankKey(album), null, d.toSelf)
}

/**
 * Which arrivals are income, which are the person's own money moving, and
 * which might be.
 *
 * Every arrival is judged, saved and thrown away ones included, so that a
 * movement that explained one of those cannot be used again to explain
 * another. Each movement explains one arrival at most, nearest in time first;
 * two transfers of the same amount a minute apart are two pairs, not one slip
 * hiding both.
 *
 * @param own the person's accounts by bank, masked, as K PLUS printed them
 */
fun judgeArrivals(
    arrivals: List<Arrival>,
    movements: List<Movement>,
    own: Map<String, List<String>>,
): Map<String, Verdict> {
    val fromOwn = arrivals.filter { a ->
        val bank = a.fromBank ?: return@filter false
        own[bank].orEmpty().any { sameAccount(it, a.fromAccount) }
    }.map { it.id }.toSet()

    class Link(val a: Arrival, val m: Movement, val gap: Long)
    val links = mutableListOf<Link>()
    for (a in arrivals) {
        for (m in movements) {
            if (abs(a.amount - m.amount) >= 0.005) continue
            val gap = abs(a.at - m.at)
            val window = (if (m.toSelf) SELF_SLIP_MINUTES else NEAR_MINUTES) * 60_000L
            if (gap > window || !couldFeed(m, a)) continue
            links += Link(a, m, gap)
        }
    }
    links.sortWith(compareBy<Link>({ it.gap }, { it.a.at }, { it.a.id }, { it.m.id }))
    val matched = HashMap<String, Movement>()
    val used = HashSet<String>()
    for (l in links) {
        if (l.a.id in matched || l.m.id in used) continue
        matched[l.a.id] = l.m
        used += l.m.id
    }

    return arrivals.associate { a ->
        val m = matched[a.id]
        a.id to when {
            a.id in fromOwn -> Verdict.Own
            m == null -> Verdict.Income
            m.toSelf -> Verdict.Own
            else -> Verdict.Maybe(m.id)
        }
    }
}

/**
 * Whether money that left in [m] can be what arrived in [a]. Between banks,
 * always. Within one bank, only between two accounts both known and not the
 * same: money that leaves an account and comes back to it is two things that
 * happened, not one.
 */
private fun couldFeed(m: Movement, a: Arrival): Boolean {
    val bank = m.bank ?: return true
    if (bank != a.bank) return true
    val from = m.account ?: return false
    val into = a.account ?: return false
    return !sameAccount(from, into)
}

// ─── the words ───────────────────────────────────────────────────────────────

private const val BAHT = "THB"

/** Why an arrival should be looked at before it is saved, or null when one press may save it. */
fun arrivalCheck(v: Verdict): String? = when (v) {
    is Verdict.Maybe -> "อาจเป็นเงินโอนจากบัญชีตัวเอง เช็คก่อนบันทึก"
    else -> null
}

/** Where it arrived, and from which bank when the notice said: "Krungthai NEXT จากกสิกรไทย". */
private fun whereOf(a: Arrival): String =
    bankAppName(a.bank) + (a.fromBank?.let { " จาก" + bankName(it) } ?: "")

/**
 * The two lines of a waiting arrival's row in the money tab: "เงินเข้า · +฿1",
 * then where and when - or, when it may be the person's own money, that.
 */
fun arrivalFace(a: Arrival, v: Verdict, today: LocalDate): SlipFace {
    val day = moneyDayTitle(a.date, today).substringBefore(" · ")
    val text = arrivalCheck(v) ?: (whereOf(a) + " · " + day + " " + a.clock)
    return SlipFace("เงินเข้า · " + moneyText(a.amount, BAHT, 1), text)
}

/**
 * What an arrival is saved with as its note: which bank it went into, and
 * from which bank when the notice printed one. Not a name - neither notice
 * prints the payer's - so ได้รับจาก is left for a person to fill in.
 */
fun arrivalNote(a: Arrival): String =
    "เข้า" + bankName(a.bank) + (a.fromBank?.let { " จาก" + bankName(it) } ?: "")

/** The row one press writes, through the same validation as the form. */
fun arrivalIncome(a: Arrival): IncomeDraft = IncomeDraft(
    amount = plain(a.amount),
    source = "",
    note = arrivalNote(a),
    date = a.date,
    currency = BAHT,
)

/**
 * The last arrival as the settings screen says it, after "ล่าสุด":
 * "วันนี้ 20:39 · K PLUS +฿1", and what became of it when it was not shown.
 */
fun arrivalLine(a: Arrival, v: Verdict, today: LocalDate): String {
    val day = moneyDayTitle(a.date, today).substringBefore(" · ")
    val head = day + " " + a.clock + " · " + bankAppName(a.bank) + " " + moneyText(a.amount, BAHT, 1)
    return when (v) {
        Verdict.Own -> "$head · โอนจากบัญชีตัวเอง ไม่นับ"
        is Verdict.Maybe -> "$head · รอเช็ค"
        Verdict.Income -> head
    }
}

/**
 * The settings line for money in that could not be read, after "ล่าสุด":
 * "วันนี้ 20:39 · K PLUS · มีเงินเข้าแต่อ่านยอดไม่ออก". Which bank and when are
 * all that is kept of it.
 */
fun missedLine(bank: String, at: LocalDateTime, today: LocalDate): String =
    moneyDayTitle(iso(at.date), today).substringBefore(" · ") + " " + pad(at.hour) + ":" + pad(at.minute) +
        " · " + bankAppName(bank) + " · มีเงินเข้าแต่อ่านยอดไม่ออก"

private fun plain(a: Double): String = if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString()

private fun pad(n: Int): String = if (n < 10) "0$n" else n.toString()

private fun iso(d: LocalDate): String =
    d.year.toString().padStart(4, '0') + "-" + pad(d.monthNumber) + "-" + pad(d.dayOfMonth)
