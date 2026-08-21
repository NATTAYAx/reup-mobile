package app.reup.sync

// ─── MoneyRepo.kt — recording spending from the phone ────────────────────────
//
// Mirror of src/lib/expenseDraft.ts, plus the two reads and one write a screen
// needs around it.
//
// Separate from TaskRepo rather than added to it, because the two answer
// different questions and only one of them is on the path that sets alarms. A
// receiver waking up at four in the morning to schedule notifications has no
// business loading the code that knows what a category is.
//
// WHY THE COERCIONS ARE HERE AND NOT IN THE SCREEN
//
// Same reason as tasks. A task written slightly wrong rings at the wrong time
// and somebody notices. An amount written slightly wrong is a number inside a
// total, and a total is the kind of thing nobody audits until the month it
// matters. So the six values come from one function on both sides, against the
// `expenseDraft` section of store-vectors.json.

/** The columns a new expense row is written with, in the order the values come. */
val EXPENSE_COLUMNS: List<String> = listOf(
    "amount",
    "currency",
    "category",
    "note",
    "date",
    "slip_ref",
)

/**
 * A draft as a phone screen hands it over: everything a string, nothing coerced.
 *
 * `currency` is not optional and has no default here. The desktop's form fills
 * it from the setting in force, which is right in a form where the symbol is
 * next to the box being typed in, and wrong in a shared function — a caller
 * that forgot would file spending in whatever unit a machine happened to be set
 * to, and a number without its unit is not an amount.
 */
data class ExpenseDraft(
    val amount: String? = null,
    val currency: String? = null,
    val category: String? = null,
    val note: String? = null,
    val date: String? = null,
    val slipRef: String? = null,
)

private val YMD = Regex("""^\d{4}-\d{2}-\d{2}$""")

/**
 * Where money goes when nobody said which category.
 *
 * The schema has it as the column default, the desktop falls back to it, and so
 * does this. Three places, one string, pinned in the vectors.
 */
const val CATEGORY_FALLBACK: String = "other"

/**
 * The unit to count in when nothing has said otherwise.
 *
 * Two devices disagreeing about what "no setting yet" means is not a crash: it
 * is one machine filing a month in baht and the other filing the same month in
 * something else, and every total on both sides quietly leaving half of it out.
 *
 * Pinned for that reason. It is one of the few strings in this project where
 * two copies could disagree without anything failing.
 */
const val CURRENCY_FALLBACK: String = "THB"

/**
 * A category key, or `other`.
 *
 * Filing rather than refusing, which is what the desktop has always done. A
 * category renamed or hidden on one device should not stop a number being
 * recorded on the other; the money is the part that matters and the label can
 * be fixed afterwards.
 */
fun resolveCategory(key: String?, known: List<String>): String {
    if (key.isNullOrEmpty()) return CATEGORY_FALLBACK
    return if (known.contains(key)) key else CATEGORY_FALLBACK
}

private fun amountOf(v: String?): Double? {
    if (v.isNullOrEmpty()) return null
    return v.trim().toDoubleOrNull()
}

/** The six values, in [EXPENSE_COLUMNS] order. */
fun expenseValues(d: ExpenseDraft, known: List<String>): List<SyncValue> = listOf(
    SyncValue.Num(amountOf(d.amount) ?: 0.0),
    SyncValue.Text(d.currency ?: ""),
    SyncValue.Text(resolveCategory(d.category, known)),
    SyncValue.Text(d.note ?: ""),
    SyncValue.Text(d.date ?: ""),
    // Null rather than an empty string, and that is load bearing: the unique
    // index on this column tolerates any number of nulls and exactly one of
    // each string. Manual entries writing "" would collide with each other from
    // the second one onwards.
    if (d.slipRef.isNullOrEmpty()) SyncValue.Null else SyncValue.Text(d.slipRef),
)

/**
 * Is this a number, and is it more than nothing.
 *
 * Zero is refused as well as negative. A zero-baht row changes no total and
 * takes up a line in every month view for ever, which is a mis-tap rather than
 * a thing anyone meant to record.
 */
private fun amountProblems(v: String?): List<String> {
    if (v.isNullOrEmpty()) return listOf("amount-missing")
    val amount = amountOf(v) ?: return listOf("amount-not-a-number")
    if (amount <= 0.0) return listOf("amount-not-positive")
    return emptyList()
}

