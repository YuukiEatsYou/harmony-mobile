package com.harmony.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Field diagnostics for the call foreground service.
 *
 * The reason this exists: some OEM ROMs kill the app while a call is live, and
 * the people affected often have no PC and therefore no adb. The answers to "did
 * the foreground service ever start, and what is the device doing to us" have to
 * live on the device itself, where the shell can show them and share them from
 * the phone (see HarmonyDiagnosticsPlugin).
 *
 * State is process-wide -- CallService and InstanceWebViewActivity both report
 * into it -- and is mirrored to a small file so a kill or a crash leaves the
 * last events behind.
 */
object CallDiagnostics {
    private const val TAG = "HarmonyCallDiag"
    private const val MAX_EVENTS = 200
    private const val LOG_FILE = "harmony-diagnostics.log"

    private val lock = Any()
    private val events = ArrayDeque<String>()

    /** Whether CallService last reached `startForeground`. */
    @Volatile var fgsRunning = false

    /** The foreground service type in force, or "none". */
    @Volatile var foregroundType = "none"

    /** "never attempted", "ok" or "failed". */
    @Volatile var lastStartResult = "never attempted"

    /** The last exception that stopped the service from going foreground. */
    @Volatile var lastError: String? = null

    /** Whether the audio mode is communication, i.e. a VoIP call is live. */
    @Volatile var audioCommunicationMode = false

    /** Whether the instance's client has asked for the microphone this session. */
    @Volatile var micRequested = false

    /** What the injected shim last reported about the web client's call. */
    @Volatile var shimCallActive = false

    /** The last call state CallWatcher decided on. */
    @Volatile var lastCallChange = "never"

    /** Appends one timestamped event, to the ring buffer and the on-disk log. */
    fun record(context: Context, event: String) {
        Log.i(TAG, event)
        val line = "${stamp()}  $event"
        synchronized(lock) {
            events.addLast(line)
            while (events.size > MAX_EVENTS) events.removeFirst()
            runCatching {
                File(context.applicationContext.filesDir, LOG_FILE).writeText(
                    events.joinToString(separator = "\n", postfix = "\n"),
                )
            }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            events.clear()
            runCatching { File(context.applicationContext.filesDir, LOG_FILE).delete() }
        }
    }

    /** A plain-text report: device, live state, permissions, and the event log. */
    fun snapshot(context: Context): String {
        val log = synchronized(lock) { events.toList() }
        return buildList {
            add("Harmony call diagnostics")
            add("generated     ${stamp()}")
            add("")
            add("-- device --")
            add("manufacturer  ${Build.MANUFACTURER}")
            add("model         ${Build.MODEL}")
            add("android       ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            add("display       ${Build.DISPLAY}")
            add("fingerprint   ${Build.FINGERPRINT}")
            add("")
            add("-- call state --")
            add(
                "call service  " +
                    if (fgsRunning) "running (foreground type: $foregroundType)" else "NOT running",
            )
            add("last start    $lastStartResult")
            add("audio mode    ${if (audioCommunicationMode) "in communication" else "normal"}")
            add("mic requested ${if (micRequested) "yes" else "no"}")
            add("shim call     ${if (shimCallActive) "active" else "inactive"}")
            add("last change   $lastCallChange")
            lastError?.let { add("last error    $it") }
            add("")
            add("-- permissions --")
            add("record audio  ${check(context, android.Manifest.permission.RECORD_AUDIO)}")
            add("notifications ${if (notificationsEnabled(context)) "enabled" else "DISABLED"}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add("post notifs   ${check(context, android.Manifest.permission.POST_NOTIFICATIONS)}")
            }
            add("")
            add("-- power --")
            add("battery exempt ${if (ignoringBattery(context)) "yes" else "no"}")
            add("bg restricted  ${if (backgroundRestricted(context)) "yes" else "no"}")
            add("")
            add("-- events (oldest first) --")
            addAll(log)
        }.joinToString("\n")
    }

    private fun check(context: Context, permission: String): String =
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "DENIED"
        }

    private fun notificationsEnabled(context: Context): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .getOrDefault(true)

    private fun ignoringBattery(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun backgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.isBackgroundRestricted
    }

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
