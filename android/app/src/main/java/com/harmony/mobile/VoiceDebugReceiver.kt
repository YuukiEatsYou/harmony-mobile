package com.harmony.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug only: lets adb drive the native voice checks without the voice UI, so a
 * fix can be exercised on a device directly:
 *
 *   adb shell am broadcast -a com.harmony.mobile.VOICE_JOIN \
 *     -n com.harmony.mobile/.VoiceDebugReceiver
 *
 * A debuggable-only receiver, so a release build ignores it.
 */
class VoiceDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        when (intent.action) {
            ACTION_PROBE -> NativeVoice.probe(context)
            ACTION_JOIN -> NativeVoice.join(context)
            ACTION_LEAVE -> NativeVoice.leave(context)
            ACTION_STOP -> NativeVoice.stop(context)
            ACTION_FORGET -> InstanceSession.forget(context)
            ACTION_OPEN -> context.startActivity(
                Intent(context, VoiceViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ACTION_INSTANCE -> {
                val url = InstanceSession.lastOpened(context) ?: return
                InstanceSession.remember(context, url)
                context.startActivity(
                    Intent(context, InstanceWebViewActivity::class.java)
                        .putExtra(InstanceWebViewActivity.EXTRA_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            ACTION_MUTE -> VoiceCall.toggleMute()
            ACTION_DEAFEN -> VoiceCall.toggleDeafen()
        }
    }

    companion object {
        const val ACTION_PROBE = "com.harmony.mobile.VOICE_PROBE"
        const val ACTION_JOIN = "com.harmony.mobile.VOICE_JOIN"
        const val ACTION_LEAVE = "com.harmony.mobile.VOICE_LEAVE"
        const val ACTION_STOP = "com.harmony.mobile.VOICE_STOP"
        const val ACTION_FORGET = "com.harmony.mobile.VOICE_FORGET"
        const val ACTION_OPEN = "com.harmony.mobile.VOICE_OPEN"
        const val ACTION_INSTANCE = "com.harmony.mobile.VOICE_INSTANCE"
        const val ACTION_MUTE = "com.harmony.mobile.VOICE_MUTE"
        const val ACTION_DEAFEN = "com.harmony.mobile.VOICE_DEAFEN"
    }
}
