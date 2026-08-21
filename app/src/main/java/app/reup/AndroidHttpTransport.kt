package app.reup

import app.reup.sync.HttpRequest
import app.reup.sync.HttpResponse
import app.reup.sync.HttpTransport
import app.reup.sync.StorageErrorKind
import app.reup.sync.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

// COPY OF shared/src/jvmMain/.../JvmHttpTransport.kt, and that is worth saying out loud.
//
// It is here rather than shared because jvmMain is not compiled into the
// Android target: `app/` sees commonMain and nothing else from :shared. The
// source itself needs no change at all - HttpURLConnection is in the Android framework too.
//
// This is one thing living in two places, which is the disease this project
// keeps curing, and it is accepted here only because the alternative is an
// intermediate source set that jvm and android both depend on, which is a
// Gradle change nobody has been able to verify yet.
//
// The line that must never drift from the original: instanceFollowRedirects = false.
//
// THE ONE PLACE THIS COPY IS ALLOWED TO DIFFER, AND WHY
//
// send() runs its body on Dispatchers.IO here and does not there. That is not
// drift, it is the difference between the two platforms: Android refuses a
// socket on the main thread outright (NetworkOnMainThreadException), and the
// callers here are click listeners, which start on the main thread. The JVM
// has no such rule and :shared carries no coroutines dependency to do it with.
//
// The dispatcher lives inside the adapter rather than at the call site for the
// same reason AndroidDb.kt puts it inside execute() and select(): a rule that
// every caller has to remember is a rule that some caller will forget, and the
// symptom is a crash in a background receiver nobody is watching.

// ─── JvmHttpTransport.kt — the only part of storage that touches a socket ────
//
// Everything about WebDAV that can be wrong in an interesting way lives in
// Storage.kt and is tested with a fake. This file has one job and no decisions:
// take a request, make it, hand back status and bytes.
//
// WHY HttpURLConnection AND NOT OkHttp OR KTOR
//
// It is already in the JDK and already in Android, so it costs no dependency on
// either. OkHttp and Ktor are both better clients, and neither is better enough
// to be worth a library that has to be tracked and updated on two platforms for
// four HTTP verbs against a server on the user's own network.
//
// The interface is what makes that reversible. If something later needs
// HTTP/2 multiplexing or connection pooling that actually matters, swapping
// this file changes nothing else, because nothing else knows it exists.
//
// WHY PROPFIND AND MKCOL NEED THE REFLECTION HACK
//
// HttpURLConnection validates the method name against a fixed list — GET, POST,
// PUT, DELETE, HEAD, OPTIONS, TRACE — and throws ProtocolException for anything
// else. WebDAV's verbs are not on that list, and the field holding the method
// is private with no setter.
//
// Reflection into a JDK internal is normally a bad sign and is worth naming as
// such. It is used here because the alternative is a whole HTTP library to gain
// the ability to send a seven-letter string, and because there is a fallback:
// if the field cannot be reached, the request goes out as POST with
// X-HTTP-Method-Override, which most WebDAV servers honour. Loud degradation
// rather than a crash.

class AndroidHttpTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : HttpTransport {

    override suspend fun send(req: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) { sendBlocking(req) }

    /** Everything below this point blocks. Nothing above it may call it directly. */
    private fun sendBlocking(req: HttpRequest): HttpResponse {
        val conn = try {
            // URI().toURL() rather than URL(String), which is deprecated as of
            // JDK 20: the old constructor parsed leniently and accepted things
            // that are not URLs, so two libraries could disagree about what a
            // string pointed at. Parsing strictly first means a malformed
            // WebDAV URL is rejected here rather than reaching a socket.
            URI(req.url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw StorageException(StorageErrorKind.CONFIG, "cannot open ${req.url}: ${e.message}", null, e)
        }

        try {
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            // Redirects are not followed. A redirect on a PUT is either a
            // misconfigured server or something standing in the middle, and
            // silently re-sending the Authorization header to wherever it points
            // is how a password ends up somewhere nobody chose.
            conn.instanceFollowRedirects = false

            setMethod(conn, req.method)
            for ((k, v) in req.headers) conn.setRequestProperty(k, v)

            // Read into a local first. Kotlin will not smart cast a `val` that
            // was declared in another module, because that module could be
            // recompiled with a custom getter behind this one's back. Inside
            // :shared this compiled without it; from the app module it does
            // not, and the same source has to satisfy both.
            val outgoing = req.body
            if (outgoing != null) {
                conn.doOutput = true
                // Streaming mode, so a large blob is not buffered twice in
                // memory on a phone before it goes out.
                conn.setFixedLengthStreamingMode(outgoing.size)
                conn.outputStream.use { it.write(outgoing) }
            }

            val status = conn.responseCode
            // Above 399 the body is on errorStream and inputStream throws. A
            // client that reads the wrong one turns "401 wrong password" into a
            // stack trace with no status in it.
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(status, body)
        } catch (e: SocketTimeoutException) {
            throw StorageException(StorageErrorKind.NETWORK, "the server did not answer in time", null, e)
        } catch (e: UnknownHostException) {
            throw StorageException(StorageErrorKind.NETWORK, "cannot find that server", null, e)
        } catch (e: IOException) {
            throw StorageException(StorageErrorKind.NETWORK, "network error: ${e.message}", null, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun setMethod(conn: HttpURLConnection, method: String) {
        try {
            conn.requestMethod = method
            return
        } catch (_: java.net.ProtocolException) {
            // Not on the JDK's allowed list. Expected for PROPFIND and MKCOL.
        }
        try {
            val f = HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true
            f.set(conn, method)
        } catch (_: Exception) {
            // Reflection blocked, most likely by a future JDK module policy.
            // POST with an override header is understood by every WebDAV server
            // that has ever had to sit behind a corporate proxy.
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", method)
        }
    }
}