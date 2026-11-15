package io.github.sp9r.meltype

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.AppEngineHolder
import io.github.sp9r.meltype.core.CandidateItem
import io.github.sp9r.meltype.core.CandidateKind
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.CompositionSnapshot
import io.github.sp9r.meltype.core.EmojiCatalog
import io.github.sp9r.meltype.core.EmojiCategory
import io.github.sp9r.meltype.core.GlideEvalLogger
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import io.github.sp9r.meltype.core.MozcRuntime
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.glide.GlideCandidate
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import io.github.sp9r.meltype.glide.TouchPoint
import io.github.sp9r.meltype.ui.KeyboardActionListener
import io.github.sp9r.meltype.ui.QwertyKeyboardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Android InputMethodService for Meltype (QWERTY + dual-language glide typing + live JP/EN conversion).
 */
class MeltypeImeService : InputMethodService(), KeyboardActionListener {

    private enum class ExpandedTab(val label: String) {
        CANDIDATES("候補・設定"),
        EMOJI("絵文字"),
        KAOMOJI("顔文字"),
        TEMPLATES("定型/履歴"),
        EDIT("編集")
    }

    private lateinit var settings: MeltypeSettings
    private lateinit var dictionaries: MeltypeDictionaries
    private lateinit var scoreEngine: ScoreEngine
    private lateinit var converter: AndroidMozcConverter
    private lateinit var session: CompositionSession
    private lateinit var evalLogger: GlideEvalLogger

    @Volatile
    private var glideDecoder: GlideDecoder? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val glidePreviewExecutor = Executors.newSingleThreadExecutor()
    private val glidePreviewSeq = AtomicLong(0L)

    private lateinit var rootContainer: LinearLayout
    private lateinit var clauseBar: LinearLayout
    private lateinit var clauseTopRow: LinearLayout
    private lateinit var clauseScroll: HorizontalScrollView
    private lateinit var clausePillRow: LinearLayout
    private lateinit var prevClauseBtn: TextView
    private lateinit var nextClauseBtn: TextView
    private lateinit var partialCommitBtn: TextView
    private lateinit var langSwitchBtn: TextView
    private lateinit var boundaryBar: LinearLayout
    private lateinit var shrinkClauseBtn: TextView
    private lateinit var boundaryScroll: HorizontalScrollView
    private lateinit var boundaryKanaRow: LinearLayout
    private lateinit var extendClauseBtn: TextView

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
    private lateinit var emojiCatalog: EmojiCatalog
    private lateinit var expandedPanel: LinearLayout
    private lateinit var expandedHeader: LinearLayout
    private lateinit var expandedBodyFrame: FrameLayout
    private lateinit var expandedScroll: ScrollView
    private lateinit var expandedContent: LinearLayout
    private lateinit var expandedRecyclerHost: FrameLayout
    private lateinit var expandedFooter: LinearLayout

    private var isExpandedPanelOpen: Boolean = false
    private var expandedTab: ExpandedTab = ExpandedTab.CANDIDATES
    private var selectedEmojiCategory: Int = 0
    private var isEmojiSearchActive: Boolean = false
    private val emojiSearchQuery = StringBuilder()
    private var emojiTabCells: List<LinearLayout> = emptyList()
    private var emojiRecyclerView: RecyclerView? = null
    private var emojiAdapter: EmojiGridAdapter? = null
    private var emojiHeaderPositions: Map<EmojiCategory, Int> = emptyMap()
    private var emojiCategoriesInAdapter: List<EmojiCategory> = emptyList()
    private var programmaticEmojiScroll: Boolean = false
    private val emojiGlyphPaint = Paint().apply { textSize = 24f }
    private val hasGlyphCache = HashMap<String, Boolean>(2200)
    private var lastUndoRawInput: String? = null
    private var lastUndoCommittedText: String? = null
    private var lastUndoDeletedText: String? = null
    private var lastDark: Boolean = false

