package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
 *  BOOT_COMPLETED       the ordinary restart
 *  LOCKED_BOOT_COMPLETED  arrives earlier, before the user has unlocked after a
 *                       reboot; harmless to handle both since rescheduling is
 *                       idempotent, and it closes the window where a phone sits
 *                       rebooted-but-locked with nothing scheduled
 *  MY_PACKAGE_REPLACED  an app update, which clears alarms exactly like a
 *                       reboot does and is far easier to forget
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
                Log.i("BootReceiver", "rescheduling after ${intent.action}")
                Notifications.ensureChannel(context)
                Scheduler.reschedule(context)
            }
        }
    }
}