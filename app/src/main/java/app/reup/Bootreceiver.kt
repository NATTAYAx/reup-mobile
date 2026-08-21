package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

/**
 * The receiver that stops the app from silently going dead.
 *
 * Android throws away every registered alarm when the device restarts, and
 * again when the app is updated. Nothing warns anyone. The symptom is that the
 * app worked for weeks and then simply stopped notifying, with no crash, no
 * error, and nothing in the app that looks different — which is why this is one
 * of the most commonly missed pieces of Android work and one of the most
 * expensive to diagnose after the fact.
 *
 * Three broadcasts are handled:
 *
 *  BOOT_COMPLETED       the ordinary restart, after the phone has been unlocked
 *  LOCKED_BOOT_COMPLETED  arrives earlier, before the first unlock after a
 *                       reboot; see below, because this is no longer free
 *  MY_PACKAGE_REPLACED  an app update, which clears alarms exactly like a
 *                       reboot does and is far easier to forget
 *
 * WHY LOCKED_BOOT_COMPLETED NOW NEEDS A GUARD
 * -------------------------------------------
 * It used to be free: rescheduling read a hardcoded list, so it worked before
 * the phone was unlocked and closed the window where a rebooted phone sat with
 * nothing scheduled. The list is a database now, and the database is in
 * credential-encrypted storage — the ordinary kind, which is the right kind for
 * a file holding medication times and money. That storage does not exist yet at
 * LOCKED_BOOT_COMPLETED. Opening it there does not return empty; it throws.
 *
 * So the guard, rather than dropping the broadcast: on a phone with no secure
 * lock screen the user is already unlocked when this arrives, and the early
 * reschedule still happens. On a phone with one, this returns and does nothing,
 * and BOOT_COMPLETED does the work a moment after the first unlock. The window
 * that leaves is a phone that has restarted and not yet been unlocked, which is
 * a phone nobody is reading notifications on.
 *
 * Moving the database to device-protected storage would close that window and
 * is the wrong trade by a long way: it would mean the task list is readable
 * without unlocking the phone.
 *
 * Timezone changes are deliberately not handled here. The broadcast is
 * unreliable across versions and vendors, and a flight lands with the phone in
 * someone's hand — the app gets opened, and opening it recomputes everything.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
                -> {
                val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
                if (!unlocked) {
                    Log.i(TAG, "still locked after ${intent.action}; waiting for the unlock")
                    return
                }

                Log.i(TAG, "rescheduling after ${intent.action}")
                Notifications.ensureChannel(context)
                finishLater(TAG) { Scheduler.reschedule(context) }
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}