package app.reup.sync

import app.reup.core.ResetSpec
import app.reup.core.ResetType
import app.reup.core.ScheduledTask
import kotlinx.datetime.Instant

// ─── TaskRepo.kt — the task list, from the database this time ────────────────
//
// This replaces Samples.kt, which said in its own header that it existed because
// two things needed the same list and neither could own it: the screen, and the
// receiver that runs when the app is not open. A database is what owns it.
//
// The query and the mapping live in commonMain, not in the Android module, for
// the same reason the WebDAV logic lives here and only the socket call is
// platform code. What a row means is a decision; reading bytes off a disk is not.
//
// WHY THE ID IS THE uid AND NOT THE ROW id
// ----------------------------------------
// `id` is an autoincrement local to one database, so the desktop's task 5 and
// the phone's task 5 are different tasks. Alarms are keyed by this id and
// survive reboots and syncs, so keying them on a number that means something
// different on each device would mean an alarm firing for whatever row happened
// to land on that number. `uid` is the same string everywhere, forever.

/**
 * Only the rows the reset engine can act on.
 *
 * Tombstones and trashed rows are excluded here rather than filtered later,
 * because a task that is not in the list cannot have an alarm scheduled for it
 * by mistake. `is_active = 1` is the desktop's own definition of "not in the
 * bin", and `deleted = 0` keeps a synced tombstone from ever becoming an alarm.
 *
 * WHY `*` AND NOT A COLUMN LIST
 * -----------------------------
 * The mapper below reads every field defensively, so that a column holding
 * something this version did not expect costs one task its alarm rather than
 * crashing the receiver that was about to schedule all of them. A named column
 * list is the exact opposite of that promise: it makes the read fail entirely
 * if any one name is absent, and it fails while SQLite is still compiling the
 * statement, which is before the defensive mapper is ever reached.
 *
 * That is not hypothetical. This query shipped naming `notify_before_min`, a
 * lead-time column that was designed in a planning conversation but never
 * actually added to the desktop schema. The result was not one task without a
 * lead time. It was the entire task list failing to load on a device where
 * everything compiled and every test was green, because a column name inside a
 * string literal is checked against nothing at all.
 *
 * `*` makes the two directions symmetric. A column this version has never
 * heard of is ignored by the mapper. A column this version asks for but the
 * schema does not have reads as absent. Both are survivable; neither takes the
 * list down. The cost is a few unused columns per row on a table with tens of
 * rows, which is nothing next to the failure it removes.
 *
 * TaskRepoTest is what keeps the names honest now, since the compiler cannot.
 */
const val ACTIVE_TASKS_SQL: String =
    "SELECT * FROM tasks WHERE is_active = 1 AND deleted = 0 AND uid IS NOT NULL " +
            "ORDER BY uid ASC"

/** Is there a task under this uid that is still on the list. */
const val LIVE_TASK_SQL: String =
    "SELECT uid FROM tasks WHERE uid = ? AND is_active = 1 AND deleted = 0"

/**
 * Into the bin. The same two columns the desktop's deleteTask writes, keyed by
 * uid rather than by a row number that means something different here.
 */
const val BIN_TASK_SQL: String =
    "UPDATE tasks SET is_active = 0, deleted_at = ? WHERE uid = ? AND is_active = 1 AND deleted = 0"

private fun text(r: DbRow, c: String): String? = (r[c] as? SyncValue.Text)?.value

private fun int(r: DbRow, c: String): Int? = when (val v = r[c]) {
    is SyncValue.Num -> v.value.toInt()
    is SyncValue.Text -> v.value.toIntOrNull()
    else -> null
}

/**
 * A row as the engine wants it.
 *
 * Every field is read defensively. This row may have arrived from a desktop
 * running a newer build, and a column holding something this version did not
 * expect should cost one task its alarm rather than crash the receiver that was
 * about to schedule all of them.
 */
fun scheduledTaskFrom(r: DbRow): ScheduledTask? {
    val uid = text(r, "uid") ?: return null
    val type = text(r, "reset_type") ?: return null

    val paused = text(r, "paused_until")?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }

    return ScheduledTask(
        id = uid,
        spec = ResetSpec(
            resetType = type,
            resetTime = text(r, "reset_time"),
            resetDay = int(r, "reset_day"),
            anchorDate = text(r, "anchor_date"),
            resetIntervalDays = int(r, "reset_interval_days"),
            eventEnd = text(r, "event_end"),
            specificDate = text(r, "specific_date"),
            timeZone = text(r, "time_zone"),
        ),
        // Absent from the desktop schema as of this build. Left in place rather
        // than deleted so that the day the column ships, nothing here changes;
        // until then it reads as null, which the engine takes as "ring at the
        // reset". TaskRepoTest lists it as pending, so it stays a decision.
        notifyBeforeMin = int(r, "notify_before_min"),
        pausedUntil = paused,
    )
}

