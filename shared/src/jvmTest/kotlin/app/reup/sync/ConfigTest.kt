package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ─── ConfigTest.kt ───────────────────────────────────────────────────────────
//
// There is no vector file behind this one, unlike every other mirrored pair in
// the project, and Config.kt says why at length: this row never crosses between
// the two machines, so there is nothing for them to disagree about.
//
// What is still worth pinning down is the behaviour a person would notice, and
// one string. The behaviour is that a code is never thrown away by accident.
// The string is the serialised form, written out here in full rather than
// compared to itself through a round trip — a round trip passes just as happily
// when both directions are wrong in the same way.

/** A real code, so the tests exercise the decoder rather than a placeholder. */
private val REAL_CODE = PairingCode.encode(
    Pairing(bucketId = "b7f3a1c2d4e5f60718293a4b5c6d7e8f", key = ByteArray(SealedBlob.KEY_BYTES) { it.toByte() }),
)

// ─── fakes ───────────────────────────────────────────────────────────────────
//
// Same driver as EngineTest and StoreTest: nothing below ever really suspends,
// so a coroutines-test dependency would be pulled into the build to schedule
// work that never needs scheduling. The driver asserts that rather than
// assuming it.
//
// The fakes themselves are nested inside the test class rather than sitting at
// the top of the file next to it. EngineTest already has a MemoryStorage and a
// MemoryStore, and `private` does not keep two classes of the same name in one
// package apart the way it keeps two functions apart — the compiler resolves
// the name to whichever it finds and then refuses it as inaccessible, in the
// other file. Nesting sidesteps the whole question.

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
    check(finished) { "the block actually suspended; this driver only runs code backed by the fakes" }
    thrown?.let { throw it }
}

class ConfigTest {

    private class MemoryStorage : SyncStorage {
        val files = LinkedHashMap<String, ByteArray>()
        override suspend fun list(): List<String> = files.keys.toList()
        override suspend fun get(name: String): ByteArray =
            files[name] ?: throw StorageException(StorageErrorKind.NOT_FOUND, "no such file: $name")
        override suspend fun put(name: String, bytes: ByteArray) {
            check(name !in files) { "append-only violated: $name already exists" }
            files[name] = bytes
        }
        override suspend fun delete(name: String) { files.remove(name) }
    }

    /**
     * The outbox, in a map. Every write to a synced table queues the row,
     * including one that arrived from elsewhere, because the real triggers have
     * no guard that could tell the two apart without also skipping softDelete.
     *
     * The value is the version that is outstanding, not just a flag: a row
     * edited again while an upload is in flight replaces its own entry and
     * survives the settle that follows.
     */
    private class MemoryStore(device: String) : LocalStore {
        val rows = LinkedHashMap<String, ChangeRecord>()
        val queued = LinkedHashMap<String, String>()
        var state: SyncState = Engine.emptyState(device)

        fun write(table: String, uid: String, updatedAt: String, fields: Map<String, SyncValue>) {
            val key = Engine.rowKey(table, uid)
            rows[key] = ChangeRecord(table, uid, updatedAt, false, state.device, fields)
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
        override suspend fun saveState(state: SyncState) { this.state = state }
    }

    /** Answers the one settings read and nothing else, so a test that reaches
     *  further than it should fails loudly rather than quietly passing. */
    private class SettingsOnlyDb(private val value: String?) : Db {
        override suspend fun execute(sql: String, params: List<SyncValue>) {
            throw IllegalStateException("nothing should be written: $sql")
        }
        override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
            check(sql.contains("app_settings")) { "unexpected query: $sql" }
            return if (value == null) emptyList() else listOf(mapOf("value" to SyncValue.Text(value)))
        }
    }

    private val REFUSING_HTTP = object : HttpTransport {
        override suspend fun send(req: HttpRequest): HttpResponse =
            throw IllegalStateException("no request should be made")
    }


    @Test
    fun `nothing stored means off`() {
        assertEquals(SYNC_OFF, SyncConfigs.parse(null))
        assertEquals(SYNC_OFF, SyncConfigs.parse(""))
    }

