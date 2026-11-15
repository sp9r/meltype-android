package io.github.sp9r.meltype.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import io.github.sp9r.meltype.OneHandedMode
import io.github.sp9r.meltype.PunctuationStyle
import io.github.sp9r.meltype.ThemeMode
import io.github.sp9r.meltype.glide.TouchPoint
import kotlin.math.abs
import kotlin.math.hypot

interface KeyboardActionListener {
    fun onCharacter(ch: Char)
    fun onPunctuation(isPeriod: Boolean)
    fun onBackspace()
    fun onBackspaceClearAll()
    fun onSpace()
    fun onEnter()
    fun onMoveCursor(delta: Int)
    fun onToggleLanguage()
    fun onOpenSettings()
    fun onGlidePreview(points: List<TouchPoint>, letterBounds: RectF)
    fun onGlideCommit(points: List<TouchPoint>, letterBounds: RectF)
}

/**
 * Compact-Rail styled QWERTY software keyboard with:
 * - Glide typing trail & dual-language decoding hooks
 * - Up-flick numbers (1..0) and symbols on letter keys
 * - Symbol/number layer switching (?123, =\<)
 * - Spacebar horizontal cursor/clause slide
 * - Backspace repeat + swipe-left clear
 * - Full / Left / Right one-handed alignment and Light/Dark/System themes
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Layer { QWERTY, SYM1, SYM2 }
    enum class ShiftState { OFF, ONCE, LOCKED }

    private enum class KeyKind {
        CHAR, SHIFT, BACKSPACE, LAYER_SYM, LAYER_ABC, LAYER_ALT,
        LANG_TOGGLE, SETTINGS, COMMA, PERIOD, SPACE, ENTER
    }

    private data class VisualKey(
        val rect: RectF,
        val label: String,
        val subLabel: String,
        val kind: KeyKind,
        val outputChar: Char = '\u0000',
        val subOutputChar: Char = '\u0000',
        val isFunctionKey: Boolean = false
    )

    var listener: KeyboardActionListener? = null
    var glideEnabled: Boolean = true
    var hapticEnabled: Boolean = true
    var showSubLabels: Boolean = true
    var themeMode: ThemeMode = ThemeMode.SYSTEM
        set(value) {
            field = value
            applyThemePalette()
            invalidate()
        }
    var oneHandedMode: OneHandedMode = OneHandedMode.FULL
        set(value) {
            field = value
            rebuildLayout()
            invalidate()
        }
    var heightScale: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.85f, 1.25f)
            requestLayout()
        }
    var punctuationStyle: PunctuationStyle = PunctuationStyle.JAPANESE
        set(value) {
            field = value
            rebuildLayout()
            invalidate()
        }

    private var layer: Layer = Layer.QWERTY
    private var shiftState: ShiftState = ShiftState.OFF

    private val keys = ArrayList<VisualKey>(48)
    private val letterBounds = RectF()

    // Touch tracking
    private var activePointerId = -1
    private var downKey: VisualKey? = null
    private var currentHoverKey: VisualKey? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var keyWidthAvg = 80f
    private var keyHeightAvg = 110f
    private var isGliding = false
    private var isSpaceSliding = false
    private var spaceSlideAccumX = 0f
    private var lastShiftTapTime = 0L
    private var lastGlidePreviewTime = 0L
    private var backspaceLongPressed = false
    private var upFlickActive = false

    private val glideTrail = ArrayList<TouchPoint>(64)

    private val handler = Handler(Looper.getMainLooper())
    private val bsRepeatRunnable = object : Runnable {
        override fun run() {
            if (downKey?.kind == KeyKind.BACKSPACE && activePointerId != -1 && !isGliding) {
                backspaceLongPressed = true
                performHaptic()
                listener?.onBackspace()
                handler.postDelayed(this, 58L)
            }
        }
    }

    // Palette (Compact Rail Light & Dark)
    private var cBg = 0
    private var cKeyFill = 0
    private var cKeyPressed = 0
    private var cFnFill = 0
    private var cFnPressed = 0
    private var cKeyBorder = 0
    private var cText = 0
    private var cSubText = 0
    private var cAccent = 0
    private var cAccentText = 0

    // Paints
    private val paintBg = Paint()
    private val paintKey = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintSubText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val paintTrail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintTrailHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintPopup = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPath = Path()

    init {
        isClickable = true
        isFocusable = false
        applyThemePalette()
    }

    private fun isEffectiveDark(): Boolean {
        return when (themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                night == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun applyThemePalette() {
        val dark = isEffectiveDark()
        if (dark) {
            cBg = Color.parseColor("#121417")
            cKeyFill = Color.parseColor("#23272D")
            cKeyPressed = Color.parseColor("#2F353E")
            cFnFill = Color.parseColor("#1A1D22")
            cFnPressed = Color.parseColor("#262B33")
            cKeyBorder = Color.parseColor("#323741")
            cText = Color.parseColor("#ECEEF0")
            cSubText = Color.parseColor("#8D95A0")
            cAccent = Color.parseColor("#5C8AC4")
            cAccentText = Color.parseColor("#FFFFFF")
        } else {
            cBg = Color.parseColor("#EAECEF")
            cKeyFill = Color.parseColor("#FFFFFF")
            cKeyPressed = Color.parseColor("#E3E7ED")
            cFnFill = Color.parseColor("#DCE0E6")
            cFnPressed = Color.parseColor("#CCD2DA")
            cKeyBorder = Color.parseColor("#D0D5DC")
            cText = Color.parseColor("#202328")
            cSubText = Color.parseColor("#737A84")
            cAccent = Color.parseColor("#4A6F9E")
            cAccentText = Color.parseColor("#FFFFFF")
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val baseHeightDp = 224f * heightScale
        val desiredHeight = dp(baseHeightDp).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    fun refreshAppearance() {
        applyThemePalette()
        rebuildLayout()
        invalidate()
    }

    private fun rebuildLayout() {
        keys.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sideMargin = dp(4f)
        val topPad = dp(6f)
        val bottomPad = dp(6f)
        val availH = (h - topPad - bottomPad).coerceAtLeast(100f)

        val activeFrac = if (oneHandedMode == OneHandedMode.FULL) 1.0f else 0.85f
        val areaW = (w - sideMargin * 2f) * activeFrac
        val areaLeft = when (oneHandedMode) {
            OneHandedMode.FULL -> sideMargin
            OneHandedMode.LEFT -> sideMargin
            OneHandedMode.RIGHT -> w - sideMargin - areaW
        }

        val rowH = availH / 4f
        val gapX = dp(4f)
        val gapY = dp(6f)
        val unitW = areaW / 10f

        keyWidthAvg = unitW
        keyHeightAvg = rowH

        // Record exact bounding box covering the 3-row letter region for KeyLayoutGeometry normalization
        letterBounds.set(areaLeft, topPad, areaLeft + areaW, topPad + rowH * 3f)

        fun addRowKeys(rowIdx: Int, specs: List<Triple<Float, String, () -> VisualKeyBuilder>>) {
            val y0 = topPad + rowIdx * rowH + gapY * 0.5f
            val y1 = topPad + (rowIdx + 1) * rowH - gapY * 0.5f
            var curX = areaLeft
            for ((units, _, builderFn) in specs) {
                val kw = unitW * units
                val b = builderFn()
                keys.add(
                    VisualKey(
                        rect = RectF(curX + gapX * 0.5f, y0, curX + kw - gapX * 0.5f, y1),
                        label = b.label,
                        subLabel = b.subLabel,
                        kind = b.kind,
                        outputChar = b.outputChar,
                        subOutputChar = b.subOutputChar,
                        isFunctionKey = b.isFn
                    )
                )
                curX += kw
            }
        }

        when (layer) {
            Layer.QWERTY -> {
                val upper = shiftState != ShiftState.OFF
                val r0Chars = "qwertyuiop"
                val r0Nums = "1234567890"
                val r0 = r0Chars.indices.map { i ->
                    val ch = if (upper) r0Chars[i].uppercaseChar() else r0Chars[i]
                    val num = r0Nums[i]
                    Triple(1f, ch.toString()) {
                        VisualKeyBuilder(ch.toString(), num.toString(), KeyKind.CHAR, ch, num, false)
                    }
                }
                addRowKeys(0, r0)

                val r1Chars = "asdfghjkl"
                val r1Syms = "@#$&_+-()"
                val r1 = ArrayList<Triple<Float, String, () -> VisualKeyBuilder>>()
                // Row 1 centered with 0.5u outer visual padding via inset
                val y0r1 = topPad + rowH + gapY * 0.5f
                val y1r1 = topPad + rowH * 2f - gapY * 0.5f
                var rx = areaLeft + unitW * 0.5f
                for (i in r1Chars.indices) {
                    val ch = if (upper) r1Chars[i].uppercaseChar() else r1Chars[i]
                    val sym = r1Syms[i]
                    keys.add(
                        VisualKey(
                            rect = RectF(rx + gapX * 0.5f, y0r1, rx + unitW - gapX * 0.5f, y1r1),
                            label = ch.toString(),
                            subLabel = sym.toString(),
                            kind = KeyKind.CHAR,
                            outputChar = ch,
                            subOutputChar = sym,
                            isFunctionKey = false
                        )
                    )
                    rx += unitW
                }
                ignore(r1)

                val r2 = ArrayList<Triple<Float, String, () -> VisualKeyBuilder>>()
                val shiftLabel = when (shiftState) {
                    ShiftState.OFF -> "⇧"
                    ShiftState.ONCE -> "⬆"
                    ShiftState.LOCKED -> "⇪"
                }
                r2.add(Triple(1.5f, shiftLabel) { VisualKeyBuilder(shiftLabel, "", KeyKind.SHIFT, isFn = true) })
                val r2Chars = "zxcvbnm"
                val r2Syms = "*\"':;!?"
                for (i in r2Chars.indices) {
                    val ch = if (upper) r2Chars[i].uppercaseChar() else r2Chars[i]
                    val sym = r2Syms[i]
                    r2.add(Triple(1f, ch.toString()) {
                        VisualKeyBuilder(ch.toString(), sym.toString(), KeyKind.CHAR, ch, sym, false)
                    })
                }
                r2.add(Triple(1.5f, "⌫") { VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true) })
                addRowKeys(2, r2)

                addRowKeys(
                    3,
                    listOf(
                        Triple(1.4f, "?123") { VisualKeyBuilder("?123", "", KeyKind.LAYER_SYM, isFn = true) },
                        Triple(1.1f, "日/英") { VisualKeyBuilder("日/英", "", KeyKind.LANG_TOGGLE, isFn = true) },
                        Triple(1.0f, punctuationStyle.comma) { VisualKeyBuilder(punctuationStyle.comma, "", KeyKind.COMMA, isFn = false) },
                        Triple(3.5f, "Meltype") { VisualKeyBuilder("空白 / グライド", "", KeyKind.SPACE, isFn = false) },
                        Triple(1.0f, punctuationStyle.period) { VisualKeyBuilder(punctuationStyle.period, "", KeyKind.PERIOD, isFn = false) },
                        Triple(2.0f, "確定") { VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true) }
                    )
                )
            }

            Layer.SYM1 -> {
                val r0 = "1234567890".map { ch ->
                    Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) }
                }
                addRowKeys(0, r0)

                val r1 = "@#¥_&-+()/".map { ch ->
                    Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) }
                }
                addRowKeys(1, r1)

                val r2 = ArrayList<Triple<Float, String, () -> VisualKeyBuilder>>()
                r2.add(Triple(1.5f, "=\\<") { VisualKeyBuilder("=\\<", "", KeyKind.LAYER_ALT, isFn = true) })
                for (ch in "*\"':;!?") {
                    r2.add(Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) })
                }
                r2.add(Triple(1.5f, "⌫") { VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true) })
                addRowKeys(2, r2)

                addRowKeys(
                    3,
                    listOf(
                        Triple(1.4f, "ABC") { VisualKeyBuilder("ABC", "", KeyKind.LAYER_ABC, isFn = true) },
                        Triple(1.1f, "設定") { VisualKeyBuilder("⚙", "", KeyKind.SETTINGS, isFn = true) },
                        Triple(1.0f, ",") { VisualKeyBuilder(",", "", KeyKind.CHAR, ',', isFn = false) },
                        Triple(3.5f, "Space") { VisualKeyBuilder("スペース", "", KeyKind.SPACE, isFn = false) },
                        Triple(1.0f, ".") { VisualKeyBuilder(".", "", KeyKind.CHAR, '.', isFn = false) },
                        Triple(2.0f, "⏎") { VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true) }
                    )
                )
            }

            Layer.SYM2 -> {
                val r0 = "~`|•√π÷×¶Δ".map { ch ->
                    Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) }
                }
                addRowKeys(0, r0)

                val r1 = "£¢€\$^°={}\\".map { ch ->
                    Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) }
                }
                addRowKeys(1, r1)

                val r2 = ArrayList<Triple<Float, String, () -> VisualKeyBuilder>>()
                r2.add(Triple(1.5f, "123") { VisualKeyBuilder("123", "", KeyKind.LAYER_SYM, isFn = true) })
                for (ch in "%©®™✓[]") {
                    r2.add(Triple(1f, ch.toString()) { VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false) })
                }
                r2.add(Triple(1.5f, "⌫") { VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true) })
                addRowKeys(2, r2)

                addRowKeys(
                    3,
                    listOf(
                        Triple(1.4f, "ABC") { VisualKeyBuilder("ABC", "", KeyKind.LAYER_ABC, isFn = true) },
                        Triple(1.1f, "設定") { VisualKeyBuilder("⚙", "", KeyKind.SETTINGS, isFn = true) },
                        Triple(1.0f, "<") { VisualKeyBuilder("<", "", KeyKind.CHAR, '<', isFn = false) },
                        Triple(3.5f, "Space") { VisualKeyBuilder("スペース", "", KeyKind.SPACE, isFn = false) },
                        Triple(1.0f, ">") { VisualKeyBuilder(">", "", KeyKind.CHAR, '>', isFn = false) },
                        Triple(2.0f, "⏎") { VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true) }
                    )
                )
            }
        }
    }

    private fun ignore(@Suppress("UNUSED_PARAMETER") unused: Any?) {}

    private data class VisualKeyBuilder(
        val label: String,
        val subLabel: String,
        val kind: KeyKind,
        val outputChar: Char = '\u0000',
        val subOutputChar: Char = '\u0000',
        val isFn: Boolean = false
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paintBg.color = cBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        val corner = dp(5f)
        paintBorder.strokeWidth = dp(1f)
        paintBorder.color = cKeyBorder

        for (k in keys) {
            val isPressed = !isGliding && (currentHoverKey === k || downKey === k && isSpaceSliding && k.kind == KeyKind.SPACE)
            paintKey.color = when {
                k.kind == KeyKind.ENTER -> if (isPressed) adjustBrightness(cAccent, 0.85f) else cAccent
                k.kind == KeyKind.SHIFT && shiftState != ShiftState.OFF -> cAccent
                isPressed && k.isFunctionKey -> cFnPressed
                isPressed -> cKeyPressed
                k.isFunctionKey -> cFnFill
                else -> cKeyFill
            }
            canvas.drawRoundRect(k.rect, corner, corner, paintKey)
            if (k.kind != KeyKind.ENTER) {
                canvas.drawRoundRect(k.rect, corner, corner, paintBorder)
            }

            // Main label
            val onAccent = k.kind == KeyKind.ENTER || (k.kind == KeyKind.SHIFT && shiftState != ShiftState.OFF)
            paintText.color = if (onAccent) cAccentText else cText
            paintText.textSize = when {
                k.kind == KeyKind.SPACE -> sp(12f)
                k.label.length >= 2 -> sp(13.5f)
                else -> sp(19f)
            }
            val fm = paintText.fontMetrics
            val textY = k.rect.centerY() - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(k.label, k.rect.centerX(), textY, paintText)

            // Sub label (top-right corner for numbers/symbols)
            if (showSubLabels && k.subLabel.isNotEmpty()) {
                paintSubText.color = cSubText
                paintSubText.textSize = sp(9.5f)
                canvas.drawText(
                    k.subLabel,
                    k.rect.right - dp(4f),
                    k.rect.top + dp(11f),
                    paintSubText
                )
            }
        }

        // Draw glide trail
        if (isGliding && glideTrail.size >= 2) {
            val count = glideTrail.size
            for (i in 1 until count) {
                val frac = i.toFloat() / (count - 1).toFloat()
                paintTrail.color = Color.argb((70 + 170 * frac).toInt().coerceIn(0, 255), Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent))
                paintTrail.strokeWidth = dp(2.2f + 4.8f * frac)
                canvas.drawLine(glideTrail[i - 1].x, glideTrail[i - 1].y, glideTrail[i].x, glideTrail[i].y, paintTrail)
            }
            val head = glideTrail.last()
            paintTrailHead.color = cAccent
            canvas.drawCircle(head.x, head.y, dp(5.5f), paintTrailHead)
        }

        // Draw subtle Compact-Rail key tap badge when tapping or up-flicking a character
        val dk = downKey
        if (!isGliding && dk != null && dk.kind == KeyKind.CHAR && activePointerId != -1) {
            val previewChar = if (upFlickActive && dk.subOutputChar != '\u0000') dk.subLabel else dk.label
            val popW = dk.rect.width() * 1.15f
            val popH = dk.rect.height() * 0.92f
            val cx = dk.rect.centerX().coerceIn(popW * 0.5f + dp(4f), width - popW * 0.5f - dp(4f))
            // Keep badge safely inside view bounds (render downward below key only if top row clips, otherwise just above key)
            val idealTop = dk.rect.top - popH - dp(4f)
            val top = if (idealTop >= dp(2f)) idealTop else dk.rect.top + dp(2f)
            val popRect = RectF(cx - popW * 0.5f, top, cx + popW * 0.5f, top + popH)
            paintPopup.color = if (isEffectiveDark()) Color.parseColor("#2D333B") else Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(popRect, dp(5f), dp(5f), paintPopup)
            paintBorder.color = cAccent
            canvas.drawRoundRect(popRect, dp(5f), dp(5f), paintBorder)

            paintText.color = cText
            paintText.textSize = sp(22f)
            val fm = paintText.fontMetrics
            canvas.drawText(previewChar, popRect.centerX(), popRect.centerY() - (fm.ascent + fm.descent) * 0.5f, paintText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val idx = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.getX(0)
                val y = event.getY(0)
                activePointerId = event.getPointerId(0)
                downX = x
                downY = y
                lastX = x
                lastY = y
                isGliding = false
                isSpaceSliding = false
                spaceSlideAccumX = 0f
                backspaceLongPressed = false
                upFlickActive = false
                glideTrail.clear()

                val hit = findKeyAt(x, y)
                downKey = hit
                currentHoverKey = hit
                if (hit != null) {
                    performHaptic()
                    if (hit.kind == KeyKind.BACKSPACE) {
                        handler.removeCallbacks(bsRepeatRunnable)
                        handler.postDelayed(bsRepeatRunnable, 360L)
                    } else if (glideEnabled && layer == Layer.QWERTY && isLetterCharKey(hit)) {
                        glideTrail.add(TouchPoint(x, y, event.eventTime))
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val pIdx = event.findPointerIndex(activePointerId)
                if (pIdx < 0) return true
                val x = event.getX(pIdx)
                val y = event.getY(pIdx)
                val dk = downKey

                if (dk?.kind == KeyKind.SPACE) {
                    val dx = x - lastX
                    spaceSlideAccumX += dx
                    val step = keyWidthAvg * 0.42f
                    if (abs(spaceSlideAccumX) >= step) {
                        isSpaceSliding = true
                        val dir = if (spaceSlideAccumX > 0f) 1 else -1
                        spaceSlideAccumX -= dir * step
                        performHaptic()
                        listener?.onMoveCursor(dir)
                    }
                    lastX = x
                    lastY = y
                    return true
                }

                // Detect Up-Flick (within same key column, upward vertical pull) vs Glide across keys
                val hover = findKeyAt(x, y)
                if (!isGliding && glideEnabled && layer == Layer.QWERTY && isLetterCharKey(dk)) {
                    glideTrail.add(TouchPoint(x, y, event.eventTime))
                    val totalDist = hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                    val enteredAnotherLetterKey = isLetterCharKey(hover) && hover?.outputChar?.lowercaseChar() != dk?.outputChar?.lowercaseChar()
                    if (totalDist > keyWidthAvg * 0.85f && enteredAnotherLetterKey) {
                        isGliding = true
                        upFlickActive = false
                    }
                } else if (isGliding) {
                    val prev = glideTrail.lastOrNull()
                    if (prev == null || hypot((x - prev.x).toDouble(), (y - prev.y).toDouble()) >= dp(3f)) {
                        glideTrail.add(TouchPoint(x, y, event.eventTime))
                    }
                    val now = event.eventTime
                    if (now - lastGlidePreviewTime >= 90L && glideTrail.size >= 4) {
                        lastGlidePreviewTime = now
                        listener?.onGlidePreview(glideTrail.toList(), RectF(letterBounds))
                    }
                }

                if (!isGliding && dk != null && dk.kind == KeyKind.CHAR && dk.subOutputChar != '\u0000') {
                    val dyUp = downY - y
                    val dxAbs = abs(x - downX)
                    upFlickActive = (dyUp > keyHeightAvg * 0.36f && dxAbs < keyWidthAvg * 0.85f)
                }

                if (!isGliding) {
                    if (dk?.kind != KeyKind.BACKSPACE) {
                        currentHoverKey = hover
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch fast typing: finish first finger immediately as tap
                if (activePointerId != -1 && !isGliding && !isSpaceSliding) {
                    val pIdx = event.findPointerIndex(activePointerId)
                    val endX = if (pIdx >= 0) event.getX(pIdx) else lastX
                    val endY = if (pIdx >= 0) event.getY(pIdx) else lastY
                    finishTapOrGesture(endX, endY)
                }
                // Switch tracking to new pointer
                activePointerId = event.getPointerId(idx)
                downX = event.getX(idx)
                downY = event.getY(idx)
                lastX = downX
                lastY = downY
                isGliding = false
                isSpaceSliding = false
                spaceSlideAccumX = 0f
                backspaceLongPressed = false
                upFlickActive = false
                glideTrail.clear()
                val hit = findKeyAt(downX, downY)
                downKey = hit
                currentHoverKey = hit
                if (hit != null) {
                    performHaptic()
                    if (glideEnabled && layer == Layer.QWERTY && isLetterCharKey(hit)) {
                        glideTrail.add(TouchPoint(downX, downY, event.eventTime))
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(bsRepeatRunnable)
                val pIdx = event.findPointerIndex(activePointerId)
                val endX = if (pIdx >= 0) event.getX(pIdx) else lastX
                val endY = if (pIdx >= 0) event.getY(pIdx) else lastY
                if (action == MotionEvent.ACTION_UP) {
                    finishTapOrGesture(endX, endY)
                }
                activePointerId = -1
                downKey = null
                currentHoverKey = null
                isGliding = false
                isSpaceSliding = false
                upFlickActive = false
                glideTrail.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishTapOrGesture(endX: Float, endY: Float) {
        val dk = downKey
        if (isGliding) {
            if (glideTrail.size >= 2) {
                listener?.onGlideCommit(glideTrail.toList(), RectF(letterBounds))
            }
            return
        }
        if (isSpaceSliding && dk?.kind == KeyKind.SPACE) {
            return
        }
        if (dk?.kind == KeyKind.BACKSPACE) {
            if (downX - endX > keyWidthAvg * 1.4f && !backspaceLongPressed) {
                listener?.onBackspaceClearAll()
            } else if (!backspaceLongPressed) {
                listener?.onBackspace()
            }
            return
        }

        // Up-flick check
        if (dk != null && dk.kind == KeyKind.CHAR && dk.subOutputChar != '\u0000') {
            val dyUp = downY - endY
            val dxAbs = abs(endX - downX)
            if (dyUp > keyHeightAvg * 0.36f && dxAbs < keyWidthAvg * 0.85f) {
                listener?.onCharacter(dk.subOutputChar)
                return
            }
        }

        val target = currentHoverKey ?: dk ?: return
        when (target.kind) {
            KeyKind.CHAR -> {
                val out = target.outputChar
                if (out != '\u0000') {
                    listener?.onCharacter(out)
                    if (layer == Layer.QWERTY && shiftState == ShiftState.ONCE) {
                        shiftState = ShiftState.OFF
                        rebuildLayout()
                    }
                }
            }
            KeyKind.SHIFT -> {
                val now = System.currentTimeMillis()
                shiftState = when {
                    shiftState == ShiftState.ONCE && now - lastShiftTapTime < 380L -> ShiftState.LOCKED
                    shiftState == ShiftState.OFF -> ShiftState.ONCE
                    else -> ShiftState.OFF
                }
                lastShiftTapTime = now
                rebuildLayout()
            }
            KeyKind.BACKSPACE -> listener?.onBackspace()
            KeyKind.LAYER_SYM -> {
                layer = Layer.SYM1
                rebuildLayout()
            }
            KeyKind.LAYER_ALT -> {
                layer = Layer.SYM2
                rebuildLayout()
            }
            KeyKind.LAYER_ABC -> {
                layer = Layer.QWERTY
                rebuildLayout()
            }
            KeyKind.LANG_TOGGLE -> listener?.onToggleLanguage()
            KeyKind.SETTINGS -> listener?.onOpenSettings()
            KeyKind.COMMA -> listener?.onPunctuation(isPeriod = false)
            KeyKind.PERIOD -> listener?.onPunctuation(isPeriod = true)
            KeyKind.SPACE -> listener?.onSpace()
            KeyKind.ENTER -> listener?.onEnter()
        }
    }

    private fun isLetterCharKey(k: VisualKey?): Boolean {
        if (k == null || k.kind != KeyKind.CHAR) return false
        val low = k.outputChar.lowercaseChar()
        return low in 'a'..'z'
    }

    private fun findKeyAt(x: Float, y: Float): VisualKey? {
        for (k in keys) {
            if (k.rect.contains(x, y)) return k
        }
        // Fallback nearest key within reasonable vertical tolerance (handles finger near edges)
        var best: VisualKey? = null
        var bestDist = Float.MAX_VALUE
        for (k in keys) {
            val cx = x.coerceIn(k.rect.left, k.rect.right)
            val cy = y.coerceIn(k.rect.top, k.rect.bottom)
            val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
            if (d < bestDist) {
                bestDist = d
                best = k
            }
        }
        return if (bestDist <= dp(24f)) best else null
    }

    private fun performHaptic() {
        if (hapticEnabled) {
            performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    private fun adjustBrightness(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
