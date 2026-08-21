package app.reup.sync

// ─── Engine.kt — the part that actually moves rows between devices ───────────
//
// Mirror of src/lib/sync/engine.ts. The four layers below this one each answer
// a question in isolation. Crypto answers "can anyone else read this", Merge
// answers "which version wins", Protocol answers "what is a batch called",
// Storage answers "where do bytes go". None of them knows what a sync is. This
// is where they meet, and it is the only file in the sync stack that can lose
// data.
//
// So nearly all of it is pure functions whose names start with `plan`, and the
// suspending part at the bottom is a list of calls with no decisions in it.
// That split is not tidiness. A decision inside a suspend function that also
// does I/O can only be tested by standing up a server; the same decision as a
// function of its inputs is a line in a vector file that both languages have to
// reproduce. Everything above `sync()` runs without a network, a clock or a
// disk.
//
// ─── THE ORDER OF OPERATIONS, WHICH IS THE WHOLE DESIGN ─────────────────────
//
//   list → fetch → decrypt → decode → fold → apply → SAVE CURSOR
//                                                  → collect → seal → put
//                                                  → SAVE SEQ AND WATERMARK
//
// Two rules hold that shape together, and both exist because of a specific way
// to lose a row silently.
//
//   The cursor moves only after the write succeeded. A batch that downloaded
//   and then failed to apply must not be marked as seen, or it is skipped
//   forever and the rows it carried are missing on this device only, with
//   nothing on screen and nothing in any log.
//
//   The cursor moves only through an unbroken prefix. If file 3 fails and file
//   4 succeeds, recording 4 buries 3 permanently, because the next run asks for
//   everything above 4. So each device's cursor stops at its first failure.
//
//   Note what that second rule does NOT say. Rows from file 4 are still applied
//   even though file 3 is missing, because order does not change the answer —
//   that is exactly what the properties in Merge buy. Holding them back would
//   cost a round trip and gain nothing.
//
// ─── WHY THE SEQUENCE NUMBER IS RESERVED BEFORE THE FILE IS WRITTEN ─────────
//
// The append-only guarantee is that no name is written twice. If seq were saved
// after a successful upload, a crash in between would leave the next run
// writing the same name with different contents — and on a backend that
// overwrites silently, which is all of them, the other device may already have
// read the first version. Two devices then disagree forever about file 7.
//
// Reserving first can only skip a number, and a gap costs nothing: the cursor
// asks for "greater than", never for "the next one".
//
// ─── WHAT AN ECHO IS ────────────────────────────────────────────────────────
//
// A row pulled from the desktop is written here with the desktop's timestamp,
// which is newer than this device's watermark. Next time the phone asks "what
// have I changed", it finds that row and sends it straight back. Nothing breaks
// — merge is idempotent — but the log doubles on every sync, forever.
//
// A row is therefore not worth pushing if the remote log already holds that
// exact version, which the engine knows because it has just folded every batch
// it read.
//
// The comparison ignores `origin` deliberately. A row that arrived from the
// desktop and was written locally comes back out of the database claiming this
// device as its origin, because the local tables do not store where a row came
// from. If origin were compared, every pulled row would look different and the
// echo would be back.
//
// ─── HOW A DEVICE KNOWS WHAT IT HAS NOT SENT ───────────────────────────
//
// From a table, not from a clock. `pending()` reads `sync_outbox`, which a
// trigger fills on every write. The version that asked `updated_at > watermark`
// is gone: rows pulled from the desktop carry the desktop's clock, the watermark
// rose to meet them, and every local edit made in the meantime sorted below it
// and was never sent — no error, no retry, two databases quietly disagreeing.
//
// ─── WHY THE LOG DOES NOT GROW FOR EVER, AND WHY AGE IS THE WRONG AXIS ────
//
// The folder is append-only, so without something else it grows until the
// account fills. The obvious rule is "delete what is older than a month", and it
// loses rows.
//
// A device that has been in a drawer for six weeks holds a cursor pointing into
// the deleted range. It does not error — `filesToFetch` asks for "greater than",
// so it reads what is left and moves on, having silently skipped every change
// announced in the files that are gone. Those rows are still in the other
// device's database, but the outbox only holds what has changed since the last
// sync, so nothing will ever send them again.
//
// Age is a proxy for the question that matters, which is "could anyone still
// need this file". A snapshot answers that question exactly.
//
// Every so often a device sends its whole database instead of just its queue.
// On the wire that is an ordinary batch — no flag, no version bump, nothing for
// a reader to understand — it is simply large. The device writing it is the only
// one that needs to know, and it remembers two numbers: the sequence of its most
// recent snapshot, and the one before that.
//
// The rule is then one line: a device may delete its own files below its PRIOR
// snapshot. Whatever a reader's cursor says, one of two things is true. Either
// it is at or above that snapshot, in which case nothing it still needed was
// touched; or it is below, in which case it will read that snapshot — a complete
// copy of the writer's database — and every file after it. Complete either way,
// with no coordination, no published cursors, and no detection.
//
// Prior rather than newest is one snapshot of slack, kept on purpose: if the
// newest turns out to be truncated, the one before it plus the deltas still
// reconstructs the same database.
//
// The trigger is the number of files this device has in the folder, not their
// age. File count is the thing being bounded, so it is the thing to measure.
// Reaching the threshold makes the next push a snapshot whether or not there was
// anything to say, because refilling the queue is what gives it something to
// say — which is what lets a folder that has stopped being written to still
// finish cleaning itself up.
//
// A device retired without being told is the one case this does not cover:
// nobody deletes its files, because only it ever could. That is a folder that
// stops growing rather than one that shrinks, and it is the right cost for never
// having to decide on one device that a file belonging to another is safe to
// remove.

