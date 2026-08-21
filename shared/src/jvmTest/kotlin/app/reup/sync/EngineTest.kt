package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── EngineTest ──────────────────────────────────────────────────────────────
//
// Two halves that answer different questions.
//
// The vectors answer "does this port decide the same things as the desktop".
// Every case in engine-vectors.json came from running the TypeScript engine and
// recording what it said, the same arrangement as schedule-vectors.json and
// sync-vectors.json, and for the same reason: two implementations of one set of
// rules, kept in agreement by somebody remembering, is the thing that goes
// wrong every time.
//
// The scenarios answer "does the whole thing converge". They run the real
// engine against an in-memory folder and an in-memory database. That cannot
// find a wrong HTTP header and is not trying to. It is there for the failures
// with no symptom: a row that lands on one device and not the other, a task
// that comes back undone after being ticked, a log that doubles every time it
// runs. None of those throws, and all of them are invisible for weeks.
//
// WHY THERE IS A HAND-ROLLED COROUTINE DRIVER BELOW
//
// Same reason as WebDavStorageTest. Nothing here ever really suspends — every
// fake returns immediately — so a full coroutines-test dependency would be
// added to the build to schedule work that never needs scheduling. The driver
// asserts that assumption rather than assuming it.

private fun drive(block: suspend () -> Unit) {
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
    check(finished) {
        "the block actually suspended; this driver only runs code backed by the fakes"
    }
    thrown?.let { throw it }
}

// ─── fakes ───────────────────────────────────────────────────────────────────

private class MemoryStorage : SyncStorage {
    val files = LinkedHashMap<String, ByteArray>()
    /** Names that fail on read, standing in for a half-finished upload. */
    val unreadable = mutableSetOf<String>()
    var refusePut = false

    override suspend fun list(): List<String> = files.keys.toList()

    override suspend fun get(name: String): ByteArray {
        if (name in unreadable) throw StorageException(StorageErrorKind.SERVER, "truncated upload")
        return files[name] ?: throw StorageException(StorageErrorKind.NOT_FOUND, "no such file: $name")
    }

    override suspend fun put(name: String, bytes: ByteArray) {
        if (refusePut) throw StorageException(StorageErrorKind.NETWORK, "network dropped mid-upload")
        check(name !in files) { "append-only violated: $name already exists" }
        files[name] = bytes
    }

    override suspend fun delete(name: String) {
        files.remove(name)
    }
}

/**
 * A database that behaves the way SQLite does with the sync triggers in place:
 * a local edit stamps the clock, an applied row keeps the timestamp it arrived
 * with, and every write to a synced table lands in the outbox — including rows
 * that arrived from elsewhere, because the real triggers have no guard that
 * could tell the two apart without also skipping softDelete.
 *
 * `queued` is a map and not a set for the same reason sync_outbox has an
 * updated_at column: an entry names a row AND the version of it that is
 * outstanding, so a row edited again while an upload is in flight replaces its
 * own entry and survives the settle that follows.
 */
private class MemoryStore(device: String) : LocalStore {
    val rows = LinkedHashMap<String, ChangeRecord>()
    val queued = LinkedHashMap<String, String>()
    var state: SyncState = Engine.emptyState(device)

    /** A local edit, as the app would make it. */
    fun write(
        table: String,
        uid: String,
        updatedAt: String,
        fields: Map<String, SyncValue>,
        deleted: Boolean = false,
    ) {
        val key = Engine.rowKey(table, uid)
        rows[key] = ChangeRecord(table, uid, updatedAt, deleted, state.device, fields)
        queued[key] = updatedAt
    }

    override suspend fun pending(): List<ChangeRecord> =
        queued.keys.mapNotNull { rows[it] }
            .sortedWith(compareBy({ it.updatedAt }, { it.uid }))

    override suspend fun settle(records: List<ChangeRecord>) {
        for (r in records) {
            val key = Engine.rowKey(r)
            if (queued[key] == r.updatedAt) queued.remove(key)
        }
    }

