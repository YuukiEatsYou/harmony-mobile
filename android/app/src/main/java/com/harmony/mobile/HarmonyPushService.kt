package com.harmony.mobile

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint as ConnectorEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives deliveries and endpoint updates from the UnifiedPush connector
 * (docs/PUSH.md §2/§4). It writes them into the same seam the rest of the app
 * already uses — the endpoint into [PushEndpoint], a payload into [PushStore] —
 * so the shell registers the endpoint with every instance and routes a tap
 * without a second path to reason about.
 *
 * The connector forwards to this service from its own receiver; see the
 * manifest's `PUSH_EVENT` intent-filter.
 */
class HarmonyPushService : PushService() {
    override fun onNewEndpoint(endpoint: ConnectorEndpoint, instance: String) {
        PushEndpoint.set(this, endpoint.url)
        PushSignals.onEndpointChanged?.invoke()
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // The Harmony payload is the UTF-8 JSON of docs/PUSH.md §6. A delivery
        // only raises the tray notification; the tap routes (docs/PUSH.md §5).
        val payload = message.content.toString(Charsets.UTF_8)
        if (payload.isBlank()) return
        PushNotifications.show(this, payload)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // No user-facing failure (platform-permission reality): the path simply
        // stays dormant and a later attempt retries. Clearing any stale endpoint
        // keeps registration honest.
        PushEndpoint.set(this, null)
        PushSignals.onEndpointChanged?.invoke()
    }

    override fun onUnregistered(instance: String) {
        PushEndpoint.set(this, null)
        PushSignals.onEndpointChanged?.invoke()
    }
}