    @Test
    fun `unreadable settings fall back to off rather than throwing`() {
        // Both of these have been seen in the wild: a half-written row, and a
        // row holding something that is valid JSON but not this.
        assertEquals(SYNC_OFF, SyncConfigs.parse("{\"backend\":"))
        assertEquals(SYNC_OFF, SyncConfigs.parse("\"webdav\""))
        assertEquals(SYNC_OFF, SyncConfigs.parse("[]"))
    }

    @Test
    fun `a complete webdav config reads back whole`() {
        val raw = """{"backend":{"kind":"webdav","baseUrl":"https://box.example/dav/reup/","username":"me","password":"hunter2"},"pairing":"$REAL_CODE"}"""
        val c = SyncConfigs.parse(raw)
        val b = c.backend as SyncBackend.WebDav
        assertEquals("https://box.example/dav/reup/", b.baseUrl)
        assertEquals("me", b.username)
        assertEquals("hunter2", b.password)
        assertEquals(REAL_CODE, c.pairing)
        assertTrue(SyncConfigs.isReady(c))
    }

    @Test
    fun `a backend missing its address is off, and the code survives that`() {
        // This is the shape a half-filled setup screen leaves behind. Falling
        // back to off is fine; taking the key down with it is not.
        val raw = """{"backend":{"kind":"webdav","baseUrl":"","username":"me","password":"x"},"pairing":"$REAL_CODE"}"""
        val c = SyncConfigs.parse(raw)
        assertEquals(SyncBackend.Off, c.backend)
        assertEquals(REAL_CODE, c.pairing)
    }

    @Test
    fun `a code that cannot be read is kept, not deleted`() {
        // The whole point. Dropping it would destroy what may be the only copy
        // of a key, and no later screen could tell the person what was lost.
        val c = SyncConfigs.parse("""{"backend":{"kind":"off"},"pairing":"reup://pair?b=x&k=NOT-A-KEY!!"}""")
        assertEquals("reup://pair?b=x&k=NOT-A-KEY!!", c.pairing)
        assertNull(SyncConfigs.pairingOf(c))
        assertFalse(SyncConfigs.isReady(c))
    }

    @Test
    fun `an empty code is the same as no code`() {
        assertNull(SyncConfigs.parse("""{"backend":{"kind":"off"},"pairing":""}""").pairing)
    }

    @Test
    fun `the serialised form is exactly what the desktop writes`() {
        assertEquals(
            """{"backend":{"kind":"off"},"pairing":null}""",
            SyncConfigs.serialise(SYNC_OFF),
        )
        assertEquals(
            """{"backend":{"kind":"webdav","baseUrl":"http://192.168.1.9:8080/","username":"me","password":"p"},"pairing":"$REAL_CODE"}""",
            SyncConfigs.serialise(
                SyncConfig(SyncBackend.WebDav("http://192.168.1.9:8080/", "me", "p"), REAL_CODE),
            ),
        )
    }

    @Test
    fun `a password with a quote in it survives the trip`() {
        // Hand-built JSON would get this wrong, which is the reason none is
        // hand-built here.
        val c = SyncConfig(SyncBackend.WebDav("https://a/b/", "us\"er", "pa\\ss\"word"), null)
        assertEquals(c, SyncConfigs.parse(SyncConfigs.serialise(c)))
    }

    @Test
    fun `turning it off is not the same as forgetting the code`() {
        val on = SyncConfig(SyncBackend.WebDav("https://a/b/", "u", "p"), REAL_CODE)
        val off = on.copy(backend = SyncBackend.Off)
        assertEquals(REAL_CODE, SyncConfigs.parse(SyncConfigs.serialise(off)).pairing)
        assertFalse(SyncConfigs.isReady(off))
    }

    @Test
    fun `a folder with no key is not ready either`() {
        val c = SyncConfig(SyncBackend.WebDav("https://a/b/", "u", "p"), null)
        assertFalse(SyncConfigs.isReady(c))
    }

    @Test
    fun `storage is built only when there is somewhere to put it`() {
        val http = object : HttpTransport {
            override suspend fun send(req: HttpRequest): HttpResponse =
                throw IllegalStateException("no request should be made")
        }
        assertNull(SyncConfigs.storageFor(SYNC_OFF, http))
        assertTrue(
            SyncConfigs.storageFor(
                SyncConfig(SyncBackend.WebDav("https://a/b", "u", "p"), REAL_CODE),
                http,
            ) is WebDavStorage,
        )
    }