/** What unit, and which day. The two questions neither side may skip. */
private fun unitAndDateProblems(currency: String?, date: String?): List<String> {
    val out = mutableListOf<String>()
    if (currency.isNullOrEmpty()) out.add("currency-missing")
    if (date.isNullOrEmpty()) out.add("date-missing")
    else if (!YMD.matches(date)) out.add("date-malformed")
    return out
}

fun expenseProblems(d: ExpenseDraft, known: List<String>): List<String> {
    // Not a problem, deliberately: an unknown category is filed under `other`
    // rather than refused. See resolveCategory.
    @Suppress("UNUSED_EXPRESSION")
    known
    return amountProblems(d.amount) + unitAndDateProblems(d.currency, d.date)
}

// ─── money coming in ─────────────────────────────────────────────────────────

/** The columns a new income row is written with, in the order the values come. */
val INCOME_COLUMNS: List<String> = listOf("amount", "source", "note", "date", "currency")

/**
 * A payment as a phone screen hands it over.
 *
 * [source] is free text, not a list and not a table. What goes in it is "TELUS"
 * or "3Play" — the name on the actual payment, which is the thing worth being
 * able to find again. Five tidy buckets would file every one of those under
 * "freelance" and lose the only detail that mattered.
 */
data class IncomeDraft(
    val amount: String? = null,
    val source: String? = null,
    val note: String? = null,
    val date: String? = null,
    val currency: String? = null,
)

/** The five values, in [INCOME_COLUMNS] order. */
fun incomeValues(d: IncomeDraft): List<SyncValue> = listOf(
    SyncValue.Num(amountOf(d.amount) ?: 0.0),
    // Blank stays blank rather than becoming "other". That is what the desktop
    // has always written, and the column's default only applies to a statement
    // that leaves it out, which neither side does.
    SyncValue.Text(d.source ?: ""),
    SyncValue.Text(d.note ?: ""),
    SyncValue.Text(d.date ?: ""),
    SyncValue.Text(d.currency ?: ""),
)

/**
 * The same three questions the expense side asks, answered with the same codes,
 * so a screen that can say them once can say them for both.
 *
 * A blank source is not among them. It is allowed on the desktop and this is
 * not the place to start refusing it — and unlike an amount or a unit, a
 * payment with no name attached is still a true row.
 */
fun incomeProblems(d: IncomeDraft): List<String> =
    amountProblems(d.amount) + unitAndDateProblems(d.currency, d.date)

// ─── reading the month back ──────────────────────────────────────────────────
//
// The phone could record money and then not see any of it again without opening
// the desktop, which makes the number in the app something you have to go and
// look up somewhere else — the exact gap recording on the phone was meant to
// close.
//
// All three statements filter by currency, and that is not an optimisation. A
// total that sums across units is not a wrong number, it is not a number. Which
// is also why the third one exists: filtering in silence is how a screen shows
// a confident, wrong-looking zero.
//
// The strings are pinned against the desktop's in the `moneyQueries` section of
// store-vectors.json. Two of them the desktop already ran and now shares.

/** Spent this month, in one unit. `?` is the currency, then the `YYYY-MM`. */
const val SQL_MONTH_SPENT: String =
    "SELECT COALESCE(SUM(amount), 0) as total FROM expenses " +
            "WHERE deleted = 0 AND currency = ? AND strftime('%Y-%m', date) = ?"

/** Received this month, in one unit. Same parameters, same order. */
const val SQL_MONTH_RECEIVED: String =
    "SELECT COALESCE(SUM(amount), 0) as total FROM income " +
            "WHERE deleted = 0 AND currency = ? AND strftime('%Y-%m', date) = ?"

/**
 * How many rows this month were counted in some OTHER unit.
 *
 * A count rather than a breakdown. The desktop shows the totals per currency
 * because it has the room and the person is sitting down with it; a phone needs
 * one short line that says "there is more, and it is not here". Both answers are
 * honest, only one of them fits.
 */
const val SQL_MONTH_OTHER_COUNT: String =
    "SELECT COUNT(*) as n FROM (" +
            "SELECT currency FROM expenses WHERE deleted = 0 AND currency != ? AND strftime('%Y-%m', date) = ? " +
            "UNION ALL " +
            "SELECT currency FROM income WHERE deleted = 0 AND currency != ? AND strftime('%Y-%m', date) = ?" +
            ")"

/** How the month reads, in one unit, with what was left out counted. */
data class MoneySummary(
    val spent: Double,
    val received: Double,
    /** Rows this month in some other unit. Never silently dropped. */
    val otherRows: Int,
)

