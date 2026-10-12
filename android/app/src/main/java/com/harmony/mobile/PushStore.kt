package com.harmony.mobile

import android.content.Context
import android.content.Intent

/**
 * Where a notification tap waits until the shell can route it (docs/PUSH.md §5).
 *
 * A tap cold-starts the app with the payload as an intent extra; a tap while the
 * app is already alive arrives on the existing (singleTask) activity as a new
 * intent. Both go through [consumeIntent], which buffers the payload and pings
 * the shell if it is alive. The shell drains the buffer through the HarmonyPush
 * bridge, so a payload is routed exactly once.
 */
object PushStore {
    /** The extra MainActivity reads a tap payload from. */
    const val EXTRA_PUSH_PAYLOAD = "com.harmony.mobile.extra.PUSH_PAYLOAD"

    private const val PREFS = "harmony.push"
    private const val KEY_PENDING_TAP = "pendingTap"

    /**
     * Buffers the payload carried by a launching or new intent and pings the
     * shell. The extra is removed so a later resume of the same intent does not
     * re-deliver the same tap. Returns true when there was one.
     */
    fun consumeIntent(context: Context, intent: Intent?): Boolean {
        val payload = intent?.getStringExtra(EXTRA_PUSH_PAYLOAD) ?: return false
        intent.removeExtra(EXTRA_PUSH_PAYLOAD)
        deliver(context, payload)
        return true
    }

    /** Buffers a payload and, when the shell is alive, tells it there is one. */
    fun deliver(context: Context, payload: String) {
        prefs(context).edit().putString(KEY_PENDING_TAP, payload).commit()
        PushSignals.onTapAvailable?.invoke()
    }

    /** Returns and clears the buffered payload, or null when there is none. */
    fun takePendingTap(context: Context): String? {
        val value = prefs(context).getString(KEY_PENDING_TAP, null)
        if (value != null) prefs(context).edit().remove(KEY_PENDING_TAP).commit()
        return value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
