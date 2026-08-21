package app.reup.sync

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ─── Config.kt — turning a person's settings into a running sync ─────────────
//
// Mirror of src/lib/sync/config.ts. Everything else in this package is a part;
// this is the only file that knows how the parts fit together, and it exists so
// that no screen ever assembles a store, a transport, a storage adapter and a
// key inline.
//
// WHY THERE ARE NO CROSS-LANGUAGE VECTORS FOR THIS FILE
//
// Every other pair of mirrored files in this project — merge, the engine, the
// store, crypto — is backed by a vector suite, because those decide what goes
// into a file that the other device has to read. This one does not. The config
// row lives in `app_settings`, which is not a synced table, and it is kept out
// of the backup on purpose because it holds the key. Nothing here ever crosses
// between the two machines, so there is nothing for them to disagree about, and
// a vector file here would be ceremony rather than proof.
//
// What does cross is the pairing code itself, and that already has vectors: the
// crypto suite round-trips it against the desktop's encoder byte for byte.
//
// WHAT THE PHONE DELIBERATELY CANNOT DO
//
// Mint a pairing code. There is no `newPairing` here on purpose. The desktop
// creates the bucket and the key, once, on a screen wide enough to say plainly
// that losing it means losing the data; the phone only ever receives one. One
// place that can create a key is one place that has to warn about it.

/** The one row in `app_settings` that holds all of this. */
const val SYNC_CONFIG_KEY = "sync_config_v1"

/**
 * Google Drive carries no fields of its own.
 *
 * The folder is fixed and the tokens live under their own key, written by the
 * sign-in rather than typed into a box. That made it look, on the desktop, like
 * there was nothing to do here — and the parser was written without a branch
 * for it. The config was saved as drive and read back as off, so the settings
 * screen showed Drive selected out of memory while every sync loaded `off` from
 * the database and quietly decided there was nothing to do. Nothing errored.
 *
 * A backend with no fields still has a name, and the name is the thing that has
 * to survive being written down.
 */
sealed interface SyncBackend {
    data object Off : SyncBackend
    data class WebDav(
        val baseUrl: String,
        val username: String,
        val password: String,
    ) : SyncBackend
    data object Drive : SyncBackend
}

data class SyncConfig(
    val backend: SyncBackend,
    /**
     * The pairing code as text, exactly as it was typed in.
     *
     * Stored as the string rather than as a parsed bucket id and key, so that
     * what is on the screen and what is on disk are the same characters. A code
     * displayed after a round trip through a parser is a code that can be copied
     * down correctly and still not work.
     */
    val pairing: String?,
)

val SYNC_OFF = SyncConfig(SyncBackend.Off, null)

object SyncConfigs {

    /**
     * Reading never throws and never discards.
     *
     * A malformed backend falls back to off: the worst case is that sync stays
     * quiet until someone sets it up again. A malformed pairing code is kept
     * exactly as found, because dropping it would delete the only copy of a key,
     * which is the one mistake in this file that cannot be undone. Whether it is
     * usable is decided at the point of use, by [pairingOf].
     */
    fun parse(raw: String?): SyncConfig {
        if (raw.isNullOrEmpty()) return SYNC_OFF

        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return SYNC_OFF

        val pairing = str(root["pairing"])?.takeIf { it.isNotEmpty() }
        val b = root["backend"] as? JsonObject ?: return SyncConfig(SyncBackend.Off, pairing)

        val baseUrl = str(b["baseUrl"])
        val username = str(b["username"])
        val password = str(b["password"])

        if (str(b["kind"]) == "drive") return SyncConfig(SyncBackend.Drive, pairing)

        return if (
            str(b["kind"]) == "webdav" &&
            baseUrl != null && baseUrl.isNotEmpty() &&
            username != null && password != null
        ) {
            SyncConfig(SyncBackend.WebDav(baseUrl, username, password), pairing)
        } else {
            // Anything else, including a backend a newer version knows about,
            // reads as off rather than as a crash.
            SyncConfig(SyncBackend.Off, pairing)
        }
    }

    /**
     * The same shape and the same key order the desktop's JSON.stringify emits.
     *
     * Neither side ever reads the other's row, so this costs nothing and buys
     * one thing: anyone opening the two databases side by side sees one format
     * rather than two that mean the same.
     */
    fun serialise(c: SyncConfig): String {
        val backend = when (val b = c.backend) {
            SyncBackend.Off -> JsonObject(linkedMapOf("kind" to JsonPrimitive("off")))
            is SyncBackend.WebDav -> JsonObject(
                linkedMapOf(
                    "kind" to JsonPrimitive("webdav"),
                    "baseUrl" to JsonPrimitive(b.baseUrl),
                    "username" to JsonPrimitive(b.username),
                    "password" to JsonPrimitive(b.password),
                ),
            )
            SyncBackend.Drive -> JsonObject(linkedMapOf("kind" to JsonPrimitive("drive")))
        }
        val pairing = c.pairing?.let { JsonPrimitive(it) } ?: JsonNull
        return JsonObject(linkedMapOf("backend" to backend, "pairing" to pairing)).toString()
    }

