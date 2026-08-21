package app.reup.sync

// ─── Merge.kt — deciding which version of a row survives ─────────────────────
//
// Line-for-line mirror of src/lib/sync/merge.ts. Read the long version there;
// this file repeats only what a reader needs to not break it.
//
// It is the one function in the sync engine that has to be exactly right and
// the only one that can be proved so, because it does no I/O: two records in,
// one record out. Everything around it — HTTP, OAuth, encryption, SQLite — can
// be tested by running it. This cannot, because its failures are silent. A
// merge that is subtly wrong does not throw; it quietly resurrects a deleted
// row, or drops an edit, on one device, weeks later, with nothing on screen to
// say so.
//
// THE THREE PROPERTIES
//
//   idempotent    merge(a, a) = a
//   commutative   merge(a, b) = merge(b, a)
//   associative   merge(merge(a,b),c) = merge(a,merge(b,c))
//
// Devices do not see changes in the same order — a phone that was off for a
// week reads Tuesday's edit before Monday's — so the result must not depend on
// order at all. Together these three mean any two devices holding the same SET
// of changes hold identical rows, whatever route they took.
//
// TWO REGISTERS, NOT ONE
//
//   BASE        everything except the completion fields. Last writer wins.
//   COMPLETION  the tick and its two dependants. Most recently touched wins.
//
// They are merged separately and never consult each other. The first draft let
// the loser's completion override the winner's row, which produces a HYBRID
// that existed on neither device; feed that into the next merge and it is
// compared on content it never had, so (a·b)·c and a·(b·c) drift apart. In
// practice that is two devices that quietly stop agreeing, forever, with no
// error anywhere. The property suite caught it twice before the split was made
// structural.
//
// IF YOU CHANGE ANYTHING HERE
//
// The desktop generates sync-vectors.json from its own implementation, and
// MergeVectorsTest replays every case through this one. A change that is not
// mirrored on both sides turns that suite red, which is the entire point.

object Merge {

    /** Fields that move together, chosen by whichever side is further along in [by]. */
    private data class FieldGroup(val by: String, val fields: List<String>)

    private val GROUPS: Map<String, List<FieldGroup>> = mapOf(
        // Ticking a task done is the one write that genuinely happens on both
        // devices, because that is what the notification is for. Under plain
        // LWW it fails in the worst direction: tick it done on the phone, the
        // laptop's older row wins, and the task comes back undone.
        //
        // It cannot be one field. missed_streak resets to 0 on completion and
        // cycle_checked_until moves with it, so choosing each independently can
        // produce a row that existed nowhere — completed, yet carrying the
        // missed streak from before it was completed.
        "tasks" to listOf(
            FieldGroup(
                // Decided by WHEN the tick was last touched, not by how far it
                // reaches. "Furthest along" protected the tick and made undoing
                // one impossible: clearing it writes a value behind the old one
                // by definition, so the other device's copy won every time and
                // came back. Ticking and un-ticking are both touches, so both
                // travel. Rows from before this column tie on null and fall
                // through to completed_until, which is the old rule still
                // running for anything nobody has touched since.
                by = "completed_at",
                fields = listOf(
                    "completed_at", "completed_until", "cycle_checked_until", "missed_streak",
                ),
            ),
        ),
    )

    private fun groupsFor(table: String): List<FieldGroup> = GROUPS[table] ?: emptyList()

    private fun groupedKeys(table: String): Set<String> =
        groupsFor(table).flatMap { it.fields }.toSet()

    // ── ordering primitives ─────────────────────────────────────────────────

    /** Only has to be identical to the TypeScript ranking, not meaningful. */
    private fun typeRank(v: SyncValue): Int = when (v) {
        is SyncValue.Null -> 0
        is SyncValue.Bool -> 1
        is SyncValue.Num -> 2
        is SyncValue.Text -> 3
    }

    /** Null sorts before everything: "never completed" is behind any completion. */
    private fun valueGreater(a: SyncValue, b: SyncValue): Boolean {
        val ar = typeRank(a)
        val br = typeRank(b)
        if (ar != br) return ar > br
        return when {
            a is SyncValue.Null -> false
            a is SyncValue.Bool && b is SyncValue.Bool -> a.value && !b.value
            a is SyncValue.Num && b is SyncValue.Num -> a.value > b.value
            a is SyncValue.Text && b is SyncValue.Text -> a.value > b.value
            else -> false
        }
    }

    private fun valueEqual(a: SyncValue, b: SyncValue): Boolean =
        !valueGreater(a, b) && !valueGreater(b, a)

    private fun get(fields: Map<String, SyncValue>, key: String): SyncValue =
        fields[key] ?: SyncValue.Null

