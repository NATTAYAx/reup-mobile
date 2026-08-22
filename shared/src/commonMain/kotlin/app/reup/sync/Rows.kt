package app.reup.sync

// ─── Rows.kt — the only file that knows a row is not a record ────────────────
//
// Mirror of src/lib/sync/rows.ts, and checked against it by store-vectors.json
// rather than by anyone remembering.
//
// The engine deals in ChangeRecords: a table name, a uid, a timestamp, a
// deletion flag and a bag of fields. The database deals in rows, which have a
// local integer id, columns in a fixed order, and a schema that is not the same
// on every device. Something has to sit between them, and this is it.
//
// Everything here is pure and takes the table's shape as an argument, because
// the shape is read from the database at run time with PRAGMA rather than
// written down here. A hardcoded column list is a second copy of the schema,
// and a second copy of the schema is the thing this project keeps removing.
//
// It also buys the version-skew case for nothing. A device running last month's
// schema simply has fewer columns, so a record carrying a column it has never
// heard of loses that column instead of throwing "no such column" and wedging
// sync forever. That loss is real and is written down at the bottom.

/** One statement and its bound values, ready for whatever driver runs it. */
data class Statement(
    val sql: String,
    val params: List<SyncValue> = emptyList(),
)

data class TableShape(
    val name: String,
    /** Every column this database actually has, in declaration order. */
    val columns: List<String>,
    /** Declared type per column, as SQLite reports it. Used only by freeNaturalKeys. */
    val types: Map<String, String>,
    /** False on an older database that has not run the tombstone migration. */
    val hasDeleted: Boolean,
    /**
     * Unique constraints other than uid, as column lists.
     *
     * budgets has UNIQUE(category, month) and expense_categories has
     * UNIQUE(key). Those are the reason this field exists.
     */
    val naturalKeys: List<List<String>>,
)

object Rows {

    /**
     * Columns that belong to sync rather than to the user.
     *
     * `id` is the important one. It is the local autoincrement key and is a
     * different number on every device for the same row, so sending it would
     * mean two devices arguing about a value that has no meaning outside the
     * machine that generated it.
     */
    private val NOT_PAYLOAD = setOf("id", "uid", "updated_at", "deleted")

    fun payloadColumns(shape: TableShape): List<String> =
        shape.columns.filter { it !in NOT_PAYLOAD }

    // ── row to record ───────────────────────────────────────────────────────

    /**
     * SQLite has no boolean, so a flag is an integer, and a driver may hand it
     * back as a number, a string, or already as a boolean. Anything not clearly
     * on is treated as off, because guessing "deleted" wrong in the other
     * direction removes a row nobody removed.
     */
    private fun truthy(v: SyncValue?): Boolean = when (v) {
        is SyncValue.Num -> v.value == 1.0
        is SyncValue.Bool -> v.value
        is SyncValue.Text -> v.value == "1"
        else -> false
    }

    private fun text(v: SyncValue?): String = when (v) {
        is SyncValue.Text -> v.value
        is SyncValue.Num -> if (v.value == v.value.toLong().toDouble()) v.value.toLong().toString()
        else v.value.toString()
        is SyncValue.Bool -> v.value.toString()
        else -> ""
    }

    fun recordFromRow(
        shape: TableShape,
        row: Map<String, SyncValue>,
        origin: String,
    ): ChangeRecord {
        val fields = LinkedHashMap<String, SyncValue>()
        for (c in payloadColumns(shape)) fields[c] = row[c] ?: SyncValue.Null
        return ChangeRecord(
            table = shape.name,
            uid = text(row["uid"]),
            updatedAt = text(row["updated_at"]),
            deleted = shape.hasDeleted && truthy(row["deleted"]),
            origin = origin,
            fields = fields,
        )
    }

    // ── record to statement ─────────────────────────────────────────────────

