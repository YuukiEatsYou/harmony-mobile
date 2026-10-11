package com.harmony.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * The in-call screen: who is here, and the controls. It reads [VoiceCall] state,
 * which the session publishes, and issues mute, deafen and leave through the same
 * object -- the call itself lives in [CallService], so closing this screen (or the
 * whole app) does not touch it.
 *
 * It is opened from the ongoing notification, and from the voice button over an
 * instance. Closing it only closes the screen.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var people: LinearLayout
    private lateinit var mute: TextView
    private lateinit var deafen: TextView

    private val listener: (VoiceCall.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VoiceCall.state.active) {
            finish()
            return
        }
        val root = buildUi()
        // Edge to edge is enforced on modern Android; keep the content inside the
        // system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left + dp(20),
                top = bars.top + dp(16),
                right = bars.right + dp(20),
                bottom = bars.bottom + dp(24),
            )
            insets
        }
        setContentView(root)
        VoiceCall.addListener(listener)
    }

    override fun onDestroy() {
        VoiceCall.removeListener(listener)
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        title = TextView(this).apply {
            setTextColor(TEXT)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }
        val close = pill("Close", SURFACE, DIM).apply {
            setOnClickListener { finish() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)
        root.addView(header)

        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 13f
            setPadding(0, dp(6), 0, dp(16))
        }
        root.addView(status)

        people = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(people) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        mute = pill("Mute", SURFACE, TEXT).apply {
            setOnClickListener { VoiceCall.toggleMute(); render(VoiceCall.state) }
        }
        deafen = pill("Deafen", SURFACE, TEXT).apply {
            setOnClickListener { VoiceCall.toggleDeafen(); render(VoiceCall.state) }
        }
        val leave = pill("Leave", DANGER, Color.WHITE).apply {
            setOnClickListener { NativeVoice.leave(this@CallActivity); finish() }
        }
        controls.addView(mute, rowParams())
        controls.addView(deafen, rowParams())
        controls.addView(leave, rowParams())
        root.addView(controls)

        render(VoiceCall.state)
        return root
    }

    private fun render(state: VoiceCall.State) {
        title.text = state.channelName ?: "Voice"
        status.text = when {
            state.connecting -> "Connecting…"
            state.connected -> "${state.members.size} in call"
            state.active -> "Connecting…"
            else -> "Not in a call"
        }
        mute.text = if (state.muted) "Unmute" else "Mute"
        mute.background = rounded(if (state.muted) ACCENT else SURFACE, dp(12))
        deafen.text = if (state.deafened) "Undeafen" else "Deafen"
        deafen.background = rounded(if (state.deafened) ACCENT else SURFACE, dp(12))

        people.removeAllViews()
        for (member in state.members) {
            val label = if (member.id == VoiceCall.selfId) "${member.name}  (you)" else member.name
            val row = TextView(this).apply {
                setTextColor(TEXT)
                textSize = 15f
                setPadding(0, dp(10), 0, dp(10))
                text = if (member.muted) "$label  ·  muted" else label
            }
            people.addView(row)
        }
    }

    private fun rowParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = dp(8)
    }

    private fun pill(label: String, background: Int, foreground: Int): TextView =
        TextView(this).apply {
            text = label
            setTextColor(foreground)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            this.background = rounded(background, dp(12))
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val BG = 0xFF1A1B1EL.toInt()
        const val SURFACE = 0xFF24262BL.toInt()
        const val TEXT = 0xFFE6E7EAL.toInt()
        const val DIM = 0xFF9A9DA6L.toInt()
        const val ACCENT = 0xFF5865F2L.toInt()
        const val DANGER = 0xFFD83C3EL.toInt()
    }
}
