package com.harmony.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

/**
 * Raises the tray notification for a delivered push payload (docs/PUSH.md §5).
 *
 * The payload rides the content intent as [PushStore.EXTRA_PUSH_PAYLOAD], so a
 * tap is handled exactly like a cold-start extra: `MainActivity` buffers it and
 * the shell routes it once. Nothing is routed from here — a delivery only makes
 * the notification, and the tap is what opens the instance.
 *
 * The exact icon, wording, and the moment to ask for the notification permission
 * are a UX call (Sylphiette); this is the functional baseline.
 */
object PushNotifications {
    private const val CHANNEL_ID = "harmony-push"

    /** Shows one notification for a payload; the payload is the tap's address. */
    fun show(context: Context, payload: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        // Platform-permission reality: without the POST_NOTIFICATIONS grant (or
        // with notifications off) the tray post is suppressed. There is nothing
        // to tap, so if the shell is alive route now; otherwise the mention is
        // simply lost rather than mis-routed on a later launch.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            if (PushSignals.onTapAvailable != null) PushStore.deliver(context, payload)
            return
        }

        val (title, text) = summarize(payload)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(PushStore.EXTRA_PUSH_PAYLOAD, payload)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        // One notification per payload, so a repeat delivery replaces rather than
        // stacks; the id only has to be stable within this app.
        manager.notify(payload.hashCode(), notification)
    }

    /** The display title and body, from the docs/PUSH.md §6 payload. */
    private fun summarize(payload: String): Pair<String, String> {
        return runCatching {
            val json = JSONObject(payload)
            val title = json.optString("channelName").ifBlank { json.optString("instanceName") }
                .ifBlank { "Harmony" }
            val author = json.optString("authorName")
            val snippet = json.optString("snippet")
            val text = when {
                author.isNotBlank() && snippet.isNotBlank() -> "$author: $snippet"
                snippet.isNotBlank() -> snippet
                author.isNotBlank() -> author
                else -> "New message"
            }
            title to text
        }.getOrElse { "Harmony" to "New notification" }
    }
}