    /** The usable form, or null. Never throws: a bad code is a state, not a crash. */
    fun pairingOf(c: SyncConfig): Pairing? {
        val text = c.pairing ?: return null
        return try {
            PairingCode.decode(text)
        } catch (e: CryptoException) {
            null
        }
    }

    /** Both halves present: somewhere to put it, and something to lock it with. */
    fun isReady(c: SyncConfig): Boolean =
        c.backend !is SyncBackend.Off && pairingOf(c) != null

    /**
     * Throws for a URL that cannot be used, because that is a setting to fix
     * rather than a condition to survive. Returns null only for "turned off".
     */
    fun storageFor(
        c: SyncConfig,
        http: HttpTransport,
        /**
         * Only Drive needs this, and only to find its refresh token. Optional so
         * that every caller that only ever meant WebDAV keeps working, and so
         * that "Drive with nobody signed in" is a missing argument rather than a
         * surprise at the first request.
         */
        tokens: AccessTokenSource? = null,
        /**
         * Only Drive uses this, and only to separate the parts of one upload.
         *
         * No default, unlike the desktop's, because there is no randomness in
         * common Kotlin to default it to — the platform supplies that through
         * AeadCipher, and a boundary quietly defaulted to a constant is one that
         * appears inside a payload one day and cuts the request in half.
         */
        newBoundary: (() -> String)? = null,
    ): SyncStorage? = when (val b = c.backend) {
        SyncBackend.Off -> null
        is SyncBackend.WebDav -> WebDavStorage(
            http,
            WebDavConfig(baseUrl = b.baseUrl, username = b.username, password = b.password),
        )
        SyncBackend.Drive ->
            if (tokens == null || newBoundary == null) null
            else GoogleDriveStorage(http, tokens, newBoundary)
    }

