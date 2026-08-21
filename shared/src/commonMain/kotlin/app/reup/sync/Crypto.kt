package app.reup.sync

// ─── Crypto.kt — the frame, and the shape of a cipher ────────────────────────
//
// Mirror of src/lib/sync/crypto.ts. The long reasoning lives there; this repeats
// only what a reader needs in order not to break it.
//
// WHY THIS IS AN INTERFACE AND NOT expect/actual
//
// commonMain compiles for iOS as well as Android and the JVM, and that is
// deliberate — an Android import landing in commonMain turns CI red the same
// day rather than a year later. An `expect fun` here would force an iOS actual
// to exist before iOS is being built at all, so the choice would be between
// writing CryptoKit code nobody is ready to test, or deleting the iOS targets
// and losing the tripwire.
//
// An ordinary interface costs nothing on any platform and has the same effect:
// the framing, the AAD and the version checks are written once, and only the
// twenty lines that call the platform's AES live per-platform. iOS simply has
// no implementation yet, which is honest and does not break the build.
//
// THE FRAME
//
//   [0..4)    "REUP"                magic
//   [4]       0x01                  version
//   [5..17)   nonce                 12 random bytes
//   [17..)    ciphertext || tag     tag is the last 16 bytes
//
// Self-describing on purpose. A blob that cannot be read should say "I am a
// version you do not know" rather than decrypt into garbage, because garbage
// written into a database is not recoverable and a refusal is.
//
// THE AAD BINDS THE BLOB TO ITS SLOT
//
// The additional data is `bucketId|device|seq` — the blob's own filename. Not
// secret, not encrypted, only authenticated, which means the tag stops
// verifying if the blob is moved.
//
// Without it, whoever controls the storage can rename files. Copy last month's
// `phone-4.reup` over `phone-91.reup` and every device replays a month-old
// state as new — no key needed, nothing decrypted, just a file copy. Rows come
// back from the dead and completions get undone. With the AAD that becomes a
// decryption failure, which is loud.

/** What went wrong, in terms a caller might reasonably branch on. */
enum class CryptoErrorKind { FORMAT, VERSION, AUTH, KEY }

class CryptoException(
    val kind: CryptoErrorKind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Raw AES-256-GCM, supplied by the platform.
 *
 * Deliberately knows nothing about Reup: no frame, no version, no AAD
 * construction. Everything a mistake could be made in lives in [SealedBlob],
 * which is pure Kotlin and therefore the same on every platform by
 * construction rather than by review.
 */
interface AeadCipher {
    /** Returns ciphertext with the 16-byte tag appended. */
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Throws [CryptoException] with kind AUTH if the tag does not verify. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray

    fun randomBytes(n: Int): ByteArray
}

object SealedBlob {

    private val MAGIC = byteArrayOf(0x52, 0x45, 0x55, 0x50) // "REUP"
    const val VERSION: Int = 1
    const val NONCE_BYTES: Int = 12
    const val KEY_BYTES: Int = 32
    const val TAG_BYTES: Int = 16
    private val HEADER_BYTES = MAGIC.size + 1 + NONCE_BYTES

    /** The bytes the tag is computed over. Not secret; only authenticated. */
    fun aad(bucketId: String, device: String, seq: Long): ByteArray =
        "$bucketId|$device|$seq".encodeToByteArray()

    fun seal(
        cipher: AeadCipher,
        key: ByteArray,
        bucketId: String,
        device: String,
        seq: Long,
        plaintext: ByteArray,
    ): ByteArray = sealWithNonce(
        cipher, key, bucketId, device, seq, plaintext, cipher.randomBytes(NONCE_BYTES),
    )

    /**
     * Exposed only so the vector suite can reproduce a fixed file. Never call it
     * from the app: a caller who supplies the nonce is a caller who can repeat
     * one, and repeating a nonce under GCM does not leak one message, it leaks
     * the key that authenticates all of them.
     */
    fun sealWithNonce(
        cipher: AeadCipher,
        key: ByteArray,
        bucketId: String,
        device: String,
        seq: Long,
        plaintext: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        if (key.size != KEY_BYTES) {
            throw CryptoException(CryptoErrorKind.KEY, "key must be $KEY_BYTES bytes, got ${key.size}")
        }
        if (nonce.size != NONCE_BYTES) {
            throw CryptoException(CryptoErrorKind.FORMAT, "nonce must be $NONCE_BYTES bytes")
        }
        val ct = cipher.encrypt(key, nonce, aad(bucketId, device, seq), plaintext)
        val out = ByteArray(HEADER_BYTES + ct.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = VERSION.toByte()
        nonce.copyInto(out, MAGIC.size + 1)
        ct.copyInto(out, HEADER_BYTES)
        return out
    }

    fun open(
        cipher: AeadCipher,
        key: ByteArray,
        bucketId: String,
        device: String,
        seq: Long,
        blob: ByteArray,
    ): ByteArray {
        if (key.size != KEY_BYTES) {
            throw CryptoException(CryptoErrorKind.KEY, "key must be $KEY_BYTES bytes, got ${key.size}")
        }
        // Checked before the magic so a stray short file reports the honest
        // reason rather than "not a Reup blob", which would send someone
        // looking at the wrong thing.
        if (blob.size < HEADER_BYTES + TAG_BYTES) {
            throw CryptoException(CryptoErrorKind.FORMAT, "blob is too short to contain a frame")
        }
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) {
                throw CryptoException(CryptoErrorKind.FORMAT, "not a Reup blob")
            }
        }
        val v = blob[MAGIC.size].toInt() and 0xff
        if (v != VERSION) {
            throw CryptoException(
                CryptoErrorKind.VERSION,
                "blob version $v is newer than this app understands",
            )
        }
        val nonce = blob.copyOfRange(MAGIC.size + 1, HEADER_BYTES)
        val ct = blob.copyOfRange(HEADER_BYTES, blob.size)
        return cipher.decrypt(key, nonce, aad(bucketId, device, seq), ct)
    }
}

