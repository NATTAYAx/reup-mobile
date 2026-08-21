// shared/src/jvmMain/kotlin/app/reup/sync/JvmSha256.kt
package app.reup.sync

import java.security.MessageDigest

/**
 * SHA-256 from the JVM, for the desktop-side tests and for anything on the JVM
 * target that needs a PKCE challenge.
 *
 * The Android copy of this lives in app/AndroidSha256.kt for the same reason
 * AndroidAeadCipher exists: the jvmMain source set is not compiled into the
 * Android build. The two files must stay identical apart from the class name.
 */
class JvmSha256 : Sha256 {
    override fun hash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}