// app/src/main/java/app/reup/BankNoticeListener.kt
package app.reup

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import app.reup.sync.BANK_APPS
import java.lang.ref.WeakReference

/**
 * K PLUS's and Krungthai NEXT's notifications, as they are posted, handed to
 * BankNotices.
 *
 * ─── WHAT THE SYSTEM SENDS HERE, AND WHAT IS LOOKED AT ───────────────────────
 *
 * Android has no way to ask for two apps' notifications: a listener is sent
 * everybody's. This one asks for as few as the system allows - no
 * conversations, so nothing from a chat app, and nothing ongoing, so no music
 * or navigation; the manifest says so. Of what still arrives, the package is
 * the first thing checked and, for every app but the two banks, the only
 * thing. Nothing else about it is read, kept or logged.
 *
 * ─── AND WHEN IT IS OFF ──────────────────────────────────────────────────────
 *
 * The system keeps a listener bound for as long as the person allows it in the
 * phone's settings, which can be longer than Reup's own switch is on. Off in
 * Reup, the listener asks the system to let it go, and no notification passes
 * through this app at all until the switch is turned on again.
 *
 * ─── WHY IT READS THE SHADE ON CONNECTING ────────────────────────────────────
 *
 * A battery manager can stop this process, and the system takes a moment to
 * bring the listener back. A bank's notice posted in between is still in the
 * shade, so whatever is there is read again whenever the listener connects
 * and whenever the app is opened. BankNotices knows which it has read already.
 */
class BankNoticeListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (!BankNotices.isOn(applicationContext)) {
            letGo()
            return
        }
        live = WeakReference(this)
        catchUp()
    }

    override fun onListenerDisconnected() {
        if (live?.get() === this) live = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // First, before anything else is touched: almost everything that
        // arrives here is somebody else's business, and leaves at this line.
        if (sbn.packageName !in BANK_APPS) return
        read(sbn)
    }

    /** The two banks' notices still in the shade. */
    private fun catchUp() {
        val shade = try {
            activeNotifications
        } catch (e: Exception) {
            null
        } ?: return
        for (sbn in shade) {
            if (sbn.packageName in BANK_APPS) read(sbn)
        }
    }

    private fun read(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // A group's summary says again what its members said, in fewer words.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // The long form when there is one: the short one can stop mid-sentence.
        // Lines last, for an app that posts its text as an inbox.
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        try {
            BankNotices.posted(applicationContext, sbn.packageName, title, text, sbn.postTime)
        } catch (e: Exception) {
            // Said without the notice: what is in it is somebody's money.
            Log.e(TAG, "could not keep a bank's notice: " + e.javaClass.simpleName)
        }
    }

    private fun letGo() {
        if (live?.get() === this) live = null
        try {
            requestUnbind()
        } catch (e: Exception) {
            Log.e(TAG, "could not let go: " + e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "BankNoticeListener"

        /** The listener while it is connected, for the app to ask what is in the shade. */
        @Volatile
        private var live: WeakReference<BankNoticeListener>? = null

        /**
         * The app opening: read the shade, or ask for the listener back if the
         * system has not brought it back yet.
         */
        fun catchUp(ctx: Context) {
            if (!BankNotices.isOn(ctx)) return
            val listener = live?.get()
            if (listener != null) listener.catchUp() else rebind(ctx)
        }

        /** Reup's own switch moved. */
        fun switched(ctx: Context, on: Boolean) {
            if (on) rebind(ctx) else live?.get()?.letGo()
        }

        private fun rebind(ctx: Context) {
            if (!BankNotices.hasAccess(ctx)) return
            try {
                NotificationListenerService.requestRebind(ComponentName(ctx, BankNoticeListener::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "could not ask for the listener back: " + e.javaClass.simpleName)
            }
        }
    }
}
