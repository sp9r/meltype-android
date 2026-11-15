package io.github.sp9r.meltype

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
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
import androidx.core.view.WindowCompat
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Android InputMethodService for Meltype (QWERTY + dual-language glide typing + live JP/EN conversion).
 */
class MeltypeImeService : InputMethodService(), KeyboardActionListener {

    private enum class ExpandedTab(val label: String) {
        CANDIDATES("候補・設定"),
        EMOJI("😀 絵文字"),
        KAOMOJI("(^^) 顔文字"),
        TEMPLATES("📋 定型/履歴"),
        EDIT("✥ 編集")
    }

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
    private lateinit var emojiBarBtn: TextView
    private lateinit var expandPanelBtn: TextView
    private lateinit var oneHandBtn: TextView
    private lateinit var settingsBtn: TextView

    private lateinit var dividerView: View
    private lateinit var keyboardView: QwertyKeyboardView
    private lateinit var expandedPanel: LinearLayout
    private lateinit var expandedScroll: ScrollView
    private lateinit var expandedContent: LinearLayout

    private var isExpandedPanelOpen: Boolean = false
    private var expandedTab: ExpandedTab = ExpandedTab.CANDIDATES
    private var selectedEmojiCategory: Int = 0
    private var lastUndoRawInput: String? = null
    private var lastUndoCommittedText: String? = null
    private var lastDark: Boolean = false

