package io.github.sp9r.meltype.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Monochrome line smiley for the emoji key. Drawn in the key's text colour so it matches the other
 * keys, instead of the platform colour emoji that "☺" turns into on Android.
 * Keys keep "☺" as their label; the views draw this icon wherever that label appears.
 */
object SmileyIcon {
    const val GLYPH = "☺"

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arc = RectF()

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val sw = (radius * 0.15f).coerceAtLeast(1f)
        stroke.color = color
        stroke.strokeWidth = sw
        fill.color = color
        canvas.drawCircle(cx, cy, radius - sw / 2f, stroke)
        val eyeR = (radius * 0.1f).coerceAtLeast(0.8f)
        canvas.drawCircle(cx - radius * 0.33f, cy - radius * 0.2f, eyeR, fill)
        canvas.drawCircle(cx + radius * 0.33f, cy - radius * 0.2f, eyeR, fill)
        val m = radius * 0.48f
        arc.set(cx - m, cy - m * 0.9f, cx + m, cy + m * 0.95f)
        canvas.drawArc(arc, 28f, 124f, false, stroke)
    }
}

/** [SmileyIcon] as a Drawable for TextView-based buttons (the candidate rail). */
class SmileyDrawable(private val sizePx: Int) : Drawable() {
    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        SmileyIcon.draw(canvas, b.exactCenterX(), b.exactCenterY(), minOf(b.width(), b.height()) / 2f, color)
    }

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
