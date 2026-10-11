package com.harmony.mobile

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The call control bar, shown along the top of the instance while a call is live:
 * the channel and its state on the left, a screen-sharing indicator, and mute,
 * deafen, share and leave on the right.
 *
 * Tapping the left half opens the voice view ([VoiceViewActivity]) -- the shared
 * screen, or the roster. It reads [VoiceCall], so the buttons reflect the real
 * state, and calls back to the host activity for the two things that need it: the
 * screen-capture consent and opening the view.
 */
class VoiceCallBar(context: Context) : LinearLayout(context) {

    /** Opens the voice view; set by the host. */
    var onOpen: (() -> Unit)? = null

    /** Starts or stops sharing; set by the host (it holds the capture consent). */
    var onToggleShare: (() -> Unit)? = null

    private val title: TextView
    private val subtitle: TextView
    private val mute: TextView
    private val deafen: TextView
    private val speaker: TextView
    private val share: TextView
    private val screenChip: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(SURFACE)
        setPadding(dp(16), dp(10), dp(16), dp(10))

        val info = LinearLayout(context).apply { orientation = VERTICAL }
        title = TextView(context).apply {
            setTextColor(TEXT)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitle = TextView(context).apply {
            setTextColor(DIM)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(title)
        info.addView(subtitle)
        info.setOnClickListener { onOpen?.invoke() }
        addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        screenChip = TextView(context).apply {
            text = "Screen"
            setTextColor(ACCENT)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(SURFACE_ALT, dp(10))
            visibility = View.GONE
            // Tapping the indicator goes straight to the shared screen.
            setOnClickListener { onOpen?.invoke() }
        }
        addView(screenChip)

        mute = control("Mute") { VoiceCall.toggleMute() }
        deafen = control("Deafen") { VoiceCall.toggleDeafen() }
        speaker = control("Speaker") { VoiceCall.toggleSpeaker() }
        share = control("Share") { onToggleShare?.invoke() }
        val leave = control("Leave", danger = true) { NativeVoice.leave(context) }
        addView(mute)
        addView(deafen)
        addView(speaker)
        addView(share)
        addView(leave)
    }

    fun render(state: VoiceCall.State) {
        title.text = state.channelName ?: "Voice"
        subtitle.text = when {
            state.connecting -> "Connecting…"
            state.connected -> "Voice connected"
            else -> "Voice"
        }
        mute.text = if (state.muted) "Unmute" else "Mute"
        mute.background = rounded(if (state.muted) ACCENT else SURFACE_ALT, dp(10))
        deafen.text = if (state.deafened) "Undeafen" else "Deafen"
        deafen.background = rounded(if (state.deafened) ACCENT else SURFACE_ALT, dp(10))
        speaker.text = if (state.speaker) "Speaker" else "Earpiece"
        speaker.background = rounded(if (state.speaker) SURFACE_ALT else ACCENT, dp(10))
        share.text = if (state.localSharing) "Stop" else "Share"
        share.background = rounded(if (state.localSharing) ACCENT else SURFACE_ALT, dp(10))
        val anyoneSharing = state.localSharing || state.members.any { it.sharing }
        screenChip.visibility = if (anyoneSharing) View.VISIBLE else View.GONE
        if (anyoneSharing) screenChip.text = if (state.localSharing) "You" else "Screen"
    }

    private fun control(label: String, danger: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(if (danger) Color.WHITE else TEXT)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded(if (danger) DANGER else SURFACE_ALT, dp(10))
            setOnClickListener { onClick() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) }
            layoutParams = params
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SURFACE = 0xFF24262BL.toInt()
        const val SURFACE_ALT = 0xFF2D3037L.toInt()
        const val TEXT = 0xFFE6E7EAL.toInt()
        const val DIM = 0xFF9A9DA6L.toInt()
        const val ACCENT = 0xFF5865F2L.toInt()
        const val DANGER = 0xFFD83C3EL.toInt()
    }
}