    /**
     * Write a record, whether or not the row is already here.
     *
     * The conflict target is uid, which works because the migrations create a
     * unique index on it. The local `id` is never mentioned, so an existing row
     * keeps the one it has and a new row gets a fresh one — which is the whole
     * point of not sending ids.
     *
     * Setting updated_at explicitly is what keeps the timestamp the sender's
     * rather than this machine's. The update trigger's guard is
     * `WHEN NEW.updated_at IS OLD.updated_at`, so a statement that changes it is
     * left alone, and the insert trigger's guard is `WHEN NEW.uid IS NULL`,
     * which this never is. Both triggers sit out an incoming sync, which is
     * exactly what they were written for.
     */
    fun upsert(shape: TableShape, r: ChangeRecord): Statement {
        val known = shape.columns.toSet()
        val cols = mutableListOf("uid", "updated_at")
        val params = mutableListOf<SyncValue>(
            SyncValue.Text(r.uid),
            SyncValue.Text(r.updatedAt),
        )

        if (shape.hasDeleted) {
            cols += "deleted"
            params += SyncValue.Num(if (r.deleted) 1.0 else 0.0)
        }
        for (c in payloadColumns(shape)) {
            if (c !in known) continue
            cols += c
            params += r.fields[c] ?: SyncValue.Null
        }

        val holes = cols.joinToString(", ") { "?" }
        val sets = cols.filter { it != "uid" }.joinToString(", ") { "$it = excluded.$it" }

        return Statement(
            sql = "INSERT INTO ${shape.name} (${cols.joinToString(", ")}) VALUES ($holes) " +
                    "ON CONFLICT(uid) DO UPDATE SET $sets",
            params = params,
        )
    }

    /**
     * Everything this device has not sent yet, from one table.
     *
     * The join is the whole change. The old version of this asked which rows
     * have a timestamp above a watermark, which is a question about this device
     * answered with a number that other devices also write into: rows pulled
     * from the desktop carry the desktop's clock, the watermark was set to the
     * newest timestamp the run looked at, and a desktop an hour ahead therefore
     * pushed this device's watermark an hour into the future. Every local edit
     * made in that hour is stamped by this device's own clock, lands below the
     * watermark, and is never sent. No error, no retry, and the two databases
     * quietly stop agreeing.
     *
     * A row in a table cannot be contaminated by anyone else's clock.
     */
    fun pending(shape: TableShape): Statement = Statement(
        "SELECT r.* FROM ${shape.name} r " +
                "JOIN sync_outbox o ON o.tbl = '${shape.name}' AND o.uid = r.uid " +
                "ORDER BY r.updated_at ASC, r.uid ASC",
    )

    /**
     * One row is no longer pending — but only at the version that was dealt
     * with.
     *
     * updated_at is in the WHERE for the reason the whole table exists. A row
     * edited while the upload was in flight has already had its outbox entry
     * replaced by the trigger, so this delete does not match it and it stays
     * queued. Clearing by name alone would drop that edit on the floor,
     * silently, and only ever on a slow connection.
     */
    fun settle(table: String, uid: String, updatedAt: String): Statement = Statement(
        sql = "DELETE FROM sync_outbox WHERE tbl = ? AND uid = ? AND updated_at = ?",
        params = listOf(SyncValue.Text(table), SyncValue.Text(uid), SyncValue.Text(updatedAt)),
    )

    /**
     * SQLite refuses a statement with more than 999 bound parameters by default,
     * and a first sync looks up thousands of rows at once. Splitting here rather
     * than at the call site means every caller is safe without remembering to be.
     */
    fun <T> chunk(items: List<T>, size: Int = 400): List<List<T>> =
        if (items.isEmpty()) emptyList() else items.chunked(size)

    fun byUids(shape: TableShape, uids: List<String>): Statement = Statement(
        sql = "SELECT * FROM ${shape.name} WHERE uid IN (${uids.joinToString(", ") { "?" }})",
        params = uids.map { SyncValue.Text(it) },
    )

    // ── two devices inventing the same row ──────────────────────────────────

