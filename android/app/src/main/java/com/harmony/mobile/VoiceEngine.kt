package com.harmony.mobile

import android.content.Context
import org.webrtc.BuiltinAudioDecoderFactoryFactory
import org.webrtc.BuiltinAudioEncoderFactoryFactory
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.Logging
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The one WebRTC factory the app uses, built lazily.
 *
 * Two things here are load-bearing:
 *
 *  - the audio device module, which owns microphone capture and playout with
 *    hardware echo cancellation and noise suppression where the device has them
 *    (the same class of processing the browser applied);
 *  - explicit audio *and video* codec factories. The SFU's offer carries video
 *    lines (the member's own screen plus one per other member), so a factory
 *    built without codec factories negotiates those m-lines against an empty
 *    codec list, and libwebrtc aborts with a hardening assertion. The video
 *    factories need an EGL context even when nothing is shared yet.
 */
object VoiceEngine {
    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var eglBase: EglBase? = null

    fun factory(context: Context): PeerConnectionFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            // Surface libwebrtc's own warnings: an uncaught Java exception inside
            // native code reaches the JNI handler and aborts with no Java stack.
            Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
            val egl = EglBase.create()
            val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val created = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setAudioDeviceModule(audioModule)
                .setAudioEncoderFactoryFactory(BuiltinAudioEncoderFactoryFactory())
                .setAudioDecoderFactoryFactory(BuiltinAudioDecoderFactoryFactory())
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            eglBase = egl
            factory = created
            return created
        }
    }

    /** The EGL context, for screen capture and for rendering a shared screen. */
    fun eglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext
}
