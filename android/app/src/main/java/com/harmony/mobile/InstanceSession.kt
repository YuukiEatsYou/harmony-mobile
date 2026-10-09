package com.harmony.mobile

import android.content.Context

/**
 * Remembers the instance URL the app is showing, so the shell can return to it
 * after the instance's activity is gone -- a resumed task, or a cold start --
 * instead of dropping the person back at the server selector.
 *
 * It is cleared only when the person leaves the instance on purpose (the back
 * gesture), not when the task is swiped away: a swipe is itself a restart we
 * want to resume from.
 */
object InstanceSession {
    private const val PREFS = "harmony.instance"
    private const val KEY_LAST_URL = "lastUrl"

    fun remember(context: Context, url: String) {
        prefs(context).edit().putString(KEY_LAST_URL, url).apply()
    }

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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
