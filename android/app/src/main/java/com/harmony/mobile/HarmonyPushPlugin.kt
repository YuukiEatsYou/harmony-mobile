package com.harmony.mobile

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.util.concurrent.Executors

/**
 * The shell's bridge to the OS push provider (docs/PUSH.md §2/§3), the mobile
 * sibling of the deep-link seam. It is deliberately small so the JS <-> Kotlin
 * contract is explicit:
 *
 * - `getEndpoint` — the install's provider endpoint, or null until a distributor
 *   has been chosen. [HarmonyPushService] sets it through [PushEndpoint].
 * - `pushDevices` — carries one push-device request to an instance using that
 *   instance's own session cookie ([PushDevices]).
 * - `takePendingTap` + the `pushTap` event — the payload of a notification tap,
 *   buffered in [PushStore] so a cold start is not lost before the shell boots.
 * - the `pushEndpoint` event — the provider changed the endpoint, so the shell
 *   re-registers.
 *
 * The provider itself is the UnifiedPush connector, subscribed by [PushProvider]
 * and received by [HarmonyPushService].
 */
@CapacitorPlugin(name = "HarmonyPush")
class HarmonyPushPlugin : Plugin() {
    private val io = Executors.newSingleThreadExecutor()

    override fun load() {
        // A tap that arrives while the shell is alive is routed now rather than
        // waiting for the next cold start. The event carries no payload: the
        // shell drains the single buffer through takePendingTap().
        PushSignals.onTapAvailable = { notifyListeners(EVENT_TAP, JSObject()) }
        // The provider produces or rotates the endpoint asynchronously; the shell
        // re-registers when it changes, reading the value through getEndpoint().
        PushSignals.onEndpointChanged = { notifyListeners(EVENT_ENDPOINT, JSObject()) }
    }

    override fun handleOnDestroy() {
        PushSignals.onTapAvailable = null
        PushSignals.onEndpointChanged = null
        io.shutdown()
    }

    @PluginMethod
    fun getEndpoint(call: PluginCall) {
        call.resolve(JSObject().put("endpoint", PushEndpoint.get(context)))
    }

    @PluginMethod
    fun pushDevices(call: PluginCall) {
        val origin = call.getString("origin")
        val method = call.getString("method")
        val path = call.getString("path")
        if (origin == null || method == null || path == null) {
            call.reject("origin, method and path are required.")
            return
        }
        // Least privilege (F1): this bridge carries exactly the push-devices
        // contract (docs/PUSH.md §3), not an arbitrary authenticated API proxy.
        // A future script in the *shell* webview must not be able to reach any
        // other route with an instance's session cookie, so anything outside
        // the contract fails closed.
        if (path != PUSH_DEVICES_PATH || (method != "PUT" && method != "DELETE")) {
            call.reject("pushDevices only serves PUT/DELETE $PUSH_DEVICES_PATH.")
            return
        }
        // The origin must be exactly a scheme://host[:port] and one this app has
        // opened. The set is recorded natively (InstanceSession), so the caller
        // cannot widen it.
        if (SessionCookie.originOf(origin) != origin || !InstanceSession.knows(context, origin)) {
            call.reject("unknown instance origin.")
            return
        }
        val body = call.getObject("body")?.toString()
        io.execute {
            try {
                val result = PushDevices.send(origin, method, path, body)
                call.resolve(JSObject().put("ok", result.ok).put("status", result.status))
            } catch (e: Exception) {
                call.reject("push request failed: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun takePendingTap(call: PluginCall) {
        call.resolve(JSObject().put("payload", PushStore.takePendingTap(context)))
    }

    companion object {
        const val EVENT_TAP = "pushTap"
        const val EVENT_ENDPOINT = "pushEndpoint"

        /**
         * The one route `pushDevices` serves (mirrors `PUSH_DEVICES_PATH` in
         * `src/lib/push.ts`, docs/PUSH.md §3). Kept native so the allowlist does
         * not depend on the caller telling the truth.
         */
        private const val PUSH_DEVICES_PATH = "/users/@me/push-devices"
    }
}
