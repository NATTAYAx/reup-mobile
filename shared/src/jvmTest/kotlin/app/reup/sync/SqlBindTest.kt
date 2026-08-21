// shared/src/jvmTest/kotlin/app/reup/sync/SqlBindTest.kt
package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals

// The statement in the first test is the real one SqlLocalStore builds for a
// duplicate check, not an invented example. It is the statement that produced
// "the bind value at index 1 is null" on the phone during the first sync that
// carried real rows.

class SqlBindTest {

    private val t = SyncValue.Text("x")
    private val n = SyncValue.Num(1.0)

    @Test
    fun `a null natural key becomes IS NULL, which is what it means`() {
        val b = SqlBind.inlineNulls(
            "SELECT * FROM budgets WHERE cat IS ? AND month IS ?",
            listOf(SyncValue.Null, SyncValue.Text("2026-08")),
        )
        assertEquals(
            "SELECT * FROM budgets WHERE cat IS NULL AND month IS ?",
            b.sql,
        )
        assertEquals(listOf(SyncValue.Text("2026-08")), b.params)
    }

    @Test
    fun `a statement with no nulls comes back unchanged`() {
        // The common case by far. It is still scanned, and must come out
        // character for character identical.
        val sql = "SELECT * FROM tasks WHERE uid = ?"
        val b = SqlBind.inlineNulls(sql, listOf(t))
        assertEquals(sql, b.sql)
        assertEquals(listOf(t), b.params)
    }

    @Test
    fun `nulls are matched to placeholders by position, not by count`() {
        val b = SqlBind.inlineNulls(
            "SELECT * FROM t WHERE a IS ? AND b IS ? AND c IS ? AND d IS ?",
            listOf(t, SyncValue.Null, n, SyncValue.Null),
        )
        assertEquals(
            "SELECT * FROM t WHERE a IS ? AND b IS NULL AND c IS ? AND d IS NULL",
            b.sql,
        )
        assertEquals(listOf(t, n), b.params)
    }

    @Test
    fun `every parameter null leaves a statement that binds nothing`() {
        val b = SqlBind.inlineNulls(
            "SELECT * FROM t WHERE a IS ? AND b IS ?",
            listOf(SyncValue.Null, SyncValue.Null),
        )
        assertEquals("SELECT * FROM t WHERE a IS NULL AND b IS NULL", b.sql)
        assertEquals(emptyList(), b.params)
    }

    @Test
    fun `a question mark inside a string literal is not a placeholder`() {
        // Nothing in this app writes such a statement today. It is here because
        // the day something does, the failure would be a query that silently
        // binds the wrong values to the wrong columns, which reads as data
        // corruption rather than as a bug in this file.
        val b = SqlBind.inlineNulls(
            "SELECT * FROM t WHERE note = 'why?' AND a IS ?",
            listOf(SyncValue.Null),
        )
        assertEquals("SELECT * FROM t WHERE note = 'why?' AND a IS NULL", b.sql)
        assertEquals(emptyList(), b.params)
    }

    @Test
    fun `a doubled quote inside a literal keeps the scanner inside it`() {
        // SQLite escapes a quote by doubling it. The pair closes and reopens the
        // string, so the '?' after it is still literal text.
        val b = SqlBind.inlineNulls(
            "SELECT * FROM t WHERE note = 'it''s here? yes' AND a IS ?",
            listOf(SyncValue.Null),
        )
        assertEquals(
            "SELECT * FROM t WHERE note = 'it''s here? yes' AND a IS NULL",
            b.sql,
        )
        assertEquals(emptyList(), b.params)
    }

    @Test
    fun `fewer parameters than placeholders reads as null rather than throwing`() {
        // The caller is already wrong at this point. The useful complaint is
        // SQLite's about the statement, not one from here about arithmetic.
        val b = SqlBind.inlineNulls("SELECT * FROM t WHERE a IS ? AND b IS ?", listOf(t))
        assertEquals("SELECT * FROM t WHERE a IS ? AND b IS NULL", b.sql)
        assertEquals(listOf(t), b.params)
    }

    @Test
    fun `an insert with a null column inlines the same way`() {
        // execSQL does bind null correctly, so this path did not have to change.
        // It goes through the same helper anyway, because two ways of handing a
        // statement to the same database is one more thing to keep in agreement.
        val b = SqlBind.inlineNulls(
            "INSERT INTO tasks (uid, reset_time) VALUES (?, ?)",
            listOf(SyncValue.Text("u-1"), SyncValue.Null),
        )
        assertEquals("INSERT INTO tasks (uid, reset_time) VALUES (?, NULL)", b.sql)
        assertEquals(listOf(SyncValue.Text("u-1")), b.params)
    }

    @Test
    fun `no parameters at all is left alone`() {
        val sql = "SELECT * FROM tasks"
        val b = SqlBind.inlineNulls(sql, emptyList())
        assertEquals(sql, b.sql)
        assertEquals(emptyList(), b.params)
    }
}