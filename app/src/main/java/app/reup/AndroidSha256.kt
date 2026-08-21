package app.reup

import app.reup.sync.Sha256
import java.security.MessageDigest

/**
 * SHA-256 for the phone, used to build the PKCE challenge.
 *
 * The JVM copy of this lives in shared/jvmMain/JvmSha256.kt for the same reason
 * AndroidAeadCipher exists: the jvmMain source set is not compiled into the
 * Android build. The two files must stay identical apart from the class name.
 */
class AndroidSha256 : Sha256 {
    override fun hash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}