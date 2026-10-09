package com.harmony.mobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Runs the screen capture behind a foreground service (Android requires the
 * mediaProjection service type), turning the mirrored display into JPEG frames
 * and pushing them to the instance webview through {@link ScreenFrameSink}.
 */
class ScreenShareService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameAt = 0L
    private var minIntervalMs = 33L
    private var released = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode =
            intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val resultData = intent?.let { readProjectionData(it) }
        val targetHeight = intent?.getIntExtra(EXTRA_HEIGHT, 720) ?: 720
        val frameRate = (intent?.getIntExtra(EXTRA_FRAME_RATE, 30) ?: 30).coerceIn(1, 60)
        minIntervalMs = 1000L / frameRate

        startAsForeground()

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            ScreenFrameSink.postJson("{\"type\":\"denied\"}")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // The user stopped via the system's screen-share control.
                    ScreenFrameSink.postJson("{\"type\":\"stopped\"}")
                    release()
                    stopSelf()
                }
            },
            Handler(mainLooper),
        )
        startCapture(targetHeight)
        return START_NOT_STICKY
    }

    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

    private fun startAsForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing your screen")
            .setContentText("Harmony is sharing this device's screen.")
            .setOngoing(true)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(0, "Stop", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(targetHeight: Int) {
        val (width, height) = screenSize()
        val density = resources.displayMetrics.densityDpi

        thread = HandlerThread("harmony-screen-capture").also { it.start() }
        handler = Handler(thread!!.looper)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { source ->
            source.setOnImageAvailableListener({ available ->
                val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFrameAt < minIntervalMs) return@setOnImageAvailableListener
                    lastFrameAt = now
                    imageToJpeg(image, targetHeight)?.let { ScreenFrameSink.postFrame(it) }
                } catch (_: Throwable) {
                    // A frame that will not convert is skipped; the next may work.
                } finally {
                    image.close()
                }
            }, handler)
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "harmony-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler,
        )

        ScreenFrameSink.postJson("{\"type\":\"started\",\"width\":$width,\"height\":$height}")
    }

    private fun screenSize(): Pair<Int, Int> {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            size.x to size.y
        }
    }

    private fun imageToJpeg(image: Image, targetHeight: Int): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)

        val cropped = if (paddedWidth != image.width) {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
        } else {
            padded
        }

        val scaled = if (cropped.height > targetHeight) {
            val scale = targetHeight.toFloat() / cropped.height
            val scaledWidth = (cropped.width * scale).roundToInt().coerceAtLeast(2)
            Bitmap.createScaledBitmap(cropped, scaledWidth, targetHeight, true).also { cropped.recycle() }
        } else {
            cropped
        }

        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
            scaled.recycle()
            out.toByteArray()
        }
    }

    private fun release() {
        if (released) return
        released = true
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    override fun onDestroy() {
        release()
        ScreenFrameSink.postJson("{\"type\":\"stopped\"}")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.harmony.mobile.STOP_SCREEN_SHARE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FRAME_RATE = "frameRate"
        private const val CHANNEL_ID = "harmony-screen-share"
        private const val NOTIFICATION_ID = 4211
    }
}
