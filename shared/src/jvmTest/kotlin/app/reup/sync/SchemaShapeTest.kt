package app.reup.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ─── SchemaShapeTest ─────────────────────────────────────────────────────────
//
// WHAT THIS IS FOR
//
// schema.sql separates its statements with a line containing only `-- @@`,
// because a trigger body has semicolons in it and splitting on those would cut
// triggers in half. That rule works right up until somebody forgets the line.
//
// Forgetting it does not break the build, does not fail a vector, and does not
// throw at runtime. The two statements arrive glued into one string, and SQLite
// runs the first statement in a string and stops. The second table is never
// created and nothing anywhere says so.
//
// That happened to app_settings, and it stayed hidden for months because every
// machine already had the table from an earlier version that made it elsewhere.
// The first fresh install found it immediately, as `no such table: app_settings`
// on the sync line of the home screen — which reads like a corrupt database
// rather than one that was never finished being built.
//
// These tests are the thing that was missing. They are about the shape of the
// file rather than its contents, so they keep working as tables are added.
//
// Beside StoreTest rather than in commonTest, because this is about the schema
// the two platforms share and StoreTest is where the rest of that lives.

class SchemaShapeTest {

    private val statements = Schema.statements(SCHEMA_SQL)

    /**
     * The direct one. Every table the file names has to come out as something
     * the database will actually be handed.
     */
    @Test
    fun `every table named in the schema gets a statement of its own`() {
        val named = Regex("CREATE TABLE IF NOT EXISTS (\\w+)")
            .findAll(SCHEMA_SQL)
            .map { it.groupValues[1] }
            .toList()

        val own = statements.mapNotNull {
            Regex("^CREATE TABLE IF NOT EXISTS (\\w+)").find(it.trim())?.groupValues?.get(1)
        }

        assertEquals(
            emptyList(),
            named.filter { !own.contains(it) },
            "glued onto the statement above them, so they are never created",
        )
    }

    /**
     * The general one, which also covers indexes and triggers.
     *
     * CREATE TABLE, CREATE INDEX and CREATE TRIGGER never appear inside a
     * trigger body, so counting them is safe here in a way that counting
     * semicolons would not be.
     */
    @Test
    fun `no statement carries more than one thing to create`() {
        for (s in statements) {
            val creates = Regex("CREATE (?:TABLE|INDEX|TRIGGER)[^(\n]*")
                .findAll(s)
                .map { it.value.trim() }
                .toList()
            assertTrue(
                creates.size <= 1,
                "a chunk of schema.sql is missing its -- @@ line: " + creates.joinToString(" + "),
            )
        }
    }

    @Test
    fun `the separator has to be a line of its own`() {
        // The rule that made the file's own header dangerous once already: the
        // header is where `-- @@` is explained, so splitting on the bare text
        // instead of on a whole line cuts the explanation in half.
        val split = Schema.statements(
            "CREATE TABLE a (x TEXT);\n" +
                    "-- this line mentions -- @@ and must not split anything\n" +
                    "-- @@\n" +
                    "CREATE TABLE b (y TEXT);\n",
        )
        assertEquals(2, split.size)
        assertTrue(split[0].startsWith("CREATE TABLE a"))
        assertTrue(split[1].startsWith("CREATE TABLE b"))
    }
}