package com.harmony.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption.
 *
 * On aggressive OEM ROMs (Xiaomi, RedMagic, Samsung, ...) an app that has not been
 * exempted can be killed in the background even behind a foreground service, and a
 * stopped activity is more likely to be torn down -- which reloads the instance
 * webview and drops a call. Asking while a call is starting is the one moment the
 * person understands why it matters.
 *
 * The dialog is asked at most once a day until the app is exempt, so a declined
 * prompt is not repeated on every call but is not forgotten either. The
 * diagnostics screen's battery button is the manual way back in.
 */
object BatteryOptimization {
    private const val PREFS = "harmony.battery"
    private const val KEY_LAST_ASKED = "exemptionAskedAt"
    private const val RETRY_MS = 24L * 60L * 60L * 1000L

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Asks for the exemption when a call starts, if it is due and not already held. */
    @SuppressLint("BatteryLife")
    fun requestForCall(activity: Activity) {
        if (isExempt(activity)) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_ASKED, 0L) < RETRY_MS) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", activity.packageName, null))
        // Starting an activity is refused if we are not actually foreground (a
        // call detected in the background); only record the attempt when it took.
        if (runCatching { activity.startActivity(intent) }.isSuccess) {
            prefs.edit().putLong(KEY_LAST_ASKED, now).apply()
        }
    }
}
