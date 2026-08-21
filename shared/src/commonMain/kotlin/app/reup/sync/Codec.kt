package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// ─── Codec.kt — turning a batch into bytes and back ──────────────────────────
//
// The JSON here is written by hand against the tree API rather than with
// @Serializable, and that is not an oversight. A ChangeRecord's `fields` is a
// row of a table this file has never heard of, so its shape is only known at
// run time. A generated serialiser needs the shape at compile time, which would
// mean either a Kotlin class per table — the schema written down a second time,
// which is the disease every other file here was written to avoid — or a
// `Map<String, Any?>`, which is the same dynamic tree with the type checking
// switched off.
//
// WHY THIS VALIDATES INSTEAD OF TRUSTING
//
// The bytes have already been authenticated by the AEAD tag, so nothing hostile
// can reach this function. What can reach it is a batch written by a LATER
// version of this app, and half-understanding one of those is the bad case: a
// field silently dropped here is a column that quietly reverts on this device
// every time the newer one syncs, with nothing on screen to say so.
//
// So an envelope this version cannot read is refused as a whole and reported,
// rather than read as far as it goes.
//
// Mirror of the encodeBatch/decodeBatch half of src/lib/sync/engine.ts.

enum class BatchErrorKind { FORMAT, VERSION, MISMATCH }

class BatchException(
    val kind: BatchErrorKind,
    message: String,
) : Exception(message)

object BatchCodec {

    private val json = Json { ignoreUnknownKeys = true }

    // ── values ──────────────────────────────────────────────────────────────

    /**
     * A field map to JSON and back, for the spill table.
     *
     * Local storage only — a spill never leaves the device that wrote it, so
     * this does not have to agree byte for byte with the desktop the way a
     * batch does. It goes through the same value conversion anyway, because a
     * second opinion about what a SQLite value is would be a second place to be
     * wrong.
     */
    fun encodeFields(fields: Map<String, SyncValue>): String =
        JsonObject(fields.mapValues { fromValue(it.value) }).toString()

    fun decodeFields(text: String): Map<String, SyncValue> =
        json.parseToJsonElement(text).jsonObject.mapValues { toValue(it.value) }

    fun toValue(e: JsonElement): SyncValue = when {
        e is JsonNull -> SyncValue.Null
        // Checked first, so the string "true" stays a string. A column holding
        // the word true is not a column holding a boolean.
        e is JsonPrimitive && e.isString -> SyncValue.Text(e.content)
        e is JsonPrimitive && e.content == "true" -> SyncValue.Bool(true)
        e is JsonPrimitive && e.content == "false" -> SyncValue.Bool(false)
        e is JsonPrimitive -> e.content.toDoubleOrNull()?.let { SyncValue.Num(it) }
            ?: throw BatchException(BatchErrorKind.FORMAT, "field value ${e.content} is not a number")
        else -> throw BatchException(
            BatchErrorKind.FORMAT,
            "a field is an object or an array, which no column can hold",
        )
    }

    /**
     * Whole numbers are written without a decimal point.
     *
     * Nothing depends on it — both sides parse 1 and 1.0 to the same value — but
     * a log a person can open and read is worth two lines, and `is_active: 1.0`
     * invites the reader to wonder whether something has been rounded.
     */
    fun fromValue(v: SyncValue): JsonElement = when (v) {
        is SyncValue.Null -> JsonNull
        is SyncValue.Bool -> JsonPrimitive(v.value)
        is SyncValue.Text -> JsonPrimitive(v.value)
        is SyncValue.Num ->
            if (v.value == v.value.toLong().toDouble()) JsonPrimitive(v.value.toLong())
            else JsonPrimitive(v.value)
    }

    // ── records ─────────────────────────────────────────────────────────────

    fun encodeRecord(r: ChangeRecord): JsonObject = buildJsonObject {
        put("table", JsonPrimitive(r.table))
        put("uid", JsonPrimitive(r.uid))
        put("updatedAt", JsonPrimitive(r.updatedAt))
        put("deleted", JsonPrimitive(r.deleted))
        put("origin", JsonPrimitive(r.origin))
        put("fields", JsonObject(r.fields.mapValues { fromValue(it.value) }))
    }

    private fun str(o: JsonObject, key: String): String {
        val p = o[key] as? JsonPrimitive
            ?: throw BatchException(BatchErrorKind.FORMAT, "change has no $key")
        if (!p.isString) throw BatchException(BatchErrorKind.FORMAT, "$key is not a string")
        return p.content
    }

