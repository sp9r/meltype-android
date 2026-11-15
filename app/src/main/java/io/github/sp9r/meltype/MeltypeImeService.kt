package io.github.sp9r.meltype

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.AssetDictionarySource
import io.github.sp9r.meltype.core.CandidateItem
import io.github.sp9r.meltype.core.CandidateKind
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.CompositionSnapshot
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.MozcRuntime
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import io.github.sp9r.meltype.glide.TouchPoint
import io.github.sp9r.meltype.ui.KeyboardActionListener
import io.github.sp9r.meltype.ui.QwertyKeyboardView
import java.util.concurrent.Executors

/**
 * Android InputMethodService for Meltype (QWERTY + dual-language glide typing + live JP/EN conversion).
 */
class MeltypeImeService : InputMethodService(), KeyboardActionListener {

    private lateinit var settings: MeltypeSettings
    private lateinit var dictionaries: MeltypeDictionaries
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var converter: AndroidMozcConverter
    private lateinit var session: CompositionSession

    @Volatile
    private var glideDecoder: GlideDecoder? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private lateinit var rootContainer: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var traceBadge: TextView
    private lateinit var prevClauseBtn: TextView
    private lateinit var nextClauseBtn: TextView
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var candidateRow: LinearLayout
    private lateinit var langSwitchBtn: TextView
    private lateinit var oneHandBtn: TextView
    private lateinit var settingsBtn: TextView
    private lateinit var keyboardView: QwertyKeyboardView
    private lateinit var dividerView: View

    override fun onCreate() {
        super.onCreate()
        settings = MeltypeSettings(this)
        dictionaries = MeltypeDictionaries(AssetDictionarySource(this))
        scoreEngine = ScoreEngine(dictionaries)
        converter = AndroidMozcConverter(
            dictionaries = dictionaries,
            userDictProvider = { settings.getUserDictionary().associate { it.reading to it.word } },
            canLearn = { settings.learningEnabled }
        )
        session = CompositionSession(dictionaries, scoreEngine, converter)

        // Initialize Mozc native runtime + full Glide lexicon off the main thread
        bgExecutor.execute {
            MozcRuntime.ensureInitialized(applicationContext)
            glideDecoder = GlideDecoder(dictionaries)
        }
    }

    override fun onCreateInputView(): View {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Top Compact-Rail bar (raw trace + clause arrows + scrollable candidates +日/英+設定)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        }

        traceBadge = makeRailButton("").apply {
            visibility = View.GONE
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        prevClauseBtn = makeRailButton("<").apply {
            visibility = View.GONE
            setOnClickListener {
                val snap = session.moveFocus(-1)
                renderSnapshot(snap)
            }
        }

        nextClauseBtn = makeRailButton(">").apply {
            visibility = View.GONE
            setOnClickListener {
                val snap = session.moveFocus(+1)
                renderSnapshot(snap)
            }
        }

        candidateScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidateScroll.addView(candidateRow)

        langSwitchBtn = makeRailButton("日⇔英").apply {
            setOnClickListener { onToggleLanguage() }
        }

        oneHandBtn = makeRailButton(shortHandLabel(settings.oneHandedMode)).apply {
            setOnClickListener {
                val next = when (settings.oneHandedMode) {
                    OneHandedMode.FULL -> OneHandedMode.RIGHT
                    OneHandedMode.RIGHT -> OneHandedMode.LEFT
                    OneHandedMode.LEFT -> OneHandedMode.FULL
                }
                settings.oneHandedMode = next
                keyboardView.oneHandedMode = next
                oneHandBtn.text = shortHandLabel(next)
            }
        }

        settingsBtn = makeRailButton("⚙").apply {
            setOnClickListener { onOpenSettings() }
        }

        topBar.addView(traceBadge)
        topBar.addView(prevClauseBtn)
        topBar.addView(nextClauseBtn)
        topBar.addView(candidateScroll)
        topBar.addView(langSwitchBtn)
        topBar.addView(oneHandBtn)
        topBar.addView(settingsBtn)

        dividerView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        }

        keyboardView = QwertyKeyboardView(this).apply {
            listener = this@MeltypeImeService
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        rootContainer.addView(topBar)
        rootContainer.addView(dividerView)
        rootContainer.addView(keyboardView)

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        applySettingsToViews()
        renderSnapshot(session.snapshot)
        return rootContainer
    }

    private var isPasswordField: Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPasswordField = isPasswordInputType(attribute)
        if (!restarting && ::session.isInitialized) {
            session.clearAll()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isPasswordField = isPasswordInputType(info)
        applySettingsToViews()
        renderSnapshot(session.snapshot)
    }

