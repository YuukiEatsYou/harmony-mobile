package com.harmony.mobile

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The instance's gateway connection, for voice signaling only.
 *
 * Native voice needs its own socket: the server dispatches a member's voice
 * offers to every session that member has, and the instance webview will not
 * forward them, so the app must listen for itself. The handshake is the same as
 * the web client's (see its lib/gateway.ts): the server sends HELLO, we answer
 * with IDENTIFY, and the session cookie sent with the handshake authenticates it.
 */
class VoiceSignaling(
    private val origin: String,
    private val cookie: String,
    private val listener: Listener,
) {
    interface Listener {
        /** HELLO was answered with IDENTIFY; the handshake is not finished yet. */
        fun onReady()
        /** READY arrived: the server accepted the session, for [userId]. */
        fun onSessionReady(userId: String)
        fun onVoiceSignal(channelId: String, sdp: String)
        fun onVoiceState(channelId: String, members: JSONArray)
        fun onClosed(reason: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var heartbeat: Runnable? = null
    @Volatile private var stopped = false

    fun connect() {
        stopped = false
        val url = origin.replaceFirst("http", "ws") + "/gateway"
        val request = Request.Builder().url(url).header("Cookie", cookie).build()
        socket = client.newWebSocket(request, socketListener)
    }

    fun close() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        heartbeat = null
        socket?.close(1000, "bye")
        socket = null
    }

    private val socketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket !== webSocket) return
            socket = null
            stopHeartbeat()
            if (stopped) return
            listener.onClosed("closed $code $reason")
            // 4004 rejects the session and 4005 ends it; retrying would loop.
            if (code != 4004 && code != 4005) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket !== webSocket) return
            socket = null
            stopHeartbeat()
            if (stopped) return
            listener.onClosed("failed: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun handle(text: String) {
        val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (frame.optInt("op")) {
            10 -> { // HELLO
                send(JSONObject().put("op", 2).put("d", JSONObject()))
                listener.onReady()
                val interval = frame.optJSONObject("d")?.optLong("heartbeat_interval", 45000L)
                    ?: 45000L
                startHeartbeat(interval)
            }
            1 -> send(JSONObject().put("op", 1).put("d", JSONObject.NULL)) // heartbeat -> ack
            11 -> Unit // HEARTBEAT_ACK
            0 -> dispatch(frame.optString("t"), frame.optJSONObject("d"))
        }
    }

    private fun dispatch(type: String, data: JSONObject?) {
        when (type) {
            // The first dispatch after IDENTIFY means the server has registered
            // this session; anything that dispatches to us must wait for it.
            "READY" ->
                listener.onSessionReady(data?.optJSONObject("user")?.optString("id") ?: "")
            "VOICE_SIGNAL" ->
                if (data != null) {
                    listener.onVoiceSignal(data.optString("channelId"), data.optString("sdp"))
                }
            "VOICE_STATE_UPDATE" ->
                if (data != null) {
                    listener.onVoiceState(
                        data.optString("channelId"),
                        data.optJSONArray("members") ?: JSONArray(),
                    )
                }
        }
    }

    private fun send(frame: JSONObject) {
        socket?.send(frame.toString())
    }

    private fun startHeartbeat(intervalMs: Long) {
        stopHeartbeat()
        val beat = object : Runnable {
            override fun run() {
                if (stopped) return
                send(JSONObject().put("op", 1).put("d", JSONObject.NULL))
                main.postDelayed(this, intervalMs)
            }
        }
        heartbeat = beat
        main.postDelayed(beat, intervalMs)
    }

    private fun stopHeartbeat() {
        heartbeat?.let { main.removeCallbacks(it) }
        heartbeat = null
    }

    private fun scheduleReconnect() {
        main.postDelayed({ if (!stopped) connect() }, 2000L)
    }
}