    /**
     * A budget for food in August, created on the desktop and again on the phone
     * before they had ever talked, is two rows with two uids and one natural
     * key. `UNIQUE(category, month)` then refuses the insert, apply throws, the
     * cursor never advances, and sync is stuck permanently rather than briefly.
     *
     * The rule is: the smaller uid keeps the natural key, the other becomes a
     * tombstone. Both devices can see both uids, so both reach the same answer
     * without talking — the same property the merge rules are built on. Smaller
     * rather than newer because timestamps can tie and uids cannot.
     */
    /**
     * A key nobody is holding.
     *
     * SQLite does not constrain rows that carry a NULL in a unique index: two
     * expenses with no slip both satisfy `UNIQUE(slip_ref)` and always have. So
     * a record with a NULL anywhere in a key cannot collide with anything, and
     * asking whether it does is not a cheap extra check — it is a question with
     * the wrong answer built in.
     *
     * Getting this wrong cost a real row. findClash asked `slip_ref IS ?` with
     * a null bound, which is `IS NULL`, which matches every expense that has no
     * slip — so the store decided that every slip-less expense was the same
     * expense, kept the one whose uuid sorted first, and filed the rest as
     * tombstones. The tombstones then synced, and the other device deleted its
     * copies too. Nothing errored; the rows were simply gone, and which ones
     * survived was decided by random uuids.
     *
     * The rule is the database's own rule, written where the store can see it.
     */
    fun keyIsHeld(key: List<String>, fields: Map<String, SyncValue>): Boolean =
        key.all { c -> (fields[c] ?: SyncValue.Null) != SyncValue.Null }

    fun naturalKeyClash(
        shape: TableShape,
        incoming: ChangeRecord,
        existing: Map<String, SyncValue>,
    ): Boolean {
        val existingUid = text(existing["uid"])
        if (existingUid.isEmpty() || existingUid == incoming.uid) return false
        // Compared strictly rather than by printing both sides. A text column is
        // text on both sides and an integer column is a number on both sides, so
        // there is nothing for a loose comparison to rescue — and printing is
        // exactly where the two languages disagree.
        return shape.naturalKeys.any { key ->
            // Both sides, not just the incoming one: a live row with a NULL in
            // the key is not occupying it either, so there is nothing to take.
            keyIsHeld(key, incoming.fields) &&
                    keyIsHeld(key, existing) &&
                    key.all { c ->
                        Engine.valueEqual(
                            incoming.fields[c] ?: SyncValue.Null,
                            existing[c] ?: SyncValue.Null,
                        )
                    }
        }
    }

    fun incomingLosesClash(incoming: ChangeRecord, existing: Map<String, SyncValue>): Boolean =
        incoming.uid > text(existing["uid"])

    /**
     * Deletions first, then everything else.
     *
     * A delete releases a natural key and a create consumes one, so doing
     * creates first can only fail. The case is not exotic: delete the food
     * budget for August, add it back with a different number, sync. Both rows
     * travel in the same batch, and if the new one is written before the
     * tombstone it collides with the row the tombstone is about to remove — a
     * row the other device still believes is live.
     *
     * What made that expensive to find is that it did not fail loudly. The clash
     * rule fired, decided the incoming row was a duplicate of a live one, and
     * filed the new budget as a tombstone. Which uuid happened to sort first
     * decided whether the afternoon ended with the right budget or with none.
     *
     * The order is stable within each group, so two devices given the same batch
     * write the same rows in the same sequence.
     */
    fun deletionsFirst(records: List<ChangeRecord>): List<ChangeRecord> =
        records.filter { it.deleted } + records.filter { !it.deleted }

