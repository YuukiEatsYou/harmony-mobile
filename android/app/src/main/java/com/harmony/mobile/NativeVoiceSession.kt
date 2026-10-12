package com.harmony.mobile

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * One native voice call: a peer connection to the instance's SFU, the microphone
 * and (when sharing) the screen, and the signaling that drives it.
 *
 * The server is the only party that offers, so this side never creates an offer:
 * a join (and every membership change) arrives as a `VOICE_SIGNAL`, and the answer
 * is posted over HTTP. Negotiation is non-trickle, so the answer is not sent until
 * ICE gathering is complete, or it would carry no candidates and the media would
 * never connect.
 *
 * The join waits for the gateway's READY dispatch, not merely for HELLO: until the
 * server has authenticated the socket it will not dispatch this member's offers to
 * it, so joining any earlier races the offer and silently loses it.
 *
 * Screen sharing negotiates nothing: the offer already carries a receive-only video
 * line for this member's own screen (answered send-only) and a send-only line per
 * other member. Sharing is attaching a capture to our line, and watching is opting
 * in to theirs.
 *
 * Everything happens on one worker thread, and the WebRTC/OkHttp callbacks hop
 * back onto it before touching the peer connection -- calling back into WebRTC
 * from its own signaling callback deadlocks.
 */
