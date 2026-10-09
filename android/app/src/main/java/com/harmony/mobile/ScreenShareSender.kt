package com.harmony.mobile

import android.util.Base64
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Bridges the injected shim in an instance webview to native screen capture.
 * Control messages are JSON strings; captured frames are raw JPEG bytes, sent
 * as an ArrayBuffer or (when the webview cannot transfer buffers) base64.
 */
class ScreenShareSender(
    private val webView: WebView,
    private val onStartRequested: (height: Int, frameRate: Int) -> Unit,
    private val onStopRequested: () -> Unit,
    private val onThemeRequested: (background: String) -> Unit,
) {
    private val arrayBufferSupported =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)

    @Volatile
    private var reply: JavaScriptReplyProxy? = null

    fun handleMessage(message: WebMessageCompat, replyProxy: JavaScriptReplyProxy) {
        reply = replyProxy
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val raw = message.data ?: return
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        when (json.optString("type")) {
            "start" -> onStartRequested(json.optInt("height", 720), json.optInt("frameRate", 30))
            "stop" -> onStopRequested()
            "theme" -> onThemeRequested(json.optString("background"))
        }
    }

    fun postJson(json: String) {
        val proxy = reply ?: return
        webView.post { proxy.postMessage(json) }
    }

    fun postFrame(jpeg: ByteArray) {
        val proxy = reply ?: return
        if (arrayBufferSupported) {
            webView.post { proxy.postMessage(jpeg) }
        } else {
            postJson("{\"type\":\"frame\",\"data\":\"" +
                Base64.encodeToString(jpeg, Base64.NO_WRAP) + "\"}")
        }
    }
}

/**
 * A process-wide handle the capture service uses to push frames back to the
 * webview, since the service and the activity are separate components.
 */
object ScreenFrameSink {
    @Volatile
    var sender: ScreenShareSender? = null

    fun postJson(json: String) {
        sender?.postJson(json)
    }

    fun postFrame(jpeg: ByteArray) {
        sender?.postFrame(jpeg)
    }
}
