package com.harmony.mobile

/**
 * The push signals the native side raises at the shell, when it is alive
 * ([HarmonyPushPlugin] installs the handlers in `load`). Each is only a signal:
 * the tap payload is read from [PushStore] and the endpoint from [PushEndpoint],
 * so the shell always reads the current value rather than a copy and a payload
 * is never consumed twice.
 */
object PushSignals {
    /** A tap payload is waiting in [PushStore]. */
    @Volatile
    var onTapAvailable: (() -> Unit)? = null

    /** The endpoint in [PushEndpoint] changed: a new one, a rotation, or a clear. */
    @Volatile
    var onEndpointChanged: (() -> Unit)? = null
}
