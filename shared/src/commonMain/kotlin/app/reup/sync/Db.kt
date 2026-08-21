package app.reup.sync

// ─── Db.kt — the database, as this module is willing to know it ──────────────
//
// Two methods. That is deliberate and it is the same two the desktop store
// narrowed itself to, for the same reason: a port this small can be implemented
// over the Android framework's SQLiteDatabase in about twenty lines, over a JDBC
// driver in a test in about the same, and over anything else later without any
// of the code above it noticing.
//
// It also keeps this module honest about being common code. Nothing here
// imports anything from Android, so the whole store compiles and runs on the JVM
// where it can actually be tested.

/**
 * A row as the driver hands it back: column name to value.
 *
 * SyncValue rather than Any? because the engine already speaks SyncValue and a
 * second value type would mean a second set of conversion rules to keep in
 * agreement with the first.
 */
typealias DbRow = Map<String, SyncValue>

interface Db {
    suspend fun execute(sql: String, params: List<SyncValue> = emptyList())
    suspend fun select(sql: String, params: List<SyncValue> = emptyList()): List<DbRow>
}

/**
 * The clock, read from SQLite rather than from the platform.
 *
 * Every other timestamp in `updated_at` is written by a trigger using exactly
 * this expression. Reading the clock anywhere else would mean one column holding
 * readings from two sources — and on Windows the system timer ticks about every
 * sixteen milliseconds, so the two can disagree about which of two events came
 * first. That is not a rounding error anyone would notice by eye; it reorders
 * the push batch, which is sorted by this column, and a delete that sorts after
 * the row created to replace it turns into a duplicate that never existed.
 *
 * syncMeta already reformats created_at for the same reason: a column that is
 * only ever compared as a string has to come from one source.
 */
const val SQL_BUMP = "UPDATE sync_clock SET t = max(strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', t, '+0.001 seconds')) WHERE id = 1"

const val SQL_CLOCK_READ = "SELECT t FROM sync_clock WHERE id = 1"

/**
 * Never behind something already seen.
 *
 * [SQL_BUMP] keeps this device's clock ahead of its own past. It says nothing
 * about the other device's, and the two are not related: the clock is
 * `max(system time, last value + 1ms)`, so a device that has done more writes
 * than the other inside one tick of the system timer ends up further ahead of
 * it. On Windows that tick is about sixteen milliseconds, which is long enough
 * for a whole sync.
 *
 * Without this, an edit made straight after receiving a row can carry a stamp
 * OLDER than the row it is editing. Last-write-wins reads it as the older of
 * the two and keeps the copy that was just replaced, so the edit lands here,
 * travels, and is thrown away at the far end — no error, no retry, and the
 * person watching sees their change quietly undone a few seconds later.
 *
 * Restoring a task the other device binned is exactly that shape, which is how
 * this was found. It passed on a machine with a millisecond timer for a week.
 */
const val SQL_SEEN = "UPDATE sync_clock SET t = max(t, ?) WHERE id = 1"

/**
 * Bump, then read. Never read alone.
 *
 * `strftime('now')` on its own hands out the same value twice whenever two
 * writes fall inside one tick of the system timer, and a tick is about sixteen
 * milliseconds on Windows — long enough for a whole delete-and-recreate. Two
 * rows sharing a timestamp is not a rounding error: "what have I changed since
 * my last push" is `updated_at > watermark`, so a row written in the same tick
 * as the watermark is never sent and no error is raised anywhere.
 */
suspend fun dbNow(db: Db): String {
    db.execute(SQL_BUMP)
    val rows = db.select(SQL_CLOCK_READ)
    return (rows.firstOrNull()?.get("t") as? SyncValue.Text)?.value
        ?: throw IllegalStateException("the database did not return a clock reading")
}

// ─── loading schema.sql ──────────────────────────────────────────────────────

object Schema {

    /**
     * Split a schema file into statements.
     *
     * The file's own rules say statements are separated by a line containing
     * only `-- @@`, and that the loaders split on that rather than on semicolons
     * because a trigger body contains semicolons.
     *
     * The line has to be matched whole. Splitting on the bare text cuts the
     * header in half, because the header is where that rule is written down —
     * which is a mistake worth mentioning because it was made once already.
     */
    private val SEPARATOR = Regex("""^[ \t]*--[ \t]*@@[ \t]*$""", RegexOption.MULTILINE)

    fun statements(text: String): List<String> =
        text.split(SEPARATOR)
            .map { chunk ->
                chunk.lineSequence()
                    .filter { !it.trimStart().startsWith("--") }
                    .joinToString("\n")
                    .trim()
            }
            .filter { it.isNotEmpty() }
}

// ─── the migrations sync needs on top of the schema ──────────────────────────

