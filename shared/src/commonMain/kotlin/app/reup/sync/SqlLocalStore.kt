package app.reup.sync

// ─── SqlLocalStore.kt — the engine's LocalStore, backed by SQLite ────────────
//
// Mirror of src/lib/sync/sqlLocalStore.ts. The desktop version is proven against
// two real databases built from the real schema with the real triggers; this one
// is proven to emit the same statements for the same inputs, which is how that
// proof gets here without being repeated on a phone.
//
// The engine decides what survives. This decides nothing. Everything here is
// either a query, a translation between a row and a record, or the one place
// that stores what this device remembers about syncing.
//
// ─── WHY THERE IS NO TRANSACTION AROUND apply() ─────────────────────────────
//
// Not an oversight. Every write here is an upsert keyed on uid that sets
// updated_at to the sender's value, so running it twice leaves the database
// exactly where running it once did. A phone put in a pocket halfway through
// means the cursor was never saved, the next sync fetches the same batches, and
// the rows that did land are written again to no effect. The engine's ordering
// already depends on that being true; this file just does not undo it.
//
// A transaction would still be nice for speed. It is left to the driver, where
// it belongs, rather than asserted here where the pooling behaviour of whatever
// runs underneath is not known.
//
// ─── WHERE THE SYNC STATE LIVES ─────────────────────────────────────────────
//
// One row in app_settings, which is deliberately not a synced table. This is the
// clearest example of why: the cursor is a statement about what THIS device has
// seen. Syncing it would tell the other device it had already read files it has
// never opened, and those rows would be missing on one device with nothing
// anywhere to say so.

/**
 * Not private any more: SyncConfigs has to clear the folder-specific half of
 * this row when the app is pointed somewhere else, and two files spelling the
 * same settings key by hand is how one of them ends up writing to a row nobody
 * reads.
 */
const val SYNC_STATE_KEY = "sync_state_v1"

/**
 * The tables that sync, named here rather than discovered.
 *
 * Discovering them would mean "every table with a uid column", which is true
 * today and one migration away from quietly enrolling a table nobody meant to
 * send. Which tables leave the machine is a policy, and policies are written
 * down.
 */
val SYNCED_TABLES = listOf(
    "tasks",
    "income",
    "expenses",
    "budgets",
    "saving_goals",
    "expense_categories",
    "expected_income",
    "task_events",
    "user_settings",
)

