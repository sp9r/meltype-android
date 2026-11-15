package io.github.sp9r.meltype

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import kotlin.math.roundToInt

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
    private lateinit var clauseBar: LinearLayout
    private lateinit var clauseScroll: HorizontalScrollView
    private lateinit var clausePillRow: LinearLayout
    private lateinit var prevClauseBtn: TextView
    private lateinit var nextClauseBtn: TextView
    private lateinit var shrinkClauseBtn: TextView
    private lateinit var extendClauseBtn: TextView
    private lateinit var langSwitchBtn: TextView

    private lateinit var topBar: LinearLayout
    private lateinit var traceBadge: TextView
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var candidateRow: LinearLayout
    private lateinit var expandPanelBtn: TextView
    private lateinit var oneHandBtn: TextView
    private lateinit var settingsBtn: TextView

    private lateinit var dividerView: View
    private lateinit var keyboardView: QwertyKeyboardView
    private lateinit var expandedPanel: LinearLayout
    private lateinit var expandedScroll: ScrollView
    private lateinit var expandedContent: LinearLayout

    private var isExpandedPanelOpen: Boolean = false
    private var lastDark: Boolean = false

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

        // Clause focus & boundary adjustment bar (visible while composing)
        clauseBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(3), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(36)
            )
        }

        clauseScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        clausePillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clauseScroll.addView(clausePillRow)

        prevClauseBtn = makeRailButton("◀").apply {
            setOnClickListener { renderSnapshot(session.moveFocus(-1)) }
        }
        nextClauseBtn = makeRailButton("▶").apply {
            setOnClickListener { renderSnapshot(session.moveFocus(+1)) }
        }
        shrinkClauseBtn = makeRailButton("区-").apply {
            setOnClickListener {
                renderSnapshot(session.resizeFocusedClause(-1, getSurroundingTextBefore()))
            }
        }
        extendClauseBtn = makeRailButton("区+").apply {
            setOnClickListener {
                renderSnapshot(session.resizeFocusedClause(+1, getSurroundingTextBefore()))
            }
        }
        langSwitchBtn = makeRailButton("日⇔英").apply {
            setOnClickListener { onToggleLanguage() }
        }

        clauseBar.addView(clauseScroll)
        clauseBar.addView(prevClauseBtn)
        clauseBar.addView(nextClauseBtn)
        clauseBar.addView(shrinkClauseBtn)
        clauseBar.addView(extendClauseBtn)
        clauseBar.addView(langSwitchBtn)

        // Top Compact-Rail bar (trace + candidate/quick-symbol chips + expand/onehand/settings)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
            )
        }

        traceBadge = makeRailButton("").apply {
            visibility = View.GONE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
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

        expandPanelBtn = makeRailButton("▼").apply {
            setOnClickListener {
                isExpandedPanelOpen = !isExpandedPanelOpen
                syncExpandedPanelState(session.snapshot)
            }
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
        topBar.addView(candidateScroll)
        topBar.addView(expandPanelBtn)
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

        // Expandable overlay panel replacing keyboardView bounds for full candidate grid, script switches & quick height adjust
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250)
            )
        }
        expandedScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(10))
        }
        expandedScroll.addView(expandedContent)
        expandedPanel.addView(expandedScroll)

        rootContainer.addView(clauseBar)
        rootContainer.addView(topBar)
        rootContainer.addView(dividerView)
        rootContainer.addView(keyboardView)
        rootContainer.addView(expandedPanel)

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
        isExpandedPanelOpen = false
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
        isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            if (committed.isNotEmpty()) {
                currentInputConnection?.commitText(committed, 1)
            }
        }
        renderSnapshot(session.snapshot)
    }

    private fun isDarkTheme(): Boolean {
        return when (settings.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    private fun applySettingsToViews() {
        if (!::keyboardView.isInitialized) return
        keyboardView.glideEnabled = settings.glideEnabled
        keyboardView.hapticEnabled = settings.hapticFeedback
        keyboardView.showSubLabels = settings.showSubLabels
        keyboardView.showNumberRow = settings.showNumberRow
        keyboardView.autoReturnFromSymbols = settings.autoReturnFromSymbols
        keyboardView.flickSensitivity = settings.flickSensitivity
        keyboardView.themeMode = settings.themeMode
        keyboardView.oneHandedMode = settings.oneHandedMode
        keyboardView.heightScale = settings.keyboardHeightScale
        keyboardView.punctuationStyle = settings.punctuationStyle
        keyboardView.refreshAppearance()
        oneHandBtn.text = shortHandLabel(settings.oneHandedMode)

        val panelHeightPx = dp((keyboardView.currentBaseHeightDp() * settings.keyboardHeightScale).roundToInt().coerceIn(155, 420))
        expandedPanel.layoutParams = (expandedPanel.layoutParams as LinearLayout.LayoutParams).apply {
            height = panelHeightPx
        }

        val dark = isDarkTheme()
        lastDark = dark
        val barBg = if (dark) Color.parseColor("#181B20") else Color.parseColor("#F5F6F7")
        clauseBar.setBackgroundColor(barBg)
        topBar.setBackgroundColor(barBg)
        expandedPanel.setBackgroundColor(if (dark) Color.parseColor("#121417") else Color.parseColor("#EAECEF"))
        dividerView.setBackgroundColor(if (dark) Color.parseColor("#2E333C") else Color.parseColor("#DFE2E6"))
        styleRailButton(traceBadge, dark, subtle = true)
        styleRailButton(prevClauseBtn, dark, subtle = false)
        styleRailButton(nextClauseBtn, dark, subtle = false)
        styleRailButton(shrinkClauseBtn, dark, subtle = false)
        styleRailButton(extendClauseBtn, dark, subtle = false)
        styleRailButton(langSwitchBtn, dark, subtle = false)
        styleRailButton(expandPanelBtn, dark, subtle = false)
        styleRailButton(oneHandBtn, dark, subtle = false)
        styleRailButton(settingsBtn, dark, subtle = false)
    }

    private fun getSurroundingTextBefore(): String? {
        return currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()
    }

    // --- KeyboardActionListener implementation ---

    override fun onCharacter(ch: Char, directCommit: Boolean) {
        if (isExpandedPanelOpen) {
            isExpandedPanelOpen = false
        }
        if (isPasswordField) {
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }
        if (directCommit) {
            if (!session.isEmpty) {
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                currentInputConnection?.commitText(committed + ch, 1)
                renderSnapshot(session.snapshot)
            } else {
                currentInputConnection?.commitText(ch.toString(), 1)
            }
            return
        }
        val isPureStandaloneSymbol = session.isEmpty &&
            ch !in 'a'..'z' &&
            ch !in 'A'..'Z' &&
            !ch.isDigit() &&
            ch != '/'
        if (isPureStandaloneSymbol) {
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }
        val snap = session.appendChar(ch, getSurroundingTextBefore())
        renderSnapshot(snap)
    }

    private fun commitQuickSymbol(sym: String) {
        if (isPasswordField) {
            currentInputConnection?.commitText(sym, 1)
            return
        }
        if (sym == "ー" && !session.isEmpty) {
            val snap = session.appendChar('-', getSurroundingTextBefore())
            renderSnapshot(snap)
            return
        }
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            currentInputConnection?.commitText(committed + sym, 1)
            renderSnapshot(session.snapshot)
        } else {
            currentInputConnection?.commitText(sym, 1)
        }
    }

    override fun onPunctuation(isPeriod: Boolean) {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
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
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val snap = session.backspace(getSurroundingTextBefore())
            renderSnapshot(snap)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onBackspaceClearAll() {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
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
        val lastIsEnglish = snap.clauses.lastOrNull()?.isEnglish == true
        val alreadyHasSpace = snap.rawInput.contains(' ')
        if (lastIsEnglish || alreadyHasSpace) {
            val next = session.appendChar(' ', getSurroundingTextBefore())
            renderSnapshot(next)
            return
        }
        val currentClause = snap.clauses.getOrNull(snap.focusedClauseIndex)
        if (currentClause != null && currentClause.candidates.size > 1) {
            val curIdx = currentClause.candidates.indexOf(currentClause.text)
            val nextIdx = if (curIdx < 0) 0 else (curIdx + 1) % currentClause.candidates.size
            val nextCand = CandidateItem(
                text = currentClause.candidates[nextIdx],
                kind = CandidateKind.CONVERSION,
                targetClauseIndex = snap.focusedClauseIndex
            )
            // Keep focus on the same clause while cycling candidates via Spacebar
            renderSnapshot(session.selectCandidate(nextCand, getSurroundingTextBefore(), advanceFocus = false))
        } else {
            val next = session.appendChar(' ', getSurroundingTextBefore())
            renderSnapshot(next)
        }
    }

    override fun onEnter() {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
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
        val snap = session.snapshot
        val inEnglishSentence = snap.isComposing && snap.rawInput.contains(' ') &&
            snap.clauses.lastOrNull()?.isEnglish == true
        val cands = decoder.decode(norm, maxResults = 4, preferJapanese = !inEnglishSentence)
        if (cands.isEmpty()) return
        val preview = cands.joinToString(" | ") { c ->
            if (!c.isEnglish && !c.kanaHint.isNullOrEmpty()) "${c.kanaHint}" else c.word
        }
        showTransientTrace(preview)
    }

    override fun onGlideCommit(points: List<TouchPoint>, letterBounds: RectF) {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (isPasswordField) return
        val decoder = glideDecoder ?: GlideDecoder(dictionaries).also { glideDecoder = it }
        val norm = KeyLayoutGeometry.normalizeFromView(points, letterBounds)
        val snap = session.snapshot
        val inEnglishSentence = snap.isComposing && snap.rawInput.contains(' ') &&
            snap.clauses.lastOrNull()?.isEnglish == true
        val cands = decoder.decode(norm, maxResults = 8, preferJapanese = !inEnglishSentence)
        if (cands.isEmpty()) return
        val top = cands.first()
        val alts = cands.map { it.word to it.isEnglish }
        val updated = session.appendGlideResult(
            primaryWord = top.word,
            isPrimaryEnglish = top.isEnglish,
            alternatives = alts,
            surroundingContext = getSurroundingTextBefore()
        )
        renderSnapshot(updated)
    }

    private fun showTransientTrace(text: String) {
        if (!::traceBadge.isInitialized) return
        traceBadge.visibility = View.VISIBLE
        traceBadge.text = text
    }

    private fun renderSnapshot(snap: CompositionSnapshot) {
        val dark = isDarkTheme()
        val accent = if (dark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")
        val focusBgSpan = if (dark) Color.argb(90, 92, 138, 196) else Color.argb(65, 74, 111, 158)

        val ic = currentInputConnection
        if (snap.isComposing) {
            val spanned = SpannableString(snap.previewText)
            var offset = 0
            for ((idx, clause) in snap.clauses.withIndex()) {
                val end = (offset + clause.text.length).coerceAtMost(spanned.length)
                if (end > offset) {
                    spanned.setSpan(UnderlineSpan(), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (idx == snap.focusedClauseIndex) {
                        spanned.setSpan(BackgroundColorSpan(focusBgSpan), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                offset = end
            }
            ic?.setComposingText(spanned, 1)

            traceBadge.visibility = View.VISIBLE
            traceBadge.text = snap.romajiTrace

            clauseBar.visibility = View.VISIBLE
            val multiClause = snap.clauses.size > 1
            prevClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            nextClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            shrinkClauseBtn.visibility = if (snap.canShrinkClause) View.VISIBLE else View.GONE
            extendClauseBtn.visibility = if (snap.canExtendClause) View.VISIBLE else View.GONE
            renderClausePills(snap, dark, accent)
        } else {
            ic?.finishComposingText()
            traceBadge.visibility = View.GONE
            clauseBar.visibility = View.GONE
        }

        renderCandidateRow(snap, dark, accent)
        syncExpandedPanelState(snap)
    }

    private fun renderClausePills(snap: CompositionSnapshot, dark: Boolean, accent: Int) {
        clausePillRow.removeAllViews()
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#D5DAE0")
        val bgNormal = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val bgFocus = if (dark) Color.parseColor("#2A3B52") else Color.parseColor("#E3EDF8")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")

        for ((idx, clause) in snap.clauses.withIndex()) {
            val focused = idx == snap.focusedClauseIndex
            val pill = TextView(this).apply {
                text = clause.text
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(textCol)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    setColor(if (focused) bgFocus else bgNormal)
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(if (focused) 2 else 1), if (focused) accent else border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(28)
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    renderSnapshot(session.focusClause(idx))
                }
            }
            clausePillRow.addView(pill)
        }
    }

    private fun renderCandidateRow(snap: CompositionSnapshot, dark: Boolean, accent: Int) {
        candidateRow.removeAllViews()
        val chipBg = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val chipAltBg = if (dark) Color.parseColor("#1D232B") else Color.parseColor("#EDF2F7")
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#DFE2E6")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val subCol = if (dark) Color.parseColor("#9BA1A8") else Color.parseColor("#737A84")

        if (!snap.isComposing && snap.candidates.isEmpty()) {
            // Quick-access Japanese symbols & prolonged-sound bar when idle
            val quickSymbols = listOf("ー", "！", "？", "〜", "「", "」", "（", "）", "・", "／", "：", "＠", "…")
            for (sym in quickSymbols) {
                val btn = TextView(this).apply {
                    text = sym
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(textCol)
                    setPadding(dp(11), dp(4), dp(11), dp(4))
                    background = GradientDrawable().apply {
                        setColor(chipBg)
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), border)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(33)
                    ).apply { marginEnd = dp(4) }
                    setOnClickListener { commitQuickSymbol(sym) }
                }
                candidateRow.addView(btn)
            }
            return
        }

        val focusedText = snap.clauses.getOrNull(snap.focusedClauseIndex)?.text
        for ((idx, item) in snap.candidates.withIndex()) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(3), dp(10), dp(3))
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
                    val wasLast = snap.focusedClauseIndex >= snap.clauses.lastIndex
                    val nextSnap = session.selectCandidate(
                        item,
                        getSurroundingTextBefore(),
                        advanceFocus = true
                    )
                    if (isExpandedPanelOpen && (item.kind == CandidateKind.GLIDE_ALT || wasLast || snap.clauses.size <= 1)) {
                        isExpandedPanelOpen = false
                    }
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

    private fun syncExpandedPanelState(snap: CompositionSnapshot) {
        expandPanelBtn.text = if (isExpandedPanelOpen) "▲" else "▼"
        if (!isExpandedPanelOpen) {
            expandedPanel.visibility = View.GONE
            keyboardView.visibility = View.VISIBLE
            return
        }
        keyboardView.visibility = View.GONE
        expandedPanel.visibility = View.VISIBLE
        populateExpandedPanel(snap)
    }

    private fun populateExpandedPanel(snap: CompositionSnapshot) {
        expandedContent.removeAllViews()
        val dark = isDarkTheme()
        val accent = if (dark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")
        val cardBg = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#D5DAE0")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val mutedCol = if (dark) Color.parseColor("#9BA1A8") else Color.parseColor("#737A84")

        // Header action row
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val headerTitle = TextView(this).apply {
            text = if (snap.isComposing) {
                val fc = snap.clauses.getOrNull(snap.focusedClauseIndex)
                "注目文節: ${fc?.text ?: ""} (${fc?.reading ?: ""})"
            } else {
                "クイック設定（高さ・数字段）"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(textCol)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(headerTitle)

        if (snap.isComposing) {
            val commitBtn = makePanelButton("確定 ⏎", accent, Color.WHITE, accent) {
                isExpandedPanelOpen = false
                onEnter()
            }
            headerRow.addView(commitBtn)
        }
        val closeBtn = makePanelButton("▲ 閉じる", cardBg, textCol, border) {
            isExpandedPanelOpen = false
            syncExpandedPanelState(session.snapshot)
        }
        closeBtn.layoutParams = (closeBtn.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(6) }
        headerRow.addView(closeBtn)
        expandedContent.addView(headerRow)

        // Quick height adjustment strip inside keyboard
        val heightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val pct = (settings.keyboardHeightScale * 100f).roundToInt()
        val heightLabel = TextView(this).apply {
            text = "高さ ${pct}%"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(mutedCol)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heightRow.addView(heightLabel)

        fun adjustHeight(delta: Float?, exact: Float? = null) {
            val next = (exact ?: (settings.keyboardHeightScale + (delta ?: 0f))).coerceIn(0.65f, 1.55f)
            settings.keyboardHeightScale = next
            applySettingsToViews()
            populateExpandedPanel(session.snapshot)
        }

        val hSteps = listOf(
            "-5%" to { adjustHeight(-0.05f) },
            "-2%" to { adjustHeight(-0.02f) },
            "100%" to { adjustHeight(null, 1.0f) },
            "+2%" to { adjustHeight(+0.02f) },
            "+5%" to { adjustHeight(+0.05f) }
        )
        for ((lbl, fn) in hSteps) {
            val b = makePanelButton(lbl, cardBg, textCol, border) { fn() }
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginStart = dp(4)
            }
            heightRow.addView(b)
        }
        expandedContent.addView(heightRow)

        // Number-row quick toggle when not composing
        if (!snap.isComposing) {
            val numRowToggle = makePanelButton(
                "数字段（1〜0）: ${if (settings.showNumberRow) "ON" else "OFF"}（タップで切替）",
                cardBg,
                textCol,
                accent
            ) {
                settings.showNumberRow = !settings.showNumberRow
                applySettingsToViews()
                populateExpandedPanel(session.snapshot)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
            }
            expandedContent.addView(numRowToggle)
            return
        }

        // Script variants (Hiragana / Katakana / Half-width Katakana / Lower / Title / Upper)
        if (snap.scriptVariants.isNotEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "字種一発変換"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(mutedCol)
                setPadding(0, dp(2), 0, dp(4))
            })
            val svScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, 0, 0, dp(8))
            }
            val svRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((tag, value) in snap.scriptVariants) {
                val chip = makePanelButton("$tag: $value", cardBg, textCol, accent) {
                    val wasLast = snap.focusedClauseIndex >= snap.clauses.lastIndex
                    val nextSnap = session.applyFocusedClauseSurface(
                        value,
                        getSurroundingTextBefore(),
                        advanceFocus = true
                    )
                    if (wasLast || snap.clauses.size <= 1) {
                        isExpandedPanelOpen = false
                    }
                    renderSnapshot(nextSnap)
                }
                chip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                    marginEnd = dp(5)
                }
                svRow.addView(chip)
            }
            svScroll.addView(svRow)
            expandedContent.addView(svScroll)
        }

        // 4-column candidate grid
        val convCands = snap.candidates
        if (convCands.isNotEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "変換候補一覧"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(mutedCol)
                setPadding(0, 0, 0, dp(4))
            })
            val cols = 4
            var r = 0
            while (r < convCands.size) {
                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(5) }
                }
                for (c in 0 until cols) {
                    val item = convCands.getOrNull(r + c)
                    if (item == null) {
                        rowLayout.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                        })
                    } else {
                        val label = if (item.kind == CandidateKind.GLIDE_ALT && item.subtitle.isNotEmpty()) {
                            item.subtitle
                        } else if (item.subtitle.isNotEmpty()) {
                            "${item.text} (${item.subtitle})"
                        } else {
                            item.text
                        }
                        val btn = makePanelButton(
                            label,
                            cardBg,
                            textCol,
                            if (item.kind == CandidateKind.GLIDE_ALT) accent else border
                        ) {
                            val wasLast = snap.focusedClauseIndex >= snap.clauses.lastIndex
                            val nextSnap = session.selectCandidate(
                                item,
                                getSurroundingTextBefore(),
                                advanceFocus = true
                            )
                            if (item.kind == CandidateKind.GLIDE_ALT || wasLast || snap.clauses.size <= 1) {
                                isExpandedPanelOpen = false
                            }
                            renderSnapshot(nextSnap)
                        }
                        btn.layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            if (c < cols - 1) marginEnd = dp(5)
                        }
                        rowLayout.addView(btn)
                    }
                }
                expandedContent.addView(rowLayout)
                r += cols
            }
        }
    }

    private fun makePanelButton(
        label: String,
        bgColor: Int,
        textColor: Int,
        borderColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(textColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(5).toFloat()
                setStroke(dp(1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32))
            setOnClickListener { onClick() }
        }
    }

    private fun makeRailButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(31)
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
