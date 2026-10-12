package com.harmony.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

/**
 * Owns a live call, as a foreground service.
 *
 * This is not only so the call survives the app being backgrounded. On Android an
 * app that is not in the foreground -- and has no foreground service -- has its
 * microphone capture **silenced** by the platform (visible as `AudioRecord ... [mute]`
 * in logcat and `micEnergy=0` in the RTP stats). A foreground service of type
 * `microphone` is what keeps capture live, so a native call runs here.
 *
 * Two actions: [ACTION_JOIN] starts a native call (extras carry the instance and
 * channel), and [ACTION_STOP] ends it. A plain start with no action is the older
 * keep-alive for the webview's own call, which still just holds the process.
 */
class CallService : Service() {

    private var voice: NativeVoiceSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CallDiagnostics.record(this, "call service asked to stop")
                stopNative()
                stopForeground(STOP_FOREGROUND_REMOVE)
                CallDiagnostics.fgsRunning = false
                CallDiagnostics.foregroundType = "none"
                stopSelf()
            }
            ACTION_JOIN -> {
                CallDiagnostics.record(this, "call service start requested")
                if (startAsForeground(mediaProjection = false)) startNative(intent)
            }
            ACTION_SHARE -> {
                // Android 14+ refuses to mint a MediaProjection unless a foreground
                // service of type mediaProjection is already running, so the type is
                // added before the capture starts and dropped when it ends.
                val sharing = intent.getBooleanExtra(EXTRA_SHARING, false)
                startAsForeground(mediaProjection = sharing)
            }
            ACTION_SHARE_START -> {
                // The consent has just been given, which is what grants the
                // project_media appop the mediaProjection foreground type needs, so
                // take the type now and start the capture behind it. Doing this
                // before the consent is refused.
                startAsForeground(mediaProjection = true)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_SCREEN_DATA, Intent::class.java)
                val session = voice
                if (data != null && session != null) {
                    session.startScreenShare(
                        this,
                        data,
                        intent.getIntExtra(EXTRA_HEIGHT, 720),
                        intent.getIntExtra(EXTRA_FPS, 30),
                    )
                } else {
                    CallDiagnostics.record(this, "call service: share start with no projection or call")
                }
            }
            else -> {
                CallDiagnostics.record(this, "call service start requested")
                startAsForeground(mediaProjection = false)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopNative()
        CallDiagnostics.fgsRunning = false
        CallDiagnostics.foregroundType = "none"
        CallDiagnostics.record(this, "call foreground service destroyed")
        super.onDestroy()
    }

    private fun startNative(intent: Intent) {
        val origin = intent.getStringExtra(EXTRA_ORIGIN)
        val cookie = intent.getStringExtra(EXTRA_COOKIE)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
        if (origin == null || cookie == null || channelId == null) {
            CallDiagnostics.record(this, "call service: join without an instance")
            return
        }
        stopNative()
        val session = NativeVoiceSession(this, channelId, origin, cookie)
        voice = session
        VoiceCall.session = session
        VoiceCall.update {
            it.copy(
                active = true,
                connecting = true,
                connected = false,
                channelId = channelId,
                channelName = channelName,
                muted = false,
                deafened = false,
                members = emptyList(),
            )
        }
        session.start()
    }

    private fun stopNative() {
        voice?.stop()
        voice = null
        VoiceCall.reset()
    }

    private fun startAsForeground(mediaProjection: Boolean): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(), foregroundType(mediaProjection))
            CallDiagnostics.fgsRunning = true
            CallDiagnostics.foregroundType =
                if (mediaProjection) "microphone+mediaProjection" else "microphone"
            CallDiagnostics.lastStartResult = "ok"
            CallDiagnostics.lastError = null
            CallDiagnostics.record(this, "call foreground service up")
            true
        } catch (e: Exception) {
            // Refusing to go foreground (a bad type, or a start the OS disallows
            // because we were backgrounded) would crash the service, so fail
            // quietly for the call's sake -- but record it.
            CallDiagnostics.fgsRunning = false
            CallDiagnostics.foregroundType = "none"
            CallDiagnostics.lastStartResult = "failed"
            CallDiagnostics.lastError = "${e.javaClass.simpleName}: ${e.message}"
            CallDiagnostics.record(
                this,
                "call foreground service refused: ${e.javaClass.simpleName}: ${e.message}",
            )
            Log.w(TAG, "could not start the call foreground service", e)
            stopSelf()
            false
        }
    }

    private fun foregroundType(mediaProjection: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (mediaProjection) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return type
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = Intent(this, VoiceViewActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val leave = PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("In a call")
            .setContentText("Harmony is keeping your call connected.")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Leave", leave)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        return builder.build()
    }

    companion object {
        const val ACTION_STOP = "com.harmony.mobile.STOP_CALL"
        const val ACTION_JOIN = "com.harmony.mobile.JOIN_CALL"
        const val ACTION_SHARE = "com.harmony.mobile.SHARE_STATE"
        const val ACTION_SHARE_START = "com.harmony.mobile.SHARE_START"
        const val EXTRA_SHARING = "sharing"
        const val EXTRA_SCREEN_DATA = "screenData"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_CHANNEL_NAME = "channelName"
        private const val TAG = "HarmonyVoice"
        private const val CHANNEL_ID = "harmony-call"
        private const val NOTIFICATION_ID = 4212

        fun joinIntent(
            context: Context,
            origin: String,
            cookie: String,
            channelId: String,
            channelName: String?,
        ): Intent = Intent(context, CallService::class.java)
            .setAction(ACTION_JOIN)
            .putExtra(EXTRA_ORIGIN, origin)
            .putExtra(EXTRA_COOKIE, cookie)
            .putExtra(EXTRA_CHANNEL, channelId)
            .putExtra(EXTRA_CHANNEL_NAME, channelName)

        fun shareStartIntent(context: Context, data: Intent, height: Int, fps: Int): Intent =
            Intent(context, CallService::class.java)
                .setAction(ACTION_SHARE_START)
                .putExtra(EXTRA_SCREEN_DATA, data)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_FPS, fps)
    }
}
