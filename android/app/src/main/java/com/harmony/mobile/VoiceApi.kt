package com.harmony.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Raised for a non-2xx API response, carrying the server's error body. */
class VoiceHttpException(val code: Int, val body: String) :
    Exception("HTTP $code: ${body.take(200)}")

/**
 * The voice HTTP surface of one instance, authenticated with the instance's own
 * session cookie (see [SessionCookie]).
 *
 * These are the same routes the web client uses; the server only ever offers, so
 * the only negotiation call here is [answer]. All methods block and must be
 * called off the main thread.
 */
class VoiceApi(private val origin: String, private val cookie: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String, method: String, body: String?): Request {
        val builder = Request.Builder()
            .url("$origin/api/v1$path")
            .header("Cookie", cookie)
        when {
            body != null -> builder.method(method, body.toRequestBody(jsonType))
            method == "GET" -> builder.get()
            else -> builder.method(method, ByteArray(0).toRequestBody(null))
        }
        return builder.build()
    }

    private fun execute(request: Request): JSONObject? {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw VoiceHttpException(response.code, text)
            return if (text.isBlank()) null else JSONObject(text)
        }
    }

    /** Every occupied voice room, for a channel picker. */
    fun rooms(): JSONObject? = execute(request("/voice", "GET", null))

    /** The instance's channel list, to find a voice channel. */
    fun channels(): JSONObject? = execute(request("/channels", "GET", null))

    /** Joins (or moves to) a channel; returns the room after the join. */
    fun join(channelId: String): JSONObject? =
        execute(request("/channels/$channelId/voice", "POST", null))

    /** Answers the server's offer. */
    fun answer(channelId: String, sdp: String) {
        execute(
            request(
                "/channels/$channelId/voice/answer",
                "POST",
                JSONObject().put("sdp", sdp).toString(),
            ),
        )
    }

    /** Updates the member's own voice flags; see the schema for the fields. */
    fun patch(channelId: String, body: JSONObject) {
        execute(request("/channels/$channelId/voice", "PATCH", body.toString()))
    }

    fun leave(channelId: String) {
        execute(request("/channels/$channelId/voice", "DELETE", null))
    }
}
