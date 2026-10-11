package com.harmony.mobile

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Which output a call plays through.
 *
 * WebRTC sets the audio mode to `MODE_IN_COMMUNICATION`, and Android routes that
 * to the earpiece by default -- right for a phone call, wrong for a voice room,
 * where the speaker is expected. This picks the route explicitly, and lets the
 * call switch between the speaker and the earpiece.
 */
object VoiceAudio {
    fun useSpeaker(context: Context, speaker: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val routed = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val wanted = if (speaker) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = audio.availableCommunicationDevices.firstOrNull { it.type == wanted }
                if (device != null) audio.setCommunicationDevice(device)
                device != null
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = speaker
                true
            }
        }.getOrDefault(false)
        CallDiagnostics.record(
            context,
            "native voice: audio route ${if (speaker) "speaker" else "earpiece"} applied=$routed",
        )
    }
}
