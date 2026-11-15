package io.github.sp9r.meltype.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    fun onCharacter(ch: Char, directCommit: Boolean = false)
    fun onPunctuation(isPeriod: Boolean)
    fun onBackspace()
    fun onBackspaceClearAll()
    fun onSpace()
    fun onEnter()
    fun onMoveCursor(delta: Int)
    fun onToggleLanguage()
    fun onToggleEmojiPanel()
    fun onOpenSettings()
    fun onGlidePreview(points: List<TouchPoint>, letterBounds: RectF)
    fun onGlideCommit(points: List<TouchPoint>, letterBounds: RectF)
}

/**
 * Compact-Rail styled QWERTY software keyboard with:
 * - Freely scalable height (65%..155%)
 * - Optional persistent top number row (1..0) separated cleanly from 3-row glide geometry
 * - Non-conflicting long-press (220ms) and fast vertical flick for secondary numbers/symbols
 * - Direct prolonged-sound / hyphen ("ー") key on bottom row + dedicated Numpad layer
 * - Glide typing trail & dual-language decoding hooks
 * - Spacebar horizontal cursor/clause slide & Backspace repeat / swipe-left clear
 */
class QwertyKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Layer { QWERTY, SYM1, SYM2, NUMPAD }
    enum class ShiftState { OFF, ONCE, LOCKED }

    private enum class KeyKind {
        CHAR, SHIFT, BACKSPACE, LAYER_SYM, LAYER_ABC, LAYER_ALT, LAYER_NUMPAD,
        LANG_TOGGLE, SETTINGS, EMOJI, CURSOR_LEFT, CURSOR_RIGHT, COMMA, PERIOD, SPACE, ENTER
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
    var showNumberRow: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                rebuildLayout()
                requestLayout()
                invalidate()
            }
        }
    var autoReturnFromSymbols: Boolean = false
    var flickSensitivity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.7f, 1.4f)
        }
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
            field = value.coerceIn(0.65f, 1.55f)
            requestLayout()
            invalidate()
        }
    var punctuationStyle: PunctuationStyle = PunctuationStyle.JAPANESE
        set(value) {
            field = value
            rebuildLayout()
            invalidate()
        }

    private var layer: Layer = Layer.QWERTY
    private var shiftState: ShiftState = ShiftState.OFF

    private val keys = ArrayList<VisualKey>(60)
    private val letterBounds = RectF()

    // Touch tracking
    private var activePointerId = -1
    private var downKey: VisualKey? = null
    private var currentHoverKey: VisualKey? = null
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
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
    private var cursorLongPressed = false
    private var verticalFlickActive = false
    private var subLongPressActive = false

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

    private val cursorRepeatRunnable = object : Runnable {
        override fun run() {
            val dk = downKey
            if ((dk?.kind == KeyKind.CURSOR_LEFT || dk?.kind == KeyKind.CURSOR_RIGHT) &&
                activePointerId != -1 && !isGliding
            ) {
                cursorLongPressed = true
                performHaptic()
                listener?.onMoveCursor(if (dk.kind == KeyKind.CURSOR_LEFT) -1 else 1)
                handler.postDelayed(this, 72L)
            }
        }
    }

    private val subLongPressRunnable = Runnable {
        val dk = downKey
        if (dk != null && dk.subOutputChar != '\u0000' &&
            activePointerId != -1 && !isGliding && !isSpaceSliding &&
            hypot((lastX - downX).toDouble(), (lastY - downY).toDouble()) < keyWidthAvg * 0.45f
        ) {
            subLongPressActive = true
            verticalFlickActive = false
            performHaptic()
            invalidate()
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

    fun currentBaseHeightDp(): Float {
        return if (layer == Layer.QWERTY && showNumberRow) 252f else 224f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(currentBaseHeightDp() * heightScale).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayout()
    }

    fun refreshAppearance() {
        applyThemePalette()
        rebuildLayout()
        requestLayout()
        invalidate()
    }

    private fun rebuildLayout() {
        keys.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sideMargin = dp(4f)
        val topPad = dp(5f)
        val bottomPad = dp(5f)
        val availH = (h - topPad - bottomPad).coerceAtLeast(100f)

        val activeFrac = if (oneHandedMode == OneHandedMode.FULL) 1.0f else 0.85f
        val areaW = (w - sideMargin * 2f) * activeFrac
        val areaLeft = when (oneHandedMode) {
            OneHandedMode.FULL -> sideMargin
            OneHandedMode.LEFT -> sideMargin
            OneHandedMode.RIGHT -> w - sideMargin - areaW
        }

        val gapX = dp(3.5f)
        val gapY = dp(5f)
        val unitW = areaW / 10f
        keyWidthAvg = unitW

        val hasTopNumRow = (layer == Layer.QWERTY && showNumberRow)
        val rowWeights = if (hasTopNumRow) {
            floatArrayOf(0.76f, 1.0f, 1.0f, 1.0f, 1.04f)
        } else {
            floatArrayOf(1.0f, 1.0f, 1.0f, 1.04f)
        }
        val weightSum = rowWeights.sum()
        val rowTops = FloatArray(rowWeights.size + 1)
        rowTops[0] = topPad
        for (r in rowWeights.indices) {
            rowTops[r + 1] = rowTops[r] + availH * (rowWeights[r] / weightSum)
        }

        keyHeightAvg = (rowTops[1] - rowTops[0]).coerceAtLeast(
            if (rowWeights.size > 1) rowTops[2] - rowTops[1] else availH / 4f
        )

        if (hasTopNumRow) {
            // Keep KeyLayoutGeometry bounds strictly aligned to the 3 letter rows (rows 1..3)
            letterBounds.set(areaLeft, rowTops[1], areaLeft + areaW, rowTops[4])
        } else {
            letterBounds.set(areaLeft, rowTops[0], areaLeft + areaW, rowTops[3])
        }

        fun addRowKeys(rowIdx: Int, specs: List<Pair<Float, VisualKeyBuilder>>, insetUnits: Float = 0f) {
            val y0 = rowTops[rowIdx] + gapY * 0.5f
            val y1 = rowTops[rowIdx + 1] - gapY * 0.5f
            var curX = areaLeft + unitW * insetUnits
            for ((units, b) in specs) {
                val kw = unitW * units
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
                var rOffset = 0
                if (hasTopNumRow) {
                    val nums = "1234567890"
                    val numSyms = "!@#\$%&*()-".let { it }
                    val numSpecs = nums.indices.map { i ->
                        val n = nums[i]
                        val s = numSyms[i]
                        1f to VisualKeyBuilder(n.toString(), s.toString(), KeyKind.CHAR, n, s, false)
                    }
                    addRowKeys(0, numSpecs)
                    rOffset = 1
                }

                val r0Chars = "qwertyuiop"
                val r0Subs = if (hasTopNumRow) "+=/<>[]{}~" else "1234567890"
                val r0 = r0Chars.indices.map { i ->
                    val ch = if (upper) r0Chars[i].uppercaseChar() else r0Chars[i]
                    val sub = r0Subs[i]
                    1f to VisualKeyBuilder(ch.toString(), sub.toString(), KeyKind.CHAR, ch, sub, false)
                }
                addRowKeys(rOffset, r0)

                val r1Chars = "asdfghjkl"
                val r1Syms = "@#$&_+-()"
                val r1 = r1Chars.indices.map { i ->
                    val ch = if (upper) r1Chars[i].uppercaseChar() else r1Chars[i]
                    val sym = r1Syms[i]
                    1f to VisualKeyBuilder(ch.toString(), sym.toString(), KeyKind.CHAR, ch, sym, false)
                }
                addRowKeys(rOffset + 1, r1, insetUnits = 0.5f)

                val r2 = ArrayList<Pair<Float, VisualKeyBuilder>>()
                val shiftLabel = when (shiftState) {
                    ShiftState.OFF -> "⇧"
                    ShiftState.ONCE -> "⬆"
                    ShiftState.LOCKED -> "⇪"
                }
                r2.add(1.5f to VisualKeyBuilder(shiftLabel, "", KeyKind.SHIFT, isFn = true))
                val r2Chars = "zxcvbnm"
                val r2Syms = "*\"':;!?"
                for (i in r2Chars.indices) {
                    val ch = if (upper) r2Chars[i].uppercaseChar() else r2Chars[i]
                    val sym = r2Syms[i]
                    r2.add(1f to VisualKeyBuilder(ch.toString(), sym.toString(), KeyKind.CHAR, ch, sym, false))
                }
                r2.add(1.5f to VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true))
                addRowKeys(rOffset + 2, r2)

                addRowKeys(
                    rOffset + 3,
                    listOf(
                        1.05f to VisualKeyBuilder("?123", "123", KeyKind.LAYER_SYM, subOutputChar = '\u0001', isFn = true),
                        0.85f to VisualKeyBuilder("日/英", "☺", KeyKind.LANG_TOGGLE, subOutputChar = '\u0002', isFn = true),
                        0.80f to VisualKeyBuilder(punctuationStyle.comma, "！", KeyKind.COMMA, subOutputChar = '！', isFn = false),
                        0.80f to VisualKeyBuilder("◀", "", KeyKind.CURSOR_LEFT, isFn = true),
                        2.50f to VisualKeyBuilder("空白", "", KeyKind.SPACE, isFn = false),
                        0.80f to VisualKeyBuilder("▶", "", KeyKind.CURSOR_RIGHT, isFn = true),
                        0.80f to VisualKeyBuilder("ー", "〜", KeyKind.CHAR, outputChar = '-', subOutputChar = '〜', isFn = false),
                        0.80f to VisualKeyBuilder(punctuationStyle.period, "？", KeyKind.PERIOD, subOutputChar = '？', isFn = false),
                        1.60f to VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true)
                    )
                )
            }

            Layer.SYM1 -> {
                val r0 = "1234567890"
                val r0Sub = "!@#\$%&*()-".let { it }
                addRowKeys(0, r0.indices.map { i ->
                    1f to VisualKeyBuilder(r0[i].toString(), r0Sub[i].toString(), KeyKind.CHAR, r0[i], r0Sub[i], false)
                })

                val r1 = "@#¥_&-+()/"
                val r1Sub = "「」【】〜・:=[]"
                addRowKeys(1, r1.indices.map { i ->
                    1f to VisualKeyBuilder(r1[i].toString(), r1Sub[i].toString(), KeyKind.CHAR, r1[i], r1Sub[i], false)
                })

                val r2 = ArrayList<Pair<Float, VisualKeyBuilder>>()
                r2.add(1.5f to VisualKeyBuilder("=\\<", "", KeyKind.LAYER_ALT, isFn = true))
                for (ch in "*\"':;!?") {
                    r2.add(1f to VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false))
                }
                r2.add(1.5f to VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true))
                addRowKeys(2, r2)

                addRowKeys(
                    3,
                    listOf(
                        1.15f to VisualKeyBuilder("ABC", "", KeyKind.LAYER_ABC, isFn = true),
                        1.15f to VisualKeyBuilder("テンキー", "", KeyKind.LAYER_NUMPAD, isFn = true),
                        0.80f to VisualKeyBuilder(",", "！", KeyKind.CHAR, ',', '！', isFn = false),
                        0.80f to VisualKeyBuilder("◀", "", KeyKind.CURSOR_LEFT, isFn = true),
                        2.70f to VisualKeyBuilder("スペース", "", KeyKind.SPACE, isFn = false),
                        0.80f to VisualKeyBuilder("▶", "", KeyKind.CURSOR_RIGHT, isFn = true),
                        0.80f to VisualKeyBuilder(".", "？", KeyKind.CHAR, '.', '？', isFn = false),
                        1.80f to VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true)
                    )
                )
            }

            Layer.SYM2 -> {
                val r0 = "「」（）【】〜・÷×"
                addRowKeys(0, r0.map { ch ->
                    1f to VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false)
                })

                val r1 = "^°={}\\~|√π"
                addRowKeys(1, r1.map { ch ->
                    1f to VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false)
                })

                val r2 = ArrayList<Pair<Float, VisualKeyBuilder>>()
                r2.add(1.5f to VisualKeyBuilder("?123", "", KeyKind.LAYER_SYM, isFn = true))
                for (ch in "%£€\$[]_") {
                    r2.add(1f to VisualKeyBuilder(ch.toString(), "", KeyKind.CHAR, ch, isFn = false))
                }
                r2.add(1.5f to VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true))
                addRowKeys(2, r2)

                addRowKeys(
                    3,
                    listOf(
                        1.15f to VisualKeyBuilder("ABC", "", KeyKind.LAYER_ABC, isFn = true),
                        1.15f to VisualKeyBuilder("テンキー", "", KeyKind.LAYER_NUMPAD, isFn = true),
                        0.80f to VisualKeyBuilder("<", "", KeyKind.CHAR, '<', isFn = false),
                        0.80f to VisualKeyBuilder("◀", "", KeyKind.CURSOR_LEFT, isFn = true),
                        2.70f to VisualKeyBuilder("スペース", "", KeyKind.SPACE, isFn = false),
                        0.80f to VisualKeyBuilder("▶", "", KeyKind.CURSOR_RIGHT, isFn = true),
                        0.80f to VisualKeyBuilder(">", "", KeyKind.CHAR, '>', isFn = false),
                        1.80f to VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true)
                    )
                )
            }

            Layer.NUMPAD -> {
                addRowKeys(
                    0,
                    listOf(
                        1.3f to VisualKeyBuilder("+", "", KeyKind.CHAR, '+', isFn = true),
                        2.3f to VisualKeyBuilder("7", "", KeyKind.CHAR, '7', isFn = false),
                        2.3f to VisualKeyBuilder("8", "", KeyKind.CHAR, '8', isFn = false),
                        2.3f to VisualKeyBuilder("9", "", KeyKind.CHAR, '9', isFn = false),
                        1.8f to VisualKeyBuilder("⌫", "", KeyKind.BACKSPACE, isFn = true)
                    )
                )
                addRowKeys(
                    1,
                    listOf(
                        1.3f to VisualKeyBuilder("-", "", KeyKind.CHAR, '-', isFn = true),
                        2.3f to VisualKeyBuilder("4", "", KeyKind.CHAR, '4', isFn = false),
                        2.3f to VisualKeyBuilder("5", "", KeyKind.CHAR, '5', isFn = false),
                        2.3f to VisualKeyBuilder("6", "", KeyKind.CHAR, '6', isFn = false),
                        1.8f to VisualKeyBuilder("/", "", KeyKind.CHAR, '/', isFn = true)
                    )
                )
                addRowKeys(
                    2,
                    listOf(
                        1.3f to VisualKeyBuilder("*", "", KeyKind.CHAR, '*', isFn = true),
                        2.3f to VisualKeyBuilder("1", "", KeyKind.CHAR, '1', isFn = false),
                        2.3f to VisualKeyBuilder("2", "", KeyKind.CHAR, '2', isFn = false),
                        2.3f to VisualKeyBuilder("3", "", KeyKind.CHAR, '3', isFn = false),
                        1.8f to VisualKeyBuilder(":", "", KeyKind.CHAR, ':', isFn = true)
                    )
                )
                addRowKeys(
                    3,
                    listOf(
                        1.25f to VisualKeyBuilder("ABC", "", KeyKind.LAYER_ABC, isFn = true),
                        1.10f to VisualKeyBuilder("?123", "", KeyKind.LAYER_SYM, isFn = true),
                        0.85f to VisualKeyBuilder("◀", "", KeyKind.CURSOR_LEFT, isFn = true),
                        2.05f to VisualKeyBuilder("0", "", KeyKind.CHAR, '0', isFn = false),
                        0.85f to VisualKeyBuilder("▶", "", KeyKind.CURSOR_RIGHT, isFn = true),
                        1.00f to VisualKeyBuilder(".", ",", KeyKind.CHAR, '.', ',', isFn = false),
                        1.10f to VisualKeyBuilder("=", "", KeyKind.CHAR, '=', isFn = true),
                        1.80f to VisualKeyBuilder("⏎", "", KeyKind.ENTER, isFn = true)
                    )
                )
            }
        }
    }

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
            val isPressed = !isGliding && (
                currentHoverKey === k ||
                    (downKey === k && (subLongPressActive || verticalFlickActive)) ||
                    (downKey === k && isSpaceSliding && k.kind == KeyKind.SPACE)
                )
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

            val onAccent = k.kind == KeyKind.ENTER || (k.kind == KeyKind.SHIFT && shiftState != ShiftState.OFF)
            paintText.color = if (onAccent) cAccentText else cText
            paintText.textSize = when {
                k.kind == KeyKind.SPACE -> sp(11.5f)
                k.kind == KeyKind.CURSOR_LEFT || k.kind == KeyKind.CURSOR_RIGHT -> sp(14.5f)
                k.label.length >= 3 -> sp(12f)
                k.label.length == 2 -> sp(13.5f)
                else -> sp(18.5f)
            }
            val fm = paintText.fontMetrics
            val textY = k.rect.centerY() - (fm.ascent + fm.descent) * 0.5f
            canvas.drawText(k.label, k.rect.centerX(), textY, paintText)

            if (showSubLabels && k.subLabel.isNotEmpty()) {
                paintSubText.color = cSubText
                paintSubText.textSize = sp(9f)
                canvas.drawText(
                    k.subLabel,
                    k.rect.right - dp(3.5f),
                    k.rect.top + dp(10.5f),
                    paintSubText
                )
            }
        }

        // Draw glide trail
        if (isGliding && glideTrail.size >= 2) {
            val count = glideTrail.size
            for (i in 1 until count) {
                val frac = i.toFloat() / (count - 1).toFloat()
                paintTrail.color = Color.argb(
                    (70 + 170 * frac).toInt().coerceIn(0, 255),
                    Color.red(cAccent),
                    Color.green(cAccent),
                    Color.blue(cAccent)
                )
                paintTrail.strokeWidth = dp(2.2f + 4.8f * frac)
                canvas.drawLine(glideTrail[i - 1].x, glideTrail[i - 1].y, glideTrail[i].x, glideTrail[i].y, paintTrail)
            }
            val head = glideTrail.last()
            paintTrailHead.color = cAccent
            canvas.drawCircle(head.x, head.y, dp(5.5f), paintTrailHead)
        }

        // Key preview badge on press, vertical flick, or long-press secondary character
        val dk = downKey
        if (!isGliding && dk != null && activePointerId != -1 &&
            (dk.kind == KeyKind.CHAR || ((subLongPressActive || verticalFlickActive) && dk.subOutputChar != '\u0000'))
        ) {
            val previewChar = if ((subLongPressActive || verticalFlickActive) && dk.subOutputChar != '\u0000') {
                dk.subLabel.ifEmpty { dk.subOutputChar.toString() }
            } else {
                dk.label
            }
            val popW = (dk.rect.width() * 1.15f).coerceAtLeast(dp(38f))
            val popH = (dk.rect.height() * 0.92f).coerceAtLeast(dp(40f))
            val cx = dk.rect.centerX().coerceIn(popW * 0.5f + dp(4f), width - popW * 0.5f - dp(4f))
            val idealTop = dk.rect.top - popH - dp(4f)
            val top = if (idealTop >= dp(2f)) idealTop else dk.rect.top + dp(2f)
            val popRect = RectF(cx - popW * 0.5f, top, cx + popW * 0.5f, top + popH)
            paintPopup.color = if (isEffectiveDark()) Color.parseColor("#2D333B") else Color.parseColor("#FFFFFF")
            canvas.drawRoundRect(popRect, dp(5f), dp(5f), paintPopup)
            paintBorder.color = cAccent
            canvas.drawRoundRect(popRect, dp(5f), dp(5f), paintBorder)

            paintText.color = cText
            paintText.textSize = if (previewChar.length >= 2) sp(14f) else sp(22f)
            val fm = paintText.fontMetrics
            canvas.drawText(previewChar, popRect.centerX(), popRect.centerY() - (fm.ascent + fm.descent) * 0.5f, paintText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val idx = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(bsRepeatRunnable)
                handler.removeCallbacks(cursorRepeatRunnable)
                handler.removeCallbacks(subLongPressRunnable)

                val x = event.getX(0)
                val y = event.getY(0)
                activePointerId = event.getPointerId(0)
                downX = x
                downY = y
                downTimeMs = event.eventTime
                lastX = x
                lastY = y
                isGliding = false
                isSpaceSliding = false
                spaceSlideAccumX = 0f
                backspaceLongPressed = false
                cursorLongPressed = false
                verticalFlickActive = false
                subLongPressActive = false
                glideTrail.clear()

                val hit = findKeyAt(x, y)
                downKey = hit
                currentHoverKey = hit
                if (hit != null) {
                    performHaptic()
                    if (hit.kind == KeyKind.BACKSPACE) {
                        handler.postDelayed(bsRepeatRunnable, 360L)
                    } else if (hit.kind == KeyKind.CURSOR_LEFT || hit.kind == KeyKind.CURSOR_RIGHT) {
                        handler.postDelayed(cursorRepeatRunnable, 320L)
                    } else {
                        if (hit.subOutputChar != '\u0000') {
                            handler.postDelayed(subLongPressRunnable, 220L)
                        }
                        if (glideEnabled && layer == Layer.QWERTY && isLetterCharKey(hit)) {
                            glideTrail.add(TouchPoint(x, y, event.eventTime))
                        }
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

                val totalDist = hypot((x - downX).toDouble(), (y - downY).toDouble()).toFloat()
                if (totalDist > keyWidthAvg * 0.45f) {
                    handler.removeCallbacks(subLongPressRunnable)
                }

                val hover = findKeyAt(x, y)
                val dxAbs = abs(x - downX)
                val absDy = abs(y - downY)
                val elapsed = event.eventTime - downTimeMs

                if (!isGliding && !subLongPressActive && dk != null && dk.subOutputChar != '\u0000') {
                    val flickThresh = keyHeightAvg * (0.36f / flickSensitivity)
                    verticalFlickActive = (
                        absDy > flickThresh &&
                            absDy > dxAbs * 1.45f &&
                            dxAbs < keyWidthAvg * 0.65f
                        )
                }

                if (!isGliding && !subLongPressActive && glideEnabled && layer == Layer.QWERTY && isLetterCharKey(dk)) {
                    glideTrail.add(TouchPoint(x, y, event.eventTime))
                    val enteredAnotherLetterKey = isLetterCharKey(hover) &&
                        hover?.outputChar?.lowercaseChar() != dk?.outputChar?.lowercaseChar()
                    val stillLooksLikeQuickVerticalFlick = verticalFlickActive &&
                        elapsed < 220L &&
                        totalDist < keyHeightAvg * 1.38f
                    if (!stillLooksLikeQuickVerticalFlick && totalDist > keyWidthAvg * 0.85f && enteredAnotherLetterKey) {
                        isGliding = true
                        verticalFlickActive = false
                        handler.removeCallbacks(subLongPressRunnable)
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

                if (!isGliding && !subLongPressActive) {
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
                handler.removeCallbacks(bsRepeatRunnable)
                handler.removeCallbacks(cursorRepeatRunnable)
                handler.removeCallbacks(subLongPressRunnable)
                if (activePointerId != -1 && !isGliding && !isSpaceSliding) {
                    val pIdx = event.findPointerIndex(activePointerId)
                    val endX = if (pIdx >= 0) event.getX(pIdx) else lastX
                    val endY = if (pIdx >= 0) event.getY(pIdx) else lastY
                    finishTapOrGesture(endX, endY, event.eventTime)
                }
                activePointerId = event.getPointerId(idx)
                downX = event.getX(idx)
                downY = event.getY(idx)
                downTimeMs = event.eventTime
                lastX = downX
                lastY = downY
                isGliding = false
                isSpaceSliding = false
                spaceSlideAccumX = 0f
                backspaceLongPressed = false
                cursorLongPressed = false
                verticalFlickActive = false
                subLongPressActive = false
                glideTrail.clear()
                val hit = findKeyAt(downX, downY)
                downKey = hit
                currentHoverKey = hit
                if (hit != null) {
                    performHaptic()
                    if (hit.kind == KeyKind.BACKSPACE) {
                        handler.postDelayed(bsRepeatRunnable, 360L)
                    } else if (hit.kind == KeyKind.CURSOR_LEFT || hit.kind == KeyKind.CURSOR_RIGHT) {
                        handler.postDelayed(cursorRepeatRunnable, 320L)
                    } else {
                        if (hit.subOutputChar != '\u0000') {
                            handler.postDelayed(subLongPressRunnable, 220L)
                        }
                        if (glideEnabled && layer == Layer.QWERTY && isLetterCharKey(hit)) {
                            glideTrail.add(TouchPoint(downX, downY, event.eventTime))
                        }
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(bsRepeatRunnable)
                handler.removeCallbacks(cursorRepeatRunnable)
                handler.removeCallbacks(subLongPressRunnable)
                val pIdx = event.findPointerIndex(activePointerId)
                val endX = if (pIdx >= 0) event.getX(pIdx) else lastX
                val endY = if (pIdx >= 0) event.getY(pIdx) else lastY
                if (action == MotionEvent.ACTION_UP) {
                    finishTapOrGesture(endX, endY, event.eventTime)
                }
                activePointerId = -1
                downKey = null
                currentHoverKey = null
                isGliding = false
                isSpaceSliding = false
                verticalFlickActive = false
                subLongPressActive = false
                glideTrail.clear()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun finishTapOrGesture(endX: Float, endY: Float, eventTime: Long) {
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
        if (dk?.kind == KeyKind.CURSOR_LEFT || dk?.kind == KeyKind.CURSOR_RIGHT) {
            if (!cursorLongPressed) {
                listener?.onMoveCursor(if (dk.kind == KeyKind.CURSOR_LEFT) -1 else 1)
            }
            return
        }

        // Long-press or vertical flick for secondary symbol/number/layer/emoji
        if (dk != null && dk.subOutputChar != '\u0000') {
            val dxAbs = abs(endX - downX)
            val absDy = abs(endY - downY)
            val flickThresh = keyHeightAvg * (0.36f / flickSensitivity)
            val isFlick = absDy > flickThresh && absDy > dxAbs * 1.45f && dxAbs < keyWidthAvg * 0.65f && (eventTime - downTimeMs) < 350L
            if (subLongPressActive || verticalFlickActive || isFlick) {
                when {
                    dk.kind == KeyKind.LAYER_SYM && dk.subOutputChar == '\u0001' -> {
                        layer = Layer.NUMPAD
                        rebuildLayout()
                        requestLayout()
                    }
                    dk.kind == KeyKind.LANG_TOGGLE && dk.subOutputChar == '\u0002' -> {
                        listener?.onToggleEmojiPanel()
                    }
                    else -> listener?.onCharacter(dk.subOutputChar, directCommit = (layer == Layer.NUMPAD))
                }
                return
            }
        }

        val target = currentHoverKey ?: dk ?: return
        when (target.kind) {
            KeyKind.CHAR -> {
                val out = target.outputChar
                if (out != '\u0000') {
                    listener?.onCharacter(out, directCommit = (layer == Layer.NUMPAD))
                    if (layer == Layer.QWERTY && shiftState == ShiftState.ONCE) {
                        shiftState = ShiftState.OFF
                        rebuildLayout()
                    } else if (autoReturnFromSymbols && (layer == Layer.SYM1 || layer == Layer.SYM2)) {
                        layer = Layer.QWERTY
                        rebuildLayout()
                        requestLayout()
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
                requestLayout()
            }
            KeyKind.LAYER_ALT -> {
                layer = Layer.SYM2
                rebuildLayout()
                requestLayout()
            }
            KeyKind.LAYER_NUMPAD -> {
                layer = Layer.NUMPAD
                rebuildLayout()
                requestLayout()
            }
            KeyKind.LAYER_ABC -> {
                layer = Layer.QWERTY
                rebuildLayout()
                requestLayout()
            }
            KeyKind.LANG_TOGGLE -> listener?.onToggleLanguage()
            KeyKind.SETTINGS -> listener?.onOpenSettings()
            KeyKind.EMOJI -> listener?.onToggleEmojiPanel()
            KeyKind.CURSOR_LEFT -> listener?.onMoveCursor(-1)
            KeyKind.CURSOR_RIGHT -> listener?.onMoveCursor(1)
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
