package app.reup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri

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
 *
 * AND ONE BUTTON. The thing a person does after reading one of these is tick it
 * off, and until now that meant unlocking, finding the app, waiting for a list
 * to load and pressing a row — for an action that is already fully described by
 * the notification they are looking at. This is the one thing a phone can do
 * that the card on the desktop cannot, and it is the reason the phone was worth
 * building at all.
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

    /**
     * @param taskId the row this is about, or null when it is about nothing —
     *        the test notification, which must keep working on a phone whose
     *        database is empty. No uid, no button: a button that ticks nothing
     *        is worse than no button, because it looks like it worked.
     */
    fun post(context: Context, id: Int, title: String, body: String, taskId: String? = null) {
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

        val builder = Notification.Builder(context, CHANNEL_RESETS)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)

        if (taskId != null) {
            // ── WHY THE REQUEST CODE AND THE DATA BOTH CARRY THE ROW ──────────
            //
            // Two PendingIntents are the same object to the system when their
            // action, data, type, class and categories match. Extras are not on
            // that list. So eight of these built the obvious way — same class,
            // no data, only the uid differing in an extra — are one
            // PendingIntent, and FLAG_UPDATE_CURRENT quietly points every one of
            // them at whichever task was posted last.
            //
            // Nothing about that fails. Every button works, every press ticks
            // something off, and it is the wrong row. The only trace is a task
            // that goes quiet without being touched and another that keeps
            // ringing.
            //
            // The request code alone would be enough here, since it is the
            // notification id and that is already the uid's hash. The uri is
            // belt as well as braces, and it also makes the intent readable in a
            // log, which the extras are not.
            val done = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, DoneReceiver::class.java)
                    .setData(Uri.parse("reup://done/" + Uri.encode(taskId)))
                    .putExtra(DoneReceiver.EXTRA_TASK_ID, taskId)
                    .putExtra(DoneReceiver.EXTRA_NOTIFICATION_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.checkbox_on_background),
                    "เสร็จแล้ว",
                    done,
                ).build(),
            )
        }

        manager.notify(id, builder.build())
    }
}