    override fun onCreate() {
        super.onCreate()
        settings = MeltypeSettings(this)
        dictionaries = MeltypeDictionaries(AssetDictionarySource(this))
        scoreEngine = ScoreEngine(dictionaries, typoCorrectionEnabled = { settings.typoCorrectionEnabled })
        converter = AndroidMozcConverter(
            dictionaries = dictionaries,
            userDictProvider = { settings.getUserDictionary().associate { it.reading to it.word } },
            canLearn = { settings.learningEnabled }
        )
        session = CompositionSession(
            dictionaries = dictionaries,
            scoreEngine = scoreEngine,
            converter = converter,
            typoCorrectionEnabled = { settings.typoCorrectionEnabled }
        )

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

        emojiBarBtn = makeRailButton("☺").apply {
            setOnClickListener { onToggleEmojiPanel() }
        }

        expandPanelBtn = makeRailButton("▼").apply {
            setOnClickListener {
                if (!isExpandedPanelOpen) {
                    expandedTab = ExpandedTab.CANDIDATES
                    isExpandedPanelOpen = true
                } else {
                    isExpandedPanelOpen = false
                }
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
        topBar.addView(emojiBarBtn)
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
            if (::settings.isInitialized) {
                val dark = isDarkTheme()
                val trayBg = trayBackgroundColor(dark)
                v.post { syncImeWindowBar(dark, trayBg) }
            }
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
            clearUndoCommit()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isPasswordField = isPasswordInputType(info)
        isExpandedPanelOpen = false
        expandedTab = ExpandedTab.CANDIDATES
        captureClipboardText()
        applySettingsToViews()
        renderSnapshot(session.snapshot)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (::settings.isInitialized) {
            val dark = isDarkTheme()
            syncImeWindowBar(dark, trayBackgroundColor(dark))
        }
    }

    private fun captureClipboardText() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount > 0) {
                val txt = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!txt.isNullOrEmpty() && txt.length <= 500) {
                    settings.addClipboardItem(txt)
                }
            }
        } catch (_: Throwable) {
        }
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

        val panelHeightPx = dp((keyboardView.currentBaseHeightDp() * settings.keyboardHeightScale).roundToInt().coerceIn(200, 420))
        expandedPanel.layoutParams = (expandedPanel.layoutParams as LinearLayout.LayoutParams).apply {
            height = panelHeightPx
        }

        val dark = isDarkTheme()
        lastDark = dark
        val barBg = if (dark) Color.parseColor("#181B20") else Color.parseColor("#F5F6F7")
        val trayBg = trayBackgroundColor(dark)
        if (::rootContainer.isInitialized) {
            rootContainer.setBackgroundColor(trayBg)
        }
        clauseBar.setBackgroundColor(barBg)
        topBar.setBackgroundColor(barBg)
        expandedPanel.setBackgroundColor(trayBg)
        dividerView.setBackgroundColor(if (dark) Color.parseColor("#2E333C") else Color.parseColor("#DFE2E6"))
        syncImeWindowBar(dark, trayBg)
        styleRailButton(traceBadge, dark, subtle = true)
        styleRailButton(prevClauseBtn, dark, subtle = false)
        styleRailButton(nextClauseBtn, dark, subtle = false)
        styleRailButton(shrinkClauseBtn, dark, subtle = false)
        styleRailButton(extendClauseBtn, dark, subtle = false)
        styleRailButton(langSwitchBtn, dark, subtle = false)
        styleRailButton(emojiBarBtn, dark, subtle = false)
        styleRailButton(expandPanelBtn, dark, subtle = false)
        styleRailButton(oneHandBtn, dark, subtle = false)
        styleRailButton(settingsBtn, dark, subtle = false)
    }

    private fun trayBackgroundColor(dark: Boolean): Int =
        if (dark) Color.parseColor("#121417") else Color.parseColor("#EAECEF")

    /** Matches the IME window navigation-bar surface and icon contrast to the active tray theme. */
    private fun syncImeWindowBar(dark: Boolean, trayBg: Int) {
        val imeWindow = window?.window ?: return
        val decor = imeWindow.decorView
        val targetView = if (::rootContainer.isInitialized) rootContainer else decor
        WindowCompat.getInsetsController(imeWindow, targetView).isAppearanceLightNavigationBars = !dark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imeWindow.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            imeWindow.navigationBarColor = trayBg
        }
        // Android 15 InputMethodService places a separate NavigationBarFrame below inputArea with transparent background.
        paintNavigationBarFrames(decor, trayBg)
        decor.post {
            if (::settings.isInitialized) {
                paintNavigationBarFrames(decor, trayBackgroundColor(isDarkTheme()))
            }
        }
    }

    private fun paintNavigationBarFrames(view: View, color: Int): Boolean {
        if (view.javaClass.name.contains("NavigationBarFrame")) {
            view.setBackgroundColor(color)
            return true
        }
        if (view is ViewGroup) {
            var updated = false
            for (i in 0 until view.childCount) {
                if (paintNavigationBarFrames(view.getChildAt(i), color)) {
                    updated = true
                }
            }
            return updated
        }
        return false
    }

    private fun recordUndoCommit(rawInput: String, committedText: String) {
        if (rawInput.isNotEmpty() && committedText.isNotEmpty()) {
            lastUndoRawInput = rawInput
            lastUndoCommittedText = committedText
        }
    }

    private fun clearUndoCommit() {
        lastUndoRawInput = null
        lastUndoCommittedText = null
    }

    private fun performUndoCommit() {
        val raw = lastUndoRawInput ?: return
        val committed = lastUndoCommittedText ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(committed.length, 0)?.toString()
        clearUndoCommit()
        if (before == committed) {
            ic.deleteSurroundingText(committed.length, 0)
            renderSnapshot(session.restoreFromUndo(raw, getSurroundingTextBefore()))
        } else {
            renderSnapshot(session.snapshot)
        }
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
                val raw = session.snapshot.rawInput
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                val out = committed + ch
                currentInputConnection?.commitText(out, 1)
                recordUndoCommit(raw, out)
                renderSnapshot(session.snapshot)
            } else {
                clearUndoCommit()
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
            clearUndoCommit()
            currentInputConnection?.commitText(ch.toString(), 1)
            return
        }
        clearUndoCommit()
        val snap = session.appendChar(ch, getSurroundingTextBefore())
        renderSnapshot(snap)
    }

    private fun commitQuickSymbol(sym: String) {
        if (isPasswordField) {
            currentInputConnection?.commitText(sym, 1)
            return
        }
        if (sym == "ー" && !session.isEmpty) {
            clearUndoCommit()
            val snap = session.appendChar('-', getSurroundingTextBefore())
            renderSnapshot(snap)
            return
        }
        if (!session.isEmpty) {
            val raw = session.snapshot.rawInput
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            val out = committed + sym
            currentInputConnection?.commitText(out, 1)
            recordUndoCommit(raw, out)
            renderSnapshot(session.snapshot)
        } else {
            clearUndoCommit()
            currentInputConnection?.commitText(sym, 1)
            renderSnapshot(session.snapshot)
        }
    }

    private fun commitPairedBracket(open: String, close: String) {
        if (isPasswordField) return
        clearUndoCommit()
        val ic = currentInputConnection ?: return
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            ic.commitText(committed + open + close, 1)
            for (i in 0 until close.length) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            }
            renderSnapshot(session.snapshot)
            return
        }
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            ic.commitText(open + selected + close, 1)
        } else {
            ic.commitText(open + close, 1)
            for (i in 0 until close.length) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            }
        }
        renderSnapshot(session.snapshot)
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
                val raw = snap.rawInput
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                val out = committed + punct
                currentInputConnection?.commitText(out, 1)
                recordUndoCommit(raw, out)
                renderSnapshot(session.snapshot)
            } else {
                clearUndoCommit()
                val updated = session.appendText(punct, getSurroundingTextBefore())
                renderSnapshot(updated)
            }
        } else {
            clearUndoCommit()
            currentInputConnection?.commitText(punct, 1)
            renderSnapshot(session.snapshot)
        }
    }

    override fun onBackspace() {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val snap = session.backspace(getSurroundingTextBefore())
            renderSnapshot(snap)
        } else {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            renderSnapshot(session.snapshot)
        }
    }

    override fun onBackspaceClearAll() {
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val snap = session.clearAll()
            renderSnapshot(snap)
            currentInputConnection?.finishComposingText()
        } else {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onSpace() {
        val snap = session.snapshot
        if (!snap.isComposing) {
            clearUndoCommit()
            currentInputConnection?.commitText(" ", 1)
            renderSnapshot(session.snapshot)
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
            val raw = session.snapshot.rawInput
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            currentInputConnection?.commitText(committed, 1)
            recordUndoCommit(raw, committed)
            renderSnapshot(session.snapshot)
        } else {
            clearUndoCommit()
            if (!sendDefaultEditorAction(true)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
    }

    override fun onMoveCursor(delta: Int) {
        if (!session.isEmpty) {
            val snap = session.snapshot
            if (snap.clauses.size > 1) {
                val atEdgeShrink = delta < 0 && snap.focusedClauseIndex == 0 && snap.canShrinkClause
                val atEdgeExtend = delta > 0 && snap.focusedClauseIndex == snap.clauses.lastIndex && snap.canExtendClause
                val nextSnap = if (atEdgeShrink || atEdgeExtend) {
                    session.resizeFocusedClause(delta)
                } else {
                    session.moveFocus(delta)
                }
                renderSnapshot(nextSnap)
            } else {
                val nextSnap = session.resizeFocusedClause(delta)
                renderSnapshot(nextSnap)
            }
        } else {
            clearUndoCommit()
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            sendDownUpKeyEvents(code)
            renderSnapshot(session.snapshot)
        }
    }

    override fun onToggleLanguage() {
        if (!session.isEmpty) {
            renderSnapshot(session.toggleFocusedSegmentLanguage(getSurroundingTextBefore()))
        }
    }

    override fun onToggleEmojiPanel() {
        if (isExpandedPanelOpen && expandedTab == ExpandedTab.EMOJI) {
            isExpandedPanelOpen = false
        } else {
            expandedTab = ExpandedTab.EMOJI
            isExpandedPanelOpen = true
        }
        syncExpandedPanelState(session.snapshot)
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
        clearUndoCommit()
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
            // Undo button right after committing a conversion
            if (lastUndoRawInput != null && lastUndoCommittedText != null) {
                val undoBtn = TextView(this).apply {
                    text = "↩ 戻す"
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accent)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                    background = GradientDrawable().apply {
                        setColor(chipAltBg)
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), accent)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(33)
                    ).apply { marginEnd = dp(5) }
                    setOnClickListener { performUndoCommit() }
                }
                candidateRow.addView(undoBtn)
            }

            // Paired brackets with selection wrap & auto cursor inside
            val bracketPairs = listOf(
                "「」" to ("「" to "」"),
                "（）" to ("（" to "）"),
                "【】" to ("【" to "】"),
                "\"\"" to ("\"" to "\"")
            )
            for ((label, pair) in bracketPairs) {
                val btn = TextView(this).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                    setTextColor(textCol)
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    background = GradientDrawable().apply {
                        setColor(chipAltBg)
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), border)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(33)
                    ).apply { marginEnd = dp(4) }
                    setOnClickListener { commitPairedBracket(pair.first, pair.second) }
                }
                candidateRow.addView(btn)
            }

            // Quick-access Japanese symbols & prolonged-sound bar when idle
            val quickSymbols = listOf("ー", "！", "？", "〜", "、", "。", "・", "／", "：", "＠", "…", "→", "✓")
            for (sym in quickSymbols) {
                val btn = TextView(this).apply {
                    text = sym
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(textCol)
                    setPadding(dp(10), dp(4), dp(10), dp(4))
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

        // Tab strip + close button
        val tabBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (tab in ExpandedTab.values()) {
            val active = expandedTab == tab
            val tabBtn = makePanelButton(
                tab.label,
                if (active) accent else cardBg,
                if (active) Color.WHITE else textCol,
                if (active) accent else border
            ) {
                expandedTab = tab
                expandedScroll.scrollTo(0, 0)
                populateExpandedPanel(session.snapshot)
            }
            tabBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginEnd = dp(4)
            }
            tabInner.addView(tabBtn)
        }
        tabScroll.addView(tabInner)
        tabBarRow.addView(tabScroll)

        if (snap.isComposing) {
            val commitBtn = makePanelButton("確定", accent, Color.WHITE, accent) {
                isExpandedPanelOpen = false
                onEnter()
            }
            commitBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                marginStart = dp(4)
            }
            tabBarRow.addView(commitBtn)
        }
        val closeBtn = makePanelButton("✕", cardBg, textCol, border) {
            isExpandedPanelOpen = false
            syncExpandedPanelState(session.snapshot)
        }
        closeBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
            marginStart = dp(4)
        }
        tabBarRow.addView(closeBtn)
        expandedContent.addView(tabBarRow)

        when (expandedTab) {
            ExpandedTab.CANDIDATES -> populateCandidatesTab(snap, accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.EMOJI -> populateEmojiTab(accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.KAOMOJI -> populateKaomojiTab(accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.TEMPLATES -> populateTemplatesTab(accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.EDIT -> populateEditTab(accent, cardBg, border, textCol, mutedCol)
        }
    }

    private fun populateCandidatesTab(
        snap: CompositionSnapshot,
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        val statusText = TextView(this).apply {
            text = if (snap.isComposing) {
                val fc = snap.clauses.getOrNull(snap.focusedClauseIndex)
                "注目文節: ${fc?.text ?: ""} (${fc?.reading ?: ""})"
            } else {
                "クイック設定（高さ・数字段）"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(textCol)
            setPadding(0, 0, 0, dp(4))
        }
        expandedContent.addView(statusText)

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

    private fun commitDirectExtraText(text: String) {
        if (isPasswordField) return
        clearUndoCommit()
        val ic = currentInputConnection ?: return
        if (!session.isEmpty) {
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            ic.commitText(committed + text, 1)
            renderSnapshot(session.snapshot)
        } else {
            ic.commitText(text, 1)
        }
    }

    private fun populateEmojiTab(
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        val catNames = listOf("履歴", "顔・感情", "手・ジェスチャ", "ハート・記号", "動物・自然", "食べ物", "活動・物")
        val catScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(6))
        }
        val catRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((idx, name) in catNames.withIndex()) {
            val active = selectedEmojiCategory == idx
            val chip = makePanelButton(
                name,
                if (active) accent else cardBg,
                if (active) Color.WHITE else textCol,
                if (active) accent else border
            ) {
                selectedEmojiCategory = idx
                expandedScroll.scrollTo(0, 0)
                populateExpandedPanel(session.snapshot)
            }
            chip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginEnd = dp(4)
            }
            catRow.addView(chip)
        }
        catScroll.addView(catRow)
        expandedContent.addView(catScroll)

        val emojis = when (selectedEmojiCategory) {
            0 -> {
                val recent = settings.getRecentEmojis()
                if (recent.isNotEmpty()) recent else DEFAULT_RECENT_EMOJIS
            }
            1 -> EMOJI_CAT_FACES
            2 -> EMOJI_CAT_HANDS
            3 -> EMOJI_CAT_HEARTS_SYMBOLS
            4 -> EMOJI_CAT_NATURE
            5 -> EMOJI_CAT_FOOD
            else -> EMOJI_CAT_OBJECTS
        }

        if (selectedEmojiCategory == 0 && settings.getRecentEmojis().isEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "よく使われる絵文字（選択すると履歴に追加）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(mutedCol)
                setPadding(0, 0, 0, dp(4))
            })
        }

        val cols = 7
        var r = 0
        while (r < emojis.size) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            for (c in 0 until cols) {
                val emoji = emojis.getOrNull(r + c)
                if (emoji == null) {
                    rowLayout.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
                    })
                } else {
                    val btn = TextView(this).apply {
                        text = emoji
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                        background = GradientDrawable().apply {
                            setColor(cardBg)
                            cornerRadius = dp(5).toFloat()
                            setStroke(dp(1), border)
                        }
                        layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                            if (c < cols - 1) marginEnd = dp(4)
                        }
                        setOnClickListener {
                            settings.addRecentEmoji(emoji)
                            commitDirectExtraText(emoji)
                        }
                    }
                    rowLayout.addView(btn)
                }
            }
            expandedContent.addView(rowLayout)
            r += cols
        }

        addPanelFooterActions(cardBg, border, textCol, accent)
    }

    private fun populateKaomojiTab(
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        expandedContent.addView(TextView(this).apply {
            text = "顔文字（タップで入力）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(4))
        })
        val list = (COMMON_KAOMOJI + dictionaries.kaomojiList).distinct()
        val cols = 3
        var r = 0
        while (r < list.size) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) }
            }
            for (c in 0 until cols) {
                val km = list.getOrNull(r + c)
                if (km == null) {
                    rowLayout.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                    })
                } else {
                    val btn = makePanelButton(km, cardBg, textCol, border) {
                        commitDirectExtraText(km)
                    }.apply {
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                            if (c < cols - 1) marginEnd = dp(4)
                        }
                    }
                    rowLayout.addView(btn)
                }
            }
            expandedContent.addView(rowLayout)
            r += cols
        }
        addPanelFooterActions(cardBg, border, textCol, accent)
    }

    private fun populateTemplatesTab(
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        captureClipboardText()

        val now = Date()
        val dateChips = listOf(
            SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(now),
            SimpleDateFormat("yyyy年M月d日(E)", Locale.JAPAN).format(now),
            SimpleDateFormat("M/d(E)", Locale.JAPAN).format(now),
            SimpleDateFormat("HH:mm", Locale.JAPAN).format(now),
            SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(now)
        )
        expandedContent.addView(TextView(this).apply {
            text = "今日の日付・時刻"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(4))
        })
        val dateScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(8))
        }
        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (dStr in dateChips) {
            val btn = makePanelButton(dStr, cardBg, textCol, accent) {
                commitDirectExtraText(dStr)
            }
            btn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(4)
            }
            dateRow.addView(btn)
        }
        dateScroll.addView(dateRow)
        expandedContent.addView(dateScroll)

        expandedContent.addView(TextView(this).apply {
            text = "よく使う定型文"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(4))
        })
        val phrases = listOf(
            "ありがとうございます。",
            "よろしくお願いいたします。",
            "お疲れ様です。",
            "承知いたしました。",
            "ご確認をお願いいたします。",
            "申し訳ございません。",
            "少々お待ちください。",
            "了解です！"
        )
        val pCols = 2
        var r = 0
        while (r < phrases.size) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            for (c in 0 until pCols) {
                val p = phrases.getOrNull(r + c)
                if (p == null) {
                    rowLayout.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                    })
                } else {
                    val btn = makePanelButton(p, cardBg, textCol, border) {
                        commitDirectExtraText(p)
                    }.apply {
                        ellipsize = TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                            if (c < pCols - 1) marginEnd = dp(4)
                        }
                    }
                    rowLayout.addView(btn)
                }
            }
            expandedContent.addView(rowLayout)
            r += pCols
        }

        expandedContent.addView(TextView(this).apply {
            text = "クリップボード履歴"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, dp(6), 0, dp(4))
        })
        val clips = settings.getClipboardHistory()
        if (clips.isEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "コピーされたテキストはまだありません"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(mutedCol)
                setPadding(0, dp(4), 0, dp(8))
            })
        } else {
            for (clip in clips) {
                val btn = TextView(this).apply {
                    text = clip.replace('\n', ' ')
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(textCol)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    background = GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), border)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                    setOnClickListener { commitDirectExtraText(clip) }
                }
                expandedContent.addView(btn)
            }
        }
        addPanelFooterActions(cardBg, border, textCol, accent)
    }

    private fun populateEditTab(
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        expandedContent.addView(TextView(this).apply {
            text = "カーソル移動・編集ツール"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, 0, 0, dp(6))
        })

        fun addButtonRow(items: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            for ((idx, item) in items.withIndex()) {
                val btn = makePanelButton(item.first, cardBg, textCol, border, item.second).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                        if (idx < items.lastIndex) marginEnd = dp(5)
                    }
                }
                row.addView(btn)
            }
            expandedContent.addView(row)
        }

        addButtonRow(
            listOf(
                "⇤ 文頭" to {
                    clearUndoCommit()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
                },
                "▲ 上" to {
                    clearUndoCommit()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
                },
                "⇥ 文末" to {
                    clearUndoCommit()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
                }
            )
        )
        addButtonRow(
            listOf(
                "◀ 左" to { onMoveCursor(-1) },
                "▼ 下" to {
                    clearUndoCommit()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
                },
                "▶ 右" to { onMoveCursor(1) }
            )
        )
        addButtonRow(
            listOf(
                "全選択" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                },
                "コピー" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.copy)
                    captureClipboardText()
                },
                "切取" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.cut)
                    captureClipboardText()
                },
                "貼付" to {
                    clearUndoCommit()
                    currentInputConnection?.performContextMenuAction(android.R.id.paste)
                }
            )
        )
        addPanelFooterActions(cardBg, border, textCol, accent)
    }

    private fun addPanelFooterActions(cardBg: Int, border: Int, textCol: Int, accent: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val backKb = makePanelButton("キーボードへ", cardBg, accent, accent) {
            isExpandedPanelOpen = false
            syncExpandedPanelState(session.snapshot)
        }
        val spaceBtn = makePanelButton("空白", cardBg, textCol, border) {
            commitDirectExtraText(" ")
        }
        val bsBtn = makePanelButton("⌫ 削除", cardBg, textCol, border) {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        val enterBtn = makePanelButton("⏎ 改行", accent, Color.WHITE, accent) {
            if (!sendDefaultEditorAction(true)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
        val items = listOf(backKb to 1.4f, spaceBtn to 1f, bsBtn to 1f, enterBtn to 1f)
        for ((idx, pair) in items.withIndex()) {
            pair.first.layoutParams = LinearLayout.LayoutParams(0, dp(36), pair.second).apply {
                if (idx < items.lastIndex) marginEnd = dp(4)
            }
            row.addView(pair.first)
        }
        expandedContent.addView(row)
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

    companion object {
        private val DEFAULT_RECENT_EMOJIS = listOf(
            "😀", "😂", "🤣", "😊", "🙏", "🙇", "👍", "✨",
            "🔥", "❤️", "🎉", "😭", "😅", "🤔", "👀", "✅",
            "☀️", "🌸", "👌", "🙌", "💻"
        )

        private val EMOJI_CAT_FACES = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂",
            "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌",
            "😍", "🥰", "😘", "😗", "😋", "😛", "😜",
            "🤪", "😝", "🤔", "🤫", "🤭", "😐", "😑",
            "😶", "😏", "😒", "🙄", "😬", "😔", "😪",
            "😴", "😷", "🤒", "😵", "🤯", "🥳", "😎",
            "🤓", "🧐", "😕", "😟", "🙁", "😮", "😯",
            "😲", "😳", "🥺", "😢", "😭", "😱", "😖",
            "😣", "😞", "😓", "😩", "😫", "😤", "😡"
        )

        private val EMOJI_CAT_HANDS = listOf(
            "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘",
            "🤙", "👈", "👉", "👆", "👇", "☝️", "✋",
            "🤚", "👋", "👏", "🙌", "👐", "🤲", "🙏",
            "🤝", "💪", "✍️", "🙇", "🙋", "💁", "🙆",
            "🙅", "🤷", "🤦", "👀", "💬", "💭", "💯",
            "🔥", "💥", "💫", "💦", "💤", "🎉", "🎊"
        )

        private val EMOJI_CAT_HEARTS_SYMBOLS = listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤",
            "🤍", "💔", "💕", "💞", "💓", "💗", "💖",
            "⭐", "🌟", "✨", "⚡", "⭕", "❌", "❗",
            "❓", "‼️", "⁉️", "✅", "❎", "✔️", "⚠️",
            "⛔", "🚫", "ℹ️", "🔔", "🎵", "🎶", "📌",
            "📍", "🔗", "🔍", "🔒", "🔓", "⚙️", "💡"
        )

        private val EMOJI_CAT_NATURE = listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻",
            "🐼", "🐨", "🐯", "🦁", "🐸", "🐵", "🐧",
            "🐦", "🐥", "🦉", "🐝", "🦋", "🐢", "🐳",
            "🐬", "🐟", "🐙", "🌸", "🌹", "🌺", "🌻",
            "🌷", "🌱", "🌲", "🌳", "🍀", "🍁", "🍂",
            "☀️", "🌤️", "☁️", "🌧️", "❄️", "🌈", "🌙"
        )

        private val EMOJI_CAT_FOOD = listOf(
            "🍚", "🍙", "🍣", "🍤", "🍜", "🍝", "🍞",
            "🥐", "🍔", "🍟", "🍕", "🌭", "🥪", "🥚",
            "🍳", "🍲", "🍛", "🍱", "🍰", "🎂", "🍫",
            "🍬", "🍮", "🍩", "🍪", "☕", "🍵", "🧃",
            "🍺", "🍻", "🍷", "🥤", "🍎", "🍊", "🍋",
            "🍌", "🍓", "🍇", "🍉", "🍑", "🍒", "🥝"
        )

        private val EMOJI_CAT_OBJECTS = listOf(
            "📱", "💻", "⌨️", "🖥️", "🖱️", "📷", "🎧",
            "🎤", "⌚", "📖", "📚", "📝", "✏️", "📅",
            "📆", "⏰", "⌛", "📦", "✉️", "📩", "🔑",
            "🔧", "🔨", "🏠", "🏢", "🏫", "🏥", "🚉",
            "🚆", "🚌", "🚗", "✈️", "🚀", "⚽", "🏀",
            "🎮", "🎸", "🎨", "🎁", "💰", "💳", "🧾"
        )

        private val COMMON_KAOMOJI = listOf(
            "(^^)", "(´・ω・`)", "＼(^o^)／", "m(_ _)m", "(*^^*)", "(；´Д`)",
            "(´∀`)", "(・∀・)", "(>_<)", "(｀・ω・´)", "(*´ω`*)", "(・ω・)",
            "(^_-)-☆", "(T_T)", "(^o^)", "(-_-;)", "(*_*)", "٩(ˊᗜˋ*)و"
        )
    }
}
