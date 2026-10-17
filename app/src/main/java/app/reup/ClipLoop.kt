// app/src/main/java/app/reup/ClipLoop.kt
package app.reup

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * A clip's picture, decoded straight onto a surface and played round and round
 * with no seam - the wallpaper's player (ReupWallpaper).
 *
 * ─── WHY NOT MediaPlayer ────────────────────────────────────────────────────
 *
 * MediaPlayer can loop, but it loops by stopping at the end, seeking back and
 * starting the decoder again, and that shows: a hitch every time round, every
 * few seconds on a short clip. Here the decoder is never stopped. When the
 * file runs out, reading starts again from the top and carries on feeding the
 * same decoder, each time round stamped one clip-length later than the last,
 * so to the decoder it is one long clip that never ends. And only the picture
 * is read: a wallpaper has no sound to decode.
 *
 * ─── ON TIME ────────────────────────────────────────────────────────────────
 *
 * Each frame is handed to the screen stamped with the moment it should appear,
 * and the screen shows it at the first refresh after that. A frame that comes
 * out late, after a stall, moves the clock on rather than racing to catch up:
 * nothing is in step with a wallpaper but itself.
 *
 * ─── STOPPING ───────────────────────────────────────────────────────────────
 *
 * Hidden, it stops taking frames out, and the decoder, full, stops decoding;
 * shown again, the clock moves on by however long it was hidden and the next
 * frame shows at once. The first frame is shown even while hidden, so the
 * wallpaper is never an empty black when it is first looked at.
 */
internal class ClipLoop(private val file: File, private val surface: Surface) {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    private var quit = false

    @Volatile
    private var visible = false

    private var thread: Thread? = null

    fun start() {
        val t = Thread({ run() }, "ReupClipLoop")
        thread = t
        t.start()
    }

    /** Playing while [on]; paused, on the same frame, while not. */
    fun show(on: Boolean) {
        lock.withLock {
            visible = on
            changed.signalAll()
        }
    }

    /**
     * Stopped, and the decoder let go of the surface, before this returns:
     * the surface is not this player's once the wallpaper says it is gone.
     */
    fun stop() {
        quit = true
        lock.withLock { changed.signalAll() }
        try {
            thread?.join(2_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
    }

    private fun run() {
        try {
            // Up with the screen's own drawing, so a frame is not late for a
            // busy home screen. A phone that will not allow it plays anyway.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (e: Exception) {
            // As it is.
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            // The format as the file has it, which carries the clip's rotation:
            // a decoder drawing onto a surface turns the picture by it.
            val c = decoderFor(format, mime)
            codec = c
            c.configure(format, surface, null, 0)
            c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            c.start()
            play(extractor, c)
        } catch (e: Exception) {
            if (!quit) Log.e(TAG, "the clip stopped: " + e.javaClass.simpleName)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
                // Stopped or not, it is released next.
            }
            try {
                codec?.release()
            } catch (e: Exception) {
                // Nothing more to do with it.
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                // Nothing more to do with it.
            }
        }
    }

    private fun play(ex: MediaExtractor, codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        // The clip as read the first time round: its first and last moment,
        // and how many frames, which make its length and a frame's.
        var firstUs = Long.MAX_VALUE
        var lastUs = Long.MIN_VALUE
        var frames = 0
        var lengthUs = 0L
        var frameUs = 33_333L

        // Each time round, every sample is stamped this much later.
        var offsetUs = 0L
        // Past the first time round: see the comment where it is used.
        var again = false
        var roundStartUs = Long.MIN_VALUE

        // System.nanoTime() at which presentation time 0 shows.
        var clock = -1L
        var shownOne = false

        while (!quit) {
            // Hidden, once there is something on the screen: wait, and move the
            // clock on by however long it was.
            if (shownOne && !visible) {
                val since = System.nanoTime()
                lock.withLock {
                    while (!visible && !quit) changed.await()
                }
                if (quit) break
                if (clock >= 0) clock += System.nanoTime() - since
            }

            // Every free input buffer, filled.
            while (!quit) {
                val i = codec.dequeueInputBuffer(0)
                if (i < 0) break
                val buffer = codec.getInputBuffer(i)
                if (buffer == null) {
                    codec.queueInputBuffer(i, 0, 0, offsetUs, 0)
                    break
                }
                var size = ex.readSampleData(buffer, 0)
                var t = ex.sampleTime
                // Round again: the top of the file, one clip-length on.
                if (size < 0) {
                    if (lengthUs == 0L) {
                        if (frames == 0) return
                        frameUs = if (frames > 1) max(1L, (lastUs - firstUs) / (frames - 1)) else frameUs
                        lengthUs = lastUs - firstUs + frameUs
                    }
                    offsetUs += lengthUs
                    ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    again = true
                    roundStartUs = Long.MIN_VALUE
                    size = ex.readSampleData(buffer, 0)
                    t = ex.sampleTime
                    if (size < 0) return
                }
                if (again) {
                    if (roundStartUs == Long.MIN_VALUE) roundStartUs = t
                    // A frame shown before the one the round starts on leans on
                    // frames before it - last time round's, to a decoder that was
                    // never told the clip ended. Passed over; the first time
                    // round the decoder knew to.
                    while (size >= 0 && t < roundStartUs) {
                        ex.advance()
                        size = ex.readSampleData(buffer, 0)
                        t = ex.sampleTime
                    }
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, offsetUs, 0)
                        continue
                    }
                } else {
                    firstUs = min(firstUs, t)
                    lastUs = max(lastUs, t)
                    frames++
                }
                codec.queueInputBuffer(i, 0, size, offsetUs + t, 0)
                ex.advance()
            }
            if (quit) break

            // One frame out, shown at its moment.
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // The crop is forgotten on a change like this; said again.
                codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                continue
            }
            if (o < 0) continue
            // Never sent, since the clip never ends here; let go if a decoder says it anyway.
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                codec.releaseOutputBuffer(o, false)
                continue
            }
            val pts = info.presentationTimeUs * 1_000L
            var now = System.nanoTime()
            if (clock < 0) clock = now - pts
            var due = clock + pts
            if (now - due > 3 * frameUs * 1_000L) {
                // Fell behind: carry on from here rather than race through frames.
                clock += now - due
                due = now
            }
            rest(due - now - 2_000_000L)
            if (quit) {
                codec.releaseOutputBuffer(o, false)
                break
            }
            now = System.nanoTime()
            codec.releaseOutputBuffer(o, max(due, now))
            shownOne = true
        }
    }

    /**
     * The decoder that says it takes this very clip - its profile, size and
     * rate - which the phone lists with its video chip's first. The first one
     * named for the kind of clip, on a phone that will not say.
     */
    private fun decoderFor(format: MediaFormat, mime: String): MediaCodec {
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        } catch (e: Exception) {
            null
        }
        if (name != null) {
            try {
                return MediaCodec.createByCodecName(name)
            } catch (e: Exception) {
                // The next way.
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    /** Asleep for up to [ns], woken early by a stop or by being hidden or shown. */
    private fun rest(ns: Long) {
        if (ns <= 0) return
        lock.withLock {
            if (!quit) changed.await(ns, TimeUnit.NANOSECONDS)
        }
    }

    private companion object {
        const val TAG = "ReupClipLoop"
    }
}
