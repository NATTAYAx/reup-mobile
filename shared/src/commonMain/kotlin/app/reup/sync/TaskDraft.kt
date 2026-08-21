package app.reup.sync

// ─── TaskDraft.kt — what a new task looks like as a row ──────────────────────
//
// Mirror of src/lib/taskDraft.ts.
//
// Sixteen columns with sixteen small coercions on the way in: a blank
// description that has to be an empty string rather than null, a flag that has
// to be 1 or 0 rather than true or false, a day number where Sunday is zero,
// and a date sanitiser that applies to six of them and not the other ten.
//
// None of that is interesting and all of it has to match. It is the shape this
// project keeps finding on the wrong end of a bug: one rule, written twice,
// right on the day it was written. So the desktop generates the answers and
// this reproduces them — see the `taskDraft` and `sanitizeText` sections of
// store-vectors.json.
//
// This file makes a row. It does not decide whether the row may be saved: see
// [taskProblems], which names what is wrong and lets the caller weigh it. The
// desktop form has always refused exactly two of those and quietly defaulted
// its way past the rest; a phone with no defaults to hide behind can be
// stricter without that being a change to what the desktop does.

/** The columns a new task row is written with, in the order the values come. */
val TASK_COLUMNS: List<String> = listOf(
    "name",
    "description",
    "category",
    "reset_type",
    "reset_time",
    "reset_day",
    "reset_interval_days",
    "anchor_date",
    "event_start",
    "event_end",
    "specific_date",
    "is_priority",
    "is_urgent",
    "min_step",
    "time_zone",
    "intent",
)

/** The reset types the engine knows how to schedule. */
val RESET_TYPES: List<String> = listOf(
    "daily",
    "weekly",
    "biweekly",
    "custom_days",
    "event_window",
    "specific_date",
    "one_time",
)

/**
 * A draft as a phone screen would hand it over: everything optional, nothing
 * coerced yet.
 *
 * Strings rather than typed fields for the numbers, because a text box hands
 * over text and the place that turns "3" into 3 should be the same place that
 * decides what "" means. That place is below.
 */
data class TaskDraft(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val resetType: String? = null,
    val resetTime: String? = null,
    val resetDay: String? = null,
    val resetIntervalDays: String? = null,
    val anchorDate: String? = null,
    val eventStart: String? = null,
    val eventEnd: String? = null,
    val specificDate: String? = null,
    val isPriority: Boolean = false,
    val isUrgent: Boolean = false,
    val minStep: String? = null,
    val timeZone: String? = null,
    val intent: String? = null,
)

