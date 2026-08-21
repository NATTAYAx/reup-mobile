package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── MergeVectorsTest ────────────────────────────────────────────────────────
//
// sync-vectors.json is a spec, not a fixture. Every case in it came from
// running the desktop implementation over a matrix of timestamps, origins,
// deletion flags and completion states, and recording what it said. This port
// has to reproduce all of them.
//
// Same arrangement as schedule-vectors.json, and for the same reason: the
// alternative is two implementations of the same rules kept in agreement by
// somebody remembering, which is the thing that goes wrong every time.
//
// It earned its place immediately. Generating the file failed twice before it
// produced anything — once on commutativity, once on associativity — and both
// were real defects in the design rather than typos. Neither would have been
// found by a hand-written test, because nobody writes the case where two
// devices write the same row in the same millisecond.
//
// The property tests below matter as much as the vectors. The vectors prove the
// two languages agree; the properties prove the rule itself converges. Agreeing
// on a wrong answer is still wrong.

class MergeVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("sync-vectors.json")
            ?: fail(
                "sync-vectors.json not found on the test classpath. It is generated " +
                        "on the desktop side with `pnpm gen:sync-vectors` and copied to " +
                        "shared/src/jvmTest/resources/."
            )
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    // ── JSON to model ───────────────────────────────────────────────────────

    private fun toValue(e: JsonElement): SyncValue = when {
        e is JsonNull -> SyncValue.Null
        e is JsonPrimitive && e.isString -> SyncValue.Text(e.content)
        e is JsonPrimitive && e.content == "true" -> SyncValue.Bool(true)
        e is JsonPrimitive && e.content == "false" -> SyncValue.Bool(false)
        e is JsonPrimitive -> e.content.toDoubleOrNull()
            ?.let { SyncValue.Num(it) }
            ?: SyncValue.Text(e.content)
        else -> fail("unsupported value in vectors: $e")
    }

    private fun toRecord(o: JsonObject): ChangeRecord = ChangeRecord(
        table = o.getValue("table").jsonPrimitive.content,
        uid = o.getValue("uid").jsonPrimitive.content,
        updatedAt = o.getValue("updatedAt").jsonPrimitive.content,
        deleted = o.getValue("deleted").jsonPrimitive.content == "true",
        origin = o.getValue("origin").jsonPrimitive.content,
        fields = o.getValue("fields").jsonObject.mapValues { toValue(it.value) },
    )

    /** Field order is not part of the contract, so compare as sets of entries. */
    private fun same(a: ChangeRecord, b: ChangeRecord): Boolean =
        a.table == b.table &&
                a.uid == b.uid &&
                a.updatedAt == b.updatedAt &&
                a.deleted == b.deleted &&
                a.origin == b.origin &&
                a.fields == b.fields

    // ── the suite ───────────────────────────────────────────────────────────

    @Test
    fun `reproduces every desktop vector`() {
        val root = loadVectors()
        assertEquals(
            1,
            root.getValue("version").jsonPrimitive.content.toInt(),
            "vector file version changed; the reader has to change with it",
        )

        val cases = root.getValue("cases") as JsonArray
        assertTrue(cases.size > 100, "only ${cases.size} cases — the file looks truncated")

        val failures = mutableListOf<String>()
        for (c in cases) {
            val o = c.jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val a = toRecord(o.getValue("a").jsonObject)
            val b = toRecord(o.getValue("b").jsonObject)
            val expected = toRecord(o.getValue("expected").jsonObject)
            val actual = Merge.merge(a, b)
            if (!same(actual, expected)) {
                if (failures.size < 5) {
                    failures += "$id\n  expected $expected\n  actual   $actual"
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size}+ of ${cases.size} vectors disagree:\n" + failures.joinToString("\n"))
        }
        println("${cases.size} vectors reproduced")
    }

    @Test
    fun `merge is idempotent, commutative and associative`() {
        val root = loadVectors()
        val cases = root.getValue("cases") as JsonArray
        val universe = cases
            .flatMap { listOf(it.jsonObject.getValue("a"), it.jsonObject.getValue("b")) }
            .map { toRecord(it.jsonObject) }
            .distinct()

        for (a in universe) {
            assertTrue(same(Merge.merge(a, a), a), "not idempotent for $a")
        }

        for (a in universe) for (b in universe) {
            if (!Merge.sameRow(a, b)) continue
            assertTrue(
                same(Merge.merge(a, b), Merge.merge(b, a)),
                "not commutative:\n  a $a\n  b $b",
            )
        }

        // Every triple would be far too many; a stride still crosses each axis.
        var checked = 0
        for (i in universe.indices) for (j in universe.indices step 3) for (k in universe.indices step 7) {
            val a = universe[i]
            val b = universe[j]
            val c = universe[k]
            if (!Merge.sameRow(a, b) || !Merge.sameRow(b, c)) continue
            assertTrue(
                same(Merge.merge(Merge.merge(a, b), c), Merge.merge(a, Merge.merge(b, c))),
                "not associative:\n  a $a\n  b $b\n  c $c",
            )
            checked++
        }
        println("${universe.size} records, $checked associativity checks")
    }

    @Test
    fun `ticking done on one device is never undone by the other`() {
        // The scenario the completion group exists for, stated as itself rather
        // than as a property. Phone completes; laptop edits the name earlier;
        // laptop's row is written later. Under plain LWW the task comes back
        // undone, which is the app lying about the one thing it is for.
        val base = mapOf(
            "name" to SyncValue.Text("daily"),
            "reset_type" to SyncValue.Text("daily"),
            "completed_until" to SyncValue.Null,
            "cycle_checked_until" to SyncValue.Null,
            "missed_streak" to SyncValue.Num(2.0),
        )
        val phone = ChangeRecord(
            "tasks", "u1", "2026-08-14T09:00:00.000Z", false, "phone",
            base + mapOf(
                "completed_until" to SyncValue.Text("2026-08-15T04:00:00.000Z"),
                "cycle_checked_until" to SyncValue.Text("2026-08-15T04:00:00.000Z"),
                "missed_streak" to SyncValue.Num(0.0),
            ),
        )
        val laptop = ChangeRecord(
            "tasks", "u1", "2026-08-14T09:05:00.000Z", false, "laptop",
            base + mapOf("name" to SyncValue.Text("daily renamed")),
        )

        val merged = Merge.merge(phone, laptop)
        assertEquals(
            SyncValue.Text("2026-08-15T04:00:00.000Z"), merged.fields["completed_until"],
            "completion was undone by a later unrelated edit",
        )
        assertEquals(
            SyncValue.Num(0.0), merged.fields["missed_streak"],
            "the missed streak did not travel with the completion it belongs to",
        )
        assertEquals(
            SyncValue.Text("daily renamed"), merged.fields["name"],
            "the later edit should still win the base",
        )
    }

    @Test
    fun `a deleted row is not resurrected by an older copy`() {
        val fields = mapOf("amount" to SyncValue.Num(1200.0), "category" to SyncValue.Text("food"))
        val old = ChangeRecord("expenses", "e1", "2026-08-14T09:00:00.000Z", false, "phone", fields)
        val del = ChangeRecord("expenses", "e1", "2026-08-14T10:00:00.000Z", true, "laptop", fields)
        assertTrue(Merge.merge(old, del).deleted)
        assertTrue(Merge.merge(del, old).deleted)
    }

    @Test
    fun `cursor only moves forward and file names round-trip`() {
        var cursor = emptyMap<String, Long>()
        cursor = Protocol.advance(cursor, "phone", 5)
        cursor = Protocol.advance(cursor, "phone", 3)
        assertEquals(5L, cursor["phone"], "the cursor went backwards, which replays old batches")

        val name = Protocol.fileName("phone", 7)
        assertEquals(RemoteFile(name, "phone", 7), Protocol.parseFileName(name))
        assertEquals(null, Protocol.parseFileName("someone-elses-file.txt"))

        val pending = Protocol.filesToFetch(
            listOf("phone-4.reup", "phone-6.reup", "laptop-1.reup", "notes.txt"),
            mapOf("phone" to 5L),
        )
        assertEquals(listOf("laptop-1.reup", "phone-6.reup"), pending.map { it.name })
    }
}