    @Test
    fun `the decoded pairing is the one that went in`() {
        val c = SyncConfig(SyncBackend.Off, REAL_CODE)
        val p = SyncConfigs.pairingOf(c)!!
        assertEquals("b7f3a1c2d4e5f60718293a4b5c6d7e8f", p.bucketId)
        assertEquals(SealedBlob.KEY_BYTES, p.key.size)
    }

    // ─── the composition ────────────────────────────────────────────────────

    @Test
    fun `a device id survives the round trip through a file name`() {
        // Not cosmetic. The id is half of every file name in the bucket, and
        // the other device recovers it by parsing that name. An id the parser
        // rejects would mean batches nobody ever reads, with no error anywhere.
        val cipher = JvmAeadCipher()
        repeat(200) {
            val id = SyncConfigs.newDeviceId(cipher)
            assertTrue(id.startsWith("d-"), id)
            assertEquals(18, id.length, id)
            val parsed = Protocol.parseFileName(Protocol.fileName(id, 7))
            assertEquals(id, parsed?.device, id)
            assertEquals(7L, parsed?.seq)
        }
    }

    @Test
    fun `two device ids are not the same id`() {
        val cipher = JvmAeadCipher()
        val seen = HashSet<String>()
        repeat(500) { seen += SyncConfigs.newDeviceId(cipher) }
        assertEquals(500, seen.size)
    }

    @Test
    fun `the key that seals a batch is the one in the pairing code`() {
        // What this actually pins down is the wiring. Every layer below has its
        // own tests; the way this file can be wrong is by handing the engine
        // the wrong bucket id or the wrong key, and neither would show up as an
        // error — the batch would simply be unreadable by the device it was
        // written for.
        val cipher = JvmAeadCipher()
        val mine = SyncConfigs.pairingOf(SyncConfig(SyncBackend.Off, REAL_CODE))!!
        val theirs = Pairing(
            bucketId = "0123456789abcdef0123456789abcdef",
            key = ByteArray(SealedBlob.KEY_BYTES) { (it + 1).toByte() },
        )

        val cloud = MemoryStorage()
        val desktop = MemoryStore("d-1111111111111111")
        desktop.write(
            "tasks", "u1", "2026-08-16T00:00:00.000Z",
            mapOf("name" to SyncValue.Text("รดน้ำต้นไม้")),
        )
        drive { SyncConfigs.syncWith(desktop, cloud, cipher, mine) }
        assertEquals(1, cloud.files.size)

        // A phone holding a different code sees a file it cannot open. Skipped
        // and reported, never applied, and never fatal.
        val stranger = MemoryStore("d-2222222222222222")
        var refused: SyncReport? = null
        drive { refused = SyncConfigs.syncWith(stranger, cloud, cipher, theirs) }
        assertEquals(1, refused!!.skipped.size)
        assertEquals(0, refused!!.applied)
        assertTrue(stranger.rows.isEmpty())

        // The same file, with the code it was written for.
        val phone = MemoryStore("d-3333333333333333")
        var got: SyncReport? = null
        drive { got = SyncConfigs.syncWith(phone, cloud, cipher, mine) }
        assertEquals(0, got!!.skipped.size)
        assertEquals(1, got!!.applied)
        assertEquals(
            "รดน้ำต้นไม้",
            (phone.rows[Engine.rowKey("tasks", "u1")]!!.fields["name"] as SyncValue.Text).value,
        )
    }

    @Test
    fun `syncNow is null, not an error, when there is nothing set up`() {
        // A caller on a timer should not have to tell "never asked for sync"
        // apart from "the server is down", so this path raises nothing and
        // touches no network at all.
        val cipher = JvmAeadCipher()
        val webdav = """{"backend":{"kind":"webdav","baseUrl":"https://box.example/dav/","username":"u","password":"p"},"pairing":null}"""
        val codeOnly = """{"backend":{"kind":"off"},"pairing":"$REAL_CODE"}"""

        for (row in listOf(null, "", webdav, codeOnly)) {
            var report: SyncReport? = SyncReport(9, 9, 9, emptyList(), "not null")
            drive { report = SyncConfigs.syncNow(SettingsOnlyDb(row), REFUSING_HTTP, cipher) }
            assertNull(report, "row: $row")
        }
    }
}