    private fun isPasswordInputType(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and android.text.InputType.TYPE_MASK_CLASS
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_TEXT ->
                variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER ->
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            if (committed.isNotEmpty()) {
                currentInputConnection?.commitText(committed, 1)
            }
        }
        renderSnapshot(session.snapshot)
    }

    private fun applySettingsToViews() {
        if (!::keyboardView.isInitialized) return
        keyboardView.glideEnabled = settings.glideEnabled
        keyboardView.hapticEnabled = settings.hapticFeedback
        keyboardView.showSubLabels = settings.showSubLabels
        keyboardView.themeMode = settings.themeMode
        keyboardView.oneHandedMode = settings.oneHandedMode
        keyboardView.heightScale = settings.keyboardHeightScale
        keyboardView.punctuationStyle = settings.punctuationStyle
        keyboardView.refreshAppearance()
        oneHandBtn.text = shortHandLabel(settings.oneHandedMode)

        val dark = when (settings.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        topBar.setBackgroundColor(if (dark) Color.parseColor("#181B20") else Color.parseColor("#F5F6F7"))
        dividerView.setBackgroundColor(if (dark) Color.parseColor("#2E333C") else Color.parseColor("#DFE2E6"))
        styleRailButton(traceBadge, dark, subtle = true)
        styleRailButton(prevClauseBtn, dark, subtle = false)
        styleRailButton(nextClauseBtn, dark, subtle = false)
        styleRailButton(langSwitchBtn, dark, subtle = false)
        styleRailButton(oneHandBtn, dark, subtle = false)
        styleRailButton(settingsBtn, dark, subtle = false)
    }

    private fun getSurroundingTextBefore(): String? {
        return currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()
    }

    // --- KeyboardActionListener implementation ---

    override fun onCharacter(ch: Char) {
        if (isPasswordField) {
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }
        val snap = session.appendChar(ch, getSurroundingTextBefore())
        renderSnapshot(snap)
    }

    override fun onPunctuation(isPeriod: Boolean) {
        if (isPasswordField) {
            currentInputConnection?.commitText(if (isPeriod) "." else ",", 1)
            return
        }
        val snap = session.snapshot
        val lastIsEnglish = snap.clauses.lastOrNull()?.isEnglish == true
        val punct = when {
            lastIsEnglish && settings.punctuationStyle != PunctuationStyle.HALF ->
                if (isPeriod) "." else ","
            else ->
                if (isPeriod) settings.punctuationStyle.period else settings.punctuationStyle.comma
        }
        if (snap.isComposing) {
            if (settings.autoCommitOnPunctuation && !lastIsEnglish) {
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                currentInputConnection?.commitText(committed + punct, 1)
                renderSnapshot(session.snapshot)
            } else {
                val updated = session.appendText(punct, getSurroundingTextBefore())
                renderSnapshot(updated)
            }
        } else {
            currentInputConnection?.commitText(punct, 1)
        }
    }

    override fun onBackspace() {
        if (!session.isEmpty) {
            val snap = session.backspace(getSurroundingTextBefore())
            renderSnapshot(snap)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onBackspaceClearAll() {
        if (!session.isEmpty) {
            val snap = session.clearAll()
            renderSnapshot(snap)
            currentInputConnection?.finishComposingText()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onSpace() {
        val snap = session.snapshot
        if (!snap.isComposing) {
            currentInputConnection?.commitText(" ", 1)
            return
        }
        // If user is writing an English sentence or English token, keep composing space so multi-word English sentences work seamlessly
        val lastIsEnglish = snap.clauses.lastOrNull()?.isEnglish == true
        val alreadyHasSpace = snap.rawInput.contains(' ')
        if (lastIsEnglish || alreadyHasSpace) {
            val next = session.appendChar(' ', getSurroundingTextBefore())
            renderSnapshot(next)
            return
        }
        // Otherwise cycle candidate on current clause, or advance clause if at start
        val currentClause = snap.clauses.getOrNull(snap.focusedClauseIndex)
        if (currentClause != null && currentClause.candidates.size > 1) {
            val curIdx = currentClause.candidates.indexOf(currentClause.text)
            val nextIdx = if (curIdx < 0) 0 else (curIdx + 1) % currentClause.candidates.size
            val nextCand = CandidateItem(
                text = currentClause.candidates[nextIdx],
                kind = CandidateKind.CONVERSION,
                targetClauseIndex = snap.focusedClauseIndex
            )
            renderSnapshot(session.selectCandidate(nextCand, getSurroundingTextBefore()))
        } else {
            val next = session.appendChar(' ', getSurroundingTextBefore())
            renderSnapshot(next)
        }
    }

    override fun onEnter() {
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            currentInputConnection?.commitText(committed, 1)
            renderSnapshot(session.snapshot)
        } else {
            if (!sendDefaultEditorAction(true)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    override fun onMoveCursor(delta: Int) {
        if (!session.isEmpty) {
            renderSnapshot(session.moveFocus(delta))
        } else {
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            sendDownUpKeyEvents(code)
        }
    }

    override fun onToggleLanguage() {
        if (!session.isEmpty) {
            renderSnapshot(session.toggleFocusedSegmentLanguage(getSurroundingTextBefore()))
        }
    }

    override fun onOpenSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onGlidePreview(points: List<TouchPoint>, letterBounds: RectF) {
        if (isPasswordField) return
        val decoder = glideDecoder ?: return
        val norm = KeyLayoutGeometry.normalizeFromView(points, letterBounds)
        val cands = decoder.decode(norm, maxResults = 4)
        if (cands.isEmpty()) return
        val preview = cands.joinToString("  |  ") { c ->
            if (c.isEnglish) c.word else "${c.word}"
        }
        showTransientTrace("Glide: $preview")
    }

    override fun onGlideCommit(points: List<TouchPoint>, letterBounds: RectF) {
        if (isPasswordField) return
        val decoder = glideDecoder ?: GlideDecoder(dictionaries).also { glideDecoder = it }
        val norm = KeyLayoutGeometry.normalizeFromView(points, letterBounds)
        val cands = decoder.decode(norm, maxResults = 8)
        if (cands.isEmpty()) return
        val top = cands.first()
        val alts = cands.map { it.word to it.isEnglish }
        val snap = session.appendGlideResult(
            primaryWord = top.word,
            isPrimaryEnglish = top.isEnglish,
            alternatives = alts,
            surroundingContext = getSurroundingTextBefore()
        )
        renderSnapshot(snap)
    }

    private fun showTransientTrace(text: String) {
        if (!::traceBadge.isInitialized) return
        traceBadge.visibility = View.VISIBLE
        traceBadge.text = text
    }

    private fun renderSnapshot(snap: CompositionSnapshot) {
        val ic = currentInputConnection
        if (snap.isComposing) {
            val spanned = SpannableString(snap.previewText).apply {
                setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ic?.setComposingText(spanned, 1)
            traceBadge.visibility = View.VISIBLE
            traceBadge.text = snap.romajiTrace
            val multiClause = snap.clauses.size > 1
            prevClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            nextClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            langSwitchBtn.visibility = View.VISIBLE
        } else {
            ic?.finishComposingText()
            traceBadge.visibility = View.GONE
            prevClauseBtn.visibility = View.GONE
            nextClauseBtn.visibility = View.GONE
            langSwitchBtn.visibility = View.GONE
        }

        // Rebuild candidate chips
        candidateRow.removeAllViews()
        val dark = when (settings.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        if (snap.candidates.isEmpty() && !snap.isComposing) {
            val hint = TextView(this).apply {
                text = "QWERTY タップ / グライド入力で日英自動変換"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (dark) Color.parseColor("#8D95A0") else Color.parseColor("#737A84"))
                setPadding(dp(8), 0, dp(8), 0)
            }
            candidateRow.addView(hint)
            return
        }

        val accent = if (dark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")
        val chipBg = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val chipAltBg = if (dark) Color.parseColor("#1D232B") else Color.parseColor("#EDF2F7")
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#DFE2E6")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val subCol = if (dark) Color.parseColor("#9BA1A8") else Color.parseColor("#737A84")

        val focusedText = snap.clauses.getOrNull(snap.focusedClauseIndex)?.text
        for ((idx, item) in snap.candidates.withIndex()) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
                val isSelected = item.kind == CandidateKind.CONVERSION && item.text == focusedText && idx == 0
                val isGlide = item.kind == CandidateKind.GLIDE_ALT
                background = GradientDrawable().apply {
                    setColor(if (isGlide) chipAltBg else chipBg)
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), if (isSelected || isGlide) accent else border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(34)
                ).apply {
                    marginEnd = dp(5)
                }
                setOnClickListener {
                    val nextSnap = session.selectCandidate(item, getSurroundingTextBefore())
                    renderSnapshot(nextSnap)
                }
            }

            val title = TextView(this).apply {
                text = if (item.kind == CandidateKind.GLIDE_ALT && item.subtitle.isNotEmpty()) {
                    item.subtitle
                } else {
                    item.text
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textCol)
                maxLines = 1
            }
            chip.addView(title)

            if (item.subtitle.isNotEmpty() && item.kind != CandidateKind.GLIDE_ALT) {
                val sub = TextView(this).apply {
                    text = item.subtitle
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                    setTextColor(subCol)
                    maxLines = 1
                }
                chip.addView(sub)
            }
            candidateRow.addView(chip)
        }
    }

    private fun makeRailButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
    }

    private fun styleRailButton(tv: TextView, dark: Boolean, subtle: Boolean) {
        val bg = when {
            subtle && dark -> Color.parseColor("#1D2228")
            subtle -> Color.parseColor("#ECEFF3")
            dark -> Color.parseColor("#23272D")
            else -> Color.parseColor("#FFFFFF")
        }
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#DFE2E6")
        tv.setTextColor(if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328"))
        tv.background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(5).toFloat()
            setStroke(dp(1), border)
        }
    }

    private fun shortHandLabel(mode: OneHandedMode): String = when (mode) {
        OneHandedMode.FULL -> "全幅"
        OneHandedMode.LEFT -> "左"
        OneHandedMode.RIGHT -> "右"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
