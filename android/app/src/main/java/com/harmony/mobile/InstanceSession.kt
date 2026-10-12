package com.harmony.mobile

import android.content.Context

/**
 * Remembers the instance URL the app is showing, so the shell can return to it
 * after the instance's activity is gone -- a resumed task, or a cold start --
 * instead of dropping the person back at the server selector.
 *
 * It is cleared only when the person leaves the instance on purpose (the back
 * gesture), not when the task is swiped away: a swipe is itself a restart we
 * want to resume from. A second key, never cleared, keeps the most recent
 * instance around for things that need one even from the selector -- the call
 * diagnostics test native voice against it.
 */
object InstanceSession {
    private const val PREFS = "harmony.instance"
    private const val KEY_LAST_URL = "lastUrl"
    private const val KEY_LAST_OPENED = "lastOpened"
    private const val KEY_ORIGINS = "origins"

    fun remember(context: Context, url: String) {
        val origin = SessionCookie.originOf(url)
        val editor = prefs(context).edit()
            .putString(KEY_LAST_URL, url)
            .putString(KEY_LAST_OPENED, url)
        // Accumulate the origins this app has actually opened. It is recorded
        // here, natively, so it cannot be widened by the caller -- the push
        // bridge validates against it (HarmonyPushPlugin.pushDevices).
        if (origin != null) editor.putStringSet(KEY_ORIGINS, knownOrigins(context) + origin)
        editor.apply()
    }

    /**
     * Whether this app has opened `origin`, the native record of the instances
     * it can act against. Used to fail closed for the push-devices bridge:
     * unlike the shell's own server list, a caller cannot add to it.
     */
    @JvmStatic
    fun knows(context: Context, origin: String): Boolean = knownOrigins(context).contains(origin)

    /** Forgets the instance, so a resumed shell shows the selector. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_LAST_URL).apply()
    }

    /** The instance URL to resume, or null when there is nothing to resume. */
    @JvmStatic
    fun lastUrl(context: Context): String? {
        val url = prefs(context).getString(KEY_LAST_URL, null)
        return if (url.isNullOrBlank()) null else url
    }

    /**
     * The most recent instance ever opened, kept even after it was left on
     * purpose. Its session cookie outlives leaving too, so this is what a
     * from-the-selector tool (like the voice test) can still act on.
     */
    @JvmStatic
    fun lastOpened(context: Context): String? {
        val url = prefs(context).getString(KEY_LAST_OPENED, null)
        return if (url.isNullOrBlank()) null else url
    }

    private fun knownOrigins(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_ORIGINS, emptySet()) ?: emptySet())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
