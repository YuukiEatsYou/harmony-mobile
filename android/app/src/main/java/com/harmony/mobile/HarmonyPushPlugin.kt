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
 *   has been chosen. A UnifiedPush/ntfy adapter sets it through [PushEndpoint].
 * - `pushDevices` — carries one push-device request to an instance using that
 *   instance's own session cookie ([PushDevices]).
 * - `takePendingTap` + the `pushTap` event — the payload of a notification tap,
 *   buffered in [PushStore] so a cold start is not lost before the shell boots.
 *
 * The provider adapter itself (the UnifiedPush connector + its receiver) is not
 * wired yet; until it is, `getEndpoint` returns null and registration is a
 * no-op, exactly as `docs/PUSH.md` describes.
 */
@CapacitorPlugin(name = "HarmonyPush")
class HarmonyPushPlugin : Plugin() {
    private val io = Executors.newSingleThreadExecutor()

    override fun load() {
        instance = this
        // A tap that arrives while the shell is alive is routed now rather than
        // waiting for the next cold start. The event carries no payload: the
        // shell drains the single buffer through takePendingTap().
        PushStore.onTapAvailable = { notifyListeners(EVENT_TAP, JSObject()) }
    }

    override fun handleOnDestroy() {
        if (instance === this) instance = null
        PushStore.onTapAvailable = null
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

        @Volatile
        private var instance: HarmonyPushPlugin? = null
    }
}
