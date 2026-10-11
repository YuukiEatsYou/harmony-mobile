package com.harmony.mobile

import android.net.Uri
import android.webkit.CookieManager

/**
 * The instance's session cookie, which native voice needs in order to talk to the
 * API and the gateway on the instance's behalf.
 *
 * The client signs in with an httpOnly cookie, which page JavaScript cannot read
 * -- so the webview cannot hand it to us -- but Android's CookieManager can, and
 * it is the same cookie jar the instance's webview uses, so it is the live
 * session, not a copy.
 */
object SessionCookie {
    /** The whole Cookie header value for a URL, or null when there is no session yet. */
    fun headerFor(instanceUrl: String): String? =
        runCatching { CookieManager.getInstance().getCookie(instanceUrl) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** The scheme://host[:port] of an instance URL, or null if it cannot be read. */
    fun originOf(instanceUrl: String): String? {
        val uri = runCatching { Uri.parse(instanceUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "$scheme://$host$port"
    }
}
