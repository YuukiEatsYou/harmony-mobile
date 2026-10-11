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
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Hosts one instance's own web client in a webview this app controls, so the
 * screen-share shim (assets/screen-share-shim.js) can be injected before the
 * client runs. One instance per activity; storage stays per-origin, so being
 * signed into one instance never leaks into another.
 */
class InstanceWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var root: LinearLayout
    private var callBar: VoiceCallBar? = null
    private var insetsController: WindowInsetsControllerCompat? = null
    private var instanceHost: String? = null
    private var pendingPermission: PermissionRequest? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var sender: ScreenShareSender? = null
    private var callWatcher: CallWatcher? = null
    private var instanceUrl: String? = null
    private var pendingJoin: Pair<String, String?>? = null
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

    /** The system screen-capture consent for a *native* share (see [toggleScreenShare]). */
    private val nativeScreenConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            CallDiagnostics.record(this, "native voice: share consent result=${result.resultCode}")
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                // The service takes the mediaProjection foreground type and starts
                // the capture, in that order -- the type cannot be held before the
                // consent grants the project_media appop (see CallService).
                startService(CallService.shareStartIntent(this, data, SCREEN_HEIGHT, SCREEN_FPS))
            }
        }

    /** The microphone permission, asked when a call starts (see [requestMicThenJoin]). */
    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPendingJoin() else pendingJoin = null
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
        instanceUrl = url
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
        // The client fills the space above the call bar; the bar is added below it
        // (and only while a call is live), so it never covers the composer.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND_COLOR)
        }
        root.addView(
            container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(
            root,
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
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
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
                // A native, foreground, app-specific call signal (see CallWatcher).
                if (wantsAudio) callWatcher?.noteMicRequested()
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
                onCallChanged = { active -> callWatcher?.setShimActive(active) },
            )
            sender = bridge
            ScreenFrameSink.sender = bridge
            val listener = WebViewCompat.WebMessageListener { _, message, _, _, replyProxy ->
                bridge.handleMessage(message, replyProxy)
            }
            WebViewCompat.addWebMessageListener(webView, SCREEN_CHANNEL, setOf("*"), listener)
        }

        // The client cannot join voice itself -- its voice is switched off by the
        // shim -- so its channel list hands a tap on a voice channel to us here.
        // See UPSTREAM_CHANGES section 7.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val voiceListener = WebViewCompat.WebMessageListener { _, message, _, _, _ ->
                handleVoiceHostMessage(message)
            }
            WebViewCompat.addWebMessageListener(webView, VOICE_CHANNEL, setOf("*"), voiceListener)
        }

        // Call state is watched at the microphone (CallWatcher), not only from the
        // shim's report, so the call foreground service is started while the app
        // is still foreground. The shim report stays as a second signal.
        val watcher = CallWatcher(this) { active -> setInCall(active) }
        callWatcher = watcher
        watcher.start()
        CallDiagnostics.record(this, "instance opened at $instanceHost")

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

    /**
     * The client's channel list hands a voice-channel tap here, because the client
     * cannot join voice itself (its voice is switched off by the shim). See
     * UPSTREAM_CHANGES section 7.
     */
    private fun handleVoiceHostMessage(message: WebMessageCompat) {
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val raw = message.data ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "join" -> requestMicThenJoin(
                json.optString("channelId"),
                json.optString("channelName").takeIf { it.isNotBlank() },
            )
            "leave" -> NativeVoice.leave(this)
        }
    }

    /**
     * A native call needs the microphone permission -- starting the microphone
     * foreground service without it is refused. The webview no longer asks for the
     * microphone (its voice is switched off), so ask here, and only start the call
     * once it is held.
     */
    private fun requestMicThenJoin(channelId: String, channelName: String?) {
        if (channelId.isBlank()) return
        pendingJoin = channelId to channelName
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPendingJoin()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startPendingJoin() {
        val join = pendingJoin ?: return
        pendingJoin = null
        val url = instanceUrl ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // The notification is what makes the foreground service visible; ask,
            // but do not block the call on it.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        NativeVoice.startCall(this, url, join.first, join.second)
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

    /**
     * Runs a foreground service for the duration of a call, so the webview keeps
     * the microphone and the process is not reclaimed while the app is away. The
     * call itself is the web client; this only keeps it alive.
     */
    private fun setInCall(active: Boolean) {
        val intent = Intent(this, CallService::class.java)
        try {
            if (active) {
                // Without the notification permission the service still runs, but
                // its notification is suppressed, which makes it look like nothing
                // happened. Ask once, the way the screen-share flow does.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ContextCompat.startForegroundService(this, intent)
                Log.i(TAG, "call live; call foreground service requested")
                // Ask to be exempt from battery optimization, which is what keeps
                // this call (and the instance activity) alive on aggressive ROMs.
                BatteryOptimization.requestForCall(this)
            } else {
                stopService(intent)
                Log.i(TAG, "call ended; call foreground service stopped")
            }
        } catch (e: Exception) {
            // Losing the mic and being killable in the background is much worse
            // than a missing notification, so never let this take the call down.
            // Starting a foreground service from the background is disallowed on
            // Android 12+, and that is the failure we are hunting -- so record it
            // where the person can share it.
            CallDiagnostics.lastError =
                "startForegroundService failed: ${e.javaClass.simpleName}: ${e.message}"
            CallDiagnostics.record(
                this,
                "could not update the call foreground service: ${e.javaClass.simpleName}: ${e.message}",
            )
            Log.w(TAG, "could not update the call foreground service", e)
        }
    }

    override fun onStart() {
        super.onStart()
        // Show the call bar while a call is live, and keep it in step.
        VoiceCall.addListener(callBarListener)
    }

    override fun onResume() {
        super.onResume()
        // Returning mid-call: the recording callback may not fire again, so
        // re-read the microphone state to keep the service in sync.
        callWatcher?.seed()
        CallDiagnostics.record(this, "instance resumed")
    }

    override fun onStop() {
        super.onStop()
        VoiceCall.removeListener(callBarListener)
        CallDiagnostics.record(this, "instance stopped (backgrounded)")
    }

    private val callBarListener: (VoiceCall.State) -> Unit = { renderCallBar(it) }

    private fun renderCallBar(state: VoiceCall.State) {
        if (state.active) {
            val bar = callBar ?: VoiceCallBar(this).also {
                it.onOpen = { startActivity(Intent(this, VoiceViewActivity::class.java)) }
                it.onToggleShare = { toggleScreenShare() }
                callBar = it
                // At the top, above the client: the bottom is where the keyboard
                // and the gesture bar are, where an accidental tap is easy.
                root.addView(it, 0)
            }
            bar.render(state)
        } else {
            callBar?.let { root.removeView(it) }
            callBar = null
        }
    }

    /** Starts or stops sharing this screen; the consent comes from the system. */
    private fun toggleScreenShare() {
        val session = VoiceCall.session
        CallDiagnostics.record(
            this,
            "native voice: share tapped (session=${session != null}, sharing=${session?.isSharing})",
        )
        if (session == null) return
        if (session.isSharing) {
            session.stopScreenShare()
            setSharingForeground(false)
            return
        }
        // Consent first: the mediaProjection foreground type cannot be taken until
        // the user has consented (it grants the project_media appop).
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        nativeScreenConsent.launch(manager.createScreenCaptureIntent())
    }

    /** Adds or drops the mediaProjection foreground type on the running service. */
    private fun setSharingForeground(sharing: Boolean) {
        startService(
            Intent(this, CallService::class.java)
                .setAction(CallService.ACTION_SHARE)
                .putExtra(CallService.EXTRA_SHARING, sharing),
        )
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
        // Capture belongs to this webview; end it with the activity. The call does
        // NOT: it lives in CallService, so closing or recreating the instance must
        // leave it running -- this used to stopService here and drop the call.
        callWatcher?.stop()
        callWatcher = null
        stopScreenShare()
        CallDiagnostics.record(this, "instance closed")
        ScreenFrameSink.sender = null
        sender = null
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val SCREEN_CHANNEL = "HarmonyScreen"
        const val VOICE_CHANNEL = "HarmonyVoiceHost"
        private const val TAG = "HarmonyVoice"
        private const val SHIM_ASSET = "screen-share-shim.js"
        private const val BACKGROUND_COLOR = 0xFF1A1B1EL.toInt()
        private const val SCREEN_HEIGHT = 720
        private const val SCREEN_FPS = 30
    }
}