class NativeVoiceSession(
    private val context: Context,
    private val channelId: String,
    private val origin: String,
    private val cookie: String,
) {
    private val work = Executors.newSingleThreadScheduledExecutor()
    private val api = VoiceApi(origin, cookie)
    private val factory: PeerConnectionFactory = VoiceEngine.factory(context)

    private var signaling: VoiceSignaling? = null
    private var connection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var micTrack: AudioTrack? = null
    private var localStream: MediaStream? = null

    /** This member's own screen line, so a share can be attached without renegotiating. */
    private var ownVideoSender: RtpSender? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenTexture: SurfaceTextureHelper? = null

    /** Remote video tracks, by the member they belong to, for the voice view. */
    private val remoteVideo = ConcurrentHashMap<String, VideoTrack>()

    /** Track id -> member id, so an audio level can be attributed to a speaker. */
    private val trackOwner = ConcurrentHashMap<String, String>()

    @Volatile private var closed = false
    @Volatile private var waitingForIce = false
    @Volatile private var sharing = false
    private var watching: Set<String> = emptySet()

    val isSharing: Boolean get() = sharing

    /** The instance origin, and the session cookie, for loading avatars. */
    val instanceOrigin: String get() = origin
    val sessionCookie: String get() = cookie

    fun start() {
        CallDiagnostics.record(context, "native voice: joining $channelId")
        post { createMedia() }
        val socket = VoiceSignaling(origin, cookie, signalingListener)
        signaling = socket
        socket.connect()
    }

    fun setMuted(muted: Boolean) {
        post { micTrack?.setEnabled(!muted) }
        post { runCatching { api.patch(channelId, JSONObject().put("muted", muted)) } }
    }

    fun setDeafened(deafened: Boolean) {
        post { connection?.setAudioPlayout(!deafened) }
        post { runCatching { api.patch(channelId, JSONObject().put("deafened", deafened)) } }
    }

    /** Sends the call's audio to the speaker or the earpiece. */
    fun setSpeaker(speaker: Boolean) {
        VoiceAudio.useSpeaker(context, speaker)
    }

    /** The remote video track for a member, or null until the relay has sent one. */
    fun remoteVideoTrack(userId: String): VideoTrack? = remoteVideo[userId]

    /** This member's own screen track, for a local preview while sharing. */
    fun localScreenTrack(): VideoTrack? = screenTrack

    /**
     * Starts sharing this screen. [permissionData] is the result of the system
     * screen-capture consent. The capture is attached to the pre-negotiated
     * send-only video line, so nothing is renegotiated.
     */
    fun startScreenShare(activity: Context, permissionData: Intent, maxHeight: Int, maxFrameRate: Int) {
        post {
            if (sharing) return@post
            try {
                // Re-derive the sender if the saved one is missing, so a share
                // never silently does nothing.
                val sender = ownVideoSender
                    ?: connection?.transceivers
                        ?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                        ?.sender
                        ?.also { ownVideoSender = it }
                val egl = VoiceEngine.eglBaseContext()
                CallDiagnostics.record(
                    context,
                    "native voice: share requested (sender=${sender != null}, egl=${egl != null})",
                )
                if (sender == null || egl == null) return@post
                val metrics = activity.resources.displayMetrics
                var width = (metrics.widthPixels.toFloat() * maxHeight / metrics.heightPixels).toInt()
                if (width % 2 != 0) width += 1
                val helper = SurfaceTextureHelper.create("ScreenCapture", egl)
                val capturer = ScreenCapturerAndroid(
                    permissionData,
                    object : MediaProjection.Callback() {
                        override fun onStop() = post { stopScreenShare() }
                    },
                )
                val source = factory.createVideoSource(true)
                capturer.initialize(helper, activity, source.capturerObserver)
                capturer.startCapture(width, maxHeight, maxFrameRate)
                val track = factory.createVideoTrack("harmony-screen", source)
                screenTexture = helper
                screenCapturer = capturer
                screenSource = source
                screenTrack = track
                val accepted = sender.setTrack(track, true)
                sharing = true
                VoiceCall.update { it.copy(localSharing = true) }
                runCatching { api.patch(channelId, JSONObject().put("sharing", true)) }
                CallDiagnostics.record(
                    context,
                    "native voice: sharing the screen (${width}x$maxHeight), attached=$accepted",
                )
            } catch (e: Throwable) {
                CallDiagnostics.record(
                    context,
                    "native voice: share failed: ${e.javaClass.simpleName}: ${e.message}",
                )
            }
        }
    }

    fun stopScreenShare() {
        post { stopScreenShareNow() }
    }

    fun stop() {
        if (closed) return
        closed = true
        runCatching { signaling?.close() }
        signaling = null
        try {
            work.execute { teardown() }
        } catch (_: RejectedExecutionException) {
            // Already shut down.
        }
        work.shutdown()
    }

    /** Runs a step on the worker thread unless the session is already gone. */
    private fun post(block: () -> Unit) {
        if (closed) return
        try {
            work.execute { if (!closed) block() }
        } catch (_: RejectedExecutionException) {
            // Shutting down.
        }
    }

    private fun stopScreenShareNow() {
        if (!sharing && screenCapturer == null) return
        sharing = false
        runCatching { ownVideoSender?.setTrack(null, true) }
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenTrack?.dispose() }
        runCatching { screenTexture?.dispose() }
        screenCapturer = null
        screenSource = null
        screenTrack = null
        screenTexture = null
        VoiceCall.update { it.copy(localSharing = false) }
        runCatching { api.patch(channelId, JSONObject().put("sharing", false)) }
        CallDiagnostics.record(context, "native voice: stopped sharing the screen")
    }

    private fun teardown() {
        runCatching { api.leave(channelId) }
        // Tracks can be disposed by more than one owner (the stream, the senders,
        // the peer connection), so every disposal here is best-effort.
        runCatching { ownVideoSender?.setTrack(null, true) }
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenTrack?.dispose() }
        runCatching { screenTexture?.dispose() }
        runCatching { connection?.close() }
        runCatching { localStream?.dispose() }
        runCatching { micTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { connection?.dispose() }
        connection = null
        remoteVideo.clear()
        trackOwner.clear()
        CallDiagnostics.record(context, "native voice: left $channelId")
    }

    // -- media -----------------------------------------------------------------

    private fun createMedia() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = factory.createAudioSource(constraints)
        audioSource = source
        val track = factory.createAudioTrack("harmony-mic", source)
        micTrack = track
        val stream = factory.createLocalMediaStream("harmony")
        stream.addTrack(track)
        localStream = stream

        val peer = factory.createPeerConnection(emptyList<PeerConnection.IceServer>(), observer)
        if (peer == null) {
            CallDiagnostics.record(context, "native voice: could not create a peer connection")
            return
        }
        connection = peer
        peer.addTrack(track, listOf("harmony"))
        CallDiagnostics.record(context, "native voice: media ready")
    }

    // -- signaling -------------------------------------------------------------

    private val signalingListener = object : VoiceSignaling.Listener {
        override fun onReady() {
            CallDiagnostics.record(context, "native voice: signaling connected")
        }

        override fun onSessionReady(userId: String) {
            CallDiagnostics.record(context, "native voice: session ready as $userId")
            VoiceCall.selfId = userId
            post { joinRoom() }
        }

        override fun onVoiceSignal(channelId: String, sdp: String) {
            if (channelId != this@NativeVoiceSession.channelId) return
            val kinds = sdp.lineSequence()
                .filter { it.startsWith("m=") }
                .map { it.substringAfter("m=").substringBefore(" ") }
                .joinToString(",")
            CallDiagnostics.record(context, "native voice: offer (${sdp.length} chars) [$kinds]")
            Log.i(SDP_TAG, sdp.replace("\r\n", "\\n").replace("\n", "\\n"))
            post { applyOffer(sdp) }
        }

        override fun onVoiceState(channelId: String, members: JSONArray) {
            if (channelId != this@NativeVoiceSession.channelId) return
            val parsed = ArrayList<VoiceCall.Member>()
            val sharers = ArrayList<String>()
            for (index in 0 until members.length()) {
                val entry = members.optJSONObject(index) ?: continue
                val user = entry.optJSONObject("user") ?: continue
                val id = user.optString("id")
                if (id.isBlank()) continue
                val display = (user.opt("displayName") as? String)?.takeIf { it.isNotBlank() }
                val username = (user.opt("username") as? String)?.takeIf { it.isNotBlank() }
                val avatarHash = (user.opt("avatarHash") as? String)?.takeIf { it.isNotBlank() }
                val memberSharing = entry.optBoolean("sharing")
                parsed.add(
                    VoiceCall.Member(
                        id = id,
                        name = display ?: username ?: "someone",
                        muted = entry.optBoolean("muted"),
                        sharing = memberSharing,
                        avatarUrl = avatarHash?.let { "$origin/api/v1/users/$id/avatar?v=$it" },
                    ),
                )
                if (memberSharing) sharers.add(id)
            }
            CallDiagnostics.record(context, "native voice: $channelId now has ${members.length()}")
            VoiceCall.update { it.copy(members = parsed) }
            // Opt in to everyone sharing, so their pictures are relayed to us. The
            // relay forwards nothing until a viewer names a sharer.
            val wanted = sharers.toSet()
            if (wanted != watching) {
                watching = wanted
                post {
                    val ok = runCatching {
                        api.patch(channelId, JSONObject().put("watching", JSONArray(wanted)))
                    }.isSuccess
                    CallDiagnostics.record(context, "native voice: watching ${wanted.size} sharer(s) ok=$ok")
                }
            }
        }

        override fun onClosed(reason: String) {
            CallDiagnostics.record(context, "native voice: signaling $reason")
        }
    }

    private fun joinRoom() {
        // The server no-ops a rejoin of a room this member is already listed in
        // when another session still holds it, and then sends no offer at all, so
        // clear any existing seat first: leaving makes the join create a fresh
        // connection.
        runCatching { api.leave(channelId) }
        runCatching { api.join(channelId) }
            .onSuccess { room ->
                val count = room?.optJSONArray("members")?.length() ?: 0
                CallDiagnostics.record(context, "native voice: joined $channelId ($count in room)")
            }
            .onFailure { CallDiagnostics.record(context, "native voice: join failed: ${it.message}") }
    }

    private fun applyOffer(sdp: String) {
        val peer = connection ?: return
        peer.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() = post { sendAnswer() }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) = fail("setRemoteDescription", error)
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun sendAnswer() {
        val peer = connection ?: return
        // Our own screen line is offered receive-only; answer it send-only so a
        // share later is a track replacement, not a renegotiation. Keep its sender.
        val ownVideo = peer.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
        ownVideo?.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        ownVideoSender = ownVideo?.sender
        peer.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription?) {
                    if (answer == null) {
                        fail("createAnswer", "empty answer")
                        return
                    }
                    post { setLocal(answer) }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) = fail("createAnswer", error)
                override fun onSetFailure(error: String?) {}
            },
            MediaConstraints(),
        )
    }

    private fun setLocal(answer: SessionDescription) {
        val peer = connection ?: return
        peer.setLocalDescription(
            object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {}
                override fun onSetSuccess() = post { maybeAnswer() }
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) = fail("setLocalDescription", error)
            },
            answer,
        )
    }

    private fun maybeAnswer() {
        val peer = connection ?: return
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            postAnswer()
        } else {
            waitingForIce = true
        }
    }

    private fun postAnswer() {
        waitingForIce = false
        val sdp = connection?.localDescription?.description ?: return
        Log.i(SDP_TAG, "answer " + sdp.replace("\r\n", "\\n").replace("\n", "\\n"))
        runCatching { api.answer(channelId, sdp) }
            .onSuccess { CallDiagnostics.record(context, "native voice: answered (${sdp.length} chars)") }
            .onFailure { CallDiagnostics.record(context, "native voice: answer failed: ${it.message}") }
    }

    private fun fail(step: String, error: String?) {
        CallDiagnostics.record(context, "native voice: $step failed: ${error ?: "unknown"}")
    }

    /**
     * Samples the RTP counters, so the log says whether audio is actually moving
     * rather than only that the connection is up, and derives who is speaking from
     * the inbound audio levels. Runs for the life of the call.
     */
    private fun pumpStats() {
        val peer = connection ?: return
        if (closed) return
        peer.getStats { report ->
            if (closed) return@getStats
            var sent = 0L
            var received = 0L
            var micEnergy = 0.0
            val speaking = HashSet<String>()
            for (stat in report.statsMap.values) {
                when (stat.type) {
                    "outbound-rtp" -> sent += (stat.members["bytesSent"] as? Number)?.toLong() ?: 0L
                    "inbound-rtp" -> {
                        received += (stat.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                        if (stat.members["kind"] == "audio") {
                            val level = (stat.members["audioLevel"] as? Number)?.toDouble() ?: 0.0
                            val owner = (stat.members["trackIdentifier"] as? String)?.let { trackOwner[it] }
                            if (level > SPEAKING_LEVEL && owner != null) speaking.add(owner)
                        }
                    }
                    "media-source" -> if (stat.members["kind"] == "audio") {
                        micEnergy = (stat.members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0
                    }
                }
            }
            CallDiagnostics.record(
                context,
                "native voice: rtp sent=$sent received=$received micEnergy=${"%.4f".format(micEnergy)}",
            )
            if (speaking.isNotEmpty() || VoiceCall.state.members.any { it.speaking }) {
                VoiceCall.update { state ->
                    state.copy(members = state.members.map { it.copy(speaking = it.id in speaking) })
                }
            }
            try {
                work.schedule({ pumpStats() }, 1, TimeUnit.SECONDS)
            } catch (_: RejectedExecutionException) {
                // Shutting down.
            }
        }
    }

    // -- peer connection callbacks --------------------------------------------

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            CallDiagnostics.record(context, "native voice: ice $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE && waitingForIce) {
                post { postAnswer() }
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {}

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: DataChannel?) {}

        override fun onRenegotiationNeeded() {}

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            CallDiagnostics.record(context, "native voice: connection $state")
            if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                VoiceCall.update { it.copy(connecting = false, connected = true) }
                // Pick the output now that audio is flowing; Android defaults a
                // communication call to the earpiece.
                VoiceAudio.useSpeaker(context, VoiceCall.state.speaker)
                post { pumpStats() }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            CallDiagnostics.record(context, "native voice: got a ${track?.kind() ?: "?"} track")
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
            // The relay names each forwarded stream with its producer's user id.
            val producer = mediaStreams?.firstOrNull()?.id ?: "unknown"
            val track = receiver?.track() ?: return
            trackOwner[track.id()] = producer
            if (track is VideoTrack) {
                remoteVideo[producer] = track
                // The line exists from the start but stays muted until the relay
                // actually forwards a picture.
                track.setEnabled(true)
                VoiceCall.update { it.copy() }
            }
            CallDiagnostics.record(context, "native voice: track from $producer")
        }
    }

    private companion object {
        const val SDP_TAG = "HarmonyVoiceSdp"
        const val SPEAKING_LEVEL = 0.02
    }
}
