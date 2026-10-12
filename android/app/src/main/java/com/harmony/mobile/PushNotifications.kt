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
 * The wording, icon, permission timing, and foreground suppression follow the
 * UX review (YUC-21).
 */
object PushNotifications {
    private const val CHANNEL_ID = "harmony-push"
    private const val CHANNEL_NAME = "Messages"

    /** Shows one notification for a payload; the payload is the tap's address. */
    fun show(context: Context, payload: String) {
        // Foreground arrival (YUC-21 §4): the instance's own client already
        // surfaces the delivery in-app, so a tray post would double-signal and
        // its tap would reopen where the person already is. Nothing is lost.
        if (AppForeground.isForeground) return

        // Platform-permission reality: without the POST_NOTIFICATIONS grant (or
        // with notifications off) there is no tray post, so there is nothing to
        // tap. The mention is dropped rather than mis-routed later.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val content = contentFor(payload)
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
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setSmallIcon(R.drawable.ic_stat_harmony)
            // Category MESSAGE: Do Not Disturb and ranking treat it as a message.
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply { content.subText?.let { setSubText(it) } }
            .build()

        // One notification per payload, so a repeat delivery replaces rather than
        // stacks; the id only has to be stable within this app.
        manager.notify(payload.hashCode(), notification)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // IMPORTANCE_DEFAULT: a mention is worth a sound, not a full-screen
        // interruption.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /**
     * Parses the payload at the trust boundary (an unparseable delivery is shown
     * as a plain message rather than dropped here — the tap cannot route it
     * either way) and maps the display fields with the pure [PushText].
     */
    private fun contentFor(payload: String): PushText.Content {
        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return PushText.Content("Harmony", "New message", null)
        return PushText.summarize(
            kind = json.optString("kind"),
            authorName = json.optString("authorName"),
            channelName = json.optString("channelName"),
            instanceName = json.optString("instanceName"),
            snippet = json.optString("snippet").takeIf { it.isNotBlank() },
        )
    }
}