    override suspend fun lookup(keys: List<RowKey>): List<ChangeRecord> =
        keys.mapNotNull { rows[Engine.rowKey(it.table, it.uid)] }
    /**
     * Every row back in the queue, which is what `outboxReseed()` does to the
     * real one. Tombstones included: a device that has been away needs to be
     * told about a deletion as much as about a row.
     */
    override suspend fun reseed() {
        for (r in rows.values) queued[Engine.rowKey(r)] = r.updatedAt
    }

    override suspend fun apply(records: List<ChangeRecord>) {
        for (r in records) {
            val key = Engine.rowKey(r)
            rows[key] = r
            queued[key] = r.updatedAt
        }
    }

    override suspend fun loadState(): SyncState = state

    override suspend fun saveState(state: SyncState) {
        this.state = state
    }
}

class EngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cipher = JvmAeadCipher()
    private val key = ByteArray(32) { 7 }
    private val bucket = "bucket-for-the-suite"

    private var tick = 0
    private fun clock(): String {
        tick++
        // Rolls into minutes rather than printing a sixty-first second. Nothing
        // reads this — `writtenAt` is debug only — but a scenario that runs a
        // hundred syncs should not leave impossible timestamps in a file.
        val mm = (tick / 60).toString().padStart(2, '0')
        val ss = (tick % 60).toString().padStart(2, '0')
        return "2026-08-15T12:$mm:$ss.000Z"
    }

    private fun task(
        name: String,
        completedUntil: String? = null,
        missed: Double = 0.0,
    ): Map<String, SyncValue> = mapOf(
        "name" to SyncValue.Text(name),
        "category" to SyncValue.Text("game"),
        "reset_type" to SyncValue.Text("daily"),
        "reset_time" to SyncValue.Text("04:00"),
        "is_active" to SyncValue.Num(1.0),
        "completed_until" to (completedUntil?.let { SyncValue.Text(it) } ?: SyncValue.Null),
        "cycle_checked_until" to (completedUntil?.let { SyncValue.Text(it) } ?: SyncValue.Null),
        "missed_streak" to SyncValue.Num(missed),
    )

    private fun syncOnce(storage: MemoryStorage, store: MemoryStore): SyncReport {
        var out: SyncReport? = null
        drive { out = Engine.sync(storage, store, cipher, bucket, key, ::clock) }
        return out ?: fail("sync did not finish")
    }

    private fun sameRows(a: MemoryStore, b: MemoryStore): Boolean {
        if (a.rows.size != b.rows.size) return false
        for ((k, ra) in a.rows) {
            val rb = b.rows[k] ?: return false
            if (ra.updatedAt != rb.updatedAt || ra.deleted != rb.deleted) return false
            if (!Engine.sameVersion(ra, rb)) return false
        }
        return true
    }

    // ── vectors ─────────────────────────────────────────────────────────────

    private fun vectors(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("engine-vectors.json")
            ?: fail(
                "engine-vectors.json not found on the test classpath. It is generated on " +
                        "the desktop side with `pnpm gen:engine-vectors` and copied to " +
                        "shared/src/jvmTest/resources/.",
            )
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun cases(name: String): JsonArray = vectors()[name]?.jsonArray ?: JsonArray(emptyList())

    private fun cursorOf(o: JsonObject): Map<String, Long> = o.mapValues { it.value.jsonPrimitive.long }

    private fun records(a: JsonArray): List<ChangeRecord> = BatchCodec.recordsFrom(a)

    private fun stringsOf(a: JsonArray): List<String> = a.map { it.jsonPrimitive.content }

    @Test
    fun `picks the same files to download as the desktop`() {
        var n = 0
        for (c in cases("planPull")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val state = Engine.emptyState(o["device"]!!.jsonPrimitive.content)
                .copy(cursor = cursorOf(o["cursor"]!!.jsonObject))
            val got = Engine.planPull(stringsOf(o["names"]!!.jsonArray), state).map { it.name }
            assertEquals(stringsOf(o["expected"]!!.jsonArray), got, id)
            n++
        }
        println("$n planPull vectors")
        assertTrue(n > 0, "no planPull vectors ran")
    }

    @Test
    fun `moves the cursor exactly as far as the desktop does`() {
        var n = 0
        for (c in cases("advance")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val attempted = stringsOf(o["attempted"]!!.jsonArray).mapNotNull { Protocol.parseFileName(it) }
            val got = Engine.advanceThroughPrefix(
                cursorOf(o["cursor"]!!.jsonObject),
                attempted,
                stringsOf(o["failed"]!!.jsonArray).toSet(),
            )
            assertEquals(cursorOf(o["expected"]!!.jsonObject), got, id)
            n++
        }
        println("$n advance vectors")
        assertTrue(n > 0, "no advance vectors ran")
    }

    @Test
    fun `writes the same rows locally as the desktop would`() {
        var n = 0
        for (c in cases("planApply")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val remote = Merge.mergeAll(records(o["remote"]!!.jsonArray))
            val local = records(o["local"]!!.jsonArray).associateBy { Engine.rowKey(it) }
            assertEquals(records(o["expected"]!!.jsonArray), Engine.planApply(remote, local), id)
            n++
        }
        println("$n planApply vectors")
        assertTrue(n > 0, "no planApply vectors ran")
    }

    @Test
    fun `sends the same rows as the desktop would`() {
        var n = 0
        for (c in cases("planPush")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val state = Engine.emptyState(o["device"]!!.jsonPrimitive.content)
                .copy(seq = o["seq"]!!.jsonPrimitive.long)
            val view = records(o["remoteView"]!!.jsonArray).associateBy { Engine.rowKey(it) }
            val got = Engine.planPush(
                records(o["pending"]!!.jsonArray),
                view,
                state,
                o["writtenAt"]!!.jsonPrimitive.content,
                o["full"]?.jsonPrimitive?.content == "true",
            )
            val want = o["expected"]
            if (want == null || want is JsonNull) {
                assertNull(got, id)
            } else {
                val w = want.jsonObject
                val batch = got ?: fail("$id expected a batch and got none")
                assertEquals(w["device"]!!.jsonPrimitive.content, batch.device, "$id device")
                assertEquals(w["seq"]!!.jsonPrimitive.long, batch.seq, "$id seq")
                assertEquals(records(w["changes"]!!.jsonArray), batch.changes, "$id changes")
            }
            n++
        }
        println("$n planPush vectors")
        assertTrue(n > 0, "no planPush vectors ran")
    }

    @Test
    fun `decides to send everything at the same point the desktop does`() {
        var n = 0
        for (c in cases("wantsSnapshot")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val state = Engine.emptyState(o["device"]!!.jsonPrimitive.content)
                .copy(snapshotSeq = o["snapshotSeq"]!!.jsonPrimitive.long)
            assertEquals(
                o["expected"]!!.jsonPrimitive.content == "true",
                Engine.wantsSnapshot(stringsOf(o["names"]!!.jsonArray), state),
                id,
            )
            n++
        }
        println("$n wantsSnapshot vectors")
        assertTrue(n > 0, "no wantsSnapshot vectors ran")
    }

    @Test
    fun `deletes exactly the files the desktop would, and no others`() {
        var n = 0
        for (c in cases("planPrune")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val state = Engine.emptyState(o["device"]!!.jsonPrimitive.content)
                .copy(priorSnapshotSeq = o["priorSnapshotSeq"]!!.jsonPrimitive.long)
            assertEquals(
                stringsOf(o["expected"]!!.jsonArray),
                Engine.planPrune(
                    stringsOf(o["names"]!!.jsonArray),
                    state,
                    o["limit"]!!.jsonPrimitive.int,
                ),
                id,
            )
            n++
        }
        println("$n planPrune vectors")
        assertTrue(n > 0, "no planPrune vectors ran")
    }

    @Test
    fun `accepts and refuses exactly the envelopes the desktop does`() {
        var n = 0
        for (c in cases("decode")) {
            val o = c.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val f = o["file"]!!.jsonObject
            val file = RemoteFile(
                f["name"]!!.jsonPrimitive.content,
                f["device"]!!.jsonPrimitive.content,
                f["seq"]!!.jsonPrimitive.long,
            )
            val expected = o["expected"]!!.jsonObject
            val shouldBeOk = expected["ok"]!!.jsonPrimitive.content == "true"
            val bytes = o["text"]!!.jsonPrimitive.content.encodeToByteArray()

            if (shouldBeOk) {
                BatchCodec.decodeBatch(bytes, file)
            } else {
                val kind = expected["kind"]!!.jsonPrimitive.content
                val thrown = runCatching { BatchCodec.decodeBatch(bytes, file) }.exceptionOrNull()
                    ?: fail("$id was accepted but the desktop refuses it")
                val got = (thrown as? BatchException)?.kind?.name?.lowercase()
                    ?: fail("$id threw ${thrown::class.simpleName} rather than a BatchException")
                assertEquals(kind, got, id)
            }
            n++
        }
        println("$n decode vectors")
        assertTrue(n > 0, "no decode vectors ran")
    }

    // ── scenarios ───────────────────────────────────────────────────────────

    @Test
    fun `two devices end up holding the same rows`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("dailies"))
        phone.write("tasks", "u2", "2026-08-14T09:05:00.000Z", task("meds"))

        syncOnce(cloud, pc)
        syncOnce(cloud, phone)
        syncOnce(cloud, pc)

        assertEquals(2, pc.rows.size, "the desktop is missing a row")
        assertEquals(2, phone.rows.size, "the phone is missing a row")
        assertTrue(sameRows(pc, phone), "the two devices disagree")
    }

    @Test
    fun `the queue empties even on a run that sends nothing`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("dailies"))
        syncOnce(cloud, pc)

        // Taking the row in queues it, because the triggers cannot tell an
        // incoming row from a local edit — deliberately, since the one guard
        // that could would also skip softDelete. Done here by hand rather than
        // by syncing, because a whole run would settle it again before this
        // could be looked at, which is the point of the run rather than
        // something to hide.
        drive { phone.apply(listOf(pc.rows.values.first())) }
        assertTrue(phone.queued.isNotEmpty(), "applying should have queued the row")

        // So the push half has to recognise that the far side already holds this
        // exact version, send nothing, and still clear the entry.
        val report = syncOnce(cloud, phone)

        assertEquals(0, report.pushed)
        assertEquals(null, report.wrote)
        assertTrue(
            phone.queued.isEmpty(),
            "a row skipped because the far side has it is a decision, not a postponement",
        )
    }

    @Test
    fun `an edit made while a row arrives is not swallowed by the merge`() {
        // The failure this is written for: apply() removing outbox entries for
        // the rows it just wrote. A row that arrives while this device holds a
        // newer edit of it is merged, and the merged record carries this
        // device's version — so clearing the queue by name and version would
        // remove the entry this device's own edit had made, and that edit would
        // never be sent. The far side keeps the old name for ever, silently.
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("meds"))
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        // The phone ticks it done at ten. The desktop renames it at eleven.
        phone.write(
            "tasks", "u1", "2026-08-15T10:00:00.000Z",
            task("meds", completedUntil = "2026-08-16T04:00:00.000Z"),
        )
        syncOnce(cloud, phone)
        pc.write("tasks", "u1", "2026-08-15T11:00:00.000Z", task("ยาความดัน"))

        // The desktop pulls the tick. The rename is newer, so it wins the row;
        // the completion is further along, so it survives the merge. Both facts
        // now live only in the merged row, which nobody else has.
        val pulled = syncOnce(cloud, pc)
        assertEquals(1, pulled.applied)
        val merged = pc.rows[Engine.rowKey("tasks", "u1")] ?: fail("the row went missing")
        assertEquals(SyncValue.Text("ยาความดัน"), merged.fields["name"])
        assertEquals(SyncValue.Text("2026-08-16T04:00:00.000Z"), merged.fields["completed_until"])
        assertTrue(pulled.pushed > 0, "the merged row was never sent")

        syncOnce(cloud, phone)
        val back = phone.rows[Engine.rowKey("tasks", "u1")] ?: fail("the row went missing")
        assertEquals(SyncValue.Text("ยาความดัน"), back.fields["name"], "the rename never arrived")
        assertEquals(
            SyncValue.Text("2026-08-16T04:00:00.000Z"),
            back.fields["completed_until"],
            "the tick was undone",
        )
    }

    @Test
    fun `a foreign clock an hour ahead does not stop a local edit being sent`() {
        // What the outbox replaced. The old push asked for rows above a
        // watermark, and the watermark was moved to the newest timestamp the
        // run had looked at — rows pulled from the other device included. One
        // device an hour ahead therefore pushed the other's watermark an hour
        // into the future, and every local edit made in that hour landed below
        // it and was never sent.
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        phone.write("tasks", "u-fast", "2026-08-15T13:00:00.000Z", task("from the fast one"))
        syncOnce(cloud, phone)
        syncOnce(cloud, pc)
        assertTrue(pc.rows.containsKey(Engine.rowKey("tasks", "u-fast")))

        // An hour behind everything it has just taken in.
        pc.write("tasks", "u-slow", "2026-08-15T12:00:00.000Z", task("from the slow one"))
        val out = syncOnce(cloud, pc)
        assertEquals(1, out.pushed, "the local edit was below the old watermark and lost")

        syncOnce(cloud, phone)
        assertTrue(
            phone.rows.containsKey(Engine.rowKey("tasks", "u-slow")),
            "it never reached the other device",
        )
    }

    @Test
    fun `an idle sync writes nothing at all`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("dailies"))
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        val settled = cloud.files.size
        repeat(5) {
            syncOnce(cloud, pc)
            syncOnce(cloud, phone)
        }

        assertEquals(settled, cloud.files.size, "the log grew while nothing was happening")
        assertFalse("dev-bbb-1.reup" in cloud.files, "a pulled row was sent straight back")
    }

    @Test
    fun `ticking a task done on one device is not undone by the other`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("dailies"))
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        // Ticked on the phone at ten, renamed on the desktop at eleven. The
        // rename is newer so it wins the base — and the tick has to survive it.
        phone.write("tasks", "u1", "2026-08-14T10:00:00.000Z", task("dailies", "2026-08-15T04:00:00.000Z"))
        pc.write("tasks", "u1", "2026-08-14T11:00:00.000Z", task("dailies renamed"))

        syncOnce(cloud, phone)
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        val row = pc.rows[Engine.rowKey("tasks", "u1")] ?: fail("the row vanished")
        assertEquals(SyncValue.Text("dailies renamed"), row.fields["name"], "the newer name should win")
        assertEquals(
            SyncValue.Text("2026-08-15T04:00:00.000Z"),
            row.fields["completed_until"],
            "the task came back undone",
        )
        assertTrue(sameRows(pc, phone), "the two devices disagree")
    }

    @Test
    fun `an unreadable file is reported and retried, not skipped forever`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("one"))
        syncOnce(cloud, pc)
        pc.write("tasks", "u2", "2026-08-14T09:01:00.000Z", task("two"))
        syncOnce(cloud, pc)
        pc.write("tasks", "u3", "2026-08-14T09:02:00.000Z", task("three"))
        syncOnce(cloud, pc)

        cloud.unreadable += "dev-aaa-2.reup"
        val report = syncOnce(cloud, phone)
        assertEquals(1, report.skipped.size, "the bad file should be reported, not thrown")

        // Rows from file 3 are applied even though file 2 is missing: order does
        // not change the answer. The cursor is what has to stop, so that file 2
        // comes back next time rather than being buried under a cursor that has
        // moved past it.
        assertEquals(1L, phone.state.cursor["dev-aaa"], "the cursor jumped the gap")

        cloud.unreadable.clear()
        syncOnce(cloud, phone)
        assertTrue(sameRows(pc, phone), "the retry did not pick up what was missed")
    }

    @Test
    fun `a stranger's file in the folder is not even attempted`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        cloud.files["holiday.jpg"] = "not ours".encodeToByteArray()
        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("one"))
        assertEquals(0, syncOnce(cloud, pc).skipped.size, "a file that is not ours should be ignored silently")
    }

    @Test
    fun `an interrupted upload never reuses its sequence number`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("one"))

        cloud.refusePut = true
        val failed = runCatching { syncOnce(cloud, pc) }.exceptionOrNull()
        assertTrue(failed != null, "a failed upload should surface")

        cloud.refusePut = false
        syncOnce(cloud, pc)
        assertTrue("dev-aaa-2.reup" in cloud.files, "the retry should take a fresh number")
        assertFalse("dev-aaa-1.reup" in cloud.files, "the reserved number should stay unused")

        val phone = MemoryStore("dev-bbb")
        syncOnce(cloud, phone)
        assertTrue(
            Engine.rowKey("tasks", "u1") in phone.rows,
            "a gap in the sequence stopped the other device",
        )
    }

    @Test
    fun `a deleted row is not resurrected by an older copy`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")
        val expense = mapOf(
            "amount" to SyncValue.Num(1200.0),
            "category" to SyncValue.Text("food"),
        )

        pc.write("expenses", "e1", "2026-08-14T09:00:00.000Z", expense)
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)
        pc.write("expenses", "e1", "2026-08-14T10:00:00.000Z", expense, deleted = true)
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        // The oldest failure in this design: a stale copy turns up later and
        // brings the row back from the dead.
        phone.write("expenses", "e1", "2026-08-14T08:00:00.000Z", expense)
        syncOnce(cloud, phone)
        syncOnce(cloud, pc)

        assertEquals(true, pc.rows[Engine.rowKey("expenses", "e1")]?.deleted, "the row came back")
    }

    @Test
    fun `nothing readable is left in the file on the way out`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("blood pressure meds"))
        syncOnce(cloud, pc)

        val bytes = cloud.files.values.first()
        assertFalse(
            bytes.decodeToString().contains("blood pressure"),
            "the task name is sitting in the file in the clear",
        )
    }

    @Test
    fun `three devices agree whatever order they sync in`() {
        val orders = listOf(
            listOf("a", "b", "c", "a", "b", "c"),
            listOf("c", "b", "a", "c", "b", "a"),
            listOf("a", "a", "b", "c", "b", "c", "a"),
            listOf("b", "c", "a", "b", "a", "c", "b", "a", "c"),
        )
        for (order in orders) {
            val cloud = MemoryStorage()
            val stores = mapOf(
                "a" to MemoryStore("dev-aaa"),
                "b" to MemoryStore("dev-bbb"),
                "c" to MemoryStore("dev-ccc"),
            )
            stores["a"]!!.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("one"))
            stores["b"]!!.write("tasks", "u1", "2026-08-14T09:00:00.001Z", task("one edited"))
            stores["c"]!!.write("tasks", "u2", "2026-08-14T09:00:00.000Z", task("two"))

            for (who in order) syncOnce(cloud, stores[who]!!)
            // One more round so the last writer's batch reaches everyone.
            for (who in listOf("a", "b", "c", "a", "b", "c")) syncOnce(cloud, stores[who]!!)

            assertTrue(
                sameRows(stores["a"]!!, stores["b"]!!) && sameRows(stores["b"]!!, stores["c"]!!),
                "three devices disagree after syncing in the order ${order.joinToString("")}",
            )
        }
    }

    @Test
    fun `a device that slept through the deleted window still catches up`() {
        // The failure the whole retention rule exists to prevent, run rather
        // than argued. The phone syncs once, sleeps through a hundred batches,
        // and comes back to a folder whose early files no longer exist. Nothing
        // errors either way — the question is only whether it ends up holding
        // the same rows.
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        val phone = MemoryStore("dev-bbb")

        pc.write("tasks", "u0", "2026-08-14T09:00:00.000Z", task("first"))
        pc.write("expenses", "e1", "2026-08-14T09:10:00.000Z", mapOf("amount" to SyncValue.Num(1200.0)))
        val first = syncOnce(cloud, pc)
        assertTrue(!first.snapshot && first.wrote != null, "an ordinary first push is not a snapshot")

        val arriving = syncOnce(cloud, phone)
        assertEquals(null, arriving.wrote, "a device that only pulled should write nothing")
        assertEquals(false, phone.rows[Engine.rowKey("expenses", "e1")]?.deleted)
        val asleepAt = phone.state.cursor["dev-aaa"] ?: 0L

        // The expense is deleted while the phone is away, which is worse than a
        // missing row: the phone holds a live copy of its own, so a snapshot
        // that carried only live rows would bring it back from the dead.
        pc.write(
            "expenses", "e1", "2026-08-14T09:20:00.000Z",
            mapOf("amount" to SyncValue.Num(1200.0)), deleted = true,
        )

        for (i in 1..100) {
            val mm = (i / 60).toString().padStart(2, '0')
            val ss = (i % 60).toString().padStart(2, '0')
            pc.write("tasks", "u$i", "2026-08-14T10:$mm:$ss.000Z", task("task $i"))
            syncOnce(cloud, pc)
        }
        // And a few runs with nothing new to say, which is where a prune that
        // ran out of its per-run budget finishes the job.
        repeat(4) { syncOnce(cloud, pc) }

        val mine = cloud.files.keys.filter { it.startsWith("dev-aaa-") }
        val seqOf = { n: String -> n.removePrefix("dev-aaa-").removeSuffix(".reup").toLong() }
        assertTrue(
            mine.size < Engine.SNAPSHOT_AFTER_FILES,
            "the folder is cumulative rather than bounded: ${mine.size} files",
        )
        assertTrue(
            mine.minOf(seqOf) > asleepAt,
            "nothing the sleeping device stopped at was ever deleted",
        )
        assertTrue(
            cloud.files.keys.all { it.startsWith("dev-aaa-") },
            "a device deleted a file that was not its own",
        )
        assertEquals(asleepAt, phone.state.cursor["dev-aaa"], "the phone should have noticed nothing")

        syncOnce(cloud, phone)
        syncOnce(cloud, pc)
        syncOnce(cloud, phone)

        assertTrue(sameRows(pc, phone), "the two devices disagree after a prune")
        assertEquals(102, phone.rows.size, "rows announced only in deleted files went missing")
        assertEquals(
            true, phone.rows[Engine.rowKey("expenses", "e1")]?.deleted,
            "a deletion announced only in a deleted file was undone",
        )
        assertEquals(
            true, pc.rows[Engine.rowKey("expenses", "e1")]?.deleted,
            "the desktop got the deleted row back",
        )
    }

    @Test
    fun `pruning does not make an idle sync noisy`() {
        val cloud = MemoryStorage()
        val pc = MemoryStore("dev-aaa")
        pc.write("tasks", "u1", "2026-08-14T09:00:00.000Z", task("one"))
        syncOnce(cloud, pc)

        val after = cloud.files.size
        repeat(10) {
            val r = syncOnce(cloud, pc)
            assertTrue(!r.snapshot, "an idle sync should not keep snapshotting")
            assertEquals(0, r.pruned)
        }
        assertEquals(after, cloud.files.size, "an idle sync wrote a file")
    }

    @Test
    fun `sync state survives a round trip through storage`() {
        val s = SyncState("dev-aaa", 12L, mapOf("dev-bbb" to 4L, "dev-ccc" to 9L), 9L, 3L)
        assertEquals(s, BatchCodec.decodeState(BatchCodec.encodeState(s), "dev-aaa"))
    }

    @Test
    fun `a state blob written by the watermark version still reads`() {
        // Every install has one of these on disk right now. The field it carries
        // is gone, and an unknown key must be ignored rather than throwing —
        // decodeState falling back to a fresh state would cost a device its
        // cursor and make it re-read the whole folder on the launch after the
        // update.
        val old = """{"device":"dev-aaa","seq":12,"cursor":{"dev-bbb":4},"pushedThrough":"2026-08-15T00:00:00.000Z"}"""
        assertEquals(
            SyncState("dev-aaa", 12L, mapOf("dev-bbb" to 4L)),
            BatchCodec.decodeState(old, "dev-aaa"),
        )
    }

    @Test
    fun `a corrupted state blob costs one wasted sync, not sync itself`() {
        assertEquals(Engine.emptyState("dev-aaa"), BatchCodec.decodeState("{ not json", "dev-aaa"))
        assertEquals(Engine.emptyState("dev-aaa"), BatchCodec.decodeState(null, "dev-aaa"))
    }
}