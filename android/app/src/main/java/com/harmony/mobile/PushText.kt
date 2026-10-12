package com.harmony.mobile

/**
 * The wording of a tray notification (YUC-21 §1), as a genuine function of the
 * payload's display fields. It is separated from the Android notification so the
 * mapping can be unit-tested on the JVM (`PushTextTest`) without a device; the
 * parsing at the trust boundary and the posting stay in [PushNotifications].
 *
 * The app label (**Harmony**) is the notification header, so the title's job is
 * the strongest at-a-glance identifier: the sender. Author-first is the
 * recommendation; the channel reads as context in the body.
 */
object PushText {
    /** The three strings a notification shows. `subText` is null when empty. */
    data class Content(val title: String, val body: String, val subText: String?)

    /**
     * Maps a payload's display fields to the title/body/subText. Pure: no Android
     * types, so it is testable on the JVM.
     */
    fun summarize(
        kind: String,
        authorName: String,
        channelName: String,
        instanceName: String,
        snippet: String?,
    ): Content {
        // The bold title names the person (one distinct thing to scan); fall back
        // through channel, instance, then the app name.
        val title = authorName.trim().ifEmpty { channelName.trim() }
            .ifEmpty { instanceName.trim() }.ifEmpty { "Harmony" }

        // Front-load the kind word: the collapsed line truncates the tail, so the
        // important part must survive. Second person, and the two are symmetric.
        val verb = if (kind == "reply") "Replied to you" else "Mentioned you"
        val channel = channelName.trim()
        val lead = if (channel.isEmpty()) verb else "$verb in #$channel"
        val preview = snippet?.trim().orEmpty()
        val body = if (preview.isEmpty()) lead else "$lead: $preview"

        // Multi-instance: say which server it came from.
        return Content(title, body, instanceName.trim().ifEmpty { null })
    }
}