/** One statement, and whether failing is normal. */
data class Migration(
    val sql: String,
    /**
     * ALTER TABLE ADD COLUMN throws when the column is already there, which is
     * the normal path on every launch after the first.
     */
    val ignoreErrors: Boolean = false,
)

/**
 * Mirror of syncMigrations() in src/lib/syncMeta.ts, compared against it string
 * by string in store-vectors.json.
 *
 * Returned as data rather than run inline so that the comparison is possible at
 * all. Two implementations of a migration list that have to match by somebody
 * remembering is the disease this project keeps curing.
 */
object SyncMigrations {

    private const val SQL_BUMP = "UPDATE sync_clock SET t = max(strftime('%Y-%m-%dT%H:%M:%fZ', 'now'), strftime('%Y-%m-%dT%H:%M:%fZ', t, '+0.001 seconds')) WHERE id = 1"

    private const val SQL_CLOCK = "(SELECT t FROM sync_clock WHERE id = 1)"

    private const val SQL_UUID4 = """(
  lower(hex(randomblob(4))) || '-' ||
  lower(hex(randomblob(2))) || '-4' ||
  substr(lower(hex(randomblob(2))), 2) || '-' ||
  substr('89ab', abs(random()) % 4 + 1, 1) ||
  substr(lower(hex(randomblob(2))), 2) || '-' ||
  lower(hex(randomblob(6)))
)"""

    private data class Table(
        val name: String,
        /** Tables that hard-delete need a tombstone; ones that keep the row do not. */
        val needsDeleted: Boolean,
        /** Used to seed updated_at for rows that already exist. */
        val hasCreatedAt: Boolean,
    )

    private val TABLES = listOf(
        // tasks and budgets were once false here, with the reasoning that
        // is_active = 0 already keeps the row so no tombstone was needed. That
        // is true of the trash, which is a state a row is in, and false of the
        // purge button and the thirty-day sweep, which both run a real DELETE.
        // Under sync a deleted row that leaves no trace is worse than one that
        // stays: the other device still has it, pushes it back, and it returns
        // from the dead with no error anywhere.
        Table("tasks", needsDeleted = true, hasCreatedAt = true),
        Table("income", needsDeleted = true, hasCreatedAt = true),
        Table("expenses", needsDeleted = true, hasCreatedAt = true),
        Table("budgets", needsDeleted = true, hasCreatedAt = false),
        Table("saving_goals", needsDeleted = true, hasCreatedAt = true),
        // Hidden is not deleted, so the tombstone here is only for a category a
        // future version might truly remove. It costs one column to have ready.
        Table("expense_categories", needsDeleted = true, hasCreatedAt = false),
        // Already has its own deleted flag, so only uid and updated_at are added.
        Table("expected_income", needsDeleted = false, hasCreatedAt = true),
        // Append-only, so a tombstone column would be dead weight: nothing
        // here is ever deleted or edited, and two devices merging events is a
        // union rather than a negotiation.
        Table("task_events", needsDeleted = true, hasCreatedAt = false),
        // The settings that describe a person. A tombstone because a key can be
        // retired, and a retired key that leaves no trace is a setting the other
        // device pushes back and resurrects.
        Table("user_settings", needsDeleted = true, hasCreatedAt = false),
    )

    /**
     * One row into the outbox, from inside a trigger on [table].
     *
     * Reads the row back by rowid rather than using NEW, because on an insert
     * the uid and the timestamp are filled by a second trigger and NEW still
     * holds the nulls the statement arrived with.
     */
    private fun outboxEnqueue(table: String) = """INSERT INTO sync_outbox (tbl, uid, updated_at)
          SELECT '$table', uid, updated_at FROM $table
           WHERE id = NEW.id AND uid IS NOT NULL AND updated_at IS NOT NULL
        ON CONFLICT (tbl, uid) DO UPDATE SET updated_at = excluded.updated_at"""

