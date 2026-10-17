// app/src/main/java/app/reup/PeelReceiver.kt
package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.reup.core.Note
import app.reup.core.noteColor
import kotlinx.coroutines.withTimeout

/**
 * The round button on a note's corner, on the widget: the note comes off.
 *
 * Everything here happens with no Activity anywhere. The note is taken off
 * and the widget drawn again straight after, here, in this receiver, so the
 * paper is gone from the home screen in the time it takes to lift a thumb.
 *
 * DRAWN HERE, NOT ASKED FOR
 *
 * Everywhere else a redraw is asked for (ReupWidget.redraw), which is a
 * broadcast to this app. From inside a receiver that does not work: the system
 * hands this app one broadcast at a time, so the redraw waited until this
 * receiver was finished - and it used to finish only after the sync, seconds
 * later, with the note still on the widget looking as if the tap had missed.
 * The sync now runs after the broadcast is done (finishThen), so the next tap
 * is not kept waiting behind it either.
 *
 * THE UNDO
 *
 * A thumb on a home screen misses, and a note taken off by mistake is the
 * one thing a note must not be: gone before it was read. Taking it off blanks
 * its words on the way (see NoteRepo), so they are kept here, on this phone,
 * for [NoteUndo.KEEP_MS], and the notes on the Today tab offer them back.
 */
class PeelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uid = ReupWidget.noteOf(intent) ?: return
        finishThen(
            TAG,
            now = {
                val was = Repo.notes(context).peel(uid)
                if (was != null) NoteUndo.keep(context, was)
                ReupWidget.draw(context)
            },
            after = {
                try {
                    withTimeout(6_000) { Repo.syncQuietly(context) }
                } catch (e: Exception) {
                    Log.i(TAG, "the note will come off the other machine on the next sync: ${e.message}")
                }
            },
        )
    }

    companion object {
        private const val TAG = "PeelReceiver"
    }
}

/**
 * The last note taken off on this phone, kept for a few minutes so it can be
 * put back. Only on this phone, only the last one, and only the words: a
 * longer memory of what was taken off would be the record of it that notes are
 * meant not to leave.
 */
object NoteUndo {

    private const val PREFS = "reup_notes"
    private const val KEY = "undo"

    /** How long a note taken off can still be put back. */
    const val KEEP_MS = 10 * 60_000L

    fun keep(ctx: Context, note: Note) {
        // The uid, the paper and the time first and the words last, so words
        // that happen to hold the separator are still read back whole.
        val line = listOf(note.uid, note.color, System.currentTimeMillis().toString(), note.body).joinToString("\u0000")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, line).apply()
    }

    /** The note to offer back, if one came off in the last [KEEP_MS]. */
    fun recent(ctx: Context): Note? {
        val line = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val parts = line.split("\u0000", limit = 4)
        if (parts.size != 4) return null
        val at = parts[2].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > KEEP_MS) return null
        return Note(uid = parts[0], body = parts[3], color = noteColor(parts[1]), createdAt = "")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