/** What the notification text needs. The core module has no idea what anything
 *  is called, on purpose — names are presentation. */
fun labelFrom(r: DbRow): String = text(r, "name")?.takeIf { it.isNotBlank() } ?: "งาน"

/**
 * Quiet hours, read from the same row the desktop writes.
 *
 * It used to read `app_settings` under a key called `quiet_hours` — a key the
 * desktop has never written, because on that side the value lived in
 * localStorage. So this query has always returned nothing, and the default in
 * Repo.kt has always been the whole answer. Now there is a row, and the three
 * cases it can be in are told apart rather than collapsed. See UserSettings.kt.
 */
fun quietSettingFrom(rows: List<DbRow>): QuietSetting =
    parseQuiet(rows.firstOrNull()?.let { text(it, "value") })


// ─── ticking one done, which is the first thing this device writes ───────────
//
// Everything before this round was one-way: rows arrived, alarms were set, and
// nothing here ever changed a row. This is the write that reverses that, and it
// is deliberately the first one, because it is the write the whole design was
// bent around. `merge.ts` singles out `completed_until` as the one field that
// is NOT last-write-wins, for exactly this: the notification goes off on the
// phone, it is ticked there, and a desktop holding a slightly newer row must
// not hand back the version from before the tick.
//
// So the risky write is the one with the rule already written for it, and every
// write after this one is a smaller version of a problem already solved.

/**
 * An instant as the desktop writes it: ISO-8601, UTC, always three fractional
 * digits.
 *
 * WHY THIS IS NOT `Instant.toString()`
 *
 * kotlinx-datetime prints the shortest form it can, so a reset landing exactly
 * on the minute comes out as `2026-08-19T04:00:00Z`. JavaScript's toISOString
 * always prints milliseconds, so the desktop writes `2026-08-19T04:00:00.000Z`
 * for the same instant. Two spellings of one moment.
 *
 * That matters because `completed_until` is only ever COMPARED, never parsed,
 * by the code that decides which side is further along — and compared as a
 * string, since that is all merge has. `Z` sorts after `.`, so the phone's
 * spelling would read as later than the desktop's for the very same instant,
 * and a tick would appear to move forward when nothing had happened. It is the
 * same trap as `created_at` in syncMeta, which is reformatted on backfill for
 * this exact reason: a column compared as a string has to come from one place.
 *
 * Written out by hand rather than through the calendar API so that it can be
 * checked here against real values from the other implementation, and so that
 * it cannot pick up a locale or a zone from anywhere.
 */
fun isoMillis(epochMillis: Long): String {
    val days = epochMillis.floorDiv(86_400_000L)
    val ms = epochMillis.mod(86_400_000L)

    // days since 1970-01-01 to a civil date, by the usual era arithmetic.
    val z = days + 719_468L
    val era = (if (z >= 0) z else z - 146_096L) / 146_097L
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460L + doe / 36_524L - doe / 146_096L) / 365L
    val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
    val mp = (5L * doy + 2L) / 153L
    val d = doy - (153L * mp + 2L) / 5L + 1L
    val m = if (mp < 10L) mp + 3L else mp - 9L
    val y = yoe + era * 400L + if (m <= 2L) 1L else 0L

    val hh = ms / 3_600_000L
    val mm = (ms / 60_000L) % 60L
    val ss = (ms / 1_000L) % 60L
    val fff = ms % 1_000L

    fun p(n: Long, w: Int): String = n.toString().padStart(w, '0')
    return p(y, 4) + "-" + p(m, 2) + "-" + p(d, 2) + "T" +
            p(hh, 2) + ":" + p(mm, 2) + ":" + p(ss, 2) + "." + p(fff, 3) + "Z"
}

/** The reset kinds that come back on their own. Mirror of `isRecurring`. */
private val RECURRING = setOf(
    ResetType.DAILY, ResetType.WEEKLY, ResetType.BIWEEKLY, ResetType.CUSTOM_DAYS,
)

/**
 * How long a tick lasts, as the desktop decides it.
 *
 * Two rules, taken from `TaskCard.handleComplete`:
 *
 *   recurring   done until the next reset, so the cycle un-ticks itself
 *   one-shot    done until the end of today, so it stays visibly finished for
 *               the rest of the day and then stops being shown
 *
 * Strings in and a string out, with no clock and no calendar. That is not
 * squeamishness about types: it means this rule can be compiled and run
 * wherever the vectors are checked, instead of only on a device. The caller
 * already has both instants — it drew the countdown from them a moment ago —
 * so nothing is recomputed here that was not already known.
 *
 * A recurring task with no next reset returns null rather than falling back to
 * the end of the day. No next reset means the engine could not schedule it, and
 * marking something done until a boundary that does not exist is a value no
 * other device can interpret.
 */