/** The categories a screen can offer, in the order the desktop shows them. */
const val CATEGORIES_SQL: String =
    "SELECT key, emoji, label FROM expense_categories " +
            "WHERE deleted = 0 AND is_hidden = 0 ORDER BY sort_order ASC, id ASC"

/** One category as a chip needs it. */
data class Category(val key: String, val emoji: String, val label: String?)

// ─── the last few entries ────────────────────────────────────────────────────
//
// The month line answers "how much", and a total is the one thing a total
// cannot tell you: whether the coffee at eleven went in twice. Two identical
// rows move it by exactly what one row for twice the price would.
//
// This one does NOT filter by unit, and that is the point rather than an
// oversight. A sum across units is not a number, so the three above have to
// filter; a list adds nothing together, every row prints the unit it was
// counted in, so the rows those totals leave out can finally be looked at
// instead of only counted.
//
// The twenty is inside the string. It is a decision, not an argument, and a
// decision in one pinned copy cannot come to mean twenty here and fifty there.
// It also keeps this bindable: SyncValue has no integer, only a float, and
// SQLite is within its rights to refuse a float in a LIMIT.
//
// Pinned in the `moneyQueries` section of store-vectors.json with the rest.

/** The last twenty entries, both directions, every unit. No parameters. */
const val SQL_RECENT_MONEY: String =
    "SELECT kind, uid, date, amount, currency, tag, note FROM (" +
            "SELECT 'out' as kind, uid, date, amount, currency, created_at, category as tag, note " +
            "FROM expenses WHERE deleted = 0 " +
            "UNION ALL " +
            "SELECT 'in' as kind, uid, date, amount, currency, created_at, source as tag, note " +
            "FROM income WHERE deleted = 0" +
            ") ORDER BY date DESC, created_at DESC, uid DESC LIMIT 20"

/**
 * One line of the list.
 *
 * [tag] is the category key going out and the name on the payment coming in —
 * one column because they occupy the same place on screen and neither is ever
 * both. [currency] is per row rather than per screen, for the same reason the
 * statement does not filter by it.
 */
data class MoneyEntry(
    val incoming: Boolean,
    val uid: String,
    val date: String,
    val amount: Double,
    val currency: String,
    val tag: String?,
    val note: String?,
)

// ─── taking one back ─────────────────────────────────────────────────────────
//
// A tombstone rather than a real delete: a row that simply vanishes tells the
// other device nothing, so it comes back on the next sync.
//
// The payload is emptied as well as flagged, and that half is why these are
// pinned. `deleted = 1` on its own would leave the amount and whatever was typed
// in the note sitting in a row that travels to every device and into every
// backup, for a purchase somebody has just said they did not want recorded.
// Which columns get cleared is a decision, and a decision written down twice is
// one that comes to differ.
//
// Keyed by uid, not by id. `id` is an autoincrement and means a different row on
// each machine — the same reason alarms are keyed by uid.
//
// `AND deleted = 0` so that deleting twice is not a second write. The second one
// would only restamp updated_at, which is one more version for the other side to
// receive and agree with itself about.

/** Tombstone an expense, payload and all. `?` is the uid. */
const val SQL_DELETE_EXPENSE: String =
    "UPDATE expenses SET deleted = 1, note = '', amount = 0, slip_ref = NULL " +
            "WHERE uid = ? AND deleted = 0"

/** Tombstone a payment. Same shape, and the name on it goes too. */
const val SQL_DELETE_INCOME: String =
    "UPDATE income SET deleted = 1, source = '', note = '', amount = 0 " +
            "WHERE uid = ? AND deleted = 0"

// ─── editing one ─────────────────────────────────────────────────────────────
//
// Mirror of moneyUpdate in moneyDraft.ts, and it walks the allowlist rather
// than the caller's map for the reason that matters here: the statement built
// from it is compared against the desktop's byte for byte in the vectors, and
// two devices building the same edit in a different order build two different
// strings.
//
// A field the allowlist does not name is dropped rather than refused, because
// what is on the other end is a form. A field it names but the caller left out
// is not touched, which is what makes a two-field edit possible.
//
// No slip_ref. Pointing an existing row at a different receipt is not an edit of
// the row, it is a different claim about where it came from.

/** Which columns an expense edit may touch, in order, and how each is coerced. */
val EXPENSE_EDITABLE: List<Pair<String, String>> = listOf(
    "amount" to "amount",
    "currency" to "text",
    "category" to "text",
    "note" to "text",
    "date" to "text",
)