/** Everything this device remembers about syncing, and nothing else. */
data class SyncState(
    /** Stable per installation. Minted once, never reused, never shared. */
    val device: String,
    /** Highest sequence number this device has reserved. Zero means none yet. */
    val seq: Long,
    /** Highest sequence seen from every other device. */
    val cursor: Map<String, Long>,
    /** Seq of the newest full snapshot this device wrote. Zero means none yet. */
    val snapshotSeq: Long = 0L,
    /**
     * Seq of the snapshot before that. The deletion line: this device's own
     * files below it cannot be the only copy of anything, for any reader, ever.
     */
    val priorSnapshotSeq: Long = 0L,
)

data class RowKey(val table: String, val uid: String)

/**
 * The database, as the engine is willing to know it.
 *
 * Five methods, none of which mentions a column name. The store turns rows into
 * records and back; the engine decides which records survive. Keeping the line
 * there is what lets the phone and the desktop share every decision while
 * having nothing in common below it.
 */
interface LocalStore {
    /** Rows not sent yet, oldest first. `origin` may be this device. */
    suspend fun pending(): List<ChangeRecord>

    /** These versions are dealt with and must not come back as pending. */
    suspend fun settle(records: List<ChangeRecord>)

    /** The local version of each named row, where one exists. Order is free. */
    suspend fun lookup(keys: List<RowKey>): List<ChangeRecord>

    /**
     * Write these rows, keeping `updatedAt` exactly as given, in one transaction.
     *
     * Exactly as given is not a detail. If the store lets its own trigger stamp
     * the row with the local clock, the row instantly looks newer than the copy
     * every other device holds, and the next sync pushes it back out as an edit
     * nobody made.
     */
    suspend fun apply(records: List<ChangeRecord>)

    /**
     * Queue every row there is, so the next push is the whole database.
     *
     * Deliberately not a separate `all()` returning records. A second way to
     * enumerate rows is a second place for the spill columns, the tombstones and
     * the ordering to be got subtly differently from [pending], and a snapshot
     * that disagrees with a delta about what a row looks like is the one thing
     * this file exists to prevent. Refilling the queue means a snapshot travels
     * down the same path every other batch does.
     */
    suspend fun reseed()

    suspend fun loadState(): SyncState
    suspend fun saveState(state: SyncState)
}

data class SkippedFile(val name: String, val reason: String)

data class SyncReport(
    val read: Int,
    val applied: Int,
    val pushed: Int,
    /** Files that could not be used. Never fatal; always reported. */
    val skipped: List<SkippedFile>,
    val wrote: String?,
    /** Whether what was written was the whole database rather than the queue. */
    val snapshot: Boolean = false,
    /** Own files removed from the folder this run. */
    val pruned: Int = 0,
)

object Engine {

    /**
     * How many of this device's own files may sit in the folder before the next
     * push is sent as a snapshot instead of a queue.
     */
    const val SNAPSHOT_AFTER_FILES = 64