fun doneUntil(resetType: String, nextResetIso: String?, endOfLocalDayIso: String): String? =
    if (resetType in RECURRING) nextResetIso else endOfLocalDayIso

/**
 * Whether a task counts as done right now. Mirror of `isCompletedThisCycle`.
 *
 * String comparison rather than parsing, which works because every value in
 * this column is written by one of two places and both write ISO-8601 in UTC
 * with milliseconds — the sync trigger's own format. A value in any other shape
 * did not come from this app, and reading it as "not done" is the safe way to
 * be wrong: the task stays on the list.
 */
fun isDoneNow(completedUntilIso: String?, nowIso: String): Boolean {
    val until = completedUntilIso?.takeIf { it.isNotBlank() } ?: return false
    return until > nowIso
}

/**
 * Done until [untilIso].
 *
 * `missed_streak = 0` travels with it because the two are one thought: doing a
 * thing once clears the easing immediately, since the easing was never a
 * penalty to be worked off. They are also two of the three fields `merge.ts`
 * moves as a unit, so writing one without the other produces a row that existed
 * nowhere — completed, yet still carrying the missed streak from before it was
 * completed.
 *
 * WHY THE WHERE IS ON uid
 *
 * `id` is a local autoincrement, so task 5 here and task 5 on the desktop are
 * different tasks. `uid` is the same string on every device, which is why the
 * alarms are keyed on it too.
 *
 * Nothing here mentions the outbox. The trigger on this table queues the row,
 * and `updated_at` is left for the other trigger to stamp from the shared
 * clock — passing a timestamp in would make this device's write look like an
 * incoming sync and stamp it with a clock that is not the one the rest of the
 * column came from.
 */
const val MARK_DONE_SQL: String =
    "UPDATE tasks SET completed_until = ?, completed_at = ?, missed_streak = 0 WHERE uid = ?"

/** Undo, which clears the mark and nothing else. The streak is not restored:
 *  it was cleared by a completion that did happen. */
/**
 * Append-only, so there is no conflict to resolve and nothing to overwrite.
 * `id` is left to autoincrement and `uid`/`updated_at` are filled by the sync
 * triggers, exactly as they are for a task.
 */
const val RECORD_EVENT_SQL: String =
    "INSERT INTO task_events (task_uid, kind, at, for_cycle) VALUES (?, ?, ?, ?)"

const val CLEAR_DONE_SQL: String =
    "UPDATE tasks SET completed_until = NULL, completed_at = ? WHERE uid = ?"

class TaskRepo(private val db: Db) {

    /** Runs the bootstrap first. Both halves are idempotent, so this is what
     *  every entry point calls rather than something guarded by a flag. */
    suspend fun open(): TaskRepo {
        for (m in Bootstrap.statements()) {
            try {
                db.execute(m.sql)
            } catch (e: Exception) {
                if (!m.ignoreErrors) throw e
            }
        }
        return this
    }

    suspend fun tasks(): List<ScheduledTask> =
        db.select(ACTIVE_TASKS_SQL).mapNotNull(::scheduledTaskFrom)

    /** id to name, for the notification. One query, not one per alarm. */
    suspend fun labels(): Map<String, String> =
        db.select(ACTIVE_TASKS_SQL)
            .mapNotNull { r -> text(r, "uid")?.let { it to labelFrom(r) } }
            .toMap()

    suspend fun quietSetting(): QuietSetting =
        quietSettingFrom(db.select(userSettingSql(), listOf(SyncValue.Text(UserSettings.QUIET))))

    /** uid to completed_until, for drawing which rows are already ticked. */
    suspend fun completions(): Map<String, String?> =
        db.select(ACTIVE_TASKS_SQL)
            .mapNotNull { r -> text(r, "uid")?.let { it to text(r, "completed_until") } }
            .toMap()

    /**
     * Write a task this phone made.
     *
     * The second thing this device writes, after ticking one done, and the
     * first that creates a row rather than changing one. What makes that safe
     * is that nothing here decides what the row looks like: [taskValues] does,
     * on both sides, against a vector file.
     *
     * Refuses rather than writes when [taskProblems] finds something. The
     * desktop form has always defaulted its way past most of these, which it
     * can because every control on it has a default; a screen with a blank text
     * box and no dropdowns has nothing to fall back on, and a task the engine
     * cannot schedule is a task that sits in the list looking normal and never
     * rings.
     *
     * No uid, no updated_at, nothing about sync. The triggers stamp the row and
     * queue it in the same breath, which is the property that makes this five
     * lines instead of thirty.
     */
    suspend fun createTask(draft: TaskDraft): List<String> {
        val problems = taskProblems(draft)
        if (problems.isNotEmpty()) return problems
        db.execute(insertTaskSql(), taskValues(draft))
        return emptyList()
    }

