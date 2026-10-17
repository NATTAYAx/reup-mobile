package app.reup.sync

import app.reup.core.Note
import app.reup.core.noteBody
import app.reup.core.noteColor

// ─── NoteRepo.kt — the notes table, as this phone writes it ──────────────────
//
// Five statements, each the desktop's own (src/lib/notes.ts) and pinned to it by
// NoteVectorsTest. Nothing here mentions uid, updated_at or the outbox: the sync
// triggers stamp and queue every write, exactly as they do for a task.
//
// TAKING ONE OFF
//
// A tombstone, with the words blanked on the way. A tombstone travels - into
// the sync folder and onto the other machine, where it waits a month for the
// sweep - and what the note said has no reason to travel with it. So [peel]
// hands back the note as it was, and the undo puts it back from that.

/** Newest first: the last thing stuck up is the one nearest the eye. */
const val LIVE_NOTES_SQL: String =
    "SELECT id, uid, body, color, created_at FROM notes " +
        "WHERE deleted = 0 AND uid IS NOT NULL ORDER BY created_at DESC, uid DESC"

/** created_at is left to its default, so both machines write it in one format. */
const val ADD_NOTE_SQL: String = "INSERT INTO notes (body, color) VALUES (?, ?)"

const val EDIT_NOTE_SQL: String = "UPDATE notes SET body = ?, color = ? WHERE uid = ? AND deleted = 0"

const val PEEL_NOTE_SQL: String = "UPDATE notes SET deleted = 1, body = '' WHERE uid = ? AND deleted = 0"

const val UNPEEL_NOTE_SQL: String =
    "UPDATE notes SET deleted = 0, body = ?, color = ? WHERE uid = ? AND deleted = 1"

/** A row as the screens want it, or null for one without the parts a note needs. */
fun noteFrom(r: DbRow): Note? {
    val uid = (r["uid"] as? SyncValue.Text)?.value ?: return null
    val body = (r["body"] as? SyncValue.Text)?.value ?: return null
    return Note(
        uid = uid,
        body = body,
        color = noteColor((r["color"] as? SyncValue.Text)?.value),
        createdAt = (r["created_at"] as? SyncValue.Text)?.value ?: "",
    )
}

class NoteRepo(private val db: Db) {

    /** Every note that is up, newest first. */
    suspend fun live(): List<Note> = db.select(LIVE_NOTES_SQL).mapNotNull(::noteFrom)

    /** One note, if it is still up. */
    suspend fun find(uid: String): Note? = live().firstOrNull { it.uid == uid }

    /** False when there was nothing to stick up. */
    suspend fun add(raw: String, color: String): Boolean {
        val body = noteBody(raw) ?: return false
        db.execute(ADD_NOTE_SQL, listOf(SyncValue.Text(body), SyncValue.Text(noteColor(color))))
        return true
    }

    /**
     * New words or a new paper. False when there was no note up to change -
     * taken off on the other machine since the screen last looked - which is
     * what the desktop's editNote answers too.
     *
     * An edit that leaves nothing written takes the note off rather than
     * keeping a blank square up. The screens send that case to [peel]
     * themselves, so they have the note back for the undo; this only makes
     * sure a blank never goes up whoever calls it.
     */
    suspend fun edit(uid: String, raw: String, color: String): Boolean {
        if (find(uid) == null) return false
        val body = noteBody(raw)
        if (body == null) {
            db.execute(PEEL_NOTE_SQL, listOf(SyncValue.Text(uid)))
            return true
        }
        db.execute(
            EDIT_NOTE_SQL,
            listOf(SyncValue.Text(body), SyncValue.Text(noteColor(color)), SyncValue.Text(uid)),
        )
        return true
    }

    /**
     * Off, and blank. The note as it was, for the undo, or null when it was
     * already off - taken off on the other machine since the screen was drawn,
     * which on a device that syncs is an answer rather than an error.
     */
    suspend fun peel(uid: String): Note? {
        val was = find(uid) ?: return null
        db.execute(PEEL_NOTE_SQL, listOf(SyncValue.Text(uid)))
        return was
    }

    /** Back up, with the words the screen still had. */
    suspend fun unpeel(note: Note) {
        db.execute(
            UNPEEL_NOTE_SQL,
            listOf(SyncValue.Text(note.body), SyncValue.Text(noteColor(note.color)), SyncValue.Text(note.uid)),
        )
    }
}