    /**
     * How many files one run may delete.
     *
     * A first cleanup could otherwise be hundreds of round trips in the middle
     * of a sync somebody is watching, on a phone, on mobile data. Nothing is
     * lost by going slowly: what is left over is still below the line next time.
     */
    const val PRUNE_PER_RUN = 32

    fun emptyState(device: String): SyncState =
        SyncState(device = device, seq = 0L, cursor = emptyMap())

    fun rowKey(table: String, uid: String): String = "$table\u0000$uid"

    fun rowKey(r: ChangeRecord): String = rowKey(r.table, r.uid)

    // ── comparing versions ──────────────────────────────────────────────────

    /**
     * Equality on one field value, typed rather than printed.
     *
     * The tempting version compares the two values as strings, which reads as
     * forgiving and is a trap: Kotlin prints 1200.0 where JavaScript prints
     * 1200, so the two implementations would disagree about whether a row had
     * changed — the same disagreement the ordering in Merge already avoids for
     * the same reason.
     *
     * Numbers are compared as numbers rather than by structural equality on the
     * wrapper, so the two zeros of floating point do not count as a change
     * nobody made.
     */
    fun valueEqual(a: SyncValue, b: SyncValue): Boolean = when {
        a is SyncValue.Null && b is SyncValue.Null -> true
        a is SyncValue.Null || b is SyncValue.Null -> false
        a is SyncValue.Bool && b is SyncValue.Bool -> a.value == b.value
        a is SyncValue.Num && b is SyncValue.Num -> a.value == b.value
        a is SyncValue.Text && b is SyncValue.Text -> a.value == b.value
        else -> false
    }

    private fun fieldsEqual(a: Map<String, SyncValue>, b: Map<String, SyncValue>): Boolean {
        for (k in a.keys + b.keys) {
            val av = a[k] ?: SyncValue.Null
            val bv = b[k] ?: SyncValue.Null
            if (!valueEqual(av, bv)) return false
        }
        return true
    }

    /**
     * Same version of the same row, ignoring which device claims to have
     * written it. See the header for why origin is left out.
     *
     * Everything else is compared, including the deletion flag: a tombstone and
     * a live row with the same timestamp are emphatically not the same thing.
     */
    fun sameVersion(a: ChangeRecord?, b: ChangeRecord?): Boolean {
        if (a == null || b == null) return false
        return a.table == b.table &&
                a.uid == b.uid &&
                a.updatedAt == b.updatedAt &&
                a.deleted == b.deleted &&
                fieldsEqual(a.fields, b.fields)
    }

    // ── planning: what to read ──────────────────────────────────────────────

    /**
     * Files worth downloading.
     *
     * Our own are excluded rather than left to the cursor. A device's own
     * batches are already in its database, so reading them back is bandwidth
     * spent to learn nothing — and after a restore from backup it would be
     * worse than nothing, because the file carries a version of a row the
     * restored database has already moved past.
     */
    fun planPull(names: List<String>, state: SyncState): List<RemoteFile> =
        Protocol.filesToFetch(names, state.cursor).filter { it.device != state.device }

    /**
     * Advance the cursor through the unbroken run of successes, per device.
     *
     * [attempted] must be in the order the files were tried, which [planPull]
     * already guarantees. A device that failed anywhere keeps everything from
     * that point on for the next run.
     */
    fun advanceThroughPrefix(
        cursor: Map<String, Long>,
        attempted: List<RemoteFile>,
        failed: Set<String>,
    ): Map<String, Long> {
        var next = cursor
        val stopped = mutableSetOf<String>()
        for (f in attempted) {
            if (f.device in stopped) continue
            if (f.name in failed) {
                stopped += f.device
                continue
            }
            next = Protocol.advance(next, f.device, f.seq)
        }
        return next
    }

    // ── planning: what to write locally ─────────────────────────────────────

