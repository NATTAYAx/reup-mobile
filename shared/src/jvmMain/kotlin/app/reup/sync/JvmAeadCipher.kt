package app.reup.sync

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ─── JvmAeadCipher.kt — twenty lines of platform, and nothing else ───────────
//
// Every byte-level decision that could differ between two implementations —
// the frame, the version byte, the AAD string, base64url — lives in Crypto.kt,
// which is platform-free. This file is only the call into the platform's AES.
// That split is why "does Android agree with the desktop" is answerable by
// reading one short file instead of auditing two long ones.
//
// The same source works on Android unchanged: javax.crypto and AES/GCM/NoPadding
// have been in the platform since API 1 and AndroidKeyStore is not involved
// here, because this key is not stored by the platform — it arrives in a
// pairing code and is held by the caller.
//
// WHY THE TAG LENGTH IS SPELLED OUT
//
// GCMParameterSpec takes the tag length in BITS while almost everything else
// nearby counts bytes. Passing 16 instead of 128 does not fail loudly; it
// produces a 2-byte tag, which authenticates almost nothing while looking
// entirely normal. It is one of the few places in this codebase where a wrong
// number is silently accepted, so it is written as a named constant.

private const val TAG_BITS = 128
private const val TRANSFORM = "AES/GCM/NoPadding"

class JvmAeadCipher(
    /** Injectable so tests are deterministic. The app must never pass one. */
    private val random: SecureRandom = SecureRandom(),
) : AeadCipher {

    override fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    override fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return try {
            c.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw CryptoException(
                CryptoErrorKind.AUTH,
                "blob failed to authenticate: wrong key, corrupted bytes, or moved to a different filename",
                e,
            )
        } catch (e: javax.crypto.IllegalBlockSizeException) {
            // A truncated file can land here instead of on the tag check.
            // Reported as AUTH rather than FORMAT because from the caller's
            // side it is the same event: these bytes are not trustworthy.
            throw CryptoException(CryptoErrorKind.AUTH, "blob is truncated or malformed", e)
        }
    }

    override fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }
}