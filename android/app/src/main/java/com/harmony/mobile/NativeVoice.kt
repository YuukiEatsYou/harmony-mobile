package com.harmony.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The app's own voice client.
 *
 * Auth is the instance's own session cookie, read from the webview's cookie jar
 * (see [SessionCookie]) -- native voice joins as one more session of the same
 * member, which is why the web client's sidebar still shows them in the room.
 *
 * [probe] connects the gateway and reads the rooms, to prove auth and signaling.
 * [voiceChannels] lists the instance's voice channels for the picker, and
 * [joinChannel] hands the chosen one to [CallService], which owns the call as a
 * foreground service -- that is what keeps the microphone unmuted and the call
 * alive. Everything is reported to [CallDiagnostics], so the diagnostics screen
 * (hold the "Harmony" title) is how this is checked on a device with no adb.
 */
object NativeVoice {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var signaling: VoiceSignaling? = null

    data class VoiceChannel(val id: String, val name: String)

    /** Connects the gateway and reads the rooms, to prove auth and signaling. */
    fun probe(context: Context) {
        val target = instanceOf(context) ?: return
        CallDiagnostics.record(context, "native voice: connecting to ${target.origin}")
        val api = VoiceApi(target.origin, target.cookie)
        signaling?.close()
        val socket = VoiceSignaling(
            target.origin,
            target.cookie,
            object : VoiceSignaling.Listener {
                override fun onReady() {
                    CallDiagnostics.record(context, "native voice: signaling connected")
                }

                override fun onSessionReady(userId: String) {
                    CallDiagnostics.record(context, "native voice: session ready as $userId")
                    io.execute {
                        runCatching { api.rooms() }
                            .onSuccess { CallDiagnostics.record(context, "native voice: rooms: ${describe(it)}") }
                            .onFailure { CallDiagnostics.record(context, "native voice: rooms failed: ${it.message}") }
                    }
                }

                override fun onVoiceSignal(channelId: String, sdp: String) {
                    CallDiagnostics.record(context, "native voice: offer for $channelId (${sdp.length} chars)")
                }

                override fun onVoiceState(channelId: String, members: JSONArray) {
                    CallDiagnostics.record(context, "native voice: roster $channelId, ${members.length()} in room")
                }

                override fun onClosed(reason: String) {
                    CallDiagnostics.record(context, "native voice: gateway $reason")
                }
            },
        )
        signaling = socket
        socket.connect()
    }

    /**
     * Starts a call in a channel of [instanceUrl], owned by [CallService]. This is
     * what the client's channel list calls (through the injected host bridge) when
     * a voice channel is tapped, since the client cannot join voice itself.
     */
    fun startCall(
        context: Context,
        instanceUrl: String,
        channelId: String,
        channelName: String?,
    ) {
        val origin = SessionCookie.originOf(instanceUrl) ?: return
        val cookie = SessionCookie.headerFor(instanceUrl) ?: return
        io.execute {
            signaling?.close()
            signaling = null
            CallDiagnostics.record(context, "native voice: starting the call service")
            ContextCompat.startForegroundService(
                context,
                CallService.joinIntent(context, origin, cookie, channelId, channelName),
            )
        }
    }

    /**
     * Joins without a picker: the occupied room if any, else the first voice
     * channel. Kept for the diagnostics test.
     */
    fun join(context: Context) {
        val target = instanceOf(context) ?: return
        io.execute {
            val channel = pickChannel(VoiceApi(target.origin, target.cookie), context)
            if (channel == null) {
                CallDiagnostics.record(context, "native voice: no voice channel to join")
                return@execute
            }
            signaling?.close()
            signaling = null
            CallDiagnostics.record(context, "native voice: starting the call service")
            ContextCompat.startForegroundService(
                context,
                CallService.joinIntent(context, target.origin, target.cookie, channel.id, channel.name),
            )
        }
    }

    fun leave(context: Context) {
        context.stopService(Intent(context, CallService::class.java))
        CallDiagnostics.record(context, "native voice: left")
    }

    /** Stops everything: the probe socket and any live call. */
    fun stop(context: Context) {
        signaling?.close()
        signaling = null
        context.stopService(Intent(context, CallService::class.java))
        CallDiagnostics.record(context, "native voice: stopped")
    }

    private data class Instance(val origin: String, val cookie: String)

    private fun instanceOf(context: Context): Instance? {
        // From the selector the resume URL is usually cleared, so fall back to the
        // last instance ever opened; its cookie outlives leaving it.
        val url = InstanceSession.lastOpened(context) ?: InstanceSession.lastUrl(context)
        if (url == null) {
            CallDiagnostics.record(
                context,
                "native voice: no instance yet -- open a server once, then test from here",
            )
            return null
        }
        val origin = SessionCookie.originOf(url)
        if (origin == null) {
            CallDiagnostics.record(context, "native voice: unreadable instance url")
            return null
        }
        val cookie = SessionCookie.headerFor(url)
        if (cookie == null) {
            CallDiagnostics.record(context, "native voice: no session cookie yet (sign in first)")
            return null
        }
        return Instance(origin, cookie)
    }

    private fun pickChannel(api: VoiceApi, context: Context): VoiceChannel? {
        val byId = LinkedHashMap<String, String>()
        runCatching { api.channels() }.getOrNull()
            ?.optJSONArray("channels")
            ?.let { channels ->
                for (index in 0 until channels.length()) {
                    val channel = channels.optJSONObject(index) ?: continue
                    if (channel.optString("type") == "voice") {
                        byId[channel.optString("id")] = channel.optString("name")
                    }
                }
            }
        runCatching { api.rooms() }.getOrNull()
            ?.optJSONArray("channels")
            ?.let { rooms ->
                for (index in 0 until rooms.length()) {
                    val room = rooms.optJSONObject(index) ?: continue
                    if ((room.optJSONArray("members")?.length() ?: 0) > 0) {
                        val id = room.optString("channelId")
                        if (id.isNotBlank() && byId.containsKey(id)) {
                            CallDiagnostics.record(context, "native voice: joining occupied room $id")
                            return VoiceChannel(id, byId.getValue(id))
                        }
                    }
                }
            }
        val first = byId.entries.firstOrNull() ?: return null
        CallDiagnostics.record(context, "native voice: joining empty voice channel ${first.key}")
        return VoiceChannel(first.key, first.value)
    }

    private fun describe(rooms: JSONObject?): String {
        val channels = rooms?.optJSONArray("channels") ?: return "no rooms"
        var members = 0
        for (index in 0 until channels.length()) {
            members += channels.optJSONObject(index)?.optJSONArray("members")?.length() ?: 0
        }
        return "${channels.length()} room(s), $members member(s)"
    }
}
