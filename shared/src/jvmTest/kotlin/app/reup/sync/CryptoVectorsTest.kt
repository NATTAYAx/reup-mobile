package app.reup.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

// ─── CryptoVectorsTest ───────────────────────────────────────────────────────
//
// The merge vectors prove two implementations of a RULE agree. These prove
// something narrower and harsher: that bytes written by the desktop are
// readable here. There is no "close enough" — a frame off by one byte, a nonce
// read from the wrong offset, an AAD joined with a different separator, and
// every blob fails.
//
// That failure mode is why the file is worth the trouble. An encryption
// mismatch does not surface as a wrong answer. It surfaces as sync appearing to
// work — files listed, downloaded, nothing thrown at the top level — while
// every one is rejected and the two devices stay silently empty of each other's
// data. Without this it would be debugged over a network, against a phone,
// through OAuth, with no way to tell which of five layers was lying.
//
// The negative cases are half the value. An implementation that decrypts
// happily but ignores the AAD passes every positive case and is still broken,
// because that binding is what stops whoever holds the storage from copying an
// old blob into a new slot and replaying last month.

class CryptoVectorsTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cipher = JvmAeadCipher()

    private fun load(): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("crypto-vectors.json")
            ?: fail(
                "crypto-vectors.json not found on the test classpath. Generate it on the " +
                        "desktop with `pnpm gen:crypto-vectors`, then copy it into " +
                        "shared/src/jvmTest/resources/."
            )
        return json.parseToJsonElement(stream.bufferedReader().readText()).jsonObject
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.content.toLong()

    @Test
    fun `decrypts every blob the desktop produced`() {
        val root = load()
        assertEquals(1, root.str("version").toInt(), "vector format changed; the reader must too")

        val key = Base64Url.decode(root.str("keyB64"))
        val cases = root.getValue("positive") as JsonArray
        assertTrue(cases.size >= 8, "only ${cases.size} cases — the file looks truncated")

        for (c in cases.map { it.jsonObject }) {
            val blob = Base64Url.decode(c.str("blobB64"))
            val plain = try {
                SealedBlob.open(cipher, key, c.str("bucketId"), c.str("device"), c.long("seq"), blob)
            } catch (e: CryptoException) {
                fail("${c.str("id")} did not open: ${e.kind} ${e.message}")
            }
            assertEquals(
                c.str("plaintextUtf8"),
                plain.decodeToString(),
                "${c.str("id")} opened but the plaintext differs",
            )
        }
        println("${cases.size} blobs decrypted")
    }

    @Test
    fun `refuses every blob it is supposed to refuse`() {
        val root = load()
        val key = Base64Url.decode(root.str("keyB64"))

        for (c in (root.getValue("negative") as JsonArray).map { it.jsonObject }) {
            val blob = Base64Url.decode(c.str("blobB64"))
            val expected = when (c.str("expect")) {
                "auth" -> CryptoErrorKind.AUTH
                "version" -> CryptoErrorKind.VERSION
                "format" -> CryptoErrorKind.FORMAT
                "key" -> CryptoErrorKind.KEY
                else -> fail("unknown expectation in ${c.str("id")}")
            }
            try {
                SealedBlob.open(cipher, key, c.str("bucketId"), c.str("device"), c.long("seq"), blob)
                fail("${c.str("id")} was accepted but must not be — ${c.str("why")}")
            } catch (e: CryptoException) {
                assertEquals(
                    expected, e.kind,
                    "${c.str("id")} was refused for the wrong reason — ${c.str("why")}",
                )
            }
        }
        println("${(root.getValue("negative") as JsonArray).size} blobs correctly refused")
    }

    @Test
    fun `a blob sealed here opens here`() {
        // Positive control. If the vectors fail but this passes, the two
        // implementations disagree; if both fail, this one is broken on its own.
        val key = cipher.randomBytes(32)
        val body = """{"name":"ยาความดัน","emoji":"🎯"}""".encodeToByteArray()
        val blob = SealedBlob.seal(cipher, key, "b", "phone", 3, body)
        assertEquals(body.decodeToString(), SealedBlob.open(cipher, key, "b", "phone", 3, blob).decodeToString())

        // Random nonces, so the same plaintext must not produce the same bytes.
        val again = SealedBlob.seal(cipher, key, "b", "phone", 3, body)
        assertTrue(!blob.contentEquals(again), "two seals were byte-identical; the nonce is not random")
    }

    @Test
    fun `the pairing code round-trips and matches the desktop`() {
        val root = load()
        val fromDesktop = PairingCode.decode(root.str("samplePairing"))
        assertTrue(
            fromDesktop.key.contentEquals(Base64Url.decode(root.str("keyB64"))),
            "the pairing code and the raw key in the same file disagree",
        )
        assertEquals(root.str("samplePairing"), PairingCode.encode(fromDesktop))

        for (junk in listOf("", "reup://pair", "https://example.com", "reup://pair?b=x&k=short")) {
            try {
                PairingCode.decode(junk)
                fail("accepted '$junk' as a pairing code")
            } catch (_: CryptoException) {
            }
        }
    }

    @Test
    fun `base64url agrees with the desktop on every byte value`() {
        // The alphabet is written out by hand here rather than taken from
        // java.util.Base64, so it gets checked rather than assumed. One
        // transposed character in the table corrupts a fraction of blobs, which
        // looks like intermittent network trouble.
        val all = ByteArray(256) { it.toByte() }
        assertTrue(Base64Url.decode(Base64Url.encode(all)).contentEquals(all))
        for (n in 0..8) {
            val slice = all.copyOfRange(0, n)
            assertTrue(Base64Url.decode(Base64Url.encode(slice)).contentEquals(slice), "length $n")
            assertTrue(!Base64Url.encode(slice).contains('='), "padding leaked in at length $n")
        }

        val root = load()
        assertTrue(
            Base64Url.encode(Base64Url.decode(root.str("keyB64"))) == root.str("keyB64"),
            "re-encoding the desktop's key produced different text",
        )
    }
}