    /**
     * Change a task this phone already has.
     *
     * Keyed by uid, never by id: `id` is an autoincrement that means something
     * different in each database, so an edit sent by row number would land on
     * whatever task happened to be sitting at that number over there.
     *
     * Returns whether a row was actually touched, so a screen can tell "saved"
     * from "that task is not here any more" — which is a real answer once two
     * devices can delete things.
     */
    /**
     * One task, as the strings an edit form puts in its boxes.
     *
     * Null when there is no such row, which on a device that syncs is a real
     * answer rather than an error: the task may have been deleted on the
     * desktop between the list being drawn and the row being opened.
     */
    /**
     * Into the bin, not out of the database.
     *
     * The same two columns the desktop writes: `is_active = 0` takes it off the
     * list and out of the alarm queue, `deleted_at` is what tells the bin this
     * was thrown away rather than finished. A one-off that completed and
     * archived itself also has `is_active = 0`, and offering to undelete
     * something already dealt with is how a bin refills a list with things that
     * were done.
     *
     * The row itself stays for thirty days and the desktop's sweep turns it
     * into a tombstone after that. Nothing on this phone runs that sweep, and
     * nothing needs to: the result travels down like any other change.
     *
     * `deleted_at` comes from the database clock rather than the platform one,
     * for the reason the whole project learned the hard way — a column compared
     * as a string has to come from one place.
     *
     * Returns whether there was anything to delete, so a screen can tell
     * "removed" from "the other device removed it first", which on a device
     * that syncs is a real answer rather than an error.
     */
    suspend fun deleteTask(uid: String): Boolean {
        val live = db.select(LIVE_TASK_SQL, listOf(SyncValue.Text(uid)))
        if (live.isEmpty()) return false
        db.execute(BIN_TASK_SQL, listOf(SyncValue.Text(dbNow(db)), SyncValue.Text(uid)))
        return true
    }

    suspend fun taskForEdit(uid: String): Map<String, String?>? {
        val rows = db.select(
            "SELECT * FROM tasks WHERE uid = ? AND deleted = 0",
            listOf(SyncValue.Text(uid)),
        )
        return rows.firstOrNull()?.let(::taskEditFields)
    }

    suspend fun updateTask(uid: String, fields: Map<String, String?>): Boolean {
        val edit = taskUpdate(fields)
        if (edit.columns.isEmpty()) return false
        db.execute(updateTaskSql(edit.columns), edit.values + SyncValue.Text(uid))
        return exists(uid)
    }

    private suspend fun exists(uid: String): Boolean =
        db.select(
            "SELECT uid FROM tasks WHERE uid = ? AND deleted = 0",
            listOf(SyncValue.Text(uid)),
        ).isNotEmpty()

    /**
     * Both halves stamp `completed_at` from the same clock the sync trigger
     * uses. Without it on the clearing half the write cannot travel at all: sync
     * compares that column, and a row that clears the tick without saying when
     * loses to the copy on the other device for ever.
     */
    suspend fun markDone(uid: String, untilIso: String) {
        val now = dbNow(db)
        db.execute(
            MARK_DONE_SQL,
            listOf(SyncValue.Text(untilIso), SyncValue.Text(now), SyncValue.Text(uid)),
        )
        record(uid, "done", now, SyncValue.Text(untilIso))
    }

    suspend fun clearDone(uid: String) {
        val now = dbNow(db)
        db.execute(CLEAR_DONE_SQL, listOf(SyncValue.Text(now), SyncValue.Text(uid)))
        record(uid, "undone", now, SyncValue.Null)
    }

    /**
     * One line in the history, beside the change that caused it.
     *
     * A failure here must not undo the tick. Losing a line of history is a day
     * that reads slightly wrong in a calendar six months from now; refusing to
     * mark something done because a log write failed is the app breaking in the
     * hand of somebody who has just finished something.
     */
    private suspend fun record(uid: String, kind: String, at: String, forCycle: SyncValue) {
        try {
            db.execute(
                RECORD_EVENT_SQL,
                listOf(SyncValue.Text(uid), SyncValue.Text(kind), SyncValue.Text(at), forCycle),
            )
        } catch (e: Exception) {
            // Swallowed deliberately, and only here.
        }
    }
}