    /**
     * Deterministic order over two field maps, key by key, skipping [skip].
     *
     * Compared as values rather than as serialised JSON on purpose. Two
     * languages do not agree on how to print a float — 1200 versus 1200.0 — so
     * comparing strings would make Kotlin and TypeScript pick different winners
     * for rows that are otherwise identical, which is exactly the disagreement
     * this ordering exists to rule out.
     */
    private fun fieldsGreater(
        a: Map<String, SyncValue>,
        b: Map<String, SyncValue>,
        skip: Set<String>,
    ): Boolean {
        val keys = (a.keys + b.keys).filterNot { it in skip }.sorted()
        for (k in keys) {
            val av = get(a, k)
            val bv = get(b, k)
            if (valueEqual(av, bv)) continue
            return valueGreater(av, bv)
        }
        return false
    }

    /**
     * Total order over the BASE of a row. True when [a] wins.
     *
     * Deliberately TOTAL, not "is a newer". A partial order lets two devices
     * disagree about which version wins and stay that way — both believing they
     * are in sync while holding different rows.
     *
     * The content tier exists because two records with the same timestamp AND
     * the same origin but different contents made merge non-commutative: neither
     * side won, so the answer depended on argument position. That happens
     * whenever one device writes a row twice inside a millisecond, which is not
     * rare over months, just rare enough never to be found by hand.
     *
     * Comparing contents is arbitrary as a choice of winner. It does not need to
     * be a good choice; it needs to be the SAME choice everywhere, every time.
     */
    private fun baseGreater(a: ChangeRecord, b: ChangeRecord): Boolean {
        if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt
        if (a.origin != b.origin) return a.origin > b.origin
        if (a.deleted != b.deleted) return a.deleted
        return fieldsGreater(a.fields, b.fields, groupedKeys(a.table))
    }

    /**
     * Total order over one field group, using nothing but the group's own values.
     *
     * [FieldGroup.by] decides it — that is the meaning of the group. The rest
     * only break a tie, in sorted order so both languages walk them the same
     * way. If every field matches the two groups are identical and the answer
     * does not matter.
     *
     * An earlier version broke the tie by falling back to the base order, which
     * looks harmless and is not: the merged row keeps the base winner's
     * identity, so where its completion came from is no longer recoverable.
     */
    private fun groupGreater(a: ChangeRecord, b: ChangeRecord, g: FieldGroup): Boolean {
        val av = get(a.fields, g.by)
        val bv = get(b.fields, g.by)
        if (!valueEqual(av, bv)) return valueGreater(av, bv)
        for (f in g.fields.sorted()) {
            if (f == g.by) continue
            val x = get(a.fields, f)
            val y = get(b.fields, f)
            if (!valueEqual(x, y)) return valueGreater(x, y)
        }
        return false
    }

    // ── the merge ───────────────────────────────────────────────────────────

    fun sameRow(a: ChangeRecord, b: ChangeRecord): Boolean =
        a.table == b.table && a.uid == b.uid

    fun merge(a: ChangeRecord, b: ChangeRecord): ChangeRecord {
        require(sameRow(a, b)) {
            "merge across rows: ${a.table}/${a.uid} vs ${b.table}/${b.uid}"
        }

        val base = if (baseGreater(a, b)) a else b
        val grouped = groupedKeys(a.table)

        val fields = LinkedHashMap<String, SyncValue>()
        for ((k, v) in base.fields) if (k !in grouped) fields[k] = v

        // Each group is its own register, ordered ONLY by its own contents.
        // Note this reads `a` and `b`, never `base` — the two registers must
        // not consult each other.
        for (g in groupsFor(a.table)) {
            val from = if (groupGreater(a, b, g)) a else b
            // Only what the winner actually carries. Writing every field of the
            // group unconditionally invents keys: a group naming a column that
            // no row in the batch has would gain it as an explicit null, and
            // merge(a, a) would stop returning a.
            for (f in g.fields) if (from.fields.containsKey(f)) fields[f] = from.fields.getValue(f)
        }

        return ChangeRecord(
            table = base.table,
            uid = base.uid,
            updatedAt = base.updatedAt,
            deleted = base.deleted,
            origin = base.origin,
            fields = fields,
        )
    }

    /**
     * Fold a stream of changes into one row per uid.
     *
     * The whole apply path is this plus a write, which is why the properties
     * above are enough: if merge converges, so does everything built on it.
     */
    fun mergeAll(records: Iterable<ChangeRecord>): Map<String, ChangeRecord> {
        val out = LinkedHashMap<String, ChangeRecord>()
        for (r in records) {
            val key = "${r.table}\u0000${r.uid}"
            val prev = out[key]
            out[key] = if (prev == null) r else merge(prev, r)
        }
        return out
    }
}