// ── pairing ──────────────────────────────────────────────────────────────────
//
// What the desktop shows as a QR code and the phone's camera reads. Short
// enough to stay a low-density QR that scans on a cracked screen in bad light,
// which is a real constraint: this is the one moment where somebody is holding
// two devices trying to make them agree, and the moment they are most likely to
// give up on sync altogether.

data class Pairing(val bucketId: String, val key: ByteArray) {
    // Data classes compare ByteArray by reference, which would make two
    // identical pairings unequal and any test of them quietly meaningless.
    override fun equals(other: Any?): Boolean =
        other is Pairing && bucketId == other.bucketId && key.contentEquals(other.key)

    override fun hashCode(): Int = 31 * bucketId.hashCode() + key.contentHashCode()
}

object PairingCode {

    private val RE = Regex("""^reup://pair\?b=([A-Za-z0-9_-]+)&k=([A-Za-z0-9_-]+)$""")

    fun encode(p: Pairing): String = "reup://pair?b=${p.bucketId}&k=${Base64Url.encode(p.key)}"

    fun decode(s: String): Pairing {
        val m = RE.find(s.trim())
            ?: throw CryptoException(CryptoErrorKind.FORMAT, "not a pairing code")
        val key = Base64Url.decode(m.groupValues[2])
        if (key.size != SealedBlob.KEY_BYTES) {
            throw CryptoException(CryptoErrorKind.KEY, "pairing code carries a bad key")
        }
        return Pairing(m.groupValues[1], key)
    }
}

/**
 * Unpadded base64url, written out rather than taken from a platform.
 *
 * java.util.Base64 exists and is faster, and using it would put this file in
 * androidMain, which would drag the frame and the pairing format along with it
 * and mean iOS eventually gets a second copy of both. Thirty lines here keeps
 * every byte-level decision on one platform-free page.
 */
object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xff) shl 16) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8) or
                    (bytes[i + 2].toInt() and 0xff)
            sb.append(ALPHABET[(n shr 18) and 63])
            sb.append(ALPHABET[(n shr 12) and 63])
            sb.append(ALPHABET[(n shr 6) and 63])
            sb.append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xff) shl 16
                sb.append(ALPHABET[(n shr 18) and 63])
                sb.append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xff) shl 16) or ((bytes[i + 1].toInt() and 0xff) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63])
                sb.append(ALPHABET[(n shr 12) and 63])
                sb.append(ALPHABET[(n shr 6) and 63])
            }
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = ArrayList<Byte>(s.length * 3 / 4 + 3)
        var buf = 0
        var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) throw CryptoException(CryptoErrorKind.FORMAT, "bad base64url character '$c'")
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf shr bits) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }
}