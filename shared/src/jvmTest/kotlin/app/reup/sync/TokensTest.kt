// shared/src/jvmTest/kotlin/app/reup/sync/TokensTest.kt
package app.reup.sync

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Same driver as the other suites: nothing below ever really suspends, so a
// coroutines-test dependency would be added to schedule work that never needs
// scheduling.
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

class TokensTest {

    private val full = OAuthTokens("at-1", "rt-1", 1_800_000_000L)

    @Test
    fun `tokens survive being written down and read back`() = runHere {
        val db = FakeDb()
        val store = SettingsTokenStore(db)
        store.save(full)
        assertEquals(full, store.load())
    }

    @Test
    fun `the stored shape is the one the desktop writes`() {
        // Neither device reads the other's row — app_settings is the table kept
        // out of sync on purpose — so this buys one thing: two databases opened
        // side by side show one format rather than two that mean the same.
        assertEquals(
            """{"accessToken":"at-1","refreshToken":"rt-1","expiresAtSec":1800000000}""",
            SettingsTokenStore.serialise(full),
        )
    }

    @Test
    fun `no refresh token is left out rather than written as null`() {
        assertEquals(
            """{"accessToken":"at-1","expiresAtSec":1800000000}""",
            SettingsTokenStore.serialise(OAuthTokens("at-1", null, 1_800_000_000L)),
        )
    }

    @Test
    fun `nothing stored is not connected, and is not an error`() = runHere {
        assertNull(SettingsTokenStore(FakeDb()).load())
    }

    @Test
    fun `an unreadable row reads as not connected rather than throwing`() = runHere {
        // The recovery is one button. A settings screen that will not open
        // because a stored value went strange is worse than one that offers to
        // sign in again.
        val db = FakeDb()
        for (bad in listOf("not json", "[]", "{}", """{"accessToken":"a"}""", """{"expiresAtSec":1}""")) {
            db.rows[SYNC_TOKENS_KEY] = bad
            assertNull(SettingsTokenStore(db).load(), bad)
        }
    }

    @Test
    fun `clearing forgets the row entirely`() = runHere {
        val db = FakeDb()
        val store = SettingsTokenStore(db)
        store.save(full)
        store.clear()
        assertNull(store.load())
        assertTrue(SYNC_TOKENS_KEY !in db.rows)
    }

    @Test
    fun `saving twice replaces rather than duplicates`() = runHere {
        val db = FakeDb()
        val store = SettingsTokenStore(db)
        store.save(full)
        store.save(full.copy(accessToken = "at-2"))
        assertEquals("at-2", store.load()?.accessToken)
        assertEquals(1, db.rows.size)
    }

    @Test
    fun `a token with no refresh token counts as not signed in`() = runHere {
        // It works for the rest of this hour and then cannot be renewed, which
        // is a state that looks connected and behaves like a countdown. Better
        // to call it what it is now than to have it fail on its own later.
        val db = FakeDb()
        SettingsTokenStore(db).save(OAuthTokens("at-1", null, 1_800_000_000L))
        assertNull(driveTokenSource(db, FakeHttp(), "client-1") { 0L })
    }

    @Test
    fun `a build with no client id cannot be signed in either`() = runHere {
        val db = FakeDb()
        SettingsTokenStore(db).save(full)
        assertNull(driveTokenSource(db, FakeHttp(), null) { 0L })
        assertNull(driveTokenSource(db, FakeHttp(), "") { 0L })
    }

    @Test
    fun `a complete token and a client id make a source`() = runHere {
        val db = FakeDb()
        SettingsTokenStore(db).save(full)
        assertTrue(driveTokenSource(db, FakeHttp(), "client-1") { 0L } != null)
    }

    private class FakeDb : Db {
        val rows = mutableMapOf<String, String>()

        override suspend fun execute(sql: String, params: List<SyncValue>) {
            val key = (params[0] as SyncValue.Text).value
            if (sql.startsWith("DELETE")) rows.remove(key)
            else rows[key] = (params[1] as SyncValue.Text).value
        }

        override suspend fun select(sql: String, params: List<SyncValue>): List<DbRow> {
            val key = (params.firstOrNull() as? SyncValue.Text)?.value ?: return emptyList()
            val v = rows[key] ?: return emptyList()
            return listOf(mapOf("value" to SyncValue.Text(v)))
        }
    }

    /** Never reached: every test here stops before anything would be sent. */
    private class FakeHttp : HttpTransport {
        override suspend fun send(req: HttpRequest): HttpResponse =
            throw AssertionError("no request should have been made")
    }
}