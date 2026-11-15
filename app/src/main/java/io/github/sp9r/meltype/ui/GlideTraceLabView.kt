package io.github.sp9r.meltype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import io.github.sp9r.meltype.glide.GlideCandidate
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import io.github.sp9r.meltype.glide.TouchPoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Interactive architectural QWERTY trace canvas for inspecting Meltype's glide geometry
 * and live candidate ranking directly inside the companion studio app.
 */
class GlideTraceLabView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var darkTheme: Boolean = false
        set(value) {
            field = value
            applyPalette()
            invalidate()
        }

    var onTraceSampled: ((normPoints: List<TouchPoint>, isFinal: Boolean) -> Unit)? = null

    private val padRect = RectF()
    private val keyRect = RectF()
    private val path = Path()

    private val rawPoints = ArrayList<TouchPoint>()
    private var highlightedChars: Set<Char> = emptySet()
    private var topCandidates: List<GlideCandidate> = emptyList()
    private var traceLabel: String = "キー上をなぞって軌道デコードを試せます"
    private var lastLiveSampleMs = 0L

    private var cBg = 0
    private var cKeyBg = 0
    private var cKeyHitBg = 0
    private var cKeyStroke = 0
    private var cText = 0
    private var cMuted = 0
    private var cAccent = 0
    private var cFooterBg = 0
    private var cBarTrack = 0

    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKey = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKeyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val paintTraceGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintTrace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintMono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    init {
        isClickable = true
        applyPalette()
    }

    private fun applyPalette() {
        if (darkTheme) {
            cBg = Color.parseColor("#16191E")
            cKeyBg = Color.parseColor("#21252C")
            cKeyHitBg = Color.parseColor("#283446")
            cKeyStroke = Color.parseColor("#2E343E")
            cText = Color.parseColor("#ECEEF0")
            cMuted = Color.parseColor("#8E96A0")
            cAccent = Color.parseColor("#5C8AC4")
            cFooterBg = Color.parseColor("#1B1F26")
            cBarTrack = Color.parseColor("#2A2F38")
        } else {
            cBg = Color.parseColor("#F8F9FA")
            cKeyBg = Color.parseColor("#FFFFFF")
            cKeyHitBg = Color.parseColor("#E6EEF7")
            cKeyStroke = Color.parseColor("#DFE2E6")
            cText = Color.parseColor("#202328")
            cMuted = Color.parseColor("#737A84")
            cAccent = Color.parseColor("#4A6F9E")
            cFooterBg = Color.parseColor("#F0F2F5")
            cBarTrack = Color.parseColor("#E1E5EA")
        }
    }

    fun setDecodedState(label: String, normPoints: List<TouchPoint>, results: List<GlideCandidate>, highlightWord: String? = null) {
        traceLabel = label
        topCandidates = results.take(3)
        if (width > 0 && height > 0) {
            updatePadBounds()
            rawPoints.clear()
            val w = padRect.width().coerceAtLeast(1f)
            val h = padRect.height().coerceAtLeast(1f)
            for (pt in normPoints) {
                val px = padRect.left + (pt.x / KeyLayoutGeometry.ASPECT_RATIO) * w
                val py = padRect.top + pt.y * h
                rawPoints.add(TouchPoint(px, py, pt.tMs))
            }
        }
        val best = highlightWord ?: results.firstOrNull()?.word.orEmpty()
        highlightedChars = best.lowercase().toSet()
        invalidate()
    }

    fun updateResultsOnly(label: String, results: List<GlideCandidate>) {
        traceLabel = label
        topCandidates = results.take(3)
        val best = results.firstOrNull()?.word.orEmpty()
        if (best.isNotEmpty()) {
            highlightedChars = best.lowercase().toSet()
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = dp(178)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updatePadBounds()
    }

    private fun updatePadBounds() {
        val padH = dp(10).toFloat()
        val topPad = dp(10).toFloat()
        val footerH = dp(42).toFloat()
        padRect.set(padH, topPad, width - padH, height - footerH - dp(8).toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updatePadBounds()
        val now = System.currentTimeMillis()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                rawPoints.clear()
                highlightedChars = emptySet()
                topCandidates = emptyList()
                val clampedX = event.x.coerceIn(padRect.left, padRect.right)
                val clampedY = event.y.coerceIn(padRect.top, padRect.bottom)
                rawPoints.add(TouchPoint(clampedX, clampedY, now))
                traceLabel = "軌道を記録中..."
                lastLiveSampleMs = now
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = rawPoints.lastOrNull()
                val cx = event.x.coerceIn(padRect.left, padRect.right)
                val cy = event.y.coerceIn(padRect.top, padRect.bottom)
                if (last == null || hypot((cx - last.x).toDouble(), (cy - last.y).toDouble()) >= dp(3)) {
                    rawPoints.add(TouchPoint(cx, cy, now))
                    if (rawPoints.size >= 4 && now - lastLiveSampleMs >= 45L) {
                        lastLiveSampleMs = now
                        onTraceSampled?.invoke(KeyLayoutGeometry.normalizeFromView(rawPoints, padRect), false)
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val cx = event.x.coerceIn(padRect.left, padRect.right)
                val cy = event.y.coerceIn(padRect.top, padRect.bottom)
                rawPoints.add(TouchPoint(cx, cy, now))
                if (rawPoints.size >= 2) {
                    onTraceSampled?.invoke(KeyLayoutGeometry.normalizeFromView(rawPoints, padRect), true)
                } else {
                    traceLabel = "2文字以上なぞると順位と一致度を表示します"
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePadBounds()
        val d = resources.displayMetrics.density

        // Canvas rounded frame + clipped footer
        val rOuter = 6f * d
        paintBg.color = cBg
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), rOuter, rOuter, paintBg)

        val footerTop = padRect.bottom + dp(8).toFloat()
        val save = canvas.save()
        path.reset()
        path.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), rOuter, rOuter, Path.Direction.CW)
        canvas.clipPath(path)
        paintBg.color = cFooterBg
        canvas.drawRect(0f, footerTop, width.toFloat(), height.toFloat(), paintBg)
        canvas.restoreToCount(save)

        paintKeyStroke.color = cKeyStroke
        paintKeyStroke.strokeWidth = 1f * d
        canvas.drawLine(0f, footerTop, width.toFloat(), footerTop, paintKeyStroke)
        canvas.drawRoundRect(0.5f * d, 0.5f * d, width - 0.5f * d, height - 0.5f * d, rOuter, rOuter, paintKeyStroke)

        // Draw 3-row QWERTY wireframe keys
        val unitW = padRect.width() / 10f
        val rowH = padRect.height() / 3f
        val gapX = 2.5f * d
        val gapY = 3.0f * d
        val keyR = 4.0f * d

        paintLabel.textSize = 11.5f * d
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val rowOffsets = floatArrayOf(0f, 0.5f, 1.5f)
        for (r in 0..2) {
            val rowStr = rows[r]
            val cy = padRect.top + r * rowH
            for (i in rowStr.indices) {
                val ch = rowStr[i]
                val left = padRect.left + (rowOffsets[r] + i) * unitW + gapX
                val right = left + unitW - 2f * gapX
                val top = cy + gapY
                val bottom = cy + rowH - gapY
                keyRect.set(left, top, right, bottom)
                val hit = ch in highlightedChars
                paintKey.color = if (hit) cKeyHitBg else cKeyBg
                canvas.drawRoundRect(keyRect, keyR, keyR, paintKey)
                paintKeyStroke.color = if (hit) cAccent else cKeyStroke
                paintKeyStroke.strokeWidth = if (hit) 1.4f * d else 0.9f * d
                canvas.drawRoundRect(keyRect, keyR, keyR, paintKeyStroke)

                paintLabel.color = if (hit) cAccent else cMuted
                val fm = paintLabel.fontMetrics
                val ty = keyRect.centerY() - (fm.ascent + fm.descent) * 0.5f
                canvas.drawText(ch.toString(), keyRect.centerX(), ty, paintLabel)
            }
        }
        // Subtle Shift & Backspace flank outlines on row 2 for visual balance
        val r2Top = padRect.top + 2 * rowH + gapY
        val r2Bottom = padRect.top + 3 * rowH - gapY
        paintKey.color = cFooterBg
        paintKeyStroke.color = cKeyStroke
        paintKeyStroke.strokeWidth = 0.8f * d
        keyRect.set(padRect.left + gapX, r2Top, padRect.left + 1.5f * unitW - gapX, r2Bottom)
        canvas.drawRoundRect(keyRect, keyR, keyR, paintKey)
        canvas.drawRoundRect(keyRect, keyR, keyR, paintKeyStroke)
        keyRect.set(padRect.left + 8.5f * unitW + gapX, r2Top, padRect.right - gapX, r2Bottom)
        canvas.drawRoundRect(keyRect, keyR, keyR, paintKey)
        canvas.drawRoundRect(keyRect, keyR, keyR, paintKeyStroke)

        // Draw glide trail
        if (rawPoints.size >= 2) {
            path.reset()
            path.moveTo(rawPoints[0].x, rawPoints[0].y)
            for (i in 1 until rawPoints.size) {
                val p = rawPoints[i]
                val prev = rawPoints[i - 1]
                val mx = (prev.x + p.x) * 0.5f
                val my = (prev.y + p.y) * 0.5f
                path.quadTo(prev.x, prev.y, mx, my)
            }
            path.lineTo(rawPoints.last().x, rawPoints.last().y)

            paintTraceGlow.color = Color.argb(46, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent))
            paintTraceGlow.strokeWidth = 8f * d
            canvas.drawPath(path, paintTraceGlow)

            paintTrace.color = cAccent
            paintTrace.strokeWidth = 2.6f * d
            canvas.drawPath(path, paintTrace)

            paintDot.color = cAccent
            canvas.drawCircle(rawPoints.first().x, rawPoints.first().y, 3.2f * d, paintDot)
            canvas.drawCircle(rawPoints.last().x, rawPoints.last().y, 4.2f * d, paintDot)
        }

        // Draw compact candidate telemetry in footer
        val padL = dp(10).toFloat()
        val fCenterY = footerTop + (height - footerTop) * 0.5f
        if (topCandidates.isEmpty()) {
            paintText.color = cMuted
            paintText.textSize = 11.5f * d
            paintText.textAlign = Paint.Align.LEFT
            val fm = paintText.fontMetrics
            canvas.drawText(traceLabel, padL, fCenterY - (fm.ascent + fm.descent) * 0.5f, paintText)
        } else {
            val bestScore = topCandidates.first().scoreCost
            val slotW = (width - padL * 2f) / 3f
            for (idx in topCandidates.indices) {
                val cand = topCandidates[idx]
                val sx = padL + idx * slotW
                val relConf = (1f - ((cand.scoreCost - bestScore) / 2.2f)).coerceIn(0.22f, 1.0f)
                paintMono.textSize = 10f * d
                paintMono.color = if (idx == 0) cAccent else cMuted
                paintMono.textAlign = Paint.Align.LEFT
                canvas.drawText("#${idx + 1}", sx, footerTop + 14f * d, paintMono)

                paintText.textSize = 12f * d
                paintText.typeface = if (idx == 0) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                paintText.color = if (idx == 0) cText else cMuted
                paintText.textAlign = Paint.Align.LEFT
                val wordShown = if (cand.word.length > 10) cand.word.take(9) + "…" else cand.word
                canvas.drawText(wordShown, sx + 20f * d, footerTop + 14.5f * d, paintText)

                // Hairline confidence bar
                val barTop = footerTop + 23f * d
                val barRight = sx + slotW - 10f * d
                val barW = max(24f * d, barRight - sx)
                paintBg.color = cBarTrack
                canvas.drawRoundRect(sx, barTop, sx + barW, barTop + 3.5f * d, 2f * d, 2f * d, paintBg)
                paintBg.color = if (idx == 0) cAccent else cMuted
                canvas.drawRoundRect(sx, barTop, sx + barW * relConf, barTop + 3.5f * d, 2f * d, 2f * d, paintBg)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