    fun statements(): List<Migration> {
        val out = mutableListOf<Migration>()

        // The clock has to exist before anything can read it, so it goes first.
        out += Migration(
            """CREATE TABLE IF NOT EXISTS sync_clock (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            t  TEXT NOT NULL
          )""",
        )
        out += Migration(
            "INSERT OR IGNORE INTO sync_clock (id, t) VALUES (1, '1970-01-01T00:00:00.000Z')",
        )
        // Seeded forward on every launch. Starting at the epoch would otherwise
        // mean the first few writes after a fresh install carry 1970 timestamps.
        out += Migration(SQL_BUMP)

        // ── the outbox ──────────────────────────────────────────────────────
        //
        // What this device has not sent yet, as a fact rather than a
        // comparison. The desktop's syncMeta.ts carries the long version of
        // why; the short one is that the watermark is a timestamp other
        // devices also write into, and a phone whose clock runs ahead pushes
        // this device's watermark into the future and silently freezes its own
        // outgoing edits.
        //
        // Nothing reads this table yet. It lands with its triggers on both
        // devices in one round so that the switch afterwards is one change.
        out += Migration(
            """CREATE TABLE IF NOT EXISTS sync_outbox (
            tbl        TEXT NOT NULL,
            uid        TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (tbl, uid)
          )""",
        )
        out += Migration(
            """CREATE TABLE IF NOT EXISTS sync_outbox_state (
            id     INTEGER PRIMARY KEY CHECK (id = 1),
            seeded INTEGER NOT NULL DEFAULT 0
          )""",
        )
        out += Migration("INSERT OR IGNORE INTO sync_outbox_state (id, seeded) VALUES (1, 0)")

        // ── the spill ────────────────────────────────────────────────────────
        //
        // Columns that arrived on a row this database has no column for, kept
        // beside the row so that sending it back does not strip them. See
        // Rows.spillRead.
        //
        // Not a column on each table, because the whole point is that this
        // schema has nowhere to put them — a column would only move the problem
        // one level up.
        out += Migration(
            """CREATE TABLE IF NOT EXISTS sync_spill (
            tbl  TEXT NOT NULL,
            uid  TEXT NOT NULL,
            cols TEXT NOT NULL,
            PRIMARY KEY (tbl, uid)
          )""",
        )


        for (t in TABLES) {
            out += Migration("ALTER TABLE ${t.name} ADD COLUMN uid TEXT", ignoreErrors = true)
            out += Migration("ALTER TABLE ${t.name} ADD COLUMN updated_at TEXT", ignoreErrors = true)
            if (t.needsDeleted) {
                out += Migration(
                    "ALTER TABLE ${t.name} ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0",
                    ignoreErrors = true,
                )
            }

            // Backfill rows that existed before this migration. randomblob() is
            // evaluated per row, so every row gets its own UUID from one
            // statement.
            //
            // created_at was written as "YYYY-MM-DD HH:MM:SS" while every new
            // timestamp is ISO with a T and a Z. Mixing the two in one column
            // would make string comparison, which is all "changed since X" has,
            // quietly wrong. So old values are reformatted rather than copied.
            val source = if (t.hasCreatedAt) "COALESCE(updated_at, created_at)" else "updated_at"
            val seed = "COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ', $source), $SQL_CLOCK)"
            out += Migration(
                """UPDATE ${t.name}
          SET uid = COALESCE(uid, $SQL_UUID4),
              updated_at = $seed
        WHERE uid IS NULL OR updated_at IS NULL""",
            )

            // uid is the identity a server keys on, so it must be unique. NULLs
            // are allowed to repeat in SQLite, which is fine: the insert trigger
            // fills them.
            out += Migration(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_${t.name}_uid ON ${t.name}(uid)",
            )
            // "everything changed since my last sync" is the query sync runs most.
            out += Migration(
                "CREATE INDEX IF NOT EXISTS idx_${t.name}_updated ON ${t.name}(updated_at)",
            )

            // Both triggers are dropped before they are created. They are
            // created with IF NOT EXISTS, which means an install that already
            // has the old body would silently keep it: the migration would
            // report success and change nothing.
            out += Migration("DROP TRIGGER IF EXISTS ${t.name}_sync_insert")
            out += Migration("DROP TRIGGER IF EXISTS ${t.name}_sync_update")

            // New rows: stamp identity and time, whatever code did the insert.
            out += Migration(
                """
      CREATE TRIGGER IF NOT EXISTS ${t.name}_sync_insert
      AFTER INSERT ON ${t.name}
      FOR EACH ROW WHEN NEW.uid IS NULL OR NEW.updated_at IS NULL
      BEGIN
        $SQL_BUMP;
        UPDATE ${t.name}
           SET uid = COALESCE(NEW.uid, $SQL_UUID4),
               updated_at = COALESCE(NEW.updated_at, $SQL_CLOCK)
         WHERE id = NEW.id;
      END;
    """,
            )

            // Any write bumps the clock. The WHEN guard means a statement that
            // sets updated_at itself is left alone, which is how an incoming
            // sync keeps the sender's timestamp, and it also stops the trigger
            // firing on itself.
            out += Migration(
                """
      CREATE TRIGGER IF NOT EXISTS ${t.name}_sync_update
      AFTER UPDATE ON ${t.name}
      FOR EACH ROW WHEN NEW.updated_at IS OLD.updated_at
      BEGIN
        $SQL_BUMP;
        UPDATE ${t.name} SET updated_at = $SQL_CLOCK WHERE id = NEW.id;
      END;
    """,
            )

            // No WHEN guard, unlike the two above. Those skip a write that
            // sets updated_at itself, which is how an incoming sync keeps the
            // sender's timestamp. These must not skip it, because softDelete
            // sets updated_at itself too and a delete that is never queued is
            // a row that comes back from the dead on the other device.
            out += Migration("DROP TRIGGER IF EXISTS ${t.name}_outbox_insert")
            out += Migration("DROP TRIGGER IF EXISTS ${t.name}_outbox_update")
            out += Migration("DROP TRIGGER IF EXISTS ${t.name}_outbox_delete")

            out += Migration(
                """
      CREATE TRIGGER IF NOT EXISTS ${t.name}_outbox_insert
      AFTER INSERT ON ${t.name}
      FOR EACH ROW
      BEGIN
        ${outboxEnqueue(t.name)};
      END;
    """,
            )
            out += Migration(
                """
      CREATE TRIGGER IF NOT EXISTS ${t.name}_outbox_update
      AFTER UPDATE ON ${t.name}
      FOR EACH ROW
      BEGIN
        ${outboxEnqueue(t.name)};
      END;
    """,
            )
            // A real DELETE only happens to a tombstone old enough to sweep,
            // so what it leaves behind is an entry naming a row that is gone.
            out += Migration(
                """
      CREATE TRIGGER IF NOT EXISTS ${t.name}_outbox_delete
      AFTER DELETE ON ${t.name}
      FOR EACH ROW
      BEGIN
        DELETE FROM sync_outbox WHERE tbl = '${t.name}' AND uid = OLD.uid;
      END;
    """,
            )
        }

        out += outboxSeed()

        return out
    }

