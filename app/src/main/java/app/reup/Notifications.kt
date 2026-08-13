package app.reup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Posting the notification, and the two defaults that matter more than the rest
 * of this file put together.
 *
 * SILENT BY DEFAULT. Not because sound is bad, but because a phone lives with
 * someone all day and a reminder app that makes noise gets its notifications
 * switched off wholesale within a week — at which point it notifies about
 * nothing, forever, and the person has no idea that is what happened. Anyone
 * who wants sound can turn it on in the system channel settings, where it
 * belongs. Anyone who would have been startled by it never was.
 *
 * HIDDEN ON THE LOCK SCREEN. The default everywhere is to print the whole
 * notification on a locked screen, which means a task name ends up readable by
 * whoever is sitting nearby whenever the phone is face-up on a table. Task
 * names here are mostly games. They are also sometimes a medicine, an
 * appointment, or a line of someone's finances. The redacted version says
 * something happened; unlocking says what.
 *
 * Both are choices about the person holding the phone rather than about
 * software, and both are much harder to add later than to start with.
 */
object Notifications {

    const val CHANNEL_RESETS = "resets"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            CHANNEL_RESETS,
            "รอบรีเซ็ต",
            // DEFAULT rather than HIGH: it appears in the shade and stays there
            // until dismissed, but never takes over the screen. Nothing this app
            // has to say justifies interrupting whatever is already happening.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "แจ้งเมื่องานถึงรอบ"
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        // Creating a channel that already exists is a no-op EXCEPT that the
        // system keeps whatever the user changed. So this can be called every
        // launch, and a person who turned sound on stays turned on.
        manager.createNotificationChannel(channel)
    }

    fun post(context: Context, id: Int, title: String, body: String) {
        val manager = context.getSystemService(NotificationManager::class.java)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // What a locked screen is allowed to show. Without this the system
        // redacts to a generic app-name line, which is fine but says less than
        // it could.
        val redacted = Notification.Builder(context, CHANNEL_RESETS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("มีบางอย่างถึงรอบแล้ว")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        val notification = Notification.Builder(context, CHANNEL_RESETS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .build()

        manager.notify(id, notification)
    }
}