    /**
     * What the incoming rows should turn into on this device.
     *
     * The merged record is written, not the remote one. The difference shows up
     * on the field group that tracks completion: the desktop can hold a newer
     * name while this device holds a further-along completion, and only the
     * merge has both. Writing the remote record instead would undo a task that
     * was ticked here, which is the single failure this design exists to
     * prevent.
     *
     * Rows already equal to what the merge produces are dropped here rather
     * than in the store. A no-op UPDATE still fires the trigger, still bumps the
     * timestamp, and so still produces a row that looks freshly edited to every
     * other device.
     */
    fun planApply(
        remote: Map<String, ChangeRecord>,
        local: Map<String, ChangeRecord>,
    ): List<ChangeRecord> {
        val out = mutableListOf<ChangeRecord>()
        for ((key, r) in remote) {
            val mine = local[key]
            if (mine == null) {
                out += r
                continue
            }
            val winner = Merge.merge(mine, r)
            if (!sameVersion(winner, mine)) out += winner
        }
        return out
    }

    // ── planning: what to send ──────────────────────────────────────────────

    /**
     * The batch to upload, or null when there is nothing to say.
     *
     * Null rather than an empty batch. An empty batch is a file every other
     * device downloads and decrypts to learn nothing, and since a sync may run
     * on a timer, that is a steady drip of files forever.
     *
     * [full] turns off the echo filter, and only a snapshot passes it. The
     * filter drops rows the remote log was seen to already hold, which is right
     * for a delta and wrong for a snapshot: the whole promise of a snapshot is
     * that it alone reconstructs this database, and a row left out because some
     * other device also mentioned it is a row that vanishes the moment that
     * device prunes its own log.
     */
    fun planPush(
        pending: List<ChangeRecord>,
        remoteView: Map<String, ChangeRecord>,
        state: SyncState,
        writtenAt: String,
        full: Boolean = false,
    ): ChangeBatch? {
        val changes =
            if (full) pending else pending.filter { !sameVersion(remoteView[rowKey(it)], it) }
        if (changes.isEmpty()) return null
        return ChangeBatch(
            version = Protocol.VERSION,
            device = state.device,
            seq = state.seq + 1,
            writtenAt = writtenAt,
            changes = changes.map { it.copy(origin = state.device) },
        )
    }

    // ── planning: when to send everything, and what to delete ───────────────

    /** This device's own files in the folder, as the listing reports them. */
    private fun ownFiles(names: List<String>, device: String): List<RemoteFile> =
        names.mapNotNull { Protocol.parseFileName(it) }.filter { it.device == device }

    /**
     * Should this push carry the whole database rather than the queue.
     *
     * One line, and deliberately without a special case for a device that has
     * never written a snapshot. Forcing one on the first sync was tried and it
     * costs the property that a device which only ever pulled writes nothing at
     * all: it would pull the other device's rows and send every one back.
     *
     * The anchor arrives on its own. A device with no snapshot has nothing it is
     * allowed to delete either, and it cannot need to.
     */
    fun wantsSnapshot(names: List<String>, state: SyncState): Boolean =
        ownFiles(names, state.device).size >= SNAPSHOT_AFTER_FILES

    /**
     * Files this device may delete, oldest first.
     *
     * Own files only, and only below the prior snapshot. Both halves are load
     * bearing and neither is a judgement call: a device cannot know another
     * device's cursor, and it cannot know whether anyone has read past a file
     * not covered by a snapshot of its own.
     */
    fun planPrune(
        names: List<String>,
        state: SyncState,
        limit: Int = PRUNE_PER_RUN,
    ): List<String> {
        if (state.priorSnapshotSeq <= 0L) return emptyList()
        return ownFiles(names, state.device)
            .filter { it.seq < state.priorSnapshotSeq }
            .sortedBy { it.seq }
            .take(limit)
            .map { it.name }
    }

    /**
     * The two numbers after a snapshot has landed.
     *
     * Called only once the bytes are on the far side. Moving the line before the
     * upload succeeded would authorise deleting the files the new snapshot is
     * meant to replace, on a run where the replacement does not exist.
     */
    fun afterSnapshot(state: SyncState, seq: Long): SyncState =
        state.copy(priorSnapshotSeq = state.snapshotSeq, snapshotSeq = seq)