/** The same for a payment. `source` sits where `category` does. */
val INCOME_EDITABLE: List<Pair<String, String>> = listOf(
    "amount" to "amount",
    "currency" to "text",
    "source" to "text",
    "note" to "text",
    "date" to "text",
)

private fun coerceMoney(how: String, v: Any?): SyncValue =
    if (how == "amount") SyncValue.Num(amountOf(v as? String ?: v?.toString()) ?: 0.0)
    else SyncValue.Text(v as? String ?: "")

/** The columns and values of an edit, in allowlist order. */
fun moneyUpdate(table: String, fields: Map<String, Any?>): Pair<List<String>, List<SyncValue>> {
    val allow = if (table == "income") INCOME_EDITABLE else EXPENSE_EDITABLE
    val columns = mutableListOf<String>()
    val values = mutableListOf<SyncValue>()
    for ((column, how) in allow) {
        if (!fields.containsKey(column)) continue
        columns += column
        values += coerceMoney(how, fields[column])
    }
    return columns to values
}

/**
 * The statement for those columns. The uid is bound last, after the values.
 *
 * `AND deleted = 0` for the same reason the tombstones have it: editing a row
 * that is already gone should write nothing rather than quietly put a version of
 * it back on the other device with the payload restored.
 *
 * An empty column list gives an empty string, which is not a statement and is
 * meant to be checked for. `UPDATE expenses SET WHERE uid = ?` is a syntax error
 * that would surface at the driver rather than here.
 */
fun moneyUpdateSql(table: String, columns: List<String>): String {
    if (columns.isEmpty()) return ""
    return "UPDATE " + table + " SET " + columns.joinToString(", ") { "$it = ?" } +
            " WHERE uid = ? AND deleted = 0"
}

/**
 * Spending, from the phone.
 *
 * No uid and no updated_at: the triggers stamp the row and queue it for the
 * next sync in the same breath, which is why nothing here mentions sync at all.
 */
class MoneyRepo(private val db: Db) {

    /**
     * What the desktop is counting in, or null if this phone has not been told.
     *
     * Reads the setting the desktop writes, which travels because currency is
     * one of the three rows that were promoted into `user_settings`. Before the
     * first sync there is no row, and null is the honest answer to that rather
     * than a guess dressed up as a fact: a number filed in the wrong unit is
     * not visibly wrong anywhere, it is just quietly in the wrong total.
     *
     * The screen decides what to do about null. It has a person in front of it
     * and this does not.
     */
    suspend fun currency(): String? {
        val rows = db.select(userSettingSql(), listOf(SyncValue.Text(UserSettings.CURRENCY)))
        return (rows.firstOrNull()?.get("value") as? SyncValue.Text)?.value?.takeIf { it.isNotEmpty() }
    }

    /**
     * The month so far, in the unit this phone believes it is counting in.
     *
     * [month] is `YYYY-MM`. Nothing here guesses it from a clock: the screen
     * knows which month it is showing and the timezone that answer depends on
     * belongs to the screen, not to a repository.
     */
    suspend fun summary(month: String, currency: String): MoneySummary {
        val args = listOf(SyncValue.Text(currency), SyncValue.Text(month))
        val spent = numberOf(db.select(SQL_MONTH_SPENT, args), "total")
        val received = numberOf(db.select(SQL_MONTH_RECEIVED, args), "total")
        val others = numberOf(db.select(SQL_MONTH_OTHER_COUNT, args + args), "n")
        return MoneySummary(spent, received, others.toInt())
    }

    private fun numberOf(rows: List<DbRow>, column: String): Double =
        (rows.firstOrNull()?.get(column) as? SyncValue.Num)?.value ?: 0.0

    /**
     * The last twenty things written down, newest day first.
     *
     * A row whose `kind` is neither of the two words the statement writes is
     * dropped rather than guessed at: there is no third direction, so a value
     * that is not one of them means the row did not come from this statement,
     * and inventing a direction for it would put a payment in the spending
     * column with no way to tell.
     *
     * A missing uid drops the row for the plainer reason that the screen keys
     * on it. Neither case can happen against a database this app built.
     */
    suspend fun recent(): List<MoneyEntry> =
        db.select(SQL_RECENT_MONEY).mapNotNull { r ->
            val kind = (r["kind"] as? SyncValue.Text)?.value ?: return@mapNotNull null
            if (kind != "out" && kind != "in") return@mapNotNull null
            MoneyEntry(
                incoming = kind == "in",
                uid = (r["uid"] as? SyncValue.Text)?.value ?: return@mapNotNull null,
                date = (r["date"] as? SyncValue.Text)?.value ?: return@mapNotNull null,
                amount = (r["amount"] as? SyncValue.Num)?.value ?: 0.0,
                currency = (r["currency"] as? SyncValue.Text)?.value ?: CURRENCY_FALLBACK,
                tag = (r["tag"] as? SyncValue.Text)?.value,
                note = (r["note"] as? SyncValue.Text)?.value,
            )
        }

