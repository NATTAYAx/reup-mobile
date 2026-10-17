// shared/src/jvmTest/kotlin/app/reup/sync/NoteRepoTest.kt
package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── NoteRepoTest ────────────────────────────────────────────────────────────
//
// The phone's own half of the notes: the choices NoteRepo makes before a
// statement goes out. The statements themselves are the desktop's, string for
// string (NoteVectorsTest), and the desktop runs them against SQLite with the
// sync triggers on (check:sync), so a table that understands exactly those
// five is enough here.

// Same driver as the other suites: the fake never really suspends.
private fun runHere(block: suspend () -> Unit) {
    var thrown: Throwable? = null
    var finished = false
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                thrown = result.exceptionOrNull()
                finished = true
            }
        },
    )
    check(finished) { "the block actually suspended; this driver only runs code backed by the fake" }
    thrown?.let { throw it }
}

/** The notes table as the five statements see it, and nothing else. */
private class NotesTable : Db {
    class Row(val uid: String, var body: String, var color: String, var deleted: Boolean, val createdAt: String)

    val rows = mutableListOf<Row>()
    val sent = mutableListOf<String>()
    private var n = 0

    override suspend fun execute(sql: String, params: List<SyncValue>) {
        sent += sql
        fun text(i: Int) = (params[i] as SyncValue.Text).value
        when (sql) {
            ADD_NOTE_SQL -> {
                n++
                rows += Row("u$n", text(0), text(1), false, "2026-10-16 08:0$n:00")
            }
            EDIT_NOTE_SQL -> rows.filter { it.uid == text(2) && !it.deleted }.forEach {
                it.body = text(0)
                it.color = text(1)
            }
            PEEL_NOTE_SQL -> rows.filter { it.uid == text(0) && !it.deleted }.forEach {
                it.deleted = true
                it.body = ""
            }
            UNPEEL_NOTE_SQL -> rows.filter { it.uid == text(2) && it.deleted }.forEach {
                it.deleted = false
                it.body = text(0)
                it.color = text(1)
            }
            else -> error("not a note statement: $sql")
        }
    }

    override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
        check(sql == LIVE_NOTES_SQL) { "not the notes query: $sql" }
        return rows.filter { !it.deleted }
            .sortedWith(compareByDescending<Row> { it.createdAt }.thenByDescending { it.uid })
            .map {
                mapOf(
                    "uid" to SyncValue.Text(it.uid),
                    "body" to SyncValue.Text(it.body),
                    "color" to SyncValue.Text(it.color),
                    "created_at" to SyncValue.Text(it.createdAt),
                )
            }
    }
}

class NoteRepoTest {

    @Test
    fun `nothing written goes nowhere`() = runHere {
        val table = NotesTable()
        assertFalse(NoteRepo(table).add("  \n \t ", "yellow"))
        assertTrue(table.sent.isEmpty())
    }

    @Test
    fun `a note goes up tidied, newest first, and an unknown paper is the first`() = runHere {
        val repo = NoteRepo(NotesTable())
        assertTrue(repo.add("  ซื้อนม  ", "tartan"))
        assertTrue(repo.add("คืนหนังสือ", "blue"))
        val live = repo.live()
        assertEquals(listOf("คืนหนังสือ", "ซื้อนม"), live.map { it.body })
        assertEquals(listOf("blue", "yellow"), live.map { it.color })
    }

    @Test
    fun `an edit changes the words and the paper, and says when there was nothing left to change`() = runHere {
        val repo = NoteRepo(NotesTable())
        repo.add("ซื้อนม", "yellow")
        val uid = repo.live().single().uid
        assertTrue(repo.edit(uid, "ซื้อนม 2 ขวด", "pink"))
        assertEquals("ซื้อนม 2 ขวด", repo.find(uid)?.body)
        assertEquals("pink", repo.find(uid)?.color)
        // Taken off on the other machine in the meantime.
        repo.peel(uid)
        assertFalse(repo.edit(uid, "อีกที", "pink"))
        assertTrue(repo.live().isEmpty())
    }

    @Test
    fun `rubbing every word out takes the note off, blank`() = runHere {
        val table = NotesTable()
        val repo = NoteRepo(table)
        repo.add("ล้างแอร์", "green")
        val uid = repo.live().single().uid
        assertTrue(repo.edit(uid, " \n ", "green"))
        assertTrue(repo.live().isEmpty())
        assertTrue(table.rows.single().deleted)
        assertEquals("", table.rows.single().body)
    }

    @Test
    fun `taking one off hands back its words for the undo, and the undo puts them back`() = runHere {
        val table = NotesTable()
        val repo = NoteRepo(table)
        repo.add("คืนหนังสือ\nก่อนวันศุกร์", "green")
        val uid = repo.live().single().uid
        val was = repo.peel(uid)!!
        assertEquals("คืนหนังสือ\nก่อนวันศุกร์", was.body)
        assertEquals("", table.rows.single().body)
        // Already off: nothing to hand back, and nothing sent.
        val sent = table.sent.size
        assertNull(repo.peel(uid))
        assertEquals(sent, table.sent.size)
        repo.unpeel(was)
        val back = repo.live().single()
        assertEquals("คืนหนังสือ\nก่อนวันศุกร์", back.body)
        assertEquals("green", back.color)
    }
}