    /**
     * A deleted row has to stop holding its natural key.
     *
     * `UNIQUE(category, month)` does not care whether a row is a tombstone, so a
     * deleted budget for food in August still blocks a new one — and the failure
     * is not a warning, it is an insert that throws and a sync that stops.
     * Deleting a budget and adding it back is an ordinary afternoon.
     *
     * The replacement is derived from the uid, which matters more than it looks.
     * Both devices compute the same filler without talking, so the tombstone one
     * writes is identical to the one the other would have written. A random
     * value or a timestamp would differ, each device would think the other had
     * edited the row, and they would push the same tombstone back and forth
     * forever.
     *
     * Only text columns are rewritten. Every natural key in this schema is text,
     * and a numeric one has no value that is both in range and certain to be
     * unique — if one ever appears it needs an answer of its own rather than a
     * guess made here.
     */
    fun freeNaturalKeys(shape: TableShape, r: ChangeRecord): ChangeRecord {
        if (!r.deleted || shape.naturalKeys.isEmpty()) return r
        val fields = LinkedHashMap(r.fields)
        for (key in shape.naturalKeys) {
            // A key with a NULL in it was never held, so there is nothing to
            // release. Stamping a value in would be worse than pointless: it
            // would move the row into an index the live row was never in.
            if (!keyIsHeld(key, r.fields)) continue
            for (c in key) {
                val type = (shape.types[c] ?: "").uppercase()
                if (!type.contains("CHAR") && !type.contains("TEXT") && !type.contains("CLOB")) continue
                fields[c] = SyncValue.Text("deleted:${r.uid}")
            }
        }
        return r.copy(fields = fields)
    }

    // ─── the columns this schema has never heard of ─────────────────────────
    //
    // A field the local table has no column for used to be dropped, and the
    // loss was named out loud but not prevented. That was defensible while only
    // one device could originate a row: the older device never pushed, so it
    // could never push a row with the column missing.
    //
    // This round removed that. The phone writes now, which means the older of
    // two devices can take a row in, drop a column it does not understand, tick
    // it done, and send the row back without it. The newer device then loses a
    // value neither person ever touched, and nothing anywhere says so.
    //
    // So the fields are kept, in a table beside the row rather than in it, and
    // put back when the row is read for sending.

    fun spillRead(pairs: List<Pair<String, String>>): Statement {
        val holes = pairs.joinToString(", ") { "(?, ?)" }
        val params = mutableListOf<SyncValue>()
        for ((table, uid) in pairs) {
            params += SyncValue.Text(table)
            params += SyncValue.Text(uid)
        }
        return Statement("SELECT tbl, uid, cols FROM sync_spill WHERE (tbl, uid) IN ($holes)", params)
    }

    fun spillWrite(table: String, uid: String, cols: String): Statement = Statement(
        sql = "INSERT INTO sync_spill (tbl, uid, cols) VALUES (?, ?, ?) " +
                "ON CONFLICT(tbl, uid) DO UPDATE SET cols = excluded.cols",
        params = listOf(SyncValue.Text(table), SyncValue.Text(uid), SyncValue.Text(cols)),
    )

    /**
     * No spill for this row any more.
     *
     * Run when a row arrives carrying nothing this schema cannot read, and when
     * it arrives as a tombstone. A tombstone has no payload, so keeping a set of
     * columns beside it would mean re-attaching values to a deleted row on the
     * way out — which is how a deleted row grows a body again.
     */
    fun spillDrop(table: String, uid: String): Statement = Statement(
        sql = "DELETE FROM sync_spill WHERE tbl = ? AND uid = ?",
        params = listOf(SyncValue.Text(table), SyncValue.Text(uid)),
    )

    /**
     * The fields a record carries that this database has no column for.
     *
     * Sorted, because the result is written to a row that the other device
     * reads back and compares: two devices that list the same unknown columns in
     * a different order would disagree about whether anything changed.
     *
     * Sorted this way rather than with toSortedMap, which is JVM-only and does
     * not exist for iOS. The ordering is identical — both are the natural order
     * of the keys — and the keys here are column names, so nothing rests on how
     * two locales would rank the same pair of letters.
     */
    fun unknownFields(shape: TableShape, r: ChangeRecord): Map<String, SyncValue> {
        val known = shape.columns.toSet()
        val unknown = r.fields.filterKeys { it !in known }
        return unknown.keys.sorted().associateWith { unknown.getValue(it) }
    }
}