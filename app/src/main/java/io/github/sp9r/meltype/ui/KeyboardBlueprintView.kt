package io.github.sp9r.meltype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import io.github.sp9r.meltype.BottomKeyMode
import io.github.sp9r.meltype.OneHandedMode
import io.github.sp9r.meltype.PunctuationStyle
import io.github.sp9r.meltype.core.ImeHeightSpec
import kotlin.math.roundToInt

/**
 * Live proportional blueprint preview inside the Settings workspace showing real-time
 * height scaling (65%..155%), one-handed dock position, number row, and punctuation labels.
 */
class KeyboardBlueprintView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var previewDark: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var heightScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.65f, 1.55f)
            invalidate()
        }
    var sideMarginDp: Int = 12
        set(value) {
            field = value.coerceIn(0, 40)
            invalidate()
        }
    var showNumberRow: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var bottomKeyMode: BottomKeyMode = BottomKeyMode.EMOJI
        set(value) {
            field = value
            invalidate()
        }
    var showSubLabels: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var oneHandedMode: OneHandedMode = OneHandedMode.FULL
        set(value) {
            field = value
            invalidate()
        }
    var punctuationStyle: PunctuationStyle = PunctuationStyle.JAPANESE
        set(value) {
            field = value
            invalidate()
        }

    private val rect = RectF()
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val paintMono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, dp(164))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density

        val frameBg = if (previewDark) Color.parseColor("#0F1114") else Color.parseColor("#ECEEF1")
        val frameBorder = if (previewDark) Color.parseColor("#2A2F38") else Color.parseColor("#DFE2E6")
        val trayBg = if (previewDark) Color.parseColor("#14171C") else Color.parseColor("#EAECEF")
        val keyBg = if (previewDark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val fnBg = if (previewDark) Color.parseColor("#1A1D22") else Color.parseColor("#DCE0E6")
        val keyBorder = if (previewDark) Color.parseColor("#323741") else Color.parseColor("#D0D5DC")
        val textCol = if (previewDark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val mutedCol = if (previewDark) Color.parseColor("#8D95A0") else Color.parseColor("#737A84")
        val accentCol = if (previewDark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")

        // Outer screen mock frame
        paintFill.color = frameBg
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 6f * d, 6f * d, paintFill)
        paintStroke.color = frameBorder
        paintStroke.strokeWidth = 1f * d
        canvas.drawRoundRect(0.5f * d, 0.5f * d, width - 0.5f * d, height - 0.5f * d, 6f * d, 6f * d, paintStroke)

        // Metric telemetry header inside preview
        val baseDp = ImeHeightSpec.keyboardBaseHeightDp(showNumberRow)
        val approxDp = (baseDp * heightScale).roundToInt()
        val pct = (heightScale * 100f).roundToInt()
        paintMono.textSize = 10.5f * d
        paintMono.color = mutedCol
        paintMono.textAlign = Paint.Align.LEFT
        canvas.drawText(
            "PREVIEW · ${pct}% (${approxDp}dp) · ${oneHandedMode.labelJa}",
            10f * d,
            16f * d,
            paintMono
        )

        // Bottom-aligned proportional keyboard tray
        val topHeader = 22f * d
        val maxKbH = height - topHeader - 4f * d
        // Map 1.55x + numRow (~384dp) proportionally to preview space
        val kbH = (maxKbH * (approxDp / 385f)).coerceIn(58f * d, maxKbH)
        val kbTop = height - kbH - 2f * d
        val trayLeft = 6f * d
        val trayRight = width - 6f * d
        val trayW = trayRight - trayLeft

        paintFill.color = trayBg
        canvas.drawRoundRect(trayLeft, kbTop, trayRight, height - 2f * d, 4f * d, 4f * d, paintFill)

        val kbW = when (oneHandedMode) {
            OneHandedMode.FULL -> trayW
            else -> trayW * 0.80f
        }
        val rawKbLeft = when (oneHandedMode) {
            OneHandedMode.FULL, OneHandedMode.LEFT -> trayLeft + 2f * d
            OneHandedMode.RIGHT -> trayRight - kbW - 2f * d
        }
        // 実キーボードの余白(dp)を360dp幅の画面比でプレビューへ反映する
        val marginPx = sideMarginDp / 360f * trayW
        val kbLeft = rawKbLeft + marginPx
        val kbRight = rawKbLeft + kbW - 4f * d - marginPx
        val innerW = kbRight - kbLeft

        // Two-tier fixed header (upper toolbar/clause rail + lower candidate rail) + key rows
        val upperRailH = 6f * d
        val candRailH = 8f * d
        val railH = upperRailH + candRailH + 1f * d
        paintFill.color = fnBg
        canvas.drawRect(kbLeft, kbTop + 2f * d, kbRight, kbTop + 2f * d + upperRailH, paintFill)
        paintFill.color = keyBg
        canvas.drawRect(kbLeft, kbTop + 3f * d + upperRailH, kbRight, kbTop + 2f * d + railH, paintFill)

        val rows = buildList {
            if (showNumberRow) add("1234567890")
            add("qwertyuiop")
            add("asdfghjkl")
            add("zxcvbnm")
            add("BOTTOM")
        }
        val keysAreaTop = kbTop + railH + 4f * d
        val keysAreaH = (height - 4f * d - keysAreaTop).coerceAtLeast(36f * d)
        val sepGap = if (showNumberRow) 4.5f * d else 0f
        val rowCount = rows.size
        val rowH = (keysAreaH - sepGap) / rowCount
        val padX = 1.4f * d
        val padY = 1.3f * d
        val unitW = innerW / 10f
        val rKey = 2.5f * d

        paintText.textSize = (rowH * 0.42f).coerceIn(7f * d, 10f * d)
        paintSub.textSize = (rowH * 0.25f).coerceIn(5f * d, 6.5f * d)

        for ((ri, rStr) in rows.withIndex()) {
            val extraOffset = if (showNumberRow && ri >= 1) sepGap else 0f
            val y0 = keysAreaTop + extraOffset + ri * rowH + padY
            val y1 = keysAreaTop + extraOffset + (ri + 1) * rowH - padY
            if (showNumberRow && ri == 1) {
                val divY = keysAreaTop + rowH + sepGap * 0.5f
                paintStroke.color = mutedCol
                paintStroke.strokeWidth = 0.6f * d
                canvas.drawLine(kbLeft + 3f * d, divY, kbRight - 3f * d, divY, paintStroke)
            }
            when (rStr) {
                "1234567890", "qwertyuiop" -> {
                    for (i in rStr.indices) {
                        val x0 = kbLeft + i * unitW + padX
                        val x1 = kbLeft + (i + 1) * unitW - padX
                        drawMiniKey(canvas, x0, y0, x1, y1, rStr[i].toString(), if (showSubLabels && rStr[0] == 'q') "${(i + 1) % 10}" else "", rKey, keyBg, keyBorder, textCol, mutedCol, accentCol, false)
                    }
                }
                "asdfghjkl" -> {
                    for (i in rStr.indices) {
                        val x0 = kbLeft + i * unitW + padX
                        val x1 = kbLeft + (1f + i) * unitW - padX
                        drawMiniKey(canvas, x0, y0, x1, y1, rStr[i].toString(), "", rKey, keyBg, keyBorder, textCol, mutedCol, accentCol, false)
                    }
                    drawMiniKey(canvas, kbLeft + 9f * unitW + padX, y0, kbRight - padX, y1, "ー", "", rKey, keyBg, keyBorder, textCol, mutedCol, accentCol, false)
                }
                "zxcvbnm" -> {
                    drawMiniKey(canvas, kbLeft + padX, y0, kbLeft + 1.5f * unitW - padX, y1, "⇧", "", rKey, fnBg, keyBorder, textCol, mutedCol, accentCol, false)
                    for (i in rStr.indices) {
                        val x0 = kbLeft + (1.5f + i) * unitW + padX
                        val x1 = kbLeft + (2.5f + i) * unitW - padX
                        drawMiniKey(canvas, x0, y0, x1, y1, rStr[i].toString(), "", rKey, keyBg, keyBorder, textCol, mutedCol, accentCol, false)
                    }
                    drawMiniKey(canvas, kbLeft + 8.5f * unitW + padX, y0, kbRight - padX, y1, "⌫", "", rKey, fnBg, keyBorder, textCol, mutedCol, accentCol, false)
                }
                "BOTTOM" -> {
                    val specs = listOf(
                        1.0f to "?123",
                        0.7f to punctuationStyle.comma,
                        0.7f to (if (bottomKeyMode == BottomKeyMode.LANGUAGE) "日/英" else "☺"),
                        3.0f to "空白",
                        0.7f to punctuationStyle.period,
                        0.7f to "◀",
                        0.7f to "▶",
                        1.5f to "↵"
                    )
                    var ux = 0f
                    for ((idx, spec) in specs.withIndex()) {
                        val wUnit = spec.first
                        val lbl = spec.second
                        val x0 = kbLeft + ux * unitW + padX
                        val x1 = kbLeft + (ux + wUnit) * unitW - padX
                        val isEnter = idx == specs.lastIndex
                        val isWhite = lbl == punctuationStyle.comma || lbl == "空白" || lbl == "ー" || lbl == punctuationStyle.period
                        val bg = when {
                            isEnter -> accentCol
                            isWhite -> keyBg
                            else -> fnBg
                        }
                        drawMiniKey(canvas, x0, y0, x1, y1, lbl, "", rKey, bg, keyBorder, if (isEnter) Color.WHITE else textCol, mutedCol, accentCol, isEnter)
                        ux += wUnit
                    }
                }
            }
        }
    }

    private fun drawMiniKey(
        canvas: Canvas,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        label: String,
        sub: String,
        r: Float,
        bg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int,
        @Suppress("UNUSED_PARAMETER") accentCol: Int,
        isAccent: Boolean
    ) {
        rect.set(x0, y0, x1, y1)
        paintFill.color = bg
        canvas.drawRoundRect(rect, r, r, paintFill)
        if (!isAccent) {
            paintStroke.color = border
            paintStroke.strokeWidth = 0.7f * resources.displayMetrics.density
            canvas.drawRoundRect(rect, r, r, paintStroke)
        }
        paintText.color = textCol
        val fm = paintText.fontMetrics
        val ty = rect.centerY() - (fm.ascent + fm.descent) * 0.5f
        if (label == SmileyIcon.GLYPH) {
            SmileyIcon.draw(canvas, rect.centerX(), rect.centerY(), paintText.textSize * 0.45f, textCol)
        } else {
            canvas.drawText(label, rect.centerX(), ty, paintText)
        }
        if (sub.isNotEmpty() && rect.height() > 12f * resources.displayMetrics.density) {
            paintSub.color = mutedCol
            canvas.drawText(sub, rect.right - 2f * resources.displayMetrics.density, rect.top + 6.5f * resources.displayMetrics.density, paintSub)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
