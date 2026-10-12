package com.harmony.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug only: delivers a push payload without a provider, so the tap path can
 * be exercised on a device directly (the provider adapter is not wired yet):
 *
 *   adb shell am broadcast -a com.harmony.mobile.PUSH_TAP \
 *     -n com.harmony.mobile/.PushDebugReceiver \
 *     --es payload '{"instanceId":"...","channelId":"...","messageId":"..."}'
 *
 * and a cold start with:
 *
 *   adb shell am start -n com.harmony.mobile/.MainActivity \
 *     --es com.harmony.mobile.extra.PUSH_PAYLOAD '{"instanceId":"..."}'
 *
 * A debuggable-only receiver, so a release build ignores it.
 */
class PushDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent.action != ACTION_TAP) return
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
        PushStore.deliver(context, payload)
    }

    companion object {
        const val ACTION_TAP = "com.harmony.mobile.PUSH_TAP"
        const val EXTRA_PAYLOAD = "payload"
    }
}
