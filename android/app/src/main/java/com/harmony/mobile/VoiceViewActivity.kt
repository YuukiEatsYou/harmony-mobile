package com.harmony.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * The voice view: the shared screen when someone is sharing, otherwise the roster
 * as a grid of avatars with a ring on whoever is speaking. Opened by tapping the
 * call bar's channel name.
 *
 * While a screen is showing, tapping it hides the rest of the UI so the picture is
 * not framed by chrome; tapping again brings it back.
 */
class VoiceViewActivity : AppCompatActivity() {

    private lateinit var header: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var title: TextView
    private lateinit var mute: TextView
    private lateinit var deafen: TextView
    private lateinit var speaker: TextView
    private lateinit var fullscreen: TextView
    private lateinit var stage: FrameLayout
    private lateinit var grid: GridLayout
    private lateinit var scroll: ScrollView
    private var renderer: SurfaceViewRenderer? = null
    private var attached: VideoTrack? = null
    /** Measures the incoming picture so the renderer's view can match its aspect. */
    private val aspectSink = VideoSink { frame ->
        val height = frame.rotatedHeight
        if (height > 0) {
            val aspect = frame.rotatedWidth.toFloat() / height
            runOnUiThread { fitVideo(aspect) }
        }
    }
    private var gridIds: List<String> = emptyList()
    private var chromeless = false

