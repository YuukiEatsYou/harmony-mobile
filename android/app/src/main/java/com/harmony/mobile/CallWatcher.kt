package com.harmony.mobile

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Decides when a call is live, and keeps CallService in sync with it.
 *
 * The old trigger was a message the injected shim sent when the web client
 * created its RTCPeerConnection. That is fragile exactly when it matters most:
 * the message can arrive after the app is already backgrounded, where Android 12+
 * forbids starting a foreground service, so the service never came up and the ROM
 * was free to reclaim the process -- which is what people on aggressive ROMs saw
 * (the app vanished from Recents, with no call notification).
 *
 * This derives call state natively instead, from two things the web client cannot
 * hide:
 *
 *  - It asked for the microphone ([noteMicRequested], from the webview's own
 *    permission callback). That is app-specific and happens while we are still
 *    foreground, so it marks the moment a call starts.
 *  - Android's audio mode became [AudioManager.MODE_IN_COMMUNICATION]. WebRTC --
 *    the stack inside the webview -- switches to that mode for the duration of a
 *    call, and it is a plain public getter to read.
 *
 * The shim's RTCPeerConnection report stays as a third signal. The service runs
 * while the shim reports a call, or while a client that asked for the microphone
 * is still in communication mode, and stops when both are quiet -- so a
 * peer-connection rebuild mid-call no longer flickers it, and it is up while the
 * app is still foreground.
 *
 * (Android's recording-configuration API is deliberately not used: for a normal
 * app it hands back anonymized copies of *every* app's recordings with no way to
 * tell which is ours, so it cannot answer "is this app recording".)
 */
class CallWatcher(
    private val context: Context,
    private val onActiveChanged: (active: Boolean) -> Unit,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private var shimActive = false
    private var modeActive = false
    private var micRequested = false
    private var desired = false
    private var stopped = false

    // No callback exists for the audio mode before API 31, and polling costs
    // nothing, so just poll everywhere for one code path.
    private val poll = object : Runnable {
        override fun run() {
            if (stopped) return
            setMode(audio.mode == AudioManager.MODE_IN_COMMUNICATION)
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        seed()
        handler.postDelayed(poll, POLL_MS)
    }

    /** Re-reads the audio mode now; the activity calls this on resume. */
    fun seed() {
        setMode(audio.mode == AudioManager.MODE_IN_COMMUNICATION)
    }

    /** The instance's client asked for the microphone: a call is starting. */
    fun noteMicRequested() {
        if (stopped || micRequested) return
        micRequested = true
        CallDiagnostics.micRequested = true
        CallDiagnostics.record(context, "client requested the microphone")
        apply()
    }

    /** The shim's report that the web client has an open peer connection. */
    fun setShimActive(active: Boolean) {
        if (stopped || shimActive == active) return
        shimActive = active
        CallDiagnostics.shimCallActive = active
        CallDiagnostics.record(context, "shim reports call=$active")
        apply()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(poll)
    }

    private fun setMode(active: Boolean) {
        if (stopped || modeActive == active) return
        modeActive = active
        CallDiagnostics.audioCommunicationMode = active
        CallDiagnostics.record(context, "audio mode in-communication=$active")
        apply()
    }

    private fun apply() {
        val next = shimActive || (micRequested && modeActive)
        if (next == desired) return
        desired = next
        // A finished call forgets the mic request, so a later call in some other
        // app cannot keep our service (and its notification) up.
        if (!next) micRequested = false
        CallDiagnostics.lastCallChange = if (next) "call live" else "call ended"
        onActiveChanged(next)
    }

    private companion object {
        const val POLL_MS = 2000L
    }
}