    fun decodeRecord(o: JsonObject): ChangeRecord {
        val table = str(o, "table")
        if (table.isEmpty()) throw BatchException(BatchErrorKind.FORMAT, "change has no table")
        val uid = str(o, "uid")
        if (uid.isEmpty()) throw BatchException(BatchErrorKind.FORMAT, "change has no uid")

        val deleted = (o["deleted"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw BatchException(BatchErrorKind.FORMAT, "change has no deleted flag")

        val fields = (o["fields"] as? JsonObject)
            ?: throw BatchException(BatchErrorKind.FORMAT, "change has no fields")

        return ChangeRecord(
            table = table,
            uid = uid,
            updatedAt = str(o, "updatedAt"),
            deleted = deleted,
            origin = str(o, "origin"),
            fields = fields.mapValues { toValue(it.value) },
        )
    }

    // ── batches ─────────────────────────────────────────────────────────────

    fun encodeBatch(b: ChangeBatch): ByteArray {
        val obj = buildJsonObject {
            put("version", JsonPrimitive(b.version))
            put("device", JsonPrimitive(b.device))
            put("seq", JsonPrimitive(b.seq))
            put("writtenAt", JsonPrimitive(b.writtenAt))
            put("changes", JsonArray(b.changes.map { encodeRecord(it) }))
        }
        return obj.toString().encodeToByteArray()
    }

    /**
     * The device and seq inside the file are checked against the name it was
     * found under.
     *
     * They are already bound by the AEAD tag, so a renamed file cannot decrypt
     * at all and this can never fire on a moved file. It is here for the case
     * where something re-encrypts and rewrites, and it costs two comparisons.
     */
    fun decodeBatch(bytes: ByteArray, file: RemoteFile): ChangeBatch {
        val root = try {
            json.parseToJsonElement(bytes.decodeToString())
        } catch (_: Exception) {
            throw BatchException(BatchErrorKind.FORMAT, "batch is not JSON")
        }
        val o = root as? JsonObject
            ?: throw BatchException(BatchErrorKind.FORMAT, "batch is not an object")

        val version = (o["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toIntOrNull()
        if (version != Protocol.VERSION) {
            throw BatchException(
                BatchErrorKind.VERSION,
                "batch version $version is newer than this app understands",
            )
        }

        val device = (o["device"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val seq = (o["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
        if (device != file.device || seq != file.seq) {
            throw BatchException(
                BatchErrorKind.MISMATCH,
                "batch says $device-$seq but is filed as ${file.name}",
            )
        }

        val writtenAt = (o["writtenAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw BatchException(BatchErrorKind.FORMAT, "writtenAt is missing")

        val changes = (o["changes"] as? JsonArray)
            ?: throw BatchException(BatchErrorKind.FORMAT, "changes is not an array")

        return ChangeBatch(
            version = Protocol.VERSION,
            // The file's own device and seq, not the parsed ones. They are equal
            // by the check above, and taking them from here means the batch
            // cannot depend on the compiler proving that for itself.
            device = file.device,
            seq = file.seq,
            writtenAt = writtenAt,
            changes = changes.map {
                decodeRecord(
                    it as? JsonObject
                        ?: throw BatchException(BatchErrorKind.FORMAT, "change is not an object"),
                )
            },
        )
    }

    // ── the state this device keeps about syncing ───────────────────────────
    //
    // Persisted as one small JSON blob rather than a table, because it is read
    // and written whole, several times per sync, and never queried.

    fun encodeState(s: SyncState): String = buildJsonObject {
        put("device", JsonPrimitive(s.device))
        put("seq", JsonPrimitive(s.seq))
        put("cursor", JsonObject(s.cursor.mapValues { JsonPrimitive(it.value) }))
        put("snapshotSeq", JsonPrimitive(s.snapshotSeq))
        put("priorSnapshotSeq", JsonPrimitive(s.priorSnapshotSeq))
    }.toString()

    /**
     * A state that will not parse falls back to a fresh one for this device
     * rather than throwing.
     *
     * The worst that costs is one sync that re-reads everything and re-decides
     * it has nothing to say, because every planner is written to be safe to
     * repeat. Throwing instead would mean a corrupted settings blob stops sync
     * permanently, which is a much worse trade for the same cause.
     */
    fun decodeState(text: String?, device: String): SyncState {
        if (text.isNullOrBlank()) return Engine.emptyState(device)
        return try {
            val o = json.parseToJsonElement(text).jsonObject
            SyncState(
                device = o["device"]?.jsonPrimitive?.content ?: device,
                seq = o["seq"]?.jsonPrimitive?.long ?: 0L,
                cursor = (o["cursor"] as? JsonObject)
                    ?.mapValues { it.value.jsonPrimitive.long }
                    ?: emptyMap(),
                // Missing means never, which is the honest reading of a state
                // written before snapshots existed, and the safe one: the device
                // writes a fresh snapshot when the file count says so and
                // deletes nothing until the one after that.
                snapshotSeq = o["snapshotSeq"]?.jsonPrimitive?.long ?: 0L,
                priorSnapshotSeq = o["priorSnapshotSeq"]?.jsonPrimitive?.long ?: 0L,
            )
        } catch (_: Exception) {
            Engine.emptyState(device)
        }
    }

    /** Only used by the vector suite, which reads records straight from JSON. */
    fun recordsFrom(array: JsonArray): List<ChangeRecord> =
        array.map { decodeRecord(it.jsonObject) }
}