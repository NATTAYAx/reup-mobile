package app.reup.sync

// ─── Protocol.kt — the shape of what travels between devices ─────────────────
//
// Mirror of src/lib/sync/protocol.ts on the desktop. Nothing here does I/O and
// nothing here knows what a task is; it describes an envelope. The moment this
// file knows about tasks, the engine has to be retested every time a column is
// added, and the whole point of the vector suite is that it does not.
//
// WHY A LOG OF BATCHES AND NOT A DATABASE
//
// The storage backend belongs to the user, not to us, and the honest assumption
// about somebody else's storage is that it can do four things: list, get, put,
// delete. It cannot run a query, it cannot compare-and-swap, and it cannot be
// trusted about time.
//
// So devices never edit anything. Each appends files named
//
//     <deviceId>-<seq>.reup
//
// and nothing ever writes to a name that already exists. Two devices cannot
// collide, because a device only writes under its own id. That removes the
// whole category of problems that normally makes WebDAV and Drive behave
// differently — locking, ETags, lost updates — which is why one adapter
// interface covers both without either being a special case.
//
// WHY THE CURSOR IS A MAP AND NOT A TIMESTAMP
//
// "Everything since 3pm" requires trusting a clock. Two phones and a laptop do
// not agree what time it is, and a phone that has been off for a week can be
// minutes out on first boot. A sync that silently drops rows when a clock is
// wrong is the worst kind of bug: no error, no crash, just data present on one
// device and missing on another.
//
// So the cursor is "the highest sequence number I have seen FROM EACH DEVICE".
// Counting is something a device can do about itself without reference to
// anybody else. Nothing here needs a correct clock to be correct.
//
// Clocks still decide WHO WINS a conflict, which is a far weaker requirement:
// being wrong there loses one edit, not a row.

/**
 * A field value, restricted to what a SQLite column can hold and what JSON can
 * carry without ambiguity.
 *
 * A sealed type rather than `Any?` because the ordering in Merge has to be
 * identical to the TypeScript one, and that is only checkable if the set of
 * possible shapes is closed.
 */
sealed interface SyncValue {
    data object Null : SyncValue
    data class Bool(val value: Boolean) : SyncValue
    data class Num(val value: Double) : SyncValue
    data class Text(val value: String) : SyncValue
}

/** One row as one device last saw it. */
data class ChangeRecord(
    val table: String,
    /** UUID v4, minted where the row was born. Identity across devices. */
    val uid: String,
    /** ISO-8601 UTC with milliseconds. Ordering is string comparison. */
    val updatedAt: String,
    val deleted: Boolean,
    /** Which device wrote this version. Also the conflict tiebreaker. */
    val origin: String,
    /** The row minus `id` and minus the sync columns, which are lifted out above. */
    val fields: Map<String, SyncValue>,
)

/** What one file in the log contains, before encryption. */
data class ChangeBatch(
    /** Bumped when the envelope shape changes. Readers reject what they cannot read. */
    val version: Int,
    val device: String,
    val seq: Long,
    /** For debugging only. Never used for ordering or merging. */
    val writtenAt: String,
    val changes: List<ChangeRecord>,
)

/** A file in the log, as the storage adapter reports it. */
data class RemoteFile(val name: String, val device: String, val seq: Long)

object Protocol {

    const val VERSION = 1

    private val NAME_RE = Regex("""^([A-Za-z0-9_-]{1,64})-(\d{1,12})\.reup$""")

    fun fileName(device: String, seq: Long): String = "$device-$seq.reup"

    /** Null rather than throwing: a stranger's file in the folder is not an error. */
    fun parseFileName(name: String): RemoteFile? {
        val m = NAME_RE.find(name) ?: return null
        val seq = m.groupValues[2].toLongOrNull() ?: return null
        return RemoteFile(name, m.groupValues[1], seq)
    }

    /** Which files this device has not read yet. Sorted so replay is deterministic. */
    fun filesToFetch(all: List<String>, cursor: Map<String, Long>): List<RemoteFile> =
        all.mapNotNull { parseFileName(it) }
            .filter { it.seq > (cursor[it.device] ?: 0L) }
            .sortedWith(compareBy({ it.device }, { it.seq }))

    /**
     * Advancing the cursor is separate from fetching on purpose.
     *
     * A batch that was downloaded but failed to apply must not move the cursor,
     * or it is skipped forever and the row it carried goes missing on this
     * device only. Callers advance after the write succeeds, never before.
     */
    fun advance(cursor: Map<String, Long>, device: String, seq: Long): Map<String, Long> {
        val at = cursor[device] ?: 0L
        return if (seq > at) cursor + (device to seq) else cursor
    }
}