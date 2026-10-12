package com.harmony.mobile

import android.app.Activity
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Subscribes this install to its UnifiedPush distributor (docs/PUSH.md §2). The
 * endpoint comes back asynchronously through [HarmonyPushService]; nothing here
 * blocks.
 *
 * With no distributor installed this is a no-op, so the whole path stays dormant
 * rather than failing — a sideloaded APK, or a ROM the user has not added a
 * distributor on, is a normal state, not an error.
 */
object PushProvider {
    /** The registration identity; one per install, keyed by the distributor. */
    const val REGISTRATION_ID = "com.harmony.mobile"

    private const val DESCRIPTION = "Harmony notifications"

    /**
     * Registers with the current or default distributor when one is available.
     * Safe to call on every launch: a repeat registration is idempotent, and the
     * distributor answers on [HarmonyPushService.onNewEndpoint]. The Activity
     * overload is the one that can still show the distributor picker.
     */
    fun ensureRegistered(activity: Activity) {
        val app = activity.applicationContext
        if (UnifiedPush.getDistributors(app).isEmpty()) return
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(app, REGISTRATION_ID, messageForDistributor = DESCRIPTION, vapid = null)
            }
        }
    }
}