    /**
     * Takes one back, in whichever table it came from.
     *
     * The direction decides the statement, and it comes from the entry the
     * screen was showing rather than from a search: an amount and a date are not
     * a key, and looking a row up by them is how the wrong one gets deleted the
     * day two of them match.
     *
     * The triggers stamp `updated_at`, so nothing here reads a clock. A delete
     * that stamped its own time would be a second clock writing into a column
     * that is only ever compared as a string — which is the bug that took a
     * whole round to find on the desktop.
     */
    suspend fun delete(entry: MoneyEntry) {
        db.execute(
            if (entry.incoming) SQL_DELETE_INCOME else SQL_DELETE_EXPENSE,
            listOf(SyncValue.Text(entry.uid)),
        )
    }

    /**
     * Rewrites a row that is already there, in whichever table it came from.
     *
     * Validated with the same functions a new row is, because an edit that can
     * put a row into a state a new row could not reach is a second definition of
     * what a valid row is. The draft carries every editable field, so this is a
     * full rewrite rather than a patch — the screen it comes from has all five
     * boxes filled in and there is nothing partial to express.
     *
     * The triggers stamp `updated_at`, so nothing here reads a clock.
     */
    suspend fun editExpense(uid: String, draft: ExpenseDraft, known: List<String>): List<String> {
        val problems = expenseProblems(draft, known)
        if (problems.isNotEmpty()) return problems
        write(
            "expenses",
            uid,
            mapOf(
                "amount" to draft.amount,
                "currency" to draft.currency,
                "category" to resolveCategory(draft.category, known),
                "note" to draft.note,
                "date" to draft.date,
            ),
        )
        return emptyList()
    }

    /** Money in. Same shape as [editExpense], different table. */
    suspend fun editIncome(uid: String, draft: IncomeDraft): List<String> {
        val problems = incomeProblems(draft)
        if (problems.isNotEmpty()) return problems
        write(
            "income",
            uid,
            mapOf(
                "amount" to draft.amount,
                "currency" to draft.currency,
                "source" to draft.source,
                "note" to draft.note,
                "date" to draft.date,
            ),
        )
        return emptyList()
    }

    private suspend fun write(table: String, uid: String, fields: Map<String, Any?>) {
        val (columns, values) = moneyUpdate(table, fields)
        val sql = moneyUpdateSql(table, columns)
        if (sql.isEmpty()) return
        db.execute(sql, values + SyncValue.Text(uid))
    }

    suspend fun categories(): List<Category> =
        db.select(CATEGORIES_SQL).mapNotNull { r ->
            val key = (r["key"] as? SyncValue.Text)?.value ?: return@mapNotNull null
            Category(
                key = key,
                emoji = (r["emoji"] as? SyncValue.Text)?.value ?: "\uD83D\uDCE6",
                label = (r["label"] as? SyncValue.Text)?.value,
            )
        }

    /**
     * Refuses rather than writes when [expenseProblems] finds something, and
     * returns the codes so a screen can say which.
     *
     * The category is filed rather than checked, so an unknown one is never a
     * reason to lose a number somebody just typed in standing at a counter.
     */
    /** Money in. Same shape as [addExpense], different table. */
    suspend fun addIncome(draft: IncomeDraft): List<String> {
        val problems = incomeProblems(draft)
        if (problems.isNotEmpty()) return problems
        val marks = INCOME_COLUMNS.joinToString(", ") { "?" }
        db.execute(
            "INSERT INTO income (${INCOME_COLUMNS.joinToString(", ")}) VALUES ($marks)",
            incomeValues(draft),
        )
        return emptyList()
    }

    suspend fun addExpense(draft: ExpenseDraft, known: List<String>): List<String> {
        val problems = expenseProblems(draft, known)
        if (problems.isNotEmpty()) return problems
        val marks = EXPENSE_COLUMNS.joinToString(", ") { "?" }
        db.execute(
            "INSERT INTO expenses (${EXPENSE_COLUMNS.joinToString(", ")}) VALUES ($marks)",
            expenseValues(draft, known),
        )
        return emptyList()
    }
}