package com.harmony.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Hosts one instance's own web client in a webview this app controls, so the
 * screen-share shim (assets/screen-share-shim.js) can be injected before the
 * client runs. One instance per activity; storage stays per-origin, so being
 * signed into one instance never leaks into another.
 */
class InstanceWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private var insetsController: WindowInsetsControllerCompat? = null
    private var instanceHost: String? = null
    private var pendingPermission: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var sender: ScreenShareSender? = null
    private var pendingHeight = 720
    private var pendingFrameRate = 30

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingPermission
            pendingPermission = null
            if (request == null) return@registerForActivityResult
            if (granted) request.grant(request.resources) else request.deny()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback
            fileCallback = null
            if (result.resultCode != Activity.RESULT_OK || callback == null) {
                callback?.onReceiveValue(null)
                return@registerForActivityResult
            }
            val data = result.data
            val uris: Array<Uri>? = when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
            callback.onReceiveValue(uris)
        }

    private val screenConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val service = Intent(this, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                    .putExtra(ScreenShareService.EXTRA_HEIGHT, pendingHeight)
                    .putExtra(ScreenShareService.EXTRA_FRAME_RATE, pendingFrameRate)
                ContextCompat.startForegroundService(this, service)
            } else {
                sender?.postJson("{\"type\":\"denied\"}")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        // The shell resumes this instance if the app is restarted or the task is
        // brought back, so it does not drop the person at the server selector.
        InstanceSession.remember(this, url)
        instanceHost = runCatching { Uri.parse(url).host }.getOrNull()

        webView = WebView(this)
        webView.setBackgroundColor(BACKGROUND_COLOR)

        container = FrameLayout(this)
        container.setBackgroundColor(BACKGROUND_COLOR)
        container.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // Edge to edge is enforced on modern Android, but Harmony's own client
        // (unlike this app's shell) does not use CSS safe-area insets, so its top
        // bar would sit under the status bar. Lay the webview out inside the
        // system bars and the keyboard instead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowInsetsControllerCompat(window, container)
        insetsController?.isAppearanceLightStatusBars = false
        insetsController?.isAppearanceLightNavigationBars = false
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = if (keyboardVisible) ime.bottom else bars.bottom,
            )
            insets
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val target = request.url
                val scheme = target.scheme
                if (scheme != "http" && scheme != "https") {
                    return openExternally(target)
                }
                // Keep the instance's own origin inside; send everything else out.
                if (target.host == instanceHost) return false
                return openExternally(target)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                // The client's voice chat asks for the microphone.
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                val granted = ContextCompat.checkSelfPermission(
                    this@InstanceWebViewActivity,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                if (wantsAudio && !granted) {
                    pendingPermission = request
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    request.grant(request.resources)
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try {
                    fileChooser.launch(fileChooserParams!!.createIntent())
                    true
                } catch (_: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        // Inject getDisplayMedia before any page script runs.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val shim = assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("*"))
        }

        // Shuttle screen-share control and frames between the shim and native.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val bridge = ScreenShareSender(
                webView,
                onStartRequested = { height, frameRate -> requestScreenShare(height, frameRate) },
                onStopRequested = { stopScreenShare() },
                onThemeRequested = { background -> applyThemeColor(background) },
            )
            sender = bridge
            ScreenFrameSink.sender = bridge
            val listener = WebViewCompat.WebMessageListener { _, message, _, _, replyProxy ->
                bridge.handleMessage(message, replyProxy)
            }
            WebViewCompat.addWebMessageListener(webView, SCREEN_CHANNEL, setOf("*"), listener)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        // Leaving the instance on purpose: the shell should show
                        // the selector rather than resume this instance.
                        InstanceSession.forget(this@InstanceWebViewActivity)
                        finish()
                    }
                }
            },
        )

        webView.loadUrl(url)
    }

    private fun requestScreenShare(height: Int, frameRate: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The capture runs behind a notification; ask, but do not block on it.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        pendingHeight = height
        pendingFrameRate = frameRate
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun stopScreenShare() {
        stopService(Intent(this, ScreenShareService::class.java))
    }

    private fun openExternally(target: Uri): Boolean =
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, target))
            true
        }.getOrDefault(false)

    /**
     * Colors the system bars (and the padded strips) to the instance's own
     * background, which the shim reports from <meta name="theme-color">.
     */
    private fun applyThemeColor(background: String) {
        val color = try {
            Color.parseColor(background)
        } catch (_: IllegalArgumentException) {
            return
        }
        container.setBackgroundColor(color)
        webView.setBackgroundColor(color)
        val lightBackground = ColorUtils.calculateLuminance(color) > 0.5
        insetsController?.isAppearanceLightStatusBars = lightBackground
        insetsController?.isAppearanceLightNavigationBars = lightBackground
    }

    /**
     * This webview is deliberately not paused while the activity is in the
     * background: pausing it halts the client's timers and gateway, and on return
     * the client has lost its in-memory call state even though the call itself
     * kept going. Capacitor does not pause the shell's webview either.
     */
    override fun onDestroy() {
        // Capture belongs to this webview; end it with the activity.
        stopScreenShare()
        ScreenFrameSink.sender = null
        sender = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val SCREEN_CHANNEL = "HarmonyScreen"
        private const val SHIM_ASSET = "screen-share-shim.js"
        private const val BACKGROUND_COLOR = 0xFF1A1B1EL.toInt()
    }
}
