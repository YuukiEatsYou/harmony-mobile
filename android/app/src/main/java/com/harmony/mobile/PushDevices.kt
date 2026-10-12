package com.harmony.mobile

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The push-device route of one instance (docs/PUSH.md §3), authenticated with
 * the instance's own session cookie through [SessionCookie] — the same cookie
 * jar the instance's webview uses. The session is httpOnly, so page JavaScript
 * cannot read it and the shell cannot make this call itself; it asks the
 * HarmonyPush bridge instead.
 *
 * The request shape (method, path, body) is decided by the shell (`src/lib/push.ts`),
 * so there is one definition of the contract; this only carries it with a
 * session. All methods block and are called off the main thread.
 */
object PushDevices {
    /** The outcome of one request. A 401 (not signed in here) is not an error. */
    data class Result(val ok: Boolean, val status: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // F2: never follow a redirect off the instance. This client sets a
        // manual `Cookie` header and installs no CookieJar, so OkHttp would copy
        // the session cookie to the redirect host (and re-send the body on
        // 307/308) -- an SSRF/capability disclosure the web path already
        // rejects (`meta.ts`). A 3xx is returned as-is and treated as a
        // per-instance failure below; it is never re-issued to the target.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * Sends one request to `origin`. Returns `ok=false, status=0` when there is
     * no session at all, and the real status otherwise; only an I/O failure
     * throws, which the caller reports as a per-instance failure. A 3xx is not
     * followed (F2) -- it comes back as an unsuccessful result with its status.
     */
    fun send(origin: String, method: String, path: String, body: String?): Result {
        val cookie = SessionCookie.headerFor(origin) ?: return Result(false, 0)
        val builder = Request.Builder()
            .url("$origin/api/v1$path")
            .header("Cookie", cookie)
        when {
            body != null -> builder.method(method, body.toRequestBody(jsonType))
            method == "GET" -> builder.get()
            else -> builder.method(method, ByteArray(0).toRequestBody(null))
        }
        client.newCall(builder.build()).execute().use { response ->
            return Result(response.isSuccessful, response.code)
        }
    }
}