    override fun onCreate() {
        super.onCreate()
        settings = MeltypeSettings(this)
        evalLogger = GlideEvalLogger(filesDir)
        dictionaries = AppEngineHolder.getDictionaries(this)
        emojiCatalog = AppEngineHolder.getEmojiCatalog(this)
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
            glideDecoder = AppEngineHolder.getGlideDecoder(applicationContext)
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

        // Clause focus & 1-tap boundary adjustment bar (visible while composing)
        clauseBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(3), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        clauseTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
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

        prevClauseBtn = makeRailButton("‹").apply {
            minWidth = dp(30)
            setOnClickListener { renderSnapshot(session.moveFocus(-1)) }
        }
        nextClauseBtn = makeRailButton("›").apply {
            minWidth = dp(30)
            setOnClickListener { renderSnapshot(session.moveFocus(+1)) }
        }
        partialCommitBtn = makeRailButton("確定✓").apply {
            setOnClickListener {
                glidePreviewSeq.incrementAndGet()
                val rawBefore = session.snapshot.rawInput
                val committed = session.commitFocusedPrefix(getSurroundingTextBefore())
                if (committed.isNotEmpty()) {
                    currentInputConnection?.commitText(committed, 1)
                    if (session.isEmpty) {
                        recordUndoCommit(rawBefore, committed)
                    } else {
                        clearUndoCommit()
                    }
                    if (shouldLogEval()) {
                        evalLogger.logCommit(settings.activePromptId, rawBefore, committed, "partial")
                    }
                    renderSnapshot(session.snapshot)
                }
            }
        }
        langSwitchBtn = makeRailButton("日/英").apply {
            setOnClickListener { onToggleLanguage() }
        }

        clauseTopRow.addView(clauseScroll)
        clauseTopRow.addView(prevClauseBtn)
        clauseTopRow.addView(nextClauseBtn)
        clauseTopRow.addView(partialCommitBtn)
        clauseTopRow.addView(langSwitchBtn)

        boundaryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, dp(3), 0, dp(1))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            )
        }
        shrinkClauseBtn = makeRailButton("◀ 短").apply {
            minWidth = dp(52)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setOnClickListener {
                renderSnapshot(session.resizeFocusedClause(-1, getSurroundingTextBefore()))
            }
        }
        boundaryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
        }
        boundaryKanaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        boundaryScroll.addView(boundaryKanaRow)
        extendClauseBtn = makeRailButton("長 ▶").apply {
            minWidth = dp(52)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setOnClickListener {
                renderSnapshot(session.resizeFocusedClause(+1, getSurroundingTextBefore()))
            }
        }
        boundaryBar.addView(shrinkClauseBtn)
        boundaryBar.addView(boundaryScroll)
        boundaryBar.addView(extendClauseBtn)

        clauseBar.addView(clauseTopRow)
        clauseBar.addView(boundaryBar)

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

        // Expandable overlay panel replacing keyboardView bounds for candidates, wide emoji grid, kaomoji & tools
        expandedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250)
            )
        }
        expandedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        expandedBodyFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        expandedScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        expandedScroll.addView(expandedContent)
        expandedRecyclerHost = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        expandedBodyFrame.addView(expandedScroll)
        expandedBodyFrame.addView(expandedRecyclerHost)
        expandedFooter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        expandedPanel.addView(expandedHeader)
        expandedPanel.addView(expandedBodyFrame)
        expandedPanel.addView(expandedFooter)

        rootContainer.addView(clauseBar)
        rootContainer.addView(topBar)
        rootContainer.addView(dividerView)
        rootContainer.addView(expandedPanel)
        rootContainer.addView(keyboardView)

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
        isEmojiSearchActive = false
        emojiSearchQuery.setLength(0)
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
        glidePreviewSeq.incrementAndGet()
        super.onFinishInputView(finishingInput)
        isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val raw = session.snapshot.rawInput
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            if (committed.isNotEmpty()) {
                currentInputConnection?.commitText(committed, 1)
                if (shouldLogEval()) {
                    evalLogger.logCommit(settings.activePromptId, raw, committed, "finish_view")
                }
            }
        }
        renderSnapshot(session.snapshot)
    }

    override fun onDestroy() {
        bgExecutor.shutdownNow()
        glidePreviewExecutor.shutdownNow()
        super.onDestroy()
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
        keyboardView.letterFlickEnabled = settings.letterFlickEnabled
        keyboardView.showNumberRow = settings.showNumberRow
        keyboardView.autoReturnFromSymbols = settings.autoReturnFromSymbols
        keyboardView.flickSensitivity = settings.flickSensitivity
        keyboardView.themeMode = settings.themeMode
        keyboardView.oneHandedMode = settings.oneHandedMode
        keyboardView.heightScale = settings.keyboardHeightScale
        keyboardView.punctuationStyle = settings.punctuationStyle
        keyboardView.refreshAppearance()
        oneHandBtn.text = shortHandLabel(settings.oneHandedMode)

        updateExpandedPanelHeight()

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
        styleRailButton(partialCommitBtn, dark, subtle = false)
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
            lastUndoDeletedText = null
        }
    }

    private fun clearUndoCommit() {
        lastUndoRawInput = null
        lastUndoCommittedText = null
        lastUndoDeletedText = null
    }

    private fun shouldLogEval(): Boolean =
        ::settings.isInitialized && ::evalLogger.isInitialized && !isPasswordField && settings.glideEvalLoggingEnabled

    private fun performUndoCommit() {
        val ic = currentInputConnection ?: return
        val deleted = lastUndoDeletedText
        if (deleted != null) {
            clearUndoCommit()
            ic.commitText(deleted, 1)
            if (shouldLogEval()) {
                evalLogger.logBackspace(settings.activePromptId, "commit_undo", deleted, session.snapshot.previewText)
            }
            renderSnapshot(session.snapshot)
            return
        }
        val raw = lastUndoRawInput ?: return
        val committed = lastUndoCommittedText ?: return
        clearUndoCommit()
        if (committed.isEmpty()) {
            val restored = session.restoreFromUndo(raw, getSurroundingTextBefore())
            if (shouldLogEval()) {
                evalLogger.logBackspace(settings.activePromptId, "commit_undo", raw, restored.previewText)
            }
            renderSnapshot(restored)
            return
        }
        val before = ic.getTextBeforeCursor(committed.length, 0)?.toString()
        if (before == committed) {
            ic.deleteSurroundingText(committed.length, 0)
            val restored = session.restoreFromUndo(raw, getSurroundingTextBefore())
            if (shouldLogEval()) {
                evalLogger.logBackspace(settings.activePromptId, "commit_undo", "$raw->$committed", restored.previewText)
            }
            renderSnapshot(restored)
        } else {
            renderSnapshot(session.snapshot)
        }
    }

    private fun getSurroundingTextBefore(): String? {
        return currentInputConnection?.getTextBeforeCursor(40, 0)?.toString()
    }

    // --- KeyboardActionListener implementation ---

    override fun onCharacter(ch: Char, directCommit: Boolean, spatialHints: Map<Char, Float>) {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen && expandedTab == ExpandedTab.EMOJI && isEmojiSearchActive) {
            if (ch == ' ') return
            emojiSearchQuery.append(ch.lowercaseChar())
            populateExpandedPanel(session.snapshot)
            return
        }
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
                if (shouldLogEval()) {
                    evalLogger.logCommit(settings.activePromptId, raw, committed, "direct_char")
                    evalLogger.logTap(settings.activePromptId, ch, true, "")
                }
                renderSnapshot(session.snapshot)
            } else {
                clearUndoCommit()
                currentInputConnection?.commitText(ch.toString(), 1)
                if (shouldLogEval()) {
                    evalLogger.logTap(settings.activePromptId, ch, true, "")
                }
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
            if (shouldLogEval()) {
                evalLogger.logTap(settings.activePromptId, ch, true, "")
            }
            return
        }
        clearUndoCommit()
        val snap = session.appendChar(ch, getSurroundingTextBefore(), spatialHints)
        if (shouldLogEval()) {
            evalLogger.logTap(settings.activePromptId, ch, false, snap.previewText)
        }
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
        glidePreviewSeq.incrementAndGet()
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
                if (shouldLogEval()) {
                    evalLogger.logCommit(settings.activePromptId, raw, committed, "punct")
                }
                renderSnapshot(session.snapshot)
            } else {
                clearUndoCommit()
                val updated = session.appendText(punct, getSurroundingTextBefore())
                if (shouldLogEval()) {
                    evalLogger.logTap(settings.activePromptId, punct.firstOrNull() ?: ',', false, updated.previewText)
                }
                renderSnapshot(updated)
            }
        } else {
            clearUndoCommit()
            currentInputConnection?.commitText(punct, 1)
            if (shouldLogEval()) {
                evalLogger.logTap(settings.activePromptId, punct.firstOrNull() ?: ',', true, "")
            }
            renderSnapshot(session.snapshot)
        }
    }

    override fun onBackspace() {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen && expandedTab == ExpandedTab.EMOJI && isEmojiSearchActive) {
            if (emojiSearchQuery.isNotEmpty()) {
                emojiSearchQuery.deleteCharAt(emojiSearchQuery.length - 1)
                populateExpandedPanel(session.snapshot)
            } else {
                isEmojiSearchActive = false
                syncExpandedPanelState(session.snapshot)
            }
            return
        }
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val snapBefore = session.snapshot
            val snap = session.backspace(getSurroundingTextBefore())
            if (shouldLogEval()) {
                val kind = if (snapBefore.hasGlideUndo) "glide_undo" else "char_bs"
                evalLogger.logBackspace(settings.activePromptId, kind, snapBefore.previewText, snap.previewText)
            }
            renderSnapshot(snap)
        } else {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            if (shouldLogEval()) {
                evalLogger.logBackspace(settings.activePromptId, "committed_del", "", "")
            }
            renderSnapshot(session.snapshot)
        }
    }

    override fun onBackspaceClearAll() {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen) {
            isExpandedPanelOpen = false
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
        }
        if (!session.isEmpty) {
            val rawBefore = session.snapshot.rawInput
            val snap = session.clearAll()
            if (rawBefore.isNotEmpty()) {
                lastUndoRawInput = rawBefore
                lastUndoCommittedText = ""
                lastUndoDeletedText = null
                if (shouldLogEval()) {
                    evalLogger.logBackspace(settings.activePromptId, "clear_all", rawBefore, "")
                }
            }
            renderSnapshot(snap)
            currentInputConnection?.finishComposingText()
        } else {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onBackspaceSwipeWord() {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen) {
            isExpandedPanelOpen = false
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
        }
        if (!session.isEmpty) {
            onBackspaceClearAll()
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val delCount = computeSwipeDeleteCount(before)
        if (delCount > 0) {
            val removed = before.substring(before.length - delCount)
            ic.deleteSurroundingText(delCount, 0)
            lastUndoRawInput = null
            lastUndoCommittedText = null
            lastUndoDeletedText = removed
            if (shouldLogEval()) {
                evalLogger.logBackspace(settings.activePromptId, "word_swipe_bs", removed, "")
            }
            renderSnapshot(session.snapshot)
        } else {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun computeSwipeDeleteCount(before: String): Int {
        if (before.isEmpty()) return 0
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i == 0) return before.length
        val anchor = before[i - 1]
        when {
            anchor in 'a'..'z' || anchor in 'A'..'Z' || anchor in '0'..'9' || anchor == '_' -> {
                while (i > 0 && (before[i - 1] in 'a'..'z' || before[i - 1] in 'A'..'Z' || before[i - 1] in '0'..'9' || before[i - 1] == '_')) i--
            }
            anchor in '\u3041'..'\u309F' || anchor in '\u30A0'..'\u30FF' || anchor == 'ー' || anchor in '\u4E00'..'\u9FFF' -> {
                val maxStop = (i - 12).coerceAtLeast(0)
                while (i > maxStop && (before[i - 1] in '\u3041'..'\u309F' || before[i - 1] in '\u30A0'..'\u30FF' || before[i - 1] == 'ー' || before[i - 1] in '\u4E00'..'\u9FFF')) i--
            }
            else -> i = (i - 1).coerceAtLeast(0)
        }
        return before.length - i
    }

    override fun onSpace() {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen && expandedTab == ExpandedTab.EMOJI && isEmojiSearchActive) {
            if (emojiSearchQuery.isNotEmpty()) {
                val topEmoji = emojiCatalog.search(emojiSearchQuery.toString(), limit = 20)
                    .firstOrNull { canRenderEmoji(it.emoji) }?.emoji
                if (topEmoji != null) {
                    settings.addRecentEmoji(topEmoji)
                    commitDirectExtraText(topEmoji)
                }
            }
            return
        }
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
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen && expandedTab == ExpandedTab.EMOJI && isEmojiSearchActive) {
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
            syncExpandedPanelState(session.snapshot)
            return
        }
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (!session.isEmpty) {
            val raw = session.snapshot.rawInput
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            currentInputConnection?.commitText(committed, 1)
            recordUndoCommit(raw, committed)
            if (shouldLogEval()) {
                evalLogger.logCommit(settings.activePromptId, raw, committed, "enter")
            }
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

    override fun onMoveCursor2D(dx: Int, dy: Int) {
        if (dx != 0) {
            onMoveCursor(dx)
        }
        if (dy != 0) {
            if (!session.isEmpty) {
                val raw = session.snapshot.rawInput
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                if (committed.isNotEmpty()) {
                    currentInputConnection?.commitText(committed, 1)
                    if (shouldLogEval()) {
                        evalLogger.logCommit(settings.activePromptId, raw, committed, "cursor_dpad")
                    }
                }
                renderSnapshot(session.snapshot)
            }
            clearUndoCommit()
            sendDownUpKeyEvents(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
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
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
        } else {
            expandedTab = ExpandedTab.EMOJI
            isExpandedPanelOpen = true
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
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
        val seq = glidePreviewSeq.incrementAndGet()
        val snap = session.snapshot
        val inEnglishSentence = snap.isComposing && snap.rawInput.contains(' ') &&
            snap.clauses.lastOrNull()?.isEnglish == true
        val copiedPoints = points.toList()
        val boundsCopy = RectF(letterBounds)
        glidePreviewExecutor.execute {
            if (glidePreviewSeq.get() != seq) return@execute
            val norm = KeyLayoutGeometry.normalizeFromView(copiedPoints, boundsCopy)
            val cands = decoder.decode(norm, maxResults = 3, preferJapanese = !inEnglishSentence, fastPreview = true)
            if (cands.isEmpty() || glidePreviewSeq.get() != seq) return@execute
            val preview = cands.joinToString(" | ") { c ->
                if (!c.isEnglish && !c.kanaHint.isNullOrEmpty()) c.kanaHint else c.word
            }
            rootContainer.post {
                if (glidePreviewSeq.get() == seq && keyboardView.isActivelyGliding()) {
                    showTransientTrace(preview)
                }
            }
        }
    }

    override fun onGlideCommit(points: List<TouchPoint>, letterBounds: RectF) {
        glidePreviewSeq.incrementAndGet()
        if (isExpandedPanelOpen) isExpandedPanelOpen = false
        if (isPasswordField) return
        clearUndoCommit()
        val decoder = glideDecoder ?: AppEngineHolder.getGlideDecoder(applicationContext).also { glideDecoder = it }
        val norm = KeyLayoutGeometry.normalizeFromView(points, letterBounds)
        val snap = session.snapshot
        val inEnglishSentence = snap.isComposing && snap.rawInput.contains(' ') &&
            snap.clauses.lastOrNull()?.isEnglish == true
        val preferJp = !inEnglishSentence
        val loggingEval = shouldLogEval()
        val debugRes = if (loggingEval) {
            decoder.decodeDebug(norm, maxResults = 10, preferJapanese = preferJp)
        } else {
            null
        }
        val cands = if (debugRes != null) {
            debugRes.candidates.take(8).map { GlideCandidate(it.word, it.isEnglish, it.scoreCost, it.kanaHint) }
        } else {
            decoder.decode(norm, maxResults = 8, preferJapanese = preferJp, fastPreview = false)
        }
        val composingBefore = snap.rawInput
        val top = cands.firstOrNull()
        val updated = if (top != null) {
            val alts = cands.map { it.word to it.isEnglish }
            session.appendGlideResult(
                primaryWord = top.word,
                isPrimaryEnglish = top.isEnglish,
                alternatives = alts,
                surroundingContext = getSurroundingTextBefore()
            ).also { renderSnapshot(it) }
        } else {
            snap
        }
        if (debugRes != null && (top != null || (norm.size >= 3 && debugRes.pathLen >= 0.12f))) {
            evalLogger.logGlide(
                rawPoints = points,
                normalizedPoints = norm,
                boundsLeft = letterBounds.left,
                boundsTop = letterBounds.top,
                boundsWidth = letterBounds.width(),
                boundsHeight = letterBounds.height(),
                oneHandedMode = settings.oneHandedMode.name,
                heightScale = settings.keyboardHeightScale,
                preferJapanese = preferJp,
                debugResult = debugRes,
                composingBefore = composingBefore,
                snapshotAfter = updated,
                promptId = settings.activePromptId,
                expectedText = settings.activePromptExpected
            )
        }
    }

    private fun selectCandidateAndLog(snap: CompositionSnapshot, item: CandidateItem, closeExpanded: Boolean) {
        val rawBefore = snap.rawInput
        val focusedClause = snap.clauses.getOrNull(snap.focusedClauseIndex)
        val nextSnap = session.selectCandidate(
            item,
            getSurroundingTextBefore(),
            advanceFocus = true
        )
        if (closeExpanded) {
            isExpandedPanelOpen = false
        }
        renderSnapshot(nextSnap)
        if (shouldLogEval()) {
            if (item.kind == CandidateKind.GLIDE_ALT) {
                evalLogger.logGlideAltPicked(
                    promptId = settings.activePromptId,
                    fromRaw = rawBefore,
                    pickedWord = item.text,
                    isEnglish = item.subtitle.startsWith("英"),
                    previewAfter = nextSnap.previewText
                )
            } else {
                evalLogger.logCandidatePicked(
                    promptId = settings.activePromptId,
                    clauseReading = focusedClause?.reading.orEmpty(),
                    pickedText = item.text,
                    kind = item.kind.name,
                    previewAfter = nextSnap.previewText
                )
            }
        }
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

            val clauseTrace = snap.clauses.joinToString("│") { it.reading }.ifEmpty { snap.romajiTrace }
            traceBadge.visibility = View.VISIBLE
            traceBadge.text = clauseTrace

            clauseBar.visibility = View.VISIBLE
            val hasClauses = snap.clauses.isNotEmpty()
            val multiClause = snap.clauses.size > 1
            val canPrev = multiClause && snap.focusedClauseIndex > 0
            val canNext = multiClause && snap.focusedClauseIndex < snap.clauses.lastIndex
            prevClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            nextClauseBtn.visibility = if (multiClause) View.VISIBLE else View.GONE
            prevClauseBtn.isEnabled = canPrev
            prevClauseBtn.alpha = if (canPrev) 1.0f else 0.32f
            nextClauseBtn.isEnabled = canNext
            nextClauseBtn.alpha = if (canNext) 1.0f else 0.32f
            partialCommitBtn.isEnabled = hasClauses
            partialCommitBtn.alpha = if (hasClauses) 1.0f else 0.32f

            val showBoundaryStrip = snap.focusedAdjustableKana.length >= 2 || snap.canShrinkClause || snap.canExtendClause
            boundaryBar.visibility = if (showBoundaryStrip) View.VISIBLE else View.GONE
            shrinkClauseBtn.isEnabled = snap.canShrinkClause
            shrinkClauseBtn.alpha = if (snap.canShrinkClause) 1.0f else 0.32f
            extendClauseBtn.isEnabled = snap.canExtendClause
            extendClauseBtn.alpha = if (snap.canExtendClause) 1.0f else 0.32f
            renderClausePills(snap, dark, accent)
            if (showBoundaryStrip) {
                renderBoundaryKanaRow(boundaryKanaRow, boundaryScroll, snap, dark, accent)
            } else {
                boundaryKanaRow.removeAllViews()
            }
        } else {
            ic?.finishComposingText()
            traceBadge.visibility = View.GONE
            clauseBar.visibility = View.GONE
            boundaryBar.visibility = View.GONE
        }

        renderCandidateRow(snap, dark, accent)
        syncExpandedPanelState(snap)
    }

    private fun renderBoundaryKanaRow(
        container: LinearLayout,
        scrollHost: HorizontalScrollView,
        snap: CompositionSnapshot,
        dark: Boolean,
        accent: Int
    ) {
        container.removeAllViews()
        val adjustable = snap.focusedAdjustableKana
        val splitLen = snap.focusedAdjustableLen.coerceIn(0, adjustable.length)
        if (adjustable.isEmpty()) return

        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#D5DAE0")
        val bgInClause = if (dark) Color.parseColor("#2A3B52") else Color.parseColor("#E3EDF8")
        val bgTail = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val textIn = if (dark) Color.parseColor("#F5F7FA") else Color.parseColor("#18202A")
        val textTail = if (dark) Color.parseColor("#9BA3AF") else Color.parseColor("#616975")

        var splitView: View? = null
        fun addSplitMarker() {
            val marker = View(this).apply {
                setBackgroundColor(accent)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(22)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(3)
                }
            }
            splitView = marker
            container.addView(marker)
        }

        for (i in adjustable.indices) {
            if (i == splitLen) addSplitMarker()
            val included = i < splitLen
            val ch = adjustable[i]
            val targetLen = i + 1
            val pill = TextView(this).apply {
                text = ch.toString()
                gravity = Gravity.CENTER
                maxLines = 1
                minWidth = dp(26)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, if (included) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (included) textIn else textTail)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = GradientDrawable().apply {
                    setColor(if (included) bgInClause else bgTail)
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), if (included) accent else border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(26)
                ).apply { marginEnd = dp(2) }
                setOnClickListener {
                    if (targetLen != splitLen) {
                        val nextSnap = session.setFocusedClauseLength(targetLen, getSurroundingTextBefore())
                        renderSnapshot(nextSnap)
                    }
                }
            }
            container.addView(pill)
        }
        if (splitLen >= adjustable.length) addSplitMarker()

        splitView?.let { anchor ->
            scrollHost.post {
                val targetX = (anchor.left - scrollHost.width / 2).coerceAtLeast(0)
                scrollHost.smoothScrollTo(targetX, 0)
            }
        }
    }

    private fun renderClausePills(snap: CompositionSnapshot, dark: Boolean, accent: Int) {
        clausePillRow.removeAllViews()
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#D5DAE0")
        val bgNormal = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val bgFocus = if (dark) Color.parseColor("#2A3B52") else Color.parseColor("#E3EDF8")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")

        for ((idx, clause) in snap.clauses.withIndex()) {
            val focused = idx == snap.focusedClauseIndex
            val showReadingCue = focused && !clause.isEnglish &&
                clause.reading.isNotEmpty() && clause.reading != clause.text
            val pillLabel = if (showReadingCue) "${clause.reading}▸${clause.text}" else clause.text
            val pill = TextView(this).apply {
                text = pillLabel
                gravity = Gravity.CENTER
                maxLines = 1
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
                    dp(30)
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    if (focused) {
                        expandedTab = ExpandedTab.CANDIDATES
                        isExpandedPanelOpen = !isExpandedPanelOpen
                        syncExpandedPanelState(session.snapshot)
                    } else {
                        renderSnapshot(session.focusClause(idx))
                    }
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
            // Undo button right after committing a conversion or swipe-deleting
            if ((lastUndoRawInput != null && lastUndoCommittedText != null) || lastUndoDeletedText != null) {
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
                setPadding(dp(10), dp(2), dp(10), dp(2))
                val isSelected = item.kind == CandidateKind.CONVERSION && item.text == focusedText && idx == 0
                val isGlide = item.kind == CandidateKind.GLIDE_ALT
                background = GradientDrawable().apply {
                    setColor(if (isGlide) chipAltBg else chipBg)
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), if (isSelected || isGlide) accent else border)
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(36)
                ).apply {
                    marginEnd = dp(5)
                }
                setOnClickListener {
                    val wasLast = snap.focusedClauseIndex >= snap.clauses.lastIndex
                    val close = isExpandedPanelOpen && (item.kind == CandidateKind.GLIDE_ALT || wasLast || snap.clauses.size <= 1)
                    selectCandidateAndLog(snap, item, closeExpanded = close)
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

    private fun updateExpandedPanelHeight() {
        if (!::keyboardView.isInitialized || !::expandedPanel.isInitialized) return
        val baseDp = keyboardView.currentBaseHeightDp() * settings.keyboardHeightScale
        val isEmojiSearchStrip = isExpandedPanelOpen &&
            expandedTab == ExpandedTab.EMOJI &&
            isEmojiSearchActive
        val isWidePicker = isExpandedPanelOpen &&
            ((expandedTab == ExpandedTab.EMOJI && !isEmojiSearchActive) || expandedTab == ExpandedTab.KAOMOJI)
        val screenHeightDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val maxSafeDp = (screenHeightDp * 0.54f).roundToInt().coerceAtLeast(340)
        val targetDp = when {
            isEmojiSearchStrip -> 118
            isWidePicker -> maxOf((baseDp + 132f).roundToInt(), 388).coerceAtMost(maxSafeDp)
            else -> baseDp.roundToInt().coerceIn(220, 420)
        }
        val targetPx = dp(targetDp)
        val lp = expandedPanel.layoutParams as LinearLayout.LayoutParams
        if (lp.height != targetPx) {
            lp.height = targetPx
            expandedPanel.layoutParams = lp
        }
    }

    private fun syncExpandedPanelState(snap: CompositionSnapshot) {
        expandPanelBtn.text = if (isExpandedPanelOpen) "▲" else "▼"
        if (!isExpandedPanelOpen) {
            isEmojiSearchActive = false
            topBar.visibility = View.VISIBLE
            dividerView.visibility = View.VISIBLE
            expandedPanel.visibility = View.GONE
            keyboardView.visibility = View.VISIBLE
            return
        }
        val inEmojiSearch = expandedTab == ExpandedTab.EMOJI && isEmojiSearchActive
        val hideIdleTopBar = inEmojiSearch ||
            (!snap.isComposing && (expandedTab == ExpandedTab.EMOJI || expandedTab == ExpandedTab.KAOMOJI))
        topBar.visibility = if (hideIdleTopBar) View.GONE else View.VISIBLE
        dividerView.visibility = if (hideIdleTopBar) View.GONE else View.VISIBLE
        keyboardView.visibility = if (inEmojiSearch) View.VISIBLE else View.GONE
        expandedPanel.visibility = View.VISIBLE
        updateExpandedPanelHeight()
        populateExpandedPanel(snap)
    }

    private fun populateExpandedPanel(snap: CompositionSnapshot) {
        expandedHeader.removeAllViews()
        expandedContent.removeAllViews()
        expandedFooter.removeAllViews()

        val dark = isDarkTheme()
        val accent = if (dark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")
        val barBg = if (dark) Color.parseColor("#181B20") else Color.parseColor("#F5F6F7")
        val cardBg = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val border = if (dark) Color.parseColor("#2E333C") else Color.parseColor("#DFE2E6")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val mutedCol = if (dark) Color.parseColor("#8D95A0") else Color.parseColor("#737A84")

        val wideGrid = expandedTab == ExpandedTab.EMOJI || expandedTab == ExpandedTab.KAOMOJI
        if (wideGrid) {
            expandedContent.setPadding(dp(4), dp(4), dp(4), dp(4))
        } else {
            expandedContent.setPadding(dp(8), dp(6), dp(8), dp(8))
        }

        // Fixed top underline-tab rail
        expandedHeader.setBackgroundColor(barBg)
        val tabBarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), 0)
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tabInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (tab in ExpandedTab.values()) {
            val active = expandedTab == tab
            val tabCell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(9), dp(5), dp(9), 0)
                setOnClickListener {
                    expandedTab = tab
                    isEmojiSearchActive = false
                    emojiSearchQuery.setLength(0)
                    expandedScroll.scrollTo(0, 0)
                    updateExpandedPanelHeight()
                    syncExpandedPanelState(session.snapshot)
                }
            }
            tabCell.addView(TextView(this).apply {
                text = tab.label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) textCol else mutedCol)
                setPadding(0, 0, 0, dp(5))
            })
            tabCell.addView(View(this).apply {
                setBackgroundColor(if (active) accent else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            })
            tabInner.addView(tabCell)
        }
        tabScroll.addView(tabInner)
        tabBarRow.addView(tabScroll)

        if (snap.isComposing) {
            val commitBtn = makePanelButton("確定", accent, Color.WHITE, accent) {
                isExpandedPanelOpen = false
                onEnter()
            }
            commitBtn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                marginStart = dp(4)
                bottomMargin = dp(3)
            }
            tabBarRow.addView(commitBtn)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(mutedCol)
            background = borderlessPressedBg(dark)
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(28)).apply {
                marginStart = dp(4)
                bottomMargin = dp(2)
            }
            setOnClickListener {
                isExpandedPanelOpen = false
                isEmojiSearchActive = false
                emojiSearchQuery.setLength(0)
                syncExpandedPanelState(session.snapshot)
            }
        }
        tabBarRow.addView(closeBtn)
        expandedHeader.addView(tabBarRow)
        expandedHeader.addView(View(this).apply {
            setBackgroundColor(border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val showContinuousEmoji = expandedTab == ExpandedTab.EMOJI && !isEmojiSearchActive
        if (::expandedRecyclerHost.isInitialized) {
            expandedRecyclerHost.visibility = if (showContinuousEmoji) View.VISIBLE else View.GONE
            expandedScroll.visibility = if (showContinuousEmoji) View.GONE else View.VISIBLE
        }

        when (expandedTab) {
            ExpandedTab.CANDIDATES -> populateCandidatesTab(snap, accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.EMOJI -> populateEmojiTab(dark, accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.KAOMOJI -> populateKaomojiTab(dark, border, textCol)
            ExpandedTab.TEMPLATES -> populateTemplatesTab(accent, cardBg, border, textCol, mutedCol)
            ExpandedTab.EDIT -> populateEditTab(accent, cardBg, border, textCol, mutedCol)
        }

        if (expandedTab != ExpandedTab.CANDIDATES && !isEmojiSearchActive) {
            addPanelFooterActions(dark, barBg, border, textCol, mutedCol, accent)
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

        if (snap.focusedAdjustableKana.length >= 2 || snap.canShrinkClause || snap.canExtendClause) {
            val dark = isDarkTheme()
            expandedContent.addView(TextView(this).apply {
                text = "区切れ位置（文字をタップで直接調整）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(mutedCol)
                setPadding(0, 0, 0, dp(4))
            })
            val bRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            val bShrink = makePanelButton("◀ 短", cardBg, textCol, border) {
                renderSnapshot(session.resizeFocusedClause(-1, getSurroundingTextBefore()))
            }.apply {
                isEnabled = snap.canShrinkClause
                alpha = if (snap.canShrinkClause) 1.0f else 0.32f
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(30)).apply { marginEnd = dp(4) }
            }
            val bScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, dp(30), 1f)
            }
            val bKanaRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bScroll.addView(bKanaRow)
            val bExtend = makePanelButton("長 ▶", cardBg, textCol, border) {
                renderSnapshot(session.resizeFocusedClause(+1, getSurroundingTextBefore()))
            }.apply {
                isEnabled = snap.canExtendClause
                alpha = if (snap.canExtendClause) 1.0f else 0.32f
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(30)).apply { marginStart = dp(4) }
            }
            bRow.addView(bShrink)
            bRow.addView(bScroll)
            bRow.addView(bExtend)
            expandedContent.addView(bRow)
            renderBoundaryKanaRow(bKanaRow, bScroll, snap, dark, accent)
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
                            val close = item.kind == CandidateKind.GLIDE_ALT || wasLast || snap.clauses.size <= 1
                            selectCandidateAndLog(snap, item, closeExpanded = close)
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

    private sealed class EmojiGridItem(val category: EmojiCategory) {
        class Header(cat: EmojiCategory) : EmojiGridItem(cat)
        class Glyph(cat: EmojiCategory, val emoji: String) : EmojiGridItem(cat)
    }

    private fun canRenderEmoji(emoji: String): Boolean {
        return hasGlyphCache.getOrPut(emoji) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { emojiGlyphPaint.hasGlyph(emoji) }.getOrDefault(true)
            } else {
                true
            }
        }
    }

    private fun populateEmojiTab(
        dark: Boolean,
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        if (isEmojiSearchActive) {
            populateEmojiSearchStrip(dark, accent, cardBg, border, textCol, mutedCol)
            return
        }

        val availDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 8f
        val cols = (availDp / 42f).toInt().coerceIn(8, 10)

        val recentEmojis = (settings.getRecentEmojis() + DEFAULT_RECENT_EMOJIS)
            .distinct()
            .filter(::canRenderEmoji)
            .take(cols * 2)

        val gridItems = ArrayList<EmojiGridItem>(2000)
        val categoriesPresent = ArrayList<EmojiCategory>(10)
        val headerPositions = LinkedHashMap<EmojiCategory, Int>(10)

        if (recentEmojis.isNotEmpty()) {
            headerPositions[EmojiCategory.RECENT] = gridItems.size
            categoriesPresent.add(EmojiCategory.RECENT)
            gridItems.add(EmojiGridItem.Header(EmojiCategory.RECENT))
            for (e in recentEmojis) {
                gridItems.add(EmojiGridItem.Glyph(EmojiCategory.RECENT, e))
            }
        }
        for (cat in EmojiCategory.CATALOG_CATEGORIES) {
            val emojis = emojiCatalog.entriesByCategory[cat].orEmpty()
                .map { it.emoji }
                .filter(::canRenderEmoji)
            if (emojis.isEmpty()) continue
            headerPositions[cat] = gridItems.size
            categoriesPresent.add(cat)
            gridItems.add(EmojiGridItem.Header(cat))
            for (e in emojis) {
                gridItems.add(EmojiGridItem.Glyph(cat, e))
            }
        }
        emojiHeaderPositions = headerPositions
        emojiCategoriesInAdapter = categoriesPresent

        // Sticky Gboard-style category icon strip + Japanese emoji search button
        val catRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(2), 0, dp(2), 0)
        }
        val tabCells = ArrayList<LinearLayout>(categoriesPresent.size)
        val curCat = categoriesPresent.getOrNull(selectedEmojiCategory) ?: categoriesPresent.firstOrNull()
        for ((idx, cat) in categoriesPresent.withIndex()) {
            val active = cat == curCat
            val catCell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    selectedEmojiCategory = idx
                    updateEmojiTabHighlight(idx, accent, textCol, mutedCol)
                    val targetPos = emojiHeaderPositions[cat] ?: 0
                    val rv = emojiRecyclerView
                    val lm = rv?.layoutManager as? GridLayoutManager
                    if (lm != null) {
                        programmaticEmojiScroll = true
                        lm.scrollToPositionWithOffset(targetPos, 0)
                        rv.post { programmaticEmojiScroll = false }
                    }
                }
            }
            catCell.addView(TextView(this).apply {
                text = cat.tabIcon
                gravity = Gravity.CENTER
                maxLines = 1
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                alpha = if (active) 1f else 0.58f
                setPadding(0, dp(6), 0, dp(5))
            })
            catCell.addView(View(this).apply {
                setBackgroundColor(if (active) accent else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            })
            tabCells.add(catCell)
            catRow.addView(catCell)
        }
        emojiTabCells = tabCells

        val searchBtn = TextView(this).apply {
            text = "🔍"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setTextColor(accent)
            background = borderlessPressedBg(dark)
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
            setOnClickListener {
                isEmojiSearchActive = true
                emojiSearchQuery.setLength(0)
                syncExpandedPanelState(session.snapshot)
            }
        }
        catRow.addView(searchBtn)
        expandedHeader.addView(catRow)
        expandedHeader.addView(View(this).apply {
            setBackgroundColor(border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        val existingRv = emojiRecyclerView
        if (existingRv == null || existingRv.parent !== expandedRecyclerHost) {
            expandedRecyclerHost.removeAllViews()
            val glm = GridLayoutManager(this, cols).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val item = emojiAdapter?.currentItems?.getOrNull(position)
                        return if (item is EmojiGridItem.Header) spanCount else 1
                    }
                }
            }
            val adapter = EmojiGridAdapter(dark, mutedCol) { emoji ->
                settings.addRecentEmoji(emoji)
                commitDirectExtraText(emoji)
            }
            adapter.submit(gridItems, dark, mutedCol)
            val rv = RecyclerView(this).apply {
                layoutManager = glm
                this.adapter = adapter
                clipToPadding = false
                setPadding(dp(4), dp(2), dp(4), dp(4))
                setItemViewCacheSize(24)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (programmaticEmojiScroll || dy == 0) return
                        val layoutMgr = recyclerView.layoutManager as? GridLayoutManager ?: return
                        val firstVis = layoutMgr.findFirstVisibleItemPosition()
                        if (firstVis == RecyclerView.NO_POSITION) return
                        val visCat = emojiAdapter?.currentItems?.getOrNull(firstVis)?.category ?: return
                        val catIdx = emojiCategoriesInAdapter.indexOf(visCat)
                        if (catIdx >= 0 && catIdx != selectedEmojiCategory) {
                            selectedEmojiCategory = catIdx
                            updateEmojiTabHighlight(catIdx, accent, textCol, mutedCol)
                        }
                    }

                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            programmaticEmojiScroll = false
                        }
                    }
                })
            }
            emojiAdapter = adapter
            emojiRecyclerView = rv
            expandedRecyclerHost.addView(rv)
        } else {
            val glm = existingRv.layoutManager as? GridLayoutManager
            if (glm != null && glm.spanCount != cols) {
                glm.spanCount = cols
            }
            emojiAdapter?.submit(gridItems, dark, mutedCol)
        }
    }

    private fun updateEmojiTabHighlight(activeIdx: Int, accent: Int, textCol: Int, mutedCol: Int) {
        for ((idx, cell) in emojiTabCells.withIndex()) {
            val active = idx == activeIdx
            val labelView = cell.getChildAt(0) as? TextView
            val lineView = cell.getChildAt(1)
            labelView?.alpha = if (active) 1f else 0.58f
            labelView?.setTextColor(if (active) textCol else mutedCol)
            lineView?.setBackgroundColor(if (active) accent else Color.TRANSPARENT)
        }
    }

    private fun populateEmojiSearchStrip(
        dark: Boolean,
        accent: Int,
        cardBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int
    ) {
        val rawQ = emojiSearchQuery.toString()
        val hiraPreview = if (rawQ.isEmpty()) {
            ""
        } else {
            RomajiDetector.analyze(rawQ, strictForDetection = false).finalizedHiragana()
        }

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val queryBox = TextView(this).apply {
            text = when {
                rawQ.isEmpty() -> "🔍 下のキーボードで絵文字を検索 (例: egao, neko, ai)"
                hiraPreview.isNotEmpty() && hiraPreview != rawQ -> "🔍 $rawQ ($hiraPreview)"
                else -> "🔍 $rawQ"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(if (rawQ.isEmpty()) mutedCol else textCol)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(6).toFloat()
                setStroke(dp(1), accent)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        searchBar.addView(queryBox)

        if (rawQ.isNotEmpty()) {
            val clearBtn = makePanelButton("クリア", cardBg, textCol, border) {
                emojiSearchQuery.setLength(0)
                populateExpandedPanel(session.snapshot)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    marginStart = dp(4)
                }
            }
            searchBar.addView(clearBtn)
        }
        val backBtn = makePanelButton("一覧", cardBg, accent, accent) {
            isEmojiSearchActive = false
            emojiSearchQuery.setLength(0)
            syncExpandedPanelState(session.snapshot)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginStart = dp(4)
            }
        }
        searchBar.addView(backBtn)
        expandedHeader.addView(searchBar)

        val matches = if (rawQ.isEmpty()) {
            (settings.getRecentEmojis() + DEFAULT_RECENT_EMOJIS)
                .distinct()
                .filter(::canRenderEmoji)
                .take(24)
        } else {
            emojiCatalog.search(rawQ, limit = 60)
                .map { it.emoji }
                .filter(::canRenderEmoji)
                .distinct()
                .take(28)
        }

        if (matches.isEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "一致する絵文字が見つかりません"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(mutedCol)
                setPadding(dp(8), dp(12), dp(8), dp(8))
            })
            return
        }

        val hScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        for (emoji in matches) {
            val cell = TextView(this).apply {
                text = emoji
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
                background = borderlessPressedBg(dark)
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                setOnClickListener {
                    settings.addRecentEmoji(emoji)
                    commitDirectExtraText(emoji)
                }
            }
            strip.addView(cell)
        }
        hScroll.addView(strip)
        expandedContent.addView(hScroll)
    }

    private inner class EmojiGridAdapter(
        private var dark: Boolean,
        private var headerTextColor: Int,
        private val onEmojiTap: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var currentItems: List<EmojiGridItem> = emptyList()
            private set

        fun submit(items: List<EmojiGridItem>, isDark: Boolean, mutedColor: Int) {
            currentItems = items
            dark = isDark
            headerTextColor = mutedColor
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = currentItems.size

        override fun getItemViewType(position: Int): Int =
            if (currentItems[position] is EmojiGridItem.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val tv = TextView(parent.context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(null, Typeface.BOLD)
                    setPadding(dp(6), dp(8), dp(6), dp(3))
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                object : RecyclerView.ViewHolder(tv) {}
            } else {
                val tv = TextView(parent.context).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22.5f)
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(40)
                    )
                }
                object : RecyclerView.ViewHolder(tv) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            when (val item = currentItems[position]) {
                is EmojiGridItem.Header -> {
                    tv.text = item.category.labelJa
                    tv.setTextColor(headerTextColor)
                }
                is EmojiGridItem.Glyph -> {
                    tv.text = item.emoji
                    tv.background = borderlessPressedBg(dark)
                    tv.setOnClickListener { onEmojiTap(item.emoji) }
                }
            }
        }
    }

    private fun populateKaomojiTab(
        dark: Boolean,
        border: Int,
        textCol: Int
    ) {
        val list = (COMMON_KAOMOJI + dictionaries.kaomojiList).distinct()
        val cols = 3
        val rowHeightPx = dp(36)
        var r = 0
        while (r < list.size) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (c in 0 until cols) {
                val km = list.getOrNull(r + c)
                if (km == null) {
                    rowLayout.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, rowHeightPx, 1f)
                    })
                } else {
                    val btn = TextView(this).apply {
                        text = km
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        setTextColor(textCol)
                        setPadding(dp(4), 0, dp(4), 0)
                        background = borderlessPressedBg(dark)
                        layoutParams = LinearLayout.LayoutParams(0, rowHeightPx, 1f)
                        setOnClickListener { commitDirectExtraText(km) }
                    }
                    rowLayout.addView(btn)
                }
                if (c < cols - 1) {
                    rowLayout.addView(View(this).apply {
                        setBackgroundColor(border)
                        layoutParams = LinearLayout.LayoutParams(dp(1), dp(20)).apply {
                            gravity = Gravity.CENTER_VERTICAL
                        }
                    })
                }
            }
            expandedContent.addView(rowLayout)
            expandedContent.addView(View(this).apply {
                setBackgroundColor(border)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            })
            r += cols
        }
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
    }

    private fun borderlessPressedBg(dark: Boolean): StateListDrawable {
        val pressedColor = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(28, 32, 35, 40)
        val pressedDrawable = GradientDrawable().apply {
            setColor(pressedColor)
            cornerRadius = dp(6).toFloat()
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressedDrawable)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun addPanelFooterActions(
        dark: Boolean,
        barBg: Int,
        border: Int,
        textCol: Int,
        mutedCol: Int,
        accent: Int
    ) {
        expandedFooter.setBackgroundColor(barBg)
        expandedFooter.addView(View(this).apply {
            setBackgroundColor(border)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            )
        }
        fun footerCell(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(color)
                background = borderlessPressedBg(dark)
                setOnClickListener { onClick() }
            }
        }
        val backKb = footerCell("⌨ キーボードへ", accent, bold = true) {
            isExpandedPanelOpen = false
            syncExpandedPanelState(session.snapshot)
        }
        val spaceBtn = footerCell("空白", mutedCol) {
            commitDirectExtraText(" ")
        }
        val bsBtn = footerCell("⌫ 削除", textCol) {
            clearUndoCommit()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        val enterBtn = footerCell("⏎ 改行", accent, bold = true) {
            if (!sendDefaultEditorAction(true)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
        }
        val items = listOf(backKb to 1.45f, spaceBtn to 1.0f, bsBtn to 1.0f, enterBtn to 1.05f)
        for ((cell, weight) in items) {
            cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            row.addView(cell)
        }
        expandedFooter.addView(row)
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
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
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

    companion object {
        private val DEFAULT_RECENT_EMOJIS = listOf(
            "😀", "😂", "🤣", "😊", "🙏", "🙇", "👍", "✨", "🔥",
            "❤️", "🎉", "😭", "😅", "🤔", "👀", "✅", "☀️", "🌸",
            "👌", "🙌", "💻", "🫡", "🥹", "🫶", "💪", "🚀", "☕"
        )

        private val COMMON_KAOMOJI = listOf(
            "(^^)", "(´・ω・`)", "＼(^o^)／", "m(_ _)m", "(*^^*)", "(；´Д`)",
            "(´∀`)", "(・∀・)", "(>_<)", "(｀・ω・´)", "(*´ω`*)", "(・ω・)",
            "(^_-)-☆", "(T_T)", "(^o^)", "(-_-;)", "(*_*)", "٩(ˊᗜˋ*)و"
        )
    }
}
