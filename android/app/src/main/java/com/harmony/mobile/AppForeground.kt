package com.harmony.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether one of the app's activities is currently started (YUC-21 §4), so a
 * delivery can suppress the tray notification while the app is foreground: the
 * instance's own client already surfaces it in-app (a mention sound and an
 * unread mark), so a tray post would double-signal and its tap would reopen
 * where the person already is. Nothing is silently lost.
 *
 * A started-activity counter rather than `ProcessLifecycleOwner`, so no new
 * dependency is needed; the count is only read for a yes/no. [HarmonyApplication]
 * feeds it without every activity having to call in.
 */
object AppForeground {
    @Volatile
    private var started = 0

    @Volatile
    var isForeground: Boolean = false
        private set

    fun started() {
        started += 1
        isForeground = true
    }

    fun stopped() {
        started = (started - 1).coerceAtLeast(0)
        isForeground = started > 0
    }
}

/**
 * The app, so [AppForeground] follows every activity (the shell, an instance, a
 * call, the roster) without each one remembering to. Registered as
 * `android:name` in the manifest.
 */
class HarmonyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = AppForeground.started()
                override fun onActivityStopped(activity: Activity) = AppForeground.stopped()
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
