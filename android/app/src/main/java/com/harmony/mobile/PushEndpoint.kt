package com.harmony.mobile

import android.content.Context

/**
 * This install's push-provider endpoint (docs/PUSH.md §2/§3), set by the
 * provider adapter once a UnifiedPush/ntfy distributor has given us one. It is
 * per install, not per instance: the shell registers the same value with every
 * instance it has added.
 *
 * It lives here rather than in the shell because the provider runs natively (a
 * sideloaded APK has no Play services), and it must survive a process restart.
 */
object PushEndpoint {
    private const val PREFS = "harmony.push"
    private const val KEY_ENDPOINT = "endpoint"

    /** The provider endpoint, or null until a distributor provides one. */
    fun get(context: Context): String? =
        prefs(context).getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() }

    /** Records a new endpoint, or clears it when the provider rotates or unregisters. */
    fun set(context: Context, endpoint: String?) {
        val edit = prefs(context).edit()
        if (endpoint.isNullOrBlank()) edit.remove(KEY_ENDPOINT) else edit.putString(KEY_ENDPOINT, endpoint)
        edit.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
