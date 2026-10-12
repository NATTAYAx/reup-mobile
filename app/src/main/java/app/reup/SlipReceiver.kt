// app/src/main/java/app/reup/SlipReceiver.kt
package app.reup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.withTimeout

/**
 * The two buttons on a slip's notification: save, and don't.
 *
 * Shaped like DoneReceiver, for the same reasons, written out there at length:
 * the notification comes down first, before any of the work, because a button
 * that seems to do nothing gets pressed again; and the sync afterwards is on a
 * six-second leash, because a row that reaches the computer at the next opening
 * of the app is still a row.
 *
 * A save that does not go through is the one case that puts something back in
 * the shade, saying why. The slip itself never left the list — PendingSlips
 * only lets go of it once the row exists — so nothing is lost either way.
 */
class SlipReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id < 0L) return
        Notifications.cancelSlip(context, id)

        when (intent.action) {
            ACTION_DISMISS -> PendingSlips.remove(context, id)
            ACTION_SAVE -> finishLater(TAG) {
                val slip = PendingSlips.get(context, id)
                val result = try {
                    PendingSlips.save(context, id)
                } catch (e: Exception) {
                    Log.e(TAG, "saving a slip failed", e)
                    if (slip != null) Notifications.postSlipFailed(context, slip, e.message ?: e.toString())
                    return@finishLater
                }
                when (result) {
                    PendingSlips.Saved.Done -> try {
                        withTimeout(6_000) { Repo.syncQuietly(context) }
                    } catch (e: Exception) {
                        Log.i(TAG, "the row will go up on the next sync: ${e.message}")
                    }
                    // Already a row, or already dealt with on the other screen:
                    // the press asked for something that is true, and there is
                    // nothing to say.
                    PendingSlips.Saved.Already, PendingSlips.Saved.Gone -> Unit
                    PendingSlips.Saved.NeedsLook ->
                        if (slip != null) Notifications.postSlipFailed(context, slip, NEEDS_LOOK)
                    is PendingSlips.Saved.Refused ->
                        if (slip != null) Notifications.postSlipFailed(context, slip, result.problems.joinToString(", "))
                }
            }
        }
    }

    companion object {
        const val ACTION_SAVE = "app.reup.slip.SAVE"
        const val ACTION_DISMISS = "app.reup.slip.DISMISS"
        const val EXTRA_ID = "slip_id"
        private const val TAG = "SlipReceiver"
        private const val NEEDS_LOOK = "สลิปนี้ต้องเช็คก่อน แตะเพื่อเปิดแล้วบันทึกเอง"
    }
}
