package com.harmony.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug only: exercises the push path without a provider, so it can be checked
 * on a device directly (the UnifiedPush distributor may not be installed, and
 * upstream delivery needs a reachable instance):
 *
 *   # Raise the real tray notification (PushNotifications.show), then tap it:
 *   adb shell am broadcast -a com.harmony.mobile.PUSH_SHOW \
 *     -n com.harmony.mobile/.PushDebugReceiver \
 *     --es payload '{"instanceId":"...","channelId":"...","messageId":"..."}'
 *
 *   # Route a tap while the app is running (no tray):
 *   adb shell am broadcast -a com.harmony.mobile.PUSH_TAP \
 *     -n com.harmony.mobile/.PushDebugReceiver \
 *     --es payload '{"instanceId":"...","channelId":"...","messageId":"..."}'
 *
 *   # A cold start with the payload extra (what a notification's content intent
 *   # does):
 *   adb shell am start -n com.harmony.mobile/.MainActivity \
 *     --es com.harmony.mobile.extra.PUSH_PAYLOAD '{"instanceId":"..."}'
 *
 * A debuggable-only receiver, so a release build ignores it.
 */
class PushDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
        when (intent.action) {
            ACTION_TAP -> PushStore.deliver(context, payload)
            ACTION_SHOW -> PushNotifications.show(context, payload)
            else -> return
        }
    }

    companion object {
        const val ACTION_TAP = "com.harmony.mobile.PUSH_TAP"
        const val ACTION_SHOW = "com.harmony.mobile.PUSH_SHOW"
        const val EXTRA_PAYLOAD = "payload"
    }
}