    private val listener: (VoiceCall.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VoiceCall.state.active) {
            finish()
            return
        }
        val root = buildUi()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
        setContentView(root)
        VoiceCall.addListener(listener)
    }

    override fun onDestroy() {
        VoiceCall.removeListener(listener)
        renderer?.let { view ->
            runCatching { attached?.removeSink(view) }
            runCatching { attached?.removeSink(aspectSink) }
            runCatching { view.release() }
        }
        attached = null
        renderer = null
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        title = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val close = TextView(this).apply {
            text = "Close"
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(SURFACE, dp(12))
            setOnClickListener { finish() }
        }
        fullscreen = TextView(this).apply {
            text = "Fullscreen"
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(SURFACE, dp(12))
            visibility = View.GONE
            setOnClickListener {
                chromeless = true
                applyChrome()
            }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(fullscreen)
        header.addView(
            close,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        root.addView(header)

        stage = FrameLayout(this).apply {
            // Tapping the picture hides the chrome, and brings it back.
            setOnClickListener {
                chromeless = !chromeless
                applyChrome()
            }
        }
        grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        scroll = ScrollView(this).apply { addView(grid) }
        stage.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(20))
        }
        mute = control("Mute") { VoiceCall.toggleMute() }
        deafen = control("Deafen") { VoiceCall.toggleDeafen() }
        speaker = control("Speaker") { VoiceCall.toggleSpeaker() }
        val leave = control("Leave", danger = true) { NativeVoice.leave(this); finish() }
        controls.addView(mute, rowParams())
        controls.addView(deafen, rowParams())
        controls.addView(speaker, rowParams())
        controls.addView(leave, rowParams())
        root.addView(controls)

        render(VoiceCall.state)
        return root
    }

    private fun applyChrome() {
        val show = !chromeless
        header.visibility = if (show) View.VISIBLE else View.GONE
        controls.visibility = if (show) View.VISIBLE else View.GONE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (show) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun render(state: VoiceCall.State) {
        title.text = state.channelName ?: "Voice"
        mute.text = if (state.muted) "Unmute" else "Mute"
        mute.background = rounded(if (state.muted) ACCENT else SURFACE, dp(12))
        deafen.text = if (state.deafened) "Undeafen" else "Deafen"
        deafen.background = rounded(if (state.deafened) ACCENT else SURFACE, dp(12))
        speaker.text = if (state.speaker) "Speaker" else "Earpiece"
        speaker.background = rounded(if (state.speaker) SURFACE else ACCENT, dp(12))

        val session = VoiceCall.session
        val sharer = state.members.firstOrNull { it.sharing }
        val track = if (state.localSharing) {
            session?.localScreenTrack()
        } else {
            sharer?.let { session?.remoteVideoTrack(it.id) }
        }

        if (track != null && showVideo(track)) {
            scroll.visibility = View.GONE
            fullscreen.visibility = View.VISIBLE
            // A picture is up: chrome starts visible, and a tap hides it.
            applyChrome()
        } else {
            fullscreen.visibility = View.GONE
            hideVideo()
            renderGrid(state)
        }
    }

    private fun showVideo(track: VideoTrack): Boolean {
        val egl = VoiceEngine.eglBaseContext() ?: return false
        var view = renderer
        if (view == null) {
            view = SurfaceViewRenderer(this).apply {
                init(egl, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                // The hardware scaler resizes the surface to the video's aspect at
                // full height, which is wider than the view when the video is wider
                // than the screen, and the sides get clipped. Leave it off so the
                // surface matches the view and SCALE_ASPECT_FIT letterboxes.
                setEnableHardwareScaler(false)
                setMirror(false)
                setOnClickListener { stage.performClick() }
            }
            stage.addView(view, FrameLayout.LayoutParams(-1, -1))
            renderer = view
        }
        if (attached !== track) {
            runCatching { attached?.removeSink(view) }
            runCatching { attached?.removeSink(aspectSink) }
            track.addSink(view)
            track.addSink(aspectSink)
            attached = track
        }
        // Fit the whole picture inside the view; never crop it.
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        view.visibility = View.VISIBLE
        return true
    }

    /**
     * Sizes the renderer to the video's own aspect ratio, centred in the stage, so
     * the view is already the letterbox and no internal scaling can crop it.
     */
    private fun fitVideo(aspect: Float) {
        val view = renderer ?: return
        val stageWidth = stage.width
        val stageHeight = stage.height
        if (stageWidth <= 0 || stageHeight <= 0 || aspect <= 0f) return
        var width = stageWidth
        var height = (stageWidth / aspect).toInt()
        if (height > stageHeight) {
            height = stageHeight
            width = (stageHeight * aspect).toInt()
        }
        val params = view.layoutParams
        if (params != null && params.width == width && params.height == height) return
        view.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
    }

    private fun hideVideo() {
        renderer?.let { view ->
            runCatching { attached?.removeSink(view) }
            attached = null
            view.visibility = View.GONE
        }
        chromeless = false
        applyChrome()
    }

    private fun renderGrid(state: VoiceCall.State) {
        scroll.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        val ids = state.members.map { it.id }
        if (ids != gridIds) {
            gridIds = ids
            grid.removeAllViews()
            val cookie = VoiceCall.session?.sessionCookie
            for (member in state.members) {
                val avatar = AvatarView(this)
                val fallback = (member.name.firstOrNull() ?: '?').uppercaseChar().toString()
                avatar.bind(null, fallback)
                member.avatarUrl?.let { url ->
                    if (cookie != null) {
                        AvatarLoader.load(url, cookie) { image -> avatar.bind(image, fallback) }
                    }
                }
                val name = TextView(this).apply {
                    setTextColor(if (member.id == VoiceCall.selfId) TEXT else DIM)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    text = if (member.id == VoiceCall.selfId) "${member.name} (you)" else member.name
                }
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(avatar, LinearLayout.LayoutParams(dp(AVATAR), dp(AVATAR)))
                    addView(name, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                grid.addView(cell, cellParams())
            }
        }
        // Update the rings without rebuilding.
        for (index in state.members.indices) {
            val member = state.members[index]
            val cell = grid.getChildAt(index) as? LinearLayout ?: continue
            val avatar = cell.getChildAt(0) as? AvatarView ?: continue
            avatar.ringing = member.speaking
        }
    }

    private fun cellParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = GridLayout.LayoutParams.WRAP_CONTENT
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(8), dp(12), dp(8), dp(12))
    }

    private fun control(label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(if (danger) Color.WHITE else TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(if (danger) DANGER else SURFACE, dp(12))
            setOnClickListener { onClick() }
        }

    private fun rowParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BG = 0xFF1A1B1EL.toInt()
        const val SURFACE = 0xFF24262BL.toInt()
        const val TEXT = 0xFFE6E7EAL.toInt()
        const val DIM = 0xFF9A9DA6L.toInt()
        const val ACCENT = 0xFF5865F2L.toInt()
        const val DANGER = 0xFFD83C3EL.toInt()
        const val AVATAR = 72
    }
}
