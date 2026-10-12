package com.harmony.mobile

import java.net.URI

/**
 * The single origin a one-instance webview is allowed to reach over the bridges
 * this app injects (`WebViewCompat.addWebMessageListener` and
 * `WebViewCompat.addDocumentStartJavaScript`).
 *
 * The shell owns the `HarmonyVoiceHost` bridge, whose listener starts and ends a
 * native call (`InstanceWebViewActivity.handleVoiceHostMessage` ->
 * `requestMicThenJoin`). A wildcard (`"*"`) would let any frame the WebView
 * happens to host -- under any origin -- ask the shell for the microphone or
 * leave a call. Navigation is intended to stay on the instance host
 * (`shouldOverrideUrlLoading`), but the allowed-origin rule is the check the
 * WebView itself enforces, so it must be the instance's own origin and nothing
 * else.
 *
 * The value follows the WebView rule format `scheme://host[:port]` (see
 * `WebViewCompat.addWebMessageListener`): a rule with no port matches the
 * scheme's default port (80 for http, 443 for https), so the default port is
 * left off and only a non-default port is kept. A null result means the url is
 * not an http(s) origin this app should bridge to -- fail closed (no shim, no
 * listener) rather than widen back to a wildcard.
 *
 * Pure (no Android types), so it is unit-tested on the JVM without a device:
 * see `WebViewOriginTest` (`./gradlew testDebugUnitTest`).
 */
object WebViewOrigin {

    /**
     * The allowed-origin rule for [url], or null when [url] is not an http(s)
     * url with a host (a malformed address, a `harmony://` link, `about:blank`).
     */
    fun ruleFor(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        // java.net.URI keeps the brackets on an IPv6 literal host ("[::1]");
        // add them only if a caller handed us a bare IPv6 host.
        val hostPart = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val port = uri.port
        val defaultPort = if (scheme == "https") 443 else 80
        return if (port == -1 || port == defaultPort) {
            "$scheme://$hostPart"
        } else {
            "$scheme://$hostPart:$port"
        }
    }
}