class SqlLocalStore private constructor(
    private val db: Db,
    private val shapes: Map<String, TableShape>,
    private var state: SyncState,
    private val now: suspend () -> String,
) : LocalStore {

    companion object {

        /**
         * Ask SQLite what it actually built.
         *
         * `origin` distinguishes an index that came from a UNIQUE clause in the
         * CREATE TABLE from one created afterwards by hand. The uid index is
         * ours and must not be treated as a natural key, or every row would look
         * like a duplicate of itself.
         */
        suspend fun readShape(db: Db, name: String): TableShape {
            val cols = db.select("PRAGMA table_info($name)")
            val columns = cols.map { textOf(it["name"]) }
            val types = cols.associate { textOf(it["name"]) to textOf(it["type"]) }

            val naturalKeys = mutableListOf<List<String>>()
            for (idx in db.select("PRAGMA index_list($name)")) {
                if (!isOne(idx["unique"])) continue
                val info = db.select("PRAGMA index_info(${textOf(idx["name"])})")
                val key = info.map { textOf(it["name"]) }
                if (key.size == 1 && key[0] == "uid") continue
                if (key.any { it.isEmpty() }) continue // an index on an expression
                naturalKeys += key
            }

            return TableShape(
                name = name,
                columns = columns,
                types = types,
                hasDeleted = "deleted" in columns,
                naturalKeys = naturalKeys,
            )
        }

        suspend fun readShapes(db: Db): Map<String, TableShape> =
            SYNCED_TABLES.associateWith { readShape(db, it) }

        /**
         * Reads the shapes once, and mints a device id on first use.
         *
         * The id is random and means nothing to anybody. It is deliberately not
         * the device name, because a device name is a person's name often enough
         * that it would put one in a filename in a folder shared with other
         * people, for no benefit at all — nothing ever displays it.
         */
        suspend fun open(
            db: Db,
            newDeviceId: () -> String,
            /**
             * One clock, and it belongs to the database.
             *
             * Suspending rather than plain, because the only correct answer
             * here has to be read from SQLite. `updated_at` is written by a
             * trigger using that same expression, and a second clock writing
             * into the same column is exactly how a delete came to be recorded
             * as happening after the row created to replace it. That was found
             * on the desktop and fixed there; the phone would have reproduced
             * it, except that the symptom only appears once two devices are
             * talking, which is a much worse place to find it.
             */
            now: suspend () -> String = { dbNow(db) },
        ): SqlLocalStore {
            val shapes = readShapes(db)
            val rows = db.select(
                "SELECT value FROM app_settings WHERE key = ?",
                listOf(SyncValue.Text(SYNC_STATE_KEY)),
            )

            val state = if (rows.isEmpty()) {
                val fresh = Engine.emptyState(newDeviceId())
                writeState(db, fresh)
                fresh
            } else {
                BatchCodec.decodeState(textOf(rows[0]["value"]), newDeviceId())
            }
            return SqlLocalStore(db, shapes, state, now)
        }

        private suspend fun writeState(db: Db, state: SyncState) {
            db.execute(
                "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                listOf(SyncValue.Text(SYNC_STATE_KEY), SyncValue.Text(BatchCodec.encodeState(state))),
            )
        }

        private fun textOf(v: SyncValue?): String = when (v) {
            is SyncValue.Text -> v.value
            is SyncValue.Num ->
                if (v.value == v.value.toLong().toDouble()) v.value.toLong().toString()
                else v.value.toString()
            is SyncValue.Bool -> v.value.toString()
            else -> ""
        }

        private fun isOne(v: SyncValue?): Boolean = when (v) {
            is SyncValue.Num -> v.value == 1.0
            is SyncValue.Bool -> v.value
            is SyncValue.Text -> v.value == "1"
            else -> false
        }
    }

    val device: String get() = state.device

    private fun shape(table: String): TableShape =
        shapes[table] ?: throw IllegalArgumentException("$table is not a synced table")

    override suspend fun loadState(): SyncState = state

    override suspend fun saveState(state: SyncState) {
        this.state = state
        writeState(db, state)
    }

    /**
     * Everything queued and not yet settled, oldest first.
     *
     * Sorted across tables and not only within them, so that a batch reads the
     * same way whichever table a row came from. The queue itself is per table;
     * the order rows are sent in is not.
     */
    /**
     * Put back the columns this schema cannot hold.
     *
     * Called on every path that turns rows into records for somebody else to
     * look at — the outgoing queue AND the lookup that decides whether the far
     * side already has a row. Both, and for the same reason: if only the queue
     * re-attached them, every row carrying a spill would compare as different
     * from the copy the far side holds and be re-sent on every single run, for
     * ever.
     */
    private suspend fun withSpill(records: List<ChangeRecord>): List<ChangeRecord> {
        if (records.isEmpty()) return records
        val q = Rows.spillRead(records.map { it.table to it.uid })
        val rows = db.select(q.sql, q.params)
        if (rows.isEmpty()) return records

        val byKey = mutableMapOf<String, Map<String, SyncValue>>()
        for (row in rows) {
            val tbl = (row["tbl"] as? SyncValue.Text)?.value ?: continue
            val uid = (row["uid"] as? SyncValue.Text)?.value ?: continue
            val cols = (row["cols"] as? SyncValue.Text)?.value ?: continue
            // Unreadable spill is dropped rather than thrown on. Losing a column
            // nobody here can read is the small failure; refusing to sync until
            // somebody edits a hidden table by hand is the large one.
            val parsed = runCatching { BatchCodec.decodeFields(cols) }.getOrNull() ?: continue
            byKey[tbl + "\u0000" + uid] = parsed
        }

        return records.map { r ->
            val extra = byKey[r.table + "\u0000" + r.uid]
            // The row's own columns win. The spill is what this schema could not
            // hold, so a name collision means the schema has since grown the
            // column and the live value is the true one.
            if (extra == null) r else r.copy(fields = extra + r.fields)
        }
    }

    override suspend fun pending(): List<ChangeRecord> {
        val out = mutableListOf<ChangeRecord>()
        for (t in SYNCED_TABLES) {
            val shape = shape(t)
            for (r in db.select(Rows.pending(shape).sql)) {
                out += Rows.recordFromRow(shape, r, state.device)
            }
        }
        return withSpill(out.sortedWith(compareBy({ it.updatedAt }, { it.uid })))
    }

    /**
     * These versions have been dealt with: either sent, or found already present
     * on the far side, which is a decision rather than a postponement.
     */
    /**
     * Every row back in the queue, which is what a snapshot is made of.
     *
     * The statements are `SyncMigrations.outboxReseed()`'s, not a second copy of
     * them. There is one list of synced tables that fills this queue and it
     * lives with the triggers; a second one here is the shape of every bug this
     * project has spent a month removing.
     */
    override suspend fun reseed() {
        for (m in SyncMigrations.outboxReseed()) db.execute(m.sql)
    }

    override suspend fun settle(records: List<ChangeRecord>) {
        for (r in records) {
            val q = Rows.settle(r.table, r.uid, r.updatedAt)
            db.execute(q.sql, q.params)
        }
    }

    override suspend fun lookup(keys: List<RowKey>): List<ChangeRecord> {
        val byTable = keys.groupBy({ it.table }, { it.uid })
        val out = mutableListOf<ChangeRecord>()
        for ((table, uids) in byTable) {
            val shape = shapes[table] ?: continue // a table this version does not have
            for (part in Rows.chunk(uids)) {
                val q = Rows.byUids(shape, part)
                for (r in db.select(q.sql, q.params)) {
                    out += Rows.recordFromRow(shape, r, state.device)
                }
            }
        }
        return withSpill(out)
    }

    override suspend fun apply(records: List<ChangeRecord>) {
        // Before anything is written, and before any failure can stop it. A
        // clock that only moves forward is safe to move early; the cost of
        // moving it and then writing nothing is one millisecond of nothing.
        // See SQL_SEEN.
        var seen = ""
        for (r in records) if (r.updatedAt > seen) seen = r.updatedAt
        if (seen.isNotEmpty()) db.execute(SQL_SEEN, listOf(SyncValue.Text(seen)))

        // WHY THIS DOES NOT UNQUEUE WHAT IT WRITES
        //
        // Every write here queues a row, because the outbox triggers cannot tell
        // an incoming row from a local edit — deliberately, since the one guard
        // that could would also skip softDelete, which sets updated_at itself.
        //
        // The obvious answer, unqueueing them here, is wrong, and the desktop's
        // vector generator caught it: a row that arrives while this device has
        // its own newer edit of the same row is merged, and the merged record
        // carries this device's version. Unqueueing by name and version then
        // removes the entry the local edit had made, and that edit is never
        // sent. The far side keeps the old name for ever, with nothing
        // reporting anything.
        //
        // Nothing is needed instead. The push half runs after this, sees these
        // rows as pending, and drops the ones the far side already holds at that
        // exact version — which is every row that arrived unchanged, and none of
        // the ones merging produced. Then it settles the lot.
        //
        // Deletions first, inside one pass. A delete releases a natural key and
        // a create claims one, so doing them the other way round makes the
        // create look like a duplicate of the row it was meant to replace.
        for (r in Rows.deletionsFirst(records)) {
            val shape = shapes[r.table] ?: continue // a table this version does not have

            // A column this table does not have is kept beside the row rather
            // than dropped, so that sending the row back does not strip it.
            // Written before the row itself, so that a run dying between the two
            // leaves a spill for the row at its previous version — which the
            // next apply overwrites — rather than a row whose extra columns are
            // gone for good. See Rows.spillRead.
            val extra = Rows.unknownFields(shape, r)
            val spill = if (r.deleted || extra.isEmpty()) {
                Rows.spillDrop(r.table, r.uid)
            } else {
                Rows.spillWrite(r.table, r.uid, BatchCodec.encodeFields(extra))
            }
            db.execute(spill.sql, spill.params)

            // A row arriving with a natural key another row already holds has to
            // be settled before the insert, because SQLite will otherwise refuse
            // the statement, apply throws, the cursor never advances, and sync
            // stays broken until somebody deletes a budget by hand to make their
            // phone work.
            var incoming = r
            if (shape.naturalKeys.isNotEmpty() && !r.deleted) {
                val other = findClash(shape, r)
                if (other != null) {
                    incoming = if (Rows.incomingLosesClash(r, other)) {
                        // Kept, but as a tombstone, so nothing is dropped on the
                        // floor and the other device reaches the same conclusion
                        // from the same pair of uids without either saying
                        // anything.
                        r.copy(deleted = true, updatedAt = now())
                    } else {
                        write(
                            shape,
                            Rows.recordFromRow(shape, other, state.device)
                                .copy(deleted = true, updatedAt = now()),
                        )
                        r
                    }
                }
            }

            write(shape, incoming)
        }
    }

    /** The live row, if any, holding one of this record's natural keys. */
    private suspend fun findClash(shape: TableShape, r: ChangeRecord): DbRow? {
        for (key in shape.naturalKeys) {
            // A key with a NULL in it constrains nothing, and the query for it
            // is actively harmful: `slip_ref IS ?` with null bound becomes
            // `IS NULL`, which returns every slip-less expense in the table.
            // See Rows.keyIsHeld.
            if (!Rows.keyIsHeld(key, r.fields)) continue
            val where = key.joinToString(" AND ") { "$it IS ?" }
            val params = key.map { r.fields[it] ?: SyncValue.Null }
            val found = db.select(
                "SELECT * FROM ${shape.name} WHERE $where" +
                        if (shape.hasDeleted) " AND deleted = 0" else "",
                params,
            )
            for (other in found) if (Rows.naturalKeyClash(shape, r, other)) return other
        }
        return null
    }

    /**
     * Every write goes through here, so the rule that a tombstone stops holding
     * its natural key is applied in one place and cannot be forgotten by one of
     * the callers.
     */
    private suspend fun write(shape: TableShape, r: ChangeRecord) {
        val s = Rows.upsert(shape, Rows.freeNaturalKeys(shape, r))
        db.execute(s.sql, s.params)
    }

    /**
     * The delete path the app should call instead of DELETE, for any table that
     * syncs. Here rather than left to each caller because a hard delete leaves
     * nothing to send, and a row nobody can see deleted is a row the other
     * device pushes back.
     */
    suspend fun softDelete(table: String, uid: String) {
        val shape = shape(table)
        val found = db.select(
            "SELECT * FROM ${shape.name} WHERE uid = ?",
            listOf(SyncValue.Text(uid)),
        )
        if (found.isEmpty()) return
        write(
            shape,
            Rows.recordFromRow(shape, found[0], state.device)
                .copy(deleted = true, updatedAt = now()),
        )
    }
}