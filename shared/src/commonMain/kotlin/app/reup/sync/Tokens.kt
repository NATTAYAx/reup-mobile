package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

const val SYNC_TOKENS_KEY: String = "sync_google_tokens"

/**
 * Where the Google tokens live between runs: in app_settings, beside the
 * pairing code.
 *
 * WHY NOT EncryptedSharedPreferences AND THE ANDROID KEYSTORE
 *
 * That is the answer the Drive header assumed, and it is the wrong one here for
 * a reason that only becomes obvious once both secrets are on the same screen.
 *
 * The refresh token opens a folder of blobs this app encrypted. The pairing
 * code is what decrypts them. The pairing code is already in this table, and it
 * has to be — it is a setting a person types in and reads back. Putting the
 * weaker secret behind a stronger lock while the stronger one sits in a row
 * next to it is not defence, it is decoration. Worse, it leaves two stories
 * about where secrets live on this device, and two stories is how one of them
 * ends up in a backup by accident.
 *
 * app_settings is outside SYNC_TABLES and stripped from exported backups, and
 * both of those already had to be true for the pairing code. The token inherits
 * them.
 *
 * If that judgement is ever revisited it should be revisited for both keys at
 * once, and TokenStore being an interface is the reason that is one file rather
 * than a search.
 *
 * WHY THIS IS IN commonMain AND NOT IN app/
 *
 * Because it needs nothing but `Db`, which both platforms already have. Writing
 * it twice would put the same decision in two files that no test compares —
 * exactly the shape of the parser bug, the translation bug and the category bug
 * this project has already paid for three times.
 */
class SettingsTokenStore(private val db: Db) : TokenStore {

    override suspend fun load(): OAuthTokens? {
        val rows = db.select(
            "SELECT value FROM app_settings WHERE key = ?",
            listOf(SyncValue.Text(SYNC_TOKENS_KEY)),
        )
        val raw = (rows.firstOrNull()?.get("value") as? SyncValue.Text)?.value ?: return null

        val o = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            // Unreadable reads as "not connected" rather than throwing. The
            // recovery is one button, and a settings screen that will not open
            // because a stored value went strange is worse than one that offers
            // to sign in again.
            null
        } ?: return null

        val access = (o["accessToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val expires = (o["expiresAtSec"] as? JsonPrimitive)?.let {
            runCatching { it.long }.getOrNull()
        } ?: return null
        val refresh = (o["refreshToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        return OAuthTokens(accessToken = access, refreshToken = refresh, expiresAtSec = expires)
    }

    override suspend fun save(tokens: OAuthTokens) {
        db.execute(
            "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(SyncValue.Text(SYNC_TOKENS_KEY), SyncValue.Text(serialise(tokens))),
        )
    }

    override suspend fun clear() {
        db.execute(
            "DELETE FROM app_settings WHERE key = ?",
            listOf(SyncValue.Text(SYNC_TOKENS_KEY)),
        )
    }

    companion object {
        /**
         * The same shape and key order the desktop's JSON.stringify emits, and
         * `refreshToken` omitted rather than written as null when there is none.
         *
         * Neither device ever reads the other's row — this is the one table
         * deliberately kept out of sync — so this costs nothing and buys one
         * thing: two databases opened side by side show one format rather than
         * two that mean the same.
         */
        fun serialise(t: OAuthTokens): String {
            val m = LinkedHashMap<String, JsonPrimitive>()
            m["accessToken"] = JsonPrimitive(t.accessToken)
            t.refreshToken?.let { m["refreshToken"] = JsonPrimitive(it) }
            m["expiresAtSec"] = JsonPrimitive(t.expiresAtSec)
            return JsonObject(m).toString()
        }
    }
}

/**
 * The refreshing half of Drive, or null when this device has not signed in.
 *
 * A function rather than something the screen assembles, because "signed in"
 * has to mean the same thing to the sync button, to the settings screen and to
 * whatever calls sync on its own later. Three answers to one question is how
 * one of them ends up saying yes while another says no.
 */
suspend fun driveTokenSource(
    db: Db,
    http: HttpTransport,
    clientId: String?,
    /** Seconds since the epoch. Supplied so a test can be at a fixed moment. */
    nowSec: () -> Long,
): AccessTokenSource? {
    if (clientId.isNullOrEmpty()) return null
    val store = SettingsTokenStore(db)
    // A stored token with no refresh token is worth as little as none: it works
    // for the rest of this hour and then cannot be renewed.
    if (store.load()?.refreshToken == null) return null
    return GoogleTokenSource(http, clientId, store, nowSec)
}