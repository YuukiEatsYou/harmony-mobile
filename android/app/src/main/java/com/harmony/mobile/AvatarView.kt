package com.harmony.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * A circular member avatar, with a ring drawn around it while they speak, and a
 * letter fallback when they have no picture.
 */
class AvatarView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var letter: String = "?"

    var ringing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun bind(image: Bitmap?, fallback: String) {
        bitmap = image
        letter = fallback
        invalidate()
    }

    private val ringWidth = (3 * resources.displayMetrics.density)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = ACCENT
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE_ALT }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - ringWidth
        canvas.drawCircle(cx, cy, radius, backgroundPaint)

        val image = bitmap
        if (image != null) {
            clip.reset()
            clip.addCircle(cx, cy, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(
                image,
                Rect(0, 0, image.width, image.height),
                RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                bitmapPaint,
            )
            canvas.restoreToCount(save)
        } else {
            letterPaint.textSize = radius
            val baseline = cy - (letterPaint.descent() + letterPaint.ascent()) / 2f
            canvas.drawText(letter, cx, baseline, letterPaint)
        }

        if (ringing) canvas.drawCircle(cx, cy, radius + ringWidth / 2f, ringPaint)
    }

    private companion object {
        const val SURFACE_ALT = 0xFF2D3037L.toInt()
        const val TEXT = 0xFFE6E7EAL.toInt()
        val ACCENT = 0xFF5865F2L.toInt()
        @Suppress("unused")
        const val WHITE = Color.WHITE
    }
}