private val UTC_MS = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z$""")
private val UTC = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")
private val OFFSET_MS = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+[+-]\d{2}:\d{2}$""")
private val OFFSET = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$""")
private val LOCAL_T = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")
private val LEGACY_SPACE = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
private val HHMM = Regex("""^\d{2}:\d{2}$""")

/**
 * The date and time columns, tidied without being reinterpreted.
 *
 * Order matters: the rules are tried top to bottom and the first match wins,
 * exactly as on the desktop. Milliseconds go because this column is compared as
 * a string in more than one place, and two stamps for the same instant that
 * differ only in a fraction are two different strings.
 */
fun sanitizeText(v: String?): String? {
    if (v == null || v == "") return null
    if (UTC_MS.containsMatchIn(v)) return v.replace(Regex("""\.\d+Z$"""), "Z")
    if (UTC.containsMatchIn(v)) return v
    if (OFFSET_MS.containsMatchIn(v)) return v.replace(Regex("""\.\d+([+-])"""), "$1")
    if (OFFSET.containsMatchIn(v)) return v
    if (LOCAL_T.containsMatchIn(v)) {
        return v.replace(Regex("""\.\d+$"""), "").replace(Regex("""Z$"""), "")
    }
    if (LEGACY_SPACE.containsMatchIn(v)) return v.replaceFirst(" ", "T")
    return v
}

/**
 * A number, or null, keeping the difference between "not given" and "cleared".
 *
 * Both end up null in the row. Written out rather than folded together because
 * the two are different questions everywhere else, and a reader should not have
 * to work out that here they happen to share an answer.
 */
private fun num(v: String?): Double? {
    if (v == null || v == "") return null
    return v.trim().toDoubleOrNull()
}

/** The sixteen values, in [TASK_COLUMNS] order. */
fun taskValues(d: TaskDraft): List<SyncValue> {
    fun text(s: String?): SyncValue = if (s == null) SyncValue.Null else SyncValue.Text(s)
    fun number(n: Double?): SyncValue = if (n == null) SyncValue.Null else SyncValue.Num(n)
    return listOf(
        SyncValue.Text(d.name ?: ""),
        SyncValue.Text(if (!d.description.isNullOrEmpty()) d.description else ""),
        text(d.category),
        text(d.resetType),
        text(sanitizeText(d.resetTime)),
        number(num(d.resetDay)),
        number(num(d.resetIntervalDays)),
        text(sanitizeText(d.anchorDate)),
        text(sanitizeText(d.eventStart)),
        text(sanitizeText(d.eventEnd)),
        text(sanitizeText(d.specificDate)),
        SyncValue.Num(if (d.isPriority) 1.0 else 0.0),
        SyncValue.Num(if (d.isUrgent) 1.0 else 0.0),
        text(sanitizeText(d.minStep)),
        text(sanitizeText(d.timeZone)),
        if (d.intent == "want" || d.intent == "must") SyncValue.Text(d.intent) else SyncValue.Null,
    )
}

/**
 * What is wrong with this draft, as codes rather than sentences.
 *
 * Codes because two languages and a screen all have to agree on the list, and a
 * translated sentence is not something a test can compare. The words belong to
 * whatever draws the screen; the facts belong here.
 */
fun taskProblems(d: TaskDraft): List<String> {
    val out = mutableListOf<String>()

    if ((d.name ?: "").isBlank()) out.add("name-empty")

    val type = d.resetType
    if (type == null || type !in RESET_TYPES) {
        out.add("reset-type-unknown")
        // Everything below is a rule about a particular type. With no type to
        // stand on they would all fire at once and say nothing.
        return out
    }

    val time = sanitizeText(d.resetTime)
    if (time != null && !HHMM.matches(time)) out.add("time-malformed")

    if (type == "weekly" || type == "biweekly") {
        val day = num(d.resetDay)
        // Sunday is zero, which is falsy in the language on the other side of
        // this. Asking about null rather than about truthiness is what stops
        // Sunday being the one day a weekly task cannot be set to.
        if (day == null) out.add("weekly-needs-day")
        else if (day % 1.0 != 0.0 || day < 0.0 || day > 6.0) out.add("day-out-of-range")
    }

    if (type == "custom_days") {
        val n = num(d.resetIntervalDays)
        if (n == null) out.add("custom-needs-interval")
        else if (n % 1.0 != 0.0 || n < 1.0) out.add("interval-out-of-range")
    }

    if (type == "event_window" &&
        (sanitizeText(d.eventStart) == null || sanitizeText(d.eventEnd) == null)
    ) {
        out.add("event-needs-window")
    }

    if (type == "specific_date" && sanitizeText(d.specificDate) == null) out.add("date-missing")

    return out
}

/**
 * The INSERT, with the columns named rather than positional.
 *
 * No uid and no updated_at: the triggers fill both, and a caller that supplied
 * them would be minting an identity by hand for a row that has not been written
 * yet. The outbox trigger queues it in the same breath, which is why nothing
 * here mentions sync at all.
 */
fun insertTaskSql(): String {
    val marks = TASK_COLUMNS.joinToString(", ") { "?" }
    return "INSERT INTO tasks (${TASK_COLUMNS.joinToString(", ")}) VALUES ($marks)"
}

// ─── editing an existing row ─────────────────────────────────────────────────

/**
 * Which columns an edit may touch, and how each one is read on the way in.
 *
 * Also the allowlist, and that is the load-bearing part: column names go into
 * the SQL text itself, so they have to come from a list rather than from
 * whatever keys the caller handed over.
 *
 * `notes` is here and not in [TASK_COLUMNS] because a new task has no notes
 * yet. That is the one real difference between the two lists.
 */
val TASK_EDITABLE: Map<String, String> = linkedMapOf(
    "name" to "raw",
    "description" to "raw",
    "notes" to "raw",
    "category" to "raw",
    "reset_type" to "raw",
    "reset_time" to "clean",
    "reset_day" to "int",
    "reset_interval_days" to "int",
    "anchor_date" to "clean",
    "event_start" to "clean",
    "event_end" to "clean",
    "specific_date" to "clean",
    "is_priority" to "flag",
    "is_urgent" to "flag",
    "min_step" to "clean",
    "time_zone" to "clean",
    "intent" to "intent",
)

private fun coerce(how: String, v: String?): SyncValue = when (how) {
    "clean" -> sanitizeText(v)?.let { SyncValue.Text(it) } ?: SyncValue.Null
    "int" -> num(v)?.let { SyncValue.Num(it) } ?: SyncValue.Null
    // A text box hands over text, so "true" and "1" are the two ways a screen
    // can say yes here. Anything else, including nothing, is no.
    "flag" -> SyncValue.Num(if (v == "true" || v == "1") 1.0 else 0.0)
    "intent" -> if (v == "want" || v == "must") SyncValue.Text(v) else SyncValue.Null
    else -> if (v == null) SyncValue.Null else SyncValue.Text(v)
}

/** An edit, as the columns to set and the values to bind. */
data class TaskEdit(val columns: List<String>, val values: List<SyncValue>)

/**
 * Keys the list does not know are dropped in silence rather than reaching the
 * SQL string.
 *
 * Walking the allowlist rather than the caller's keys, so the shape of an
 * UPDATE is a property of this file and not of whatever built the map. Two
 * devices producing the same statement for the same edit is worth more than
 * preserving the order somebody typed the fields in.
 */
fun taskUpdate(fields: Map<String, String?>): TaskEdit {
    val columns = mutableListOf<String>()
    val values = mutableListOf<SyncValue>()
    for ((column, how) in TASK_EDITABLE) {
        if (!fields.containsKey(column)) continue
        columns.add(column)
        values.add(coerce(how, fields[column]))
    }
    return TaskEdit(columns, values)
}

/**
 * The UPDATE, keyed by uid rather than by id.
 *
 * `id` is an autoincrement that means something different in each database:
 * task number five here and task number five on the desktop are two different
 * tasks. `uid` is the same string on every device for ever, which is the whole
 * reason it exists.
 */
fun updateTaskSql(columns: List<String>): String {
    val sets = columns.joinToString(", ") { "$it = ?" }
    return "UPDATE tasks SET $sets WHERE uid = ?"
}

/**
 * A stored row, read back as the strings a form puts in its boxes.
 *
 * Driven by [TASK_EDITABLE] rather than by a list of its own, so the columns a
 * screen can fill in are exactly the columns it can then write. A form that
 * shows a field it cannot save, or saves one it never showed, is two lists
 * disagreeing — and this is the third place that list would otherwise appear.
 *
 * A whole number comes back without its fraction. SQLite hands `reset_day` over
 * as a double, and a day box reading "1.0" is the kind of detail that looks
 * like a bug in the app to the person holding it.
 *
 * Missing columns come back absent rather than as null, because [taskUpdate]
 * treats those differently: absent means "do not touch this column", null means
 * "clear it". A row from a desktop running an older build should not have its
 * newer columns wiped by being edited on a phone.
 */
fun taskEditFields(row: Map<String, SyncValue>): Map<String, String?> {
    val out = LinkedHashMap<String, String?>()
    for (column in TASK_EDITABLE.keys) {
        val v = row[column] ?: continue
        out[column] = when (v) {
            is SyncValue.Null -> null
            is SyncValue.Text -> v.value
            is SyncValue.Num -> if (v.value % 1.0 == 0.0) v.value.toLong().toString()
            else v.value.toString()
            is SyncValue.Bool -> if (v.value) "1" else "0"
        }
    }
    return out
}