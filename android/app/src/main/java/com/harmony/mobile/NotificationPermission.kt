package com.harmony.mobile

import android.content.Context

/**
 * Whether the notification permission has already been asked for (YUC-21 §3).
 *
 * The ask is spent once, at the first instance open, so a denial is not re-asked
 * (Android 13+ auto-denies after two asks, so the one good ask is worth spending
 * at a meaningful moment). The recovery path is the OS settings toggle; the
 * diagnostics screen can surface it later.
 */
object NotificationPermission {
    private const val PREFS = "harmony.push"
    private const val KEY_ASKED = "notificationsAsked"

    /** True until the one ask has been made. */
    fun shouldAsk(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_ASKED, false)

    /** Records that the ask was made, before the dialog, so a denial is not re-asked. */
    fun markAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_ASKED, true).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
