// app/src/main/java/app/reup/ReupWallpaper.kt
package app.reup

import android.app.WallpaperColors
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi

/**
 * The wallpaper: the clip kept by WallClip, playing round and round behind the
 * home screen.
 *
 * ─── HOW IT PLAYS ───────────────────────────────────────────────────────────
 *
 * The phone's video decoder straight onto the wallpaper's surface, cropped to
 * fill the screen the way the widget's picture is, round and round with no
 * seam and no sound (ClipLoop says how). This code runs only when something
 * changes - the clip, or whether the wallpaper can be seen. The system's
 * preview of it, while it is being set, is a second engine like this one.
 *
 * ─── WHEN IT PLAYS ──────────────────────────────────────────────────────────
 *
 * Only while it can be seen. The system says when an app covers it or the
 * screen goes off; the clip pauses there and carries on from the same frame.
 * And not while battery saver is on, or while the phone says it is running
 * warm (WallClip.resting): it holds its frame and plays again on its own when
 * neither is true, so it is never what keeps a warm phone warm.
 *
 * ─── NOTHING DRAWN BY HAND ──────────────────────────────────────────────────
 *
 * The surface is the player's alone. One that has been drawn on with a canvas
 * cannot be handed to a player afterwards, and the clip can change at any
 * moment, so with no clip nothing is drawn on it at all.
 */
class ReupWallpaper : WallpaperService() {

    override fun onCreateEngine(): Engine = ClipEngine()

    private inner class ClipEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = WallClip.prefs(this@ReupWallpaper)
        private val main = Handler(Looper.getMainLooper())

        private var holder: SurfaceHolder? = null
        private var shown = false
        private var loop: ClipLoop? = null

        /** Battery saver on, or the phone warm: held on its frame. */
        private var resting = false

        private val power = getSystemService(PowerManager::class.java)
        private val saver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = rest()
        }
        /** A PowerManager.OnThermalStatusChangedListener, from Android 10. */
        private var thermal: Any? = null

        /** The clip's colours for the system's, from its frame; a WallpaperColors from API 27. */
        @Volatile
        private var colors: Any? = null

        /** Destroyed: a frame still being looked at has nobody to tell. */
        private var gone = false

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)
            setOffsetNotificationsEnabled(false)
            prefs.registerOnSharedPreferenceChangeListener(this)
            readColors()
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(saver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(saver, filter)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && power != null) {
                val listener = PowerManager.OnThermalStatusChangedListener { rest() }
                power.addThermalStatusListener(listener)
                thermal = listener
            }
            rest()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            this.holder = holder
            play()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stop()
            this.holder = null
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            shown = visible
            follow()
        }

        override fun onDestroy() {
            gone = true
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            try {
                unregisterReceiver(saver)
            } catch (e: Exception) {
                // Never registered, or already not.
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (thermal as? PowerManager.OnThermalStatusChangedListener)?.let { power?.removeThermalStatusListener(it) }
            }
            main.removeCallbacksAndMessages(null)
            stop()
            super.onDestroy()
        }

        /** A new clip kept, or the clip taken away: play whatever there is now. */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            // A null key is the whole file cleared, from Android 11.
            if (key != null && key != WallClip.KEY_VERSION) return
            stop()
            readColors()
            play()
        }

        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors? = colors as? WallpaperColors

        private fun play() {
            val surface = holder ?: return
            if (WallClip.read(this@ReupWallpaper) == null) return
            val l = ClipLoop(WallClip.file(this@ReupWallpaper), surface.surface)
            loop = l
            l.show(shown && !resting)
            l.start()
        }

        /** Playing while it can be seen and need not rest; holding its frame otherwise. */
        private fun follow() {
            loop?.show(shown && !resting)
        }

        private fun rest() {
            resting = WallClip.resting(this@ReupWallpaper) != null
            follow()
        }

        /** The decoder off the surface before this returns; see ClipLoop.stop. */
        private fun stop() {
            val l = loop ?: return
            loop = null
            l.stop()
        }

        /** Off the main thread: a frame decoded and looked at. Told to the system when done. */
        private fun readColors() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
            Thread {
                val found = try {
                    WallClip.thumb(this@ReupWallpaper)?.let { WallpaperColors.fromBitmap(it) }
                } catch (e: Exception) {
                    null
                }
                main.post {
                    if (gone) return@post
                    colors = found
                    try {
                        notifyColorsChanged()
                    } catch (e: Exception) {
                        // Told or not, the colours are there for the next time it asks.
                    }
                }
            }.start()
        }
    }
}