    /**
     * A multipart boundary: random, and never longer than the header allows.
     *
     * From the same source as the device id rather than from a counter, because
     * the one rule a boundary has is that it must not occur inside the bytes it
     * separates, and the bytes here are ciphertext.
     */
    fun newBoundary(cipher: AeadCipher): String {
        val bytes = cipher.randomBytes(12)
        val sb = StringBuilder(26).append("b-")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Whether two configs describe the same conversation.
     *
     * Not the same settings — the same *folder and key*. Sync state is a record
     * of what has been said to one pile of files, locked with one key. Point the
     * app somewhere else, or at the same place with a different key, and every
     * sentence in that record is about something that is no longer there.
     */
    fun sameTarget(a: SyncConfig, b: SyncConfig): Boolean {
        if (a.pairing != b.pairing) return false
        val x = a.backend
        val y = b.backend
        return when {
            x is SyncBackend.WebDav && y is SyncBackend.WebDav ->
                // Only the address. A password that changed is the same folder
                // behind a new door, and re-uploading everything because a typo
                // was corrected would be silly.
                x.baseUrl == y.baseUrl
            else -> x::class == y::class
        }
    }

    suspend fun load(db: Db): SyncConfig {
        val rows = db.select(
            "SELECT value FROM app_settings WHERE key = ?",
            listOf(SyncValue.Text(SYNC_CONFIG_KEY)),
        )
        return parse(rows.firstOrNull()?.let { str(it["value"]) })
    }

    suspend fun save(db: Db, c: SyncConfig) {
        // Read before write, so "did the target change" is answered here rather
        // than remembered by every screen that can change a setting. There is
        // one such screen today; the second is where it would be forgotten.
        val previous = load(db)

        db.execute(
            "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(SyncValue.Text(SYNC_CONFIG_KEY), SyncValue.Text(serialise(c))),
        )

        // Off is not a different folder, it is no folder. Coming back to the
        // same one should not mean re-uploading the database.
        if (c.backend is SyncBackend.Off || previous.backend is SyncBackend.Off) return
        if (!sameTarget(previous, c)) forgetRemoteProgress(db)
    }

    /**
     * Forget what was said to the old folder, without forgetting who is saying it.
     *
     * `device` is this installation's name and must never change: it is half of
     * every file name this device has written, and a device that renames itself
     * becomes a second device that the first will happily read its own writes
     * back from.
     *
     * `seq` is kept for a sharper reason. It is not "how far through this
     * folder" — it is "the highest number I have ever put on a file". Resetting
     * it would mean writing `d-me-1` a second time, and if the old folder is
     * ever pointed at again there would be two different files with one name,
     * which is the hazard `an interrupted upload never reuses its sequence
     * number` exists to prevent. Numbers go up only, in every folder, for the
     * life of the install.
     *
     * `cursor` is the one that is about the folder, and it is the one that
     * goes — along with the outbox, which is the thing that actually decides
     * what gets sent. Lowering a watermark used to be what put the whole
     * database back in the outgoing pile; with a queue instead of a comparison
     * that has to be said rather than implied, so the queue is refilled here.
     * The seeding is Db's, not a second copy of it.
     *
     * WITHOUT THIS, moving this phone to a new folder reports success and sends
     * nothing: the cursor is clear so it reads the folder from the start, but
     * the queue is empty because everything in it was settled against the old
     * folder. Which is the desktop's `sent 0 out` against an empty Drive, on
     * the device that has no screen to notice it on.
     */
    suspend fun forgetRemoteProgress(db: Db) {
        val rows = db.select(
            "SELECT value FROM app_settings WHERE key = ?",
            listOf(SyncValue.Text(SYNC_STATE_KEY)),
        )
        val raw = rows.firstOrNull()?.let { str(it["value"]) } ?: return

        val root = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
            // Unreadable state is replaced wholesale by the next run anyway.
            // Nothing to preserve, and nothing worth complaining about.
        } ?: return

        val device = str(root["device"]) ?: return
        val seq = (root["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

        val kept = JsonObject(
            linkedMapOf(
                "device" to JsonPrimitive(device),
                "seq" to JsonPrimitive(seq),
                "cursor" to JsonObject(linkedMapOf()),
            ),
        )
        db.execute(
            "INSERT INTO app_settings (key, value) VALUES (?, ?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            listOf(SyncValue.Text(SYNC_STATE_KEY), SyncValue.Text(kept.toString())),
        )

        for (m in SyncMigrations.outboxReseed()) db.execute(m.sql)
    }

    private fun str(e: Any?): String? = when (e) {
        is JsonPrimitive -> if (e.isString) e.content else null
        else -> null
    }

    private fun str(v: SyncValue?): String? = (v as? SyncValue.Text)?.value

    // ─── running one ────────────────────────────────────────────────────────

    /**
     * A device id, from the only source of randomness both platforms have.
     *
     * Sixteen hex characters after a `d-`, which is the desktop's format down
     * to the character, because it ends up in a filename that the other device
     * parses. It is random and means nothing to anybody: deliberately not the
     * phone's name, which is a person's name often enough that it would put one
     * in a folder that may be shared, in exchange for nothing — nothing ever
     * displays it.
     *
     * Taken from the cipher rather than from Random because the cipher is the
     * one thing in reach that is required to be cryptographically secure, and a
     * predictable device id lets someone who can list the folder work out which
     * names will be written next.
     */
    fun newDeviceId(cipher: AeadCipher): String {
        val bytes = cipher.randomBytes(8)
        val sb = StringBuilder(18).append("d-")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * The assembly, with nothing left to look up.
     *
     * Split out from [syncNow] for the same reason as on the desktop: the whole
     * round trip can then be run against an in-memory storage in a test, with
     * no database, no transport and no settings row anywhere in sight.
     */
    suspend fun syncWith(
        store: LocalStore,
        storage: SyncStorage,
        cipher: AeadCipher,
        pairing: Pairing,
    ): SyncReport = Engine.sync(
        storage = storage,
        store = store,
        cipher = cipher,
        bucketId = pairing.bucketId,
        key = pairing.key,
        // Debug only downstream: the engine records it in the batch so a run can
        // be dated, and never compares it against anything. The clock that
        // decides which version of a row wins is the database's, not this one.
        now = { Clock.System.now().toString() },
    )

    /**
     * One round trip, or null if sync is not set up.
     *
     * Null rather than an exception because "not set up" is the normal state of
     * this app for anyone who has not asked for sync, and a caller on a timer
     * should not have to tell that apart from a failure.
     *
     * The caller runs the bootstrap first — on the phone that is `TaskRepo.open`
     * — exactly as the desktop has already built its schema before anything
     * calls this. It is not done here because a function that quietly creates
     * tables as a side effect of syncing is a function nobody can reason about
     * when the tables turn out to be wrong.
     */
    suspend fun syncNow(
        db: Db,
        http: HttpTransport,
        cipher: AeadCipher,
        /**
         * Supplied by the caller for the Drive backend, because building one
         * means asking the platform for the client id and the stored token, and
         * neither of those belongs in a file that also decides what a pairing
         * code is.
         */
        tokens: AccessTokenSource? = null,
    ): SyncReport? {
        val cfg = load(db)
        val pairing = pairingOf(cfg) ?: return null
        // Not signed in yet reads as "not set up", the same as an empty address
        // box: a state to fix on the settings screen, not a failure to report.
        val storage = storageFor(cfg, http, tokens, { newBoundary(cipher) }) ?: return null
        val store = SqlLocalStore.open(db, { newDeviceId(cipher) })
        return syncWith(store, storage, cipher, pairing)
    }
}

private const val HEX = "0123456789abcdef"