    /**
     * Queue every row there is, once.
     *
     * WHY EVERYTHING RATHER THAN A CUTOFF
     *
     * On the launch the outbox first appears it is empty, and the only record
     * of what has been sent is the watermark — the number this table exists
     * because it cannot be trusted. Seeding everything costs one full upload
     * per device, once, which the far side applies as zero rows because merge
     * is idempotent. It cannot lose an edit, which the cheaper version can.
     *
     * WHY A FLAG RATHER THAN "IF THE OUTBOX IS EMPTY"
     *
     * An outbox that is empty because everything has been sent is the normal
     * state. Seeding on that would re-upload the database on every launch for
     * ever.
     *
     * Exposed because changing folders needs exactly this and nothing else: a
     * new folder has heard none of it. See outboxReseed.
     */
    fun outboxSeed(): List<Migration> {
        val out = mutableListOf<Migration>()
        for (t in TABLES) {
            out += Migration(
                """INSERT INTO sync_outbox (tbl, uid, updated_at)
            SELECT '${t.name}', uid, updated_at FROM ${t.name}
             WHERE uid IS NOT NULL AND updated_at IS NOT NULL
               AND (SELECT seeded FROM sync_outbox_state WHERE id = 1) = 0
          ON CONFLICT (tbl, uid) DO UPDATE SET updated_at = excluded.updated_at""",
            )
        }
        out += Migration("UPDATE sync_outbox_state SET seeded = 1 WHERE id = 1")
        return out
    }

    /**
     * The same thing again, for a folder that has heard none of it.
     *
     * Lowering the flag first is what makes the shared list run a second time.
     * The alternative was a second copy of the same seven statements without
     * the guard, which is the shape of every bug this project has spent a month
     * removing.
     */
    fun outboxReseed(): List<Migration> =
        listOf(Migration("UPDATE sync_outbox_state SET seeded = 0 WHERE id = 1")) + outboxSeed()

}
// ─── the bootstrap the phone runs on every launch ────────────────────────────

/**
 * schema.sql first, then the sync migrations on top of it.
 *
 * Returned as data rather than executed here, for the same reason SyncMigrations
 * is: a list of statements can be printed, counted and compared against the
 * desktop's, and a function that runs them cannot.
 *
 * Both halves are idempotent, so every entry point calls this on every launch
 * rather than guarding it with a version number. A version number is one more
 * thing that has to be kept right, and when it is wrong the result is a table
 * that silently never got its column.
 */
object Bootstrap {

    fun statements(): List<Migration> {
        val out = mutableListOf<Migration>()

        for (s in Schema.statements(SCHEMA_SQL)) {
            // schema.sql carries the ALTERs that once upgraded databases which
            // already existed. Run against one built fresh from the same file,
            // CREATE TABLE has already made the column, so every one of them
            // raises "duplicate column name". That is the normal path here.
            //
            // Decided from the statement text rather than from a hand-kept
            // flag, so that an ALTER added to schema.sql later is classified
            // correctly without anyone having to remember this rule exists.
            val isAlter = s.trimStart().startsWith("ALTER TABLE", ignoreCase = true)
            out += Migration(s, ignoreErrors = isAlter)
        }

        out += SyncMigrations.statements()
        return out
    }
}