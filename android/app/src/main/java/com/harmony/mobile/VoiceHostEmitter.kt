package com.harmony.mobile

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pushes the native call state to the instance's own web client, through the
 * `HarmonyVoiceHost` bridge the shell already injects -- the same object the
 * client uses to hand a voice-channel tap to us (see
 * `InstanceWebViewActivity.handleVoiceHostMessage`).
 *
 * This is the host-to-client direction of that bridge, settled as contract v1
 * (`docs/native-voice.md`, `UPSTREAM_CHANGES.md` section 7). The client assigns
 * `window.HarmonyVoiceHost.onState = (message) => {}` when it starts and also
 * accepts the payload as a `harmony:voice-state` window event; the callback is
 * preferred so the payload is delivered once.
 *
 * The client is a separate program, so nothing here trusts it: the message is
 * built to the exact v1 shape, and an unchanged state is not re-sent from
 * [onCallState]. [refresh] forces one out for a webview that has just (re)loaded
 * and lost its handler, or that has come back to the foreground.
 *
 * One v1 gap this class works around: the client re-assigns `onState`
 * *asynchronously* after it boots (it waits on its own loads first), so a state
 * sent at page-load time can arrive before the handler exists, and there is no
 * client-to-shell sync request to ask again. `InstanceWebViewActivity` therefore
 * resends with [refresh] on a short interval while a call is live; the client's
 * apply is idempotent, so a repeat is harmless.
 */
class VoiceHostEmitter(private val webView: WebView) {

    /** The last state delivered, or null after a `left`; lets [refresh] repeat it. */
    private var lastState: String? = null

    /** True between a state and the `left` that clears it, so `left` is sent once. */
    private var announced = false

    /** Sends the current call state, or a terminal `left` when the call just ended. */
    fun onCallState(state: VoiceCall.State) {
        val channelId = state.channelId
        if (state.active && channelId != null) {
            val message = stateMessage(state, channelId)
            if (message == lastState) return
            lastState = message
            announced = true
            deliver(message)
        } else if (announced) {
            lastState = null
            announced = false
            deliver(LEFT)
        }
    }

    /**
     * Re-sends the current truth, forced, to a webview that may have lost the
     * handler (a reload) or its state (a background/foreground round trip), or
     * that booted while a call was already live.
     */
    fun refresh(state: VoiceCall.State) {
        val channelId = state.channelId
        if (state.active && channelId != null) {
            val message = stateMessage(state, channelId)
            lastState = message
            announced = true
            deliver(message)
        } else if (announced) {
            lastState = null
            announced = false
            deliver(LEFT)
        }
    }

    private fun deliver(message: String) {
        // evaluateJavascript must run on the UI thread; post is a no-op there and
        // hops safely if a caller ever is not.
        webView.post {
            val script = "(function(){" +
                "try{" +
                "var m=JSON.parse(${JSONObject.quote(message)});" +
                "var h=window.HarmonyVoiceHost;" +
                "if(h&&typeof h.onState==='function'){h.onState(m);return;}" +
                "window.dispatchEvent(new CustomEvent('harmony:voice-state',{detail:m}));" +
                "}catch(e){}})()"
            webView.evaluateJavascript(script, null)
        }
    }

    companion object {
        /** The terminal v1 message, when a call ends. */
        val LEFT: String = JSONObject().put("version", 1).put("type", "left").toString()

        /**
         * Builds the v1 `state` message the client consumes. [channelId] is
         * required -- the client drops a state without one -- and `members` is
         * the full roster, this member included, mirroring `VOICE_STATE_UPDATE`.
         * `deafened` is only on `self`, never per member.
         */
        fun stateMessage(state: VoiceCall.State, channelId: String): String {
            val members = JSONArray()
            for (member in state.members) {
                members.put(
                    JSONObject()
                        .put("id", member.id)
                        .put("name", member.name)
                        .put("muted", member.muted)
                        .put("sharing", member.sharing)
                        .put("speaking", member.speaking),
                )
            }
            return JSONObject()
                .put("version", 1)
                .put("type", "state")
                .put("channelId", channelId)
                .put("channelName", state.channelName ?: JSONObject.NULL)
                .put(
                    "self",
                    JSONObject()
                        .put("muted", state.muted)
                        .put("deafened", state.deafened)
                        .put("sharing", state.localSharing),
                )
                .put("members", members)
                .toString()
        }
    }
}