    /**
     * Delete what the plan says, and never mind if it will not go.
     *
     * A refused delete is not reported anywhere, which is the one place in this
     * file where silence is right. Nothing is lost by it: the file is still
     * below the line, so the next run tries again, and until then it is a file
     * that costs storage and confuses nobody. There is also nothing a person
     * could do about it, and a sync screen that reports a problem with no action
     * attached is how a screen teaches somebody to stop reading it.
     *
     * Re-listing rather than reusing the listing from the top of the run,
     * because between then and now this device uploaded a file and may have
     * taken minutes doing it.
     */
    private suspend fun prune(storage: SyncStorage, state: SyncState): Int {
        if (state.priorSnapshotSeq <= 0L) return 0
        val names = try {
            storage.list()
        } catch (e: Exception) {
            return 0
        }
        var n = 0
        for (name in planPrune(names, state)) {
            try {
                storage.delete(name)
                n++
            } catch (e: Exception) {
                // Next run. See above.
            }
        }
        return n
    }

    // ── the driver ──────────────────────────────────────────────────────────

    /**
     * One round trip. Safe to call again immediately; safe to interrupt
     * anywhere.
     *
     * Interruption is the normal case rather than the exception — this runs on
     * a phone, which is a device that can be put in a pocket mid-request. Every
     * persisted step is ordered so that stopping before it costs a repeat and
     * never a row.
     */
    suspend fun sync(
        storage: SyncStorage,
        store: LocalStore,
        cipher: AeadCipher,
        bucketId: String,
        key: ByteArray,
        now: () -> String,
    ): SyncReport {
        val state = store.loadState()
        val skipped = mutableListOf<SkippedFile>()

        // ── pull ────────────────────────────────────────────────────────────
        val names = storage.list()
        val wanted = planPull(names, state)

        val batches = mutableListOf<ChangeBatch>()
        val failed = mutableSetOf<String>()
        for (f in wanted) {
            try {
                val blob = storage.get(f.name)
                val plain = SealedBlob.open(cipher, key, bucketId, f.device, f.seq, blob)
                batches += BatchCodec.decodeBatch(plain, f)
            } catch (e: Exception) {
                // One unreadable file does not stop the rest. It could be a
                // half-finished upload, a file from a bucket whose key was
                // rotated, or something a future version wrote. All three are
                // worth reporting and none is worth refusing to sync over.
                failed += f.name
                skipped += SkippedFile(f.name, e.message ?: e.toString())
            }
        }

        val remoteView = Merge.mergeAll(batches.flatMap { it.changes })
        val localForRemote = store
            .lookup(remoteView.values.map { RowKey(it.table, it.uid) })
            .associateBy { rowKey(it) }
        val toApply = planApply(remoteView, localForRemote)

        if (toApply.isNotEmpty()) store.apply(toApply)

        // Only now. Everything above can be repeated; nothing above is remembered.
        val pulled = state.copy(cursor = advanceThroughPrefix(state.cursor, wanted, failed))
        store.saveState(pulled)

        // ── push ────────────────────────────────────────────────────────────
        val snapshot = wantsSnapshot(names, pulled)
        // Before pending(), so the queue this run reads is the refilled one.
        // Safe to repeat: a run that dies after this and before the upload
        // leaves a full queue, and a full queue is only ever an upload that says
        // more than it had to.
        if (snapshot) store.reseed()

        val pending = store.pending()
        val batch = planPush(pending, remoteView, pulled, now(), snapshot)

        if (batch == null) {
            // Nothing to send, and the queue still empties: every pending row
            // was looked at and found to be already known remotely. Skipping a
            // row because the far side has it is a decision, not a
            // postponement, and leaving it queued means making the same
            // decision again on every run for as long as the row exists.
            store.settle(pending)
            return SyncReport(
                batches.size, toApply.size, 0, skipped, null,
                snapshot = false, pruned = prune(storage, pulled),
            )
        }

        // Reserve the number before the bytes exist. See the header.
        val reserved = pulled.copy(seq = batch.seq)
        store.saveState(reserved)

        val name = Protocol.fileName(batch.device, batch.seq)
        val blob = SealedBlob.seal(
            cipher, key, bucketId, batch.device, batch.seq, BatchCodec.encodeBatch(batch),
        )
        storage.put(name, blob)

        // After the bytes are on the far side, never before. Stopping here costs
        // a repeat of one batch, which merge absorbs; stopping the other way
        // round costs the rows themselves.
        store.settle(pending)

        val settled = if (snapshot) afterSnapshot(reserved, batch.seq) else reserved
        if (snapshot) store.saveState(settled)

        return SyncReport(
            batches.size, toApply.size, batch.changes.size, skipped, name,
            snapshot = snapshot, pruned = prune(storage, settled),
        )
    }
}