package io.github.sp9r.meltype

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.AppEngineHolder
import io.github.sp9r.meltype.core.CandidateKind
import io.github.sp9r.meltype.core.ClipboardImageStore
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.GlideEvalLogger
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.MozcRuntime
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import io.github.sp9r.meltype.glide.TouchPoint
import io.github.sp9r.meltype.ui.GlideTraceLabView
import io.github.sp9r.meltype.ui.KeyboardBlueprintView
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private enum class WorkspaceTab(val label: String) {
        STUDIO("スタジオ"),
        LAYOUT("レイアウト・設定"),
        LEXICON("辞書・操作")
    }

    private data class GlideEvalPrompt(
        val id: String,
        val category: String,
        val text: String
    )

    private lateinit var settings: MeltypeSettings
    private lateinit var evalLogger: GlideEvalLogger
    private val executor = Executors.newSingleThreadExecutor()

    private var activeTab: WorkspaceTab = WorkspaceTab.STUDIO
    private var logExpanded: Boolean = false
    private var dictionaries: MeltypeDictionaries? = null
    private var scoreEngine: ScoreEngine? = null
    private var converter: AndroidMozcConverter? = null
    private var glideDecoder: GlideDecoder? = null
    private var evalPrompts: List<GlideEvalPrompt> = emptyList()
    private var clearConfirmPending: Boolean = false
    private val pendingSummaryRefresh = Runnable { refreshEvalSummaryAsync() }

    private lateinit var rootScroll: ScrollView
    private lateinit var setupStrip: LinearLayout
    private lateinit var setupHintText: TextView
    private lateinit var setupEnableBtn: TextView
    private lateinit var setupSwitchBtn: TextView
    private lateinit var headerStatusDot: View
    private lateinit var headerStatusText: TextView
    private lateinit var headerStatusPill: LinearLayout

    private val tabButtons = ArrayList<TextView>()
    private val workspaceContainers = ArrayList<LinearLayout>()

    private lateinit var sandboxInput: EditText
    private lateinit var sandboxImagePreviewRow: LinearLayout
    private lateinit var sandboxImageThumb: ImageView
    private lateinit var sandboxImageMeta: TextView
    private lateinit var probeResultBanner: TextView
    private lateinit var evalPromptCounter: TextView
    private lateinit var evalTargetTextView: TextView
    private lateinit var evalPromptInput: EditText
    private lateinit var evalToggleBtn: TextView
    private lateinit var evalClearBtn: TextView
    private lateinit var evalSummaryLabel: TextView
    private lateinit var glideLabView: GlideTraceLabView
    private lateinit var blueprintView: KeyboardBlueprintView

    private lateinit var engineSpecLabel: TextView
    private lateinit var selfTestBadgeDot: View
    private lateinit var selfTestBadgeText: TextView
    private lateinit var selfTestToggleBtn: TextView
    private lateinit var selfTestLogDrawer: LinearLayout
    private lateinit var selfTestLogView: TextView
    private lateinit var userDictContainer: LinearLayout
    private lateinit var userPhraseContainer: LinearLayout
    private var editingPhraseText: String? = null

    private var cPage = 0
    private var cRail = 0
    private var cSurface = 0
    private var cSurfaceSubtle = 0
    private var cText = 0
    private var cMuted = 0
    private var cFaint = 0
    private var cBorder = 0
    private var cBorderStrong = 0
    private var cPrimary = 0
    private var cPrimaryText = 0
    private var cAccent = 0
    private var cAccentTint = 0
    private var cSuccess = 0
    private var cWarning = 0
    private var isDarkPalette = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MeltypeSettings(this)
        settings.migrateV170()
        settings.migrateV174()
        evalLogger = GlideEvalLogger(filesDir)
        applyLaunchIntentOptions(intent)
        evalPrompts = loadGlideEvalPrompts()
        val savedIdx = savedInstanceState?.getInt(KEY_ACTIVE_TAB, 0) ?: 0
        activeTab = WorkspaceTab.entries.getOrElse(savedIdx) { WorkspaceTab.STUDIO }
        resolvePalette()
        buildUi()
        initEngineAsync(runSelfTest = intent.getBooleanExtra("selftest", false))
        if (intent.getBooleanExtra("conveval", false)) {
            executor.execute { runConversionEval() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntentOptions(intent)
        evalPrompts = loadGlideEvalPrompts()
        if (::evalPromptCounter.isInitialized) {
            bindActivePrompt(settings.activePromptIndex)
            syncEvalToggleVisual()
            refreshEvalSummaryAsync()
        }
        if (intent.getBooleanExtra("selftest", false)) {
            executor.execute { runEngineSelfTest(writeFile = true) }
        }
        if (intent.getBooleanExtra("conveval", false)) {
            executor.execute { runConversionEval() }
        }
    }

    /**
     * Batch conversion audit: reads files/conv_eval_in.txt (romaji<TAB>expected per line),
     * types each line char-by-char through the live CompositionSession, and also converts the
     * plain romaji->kana reading with Mozc alone, so Meltype-specific regressions can be isolated.
     * Writes files/conv_eval_out.tsv. Debug-only tooling; nothing is learned.
     */
    private fun runConversionEval() {
        val inFile = File(filesDir, "conv_eval_in.txt")
        val outFile = File(filesDir, "conv_eval_out.tsv")
        if (!inFile.exists()) {
            outFile.writeText("ERROR\tmissing conv_eval_in.txt\n", Charsets.UTF_8)
            return
        }
        val dicts = dictionaries ?: AppEngineHolder.getDictionaries(applicationContext).also { dictionaries = it }
        MozcRuntime.ensureInitialized(applicationContext)
        val engine = ScoreEngine(dicts)
        val converter = AndroidMozcConverter(dicts)
        val sb = StringBuilder("romaji\texpected\tmeltype\tmozc_only\tkana\tclauses\tms\n")
        for (line in inFile.readLines(Charsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            val romaji = parts[0].trim()
            val expected = parts.getOrElse(1) { "" }.trim()
            val t0 = System.nanoTime()
            val session = CompositionSession(dicts, engine, converter)
            for (ch in romaji) session.appendChar(ch)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val snap = session.snapshot
            val kana = io.github.sp9r.meltype.core.RomajiDetector.analyze(romaji).finalizedHiragana()
            val mozcOnly = runCatching { converter.convertClauses(kana)?.joinToString("") { it.text } }
                .getOrNull() ?: ""
            val clauses = snap.clauses.joinToString("|") {
                (if (it.isEnglish) "E:" else "J:") + it.raw + "=" + it.text
            }
            sb.append(romaji).append('\t').append(expected).append('\t')
                .append(snap.previewText).append('\t').append(mozcOnly).append('\t')
                .append(kana).append('\t').append(clauses).append('\t').append(ms).append('\n')
        }
        // Typo simulation: one same-row neighbor substitution per Japanese line, with the tap
        // landing near the border of the intended key (hint weight 0.6).
        sb.append("\n#typo\tromaji\texpected\ttyped\tpreview\tauto_ok\tchip_ok\tchips\n")
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        for (line in inFile.readLines(Charsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            val romaji = parts[0].trim()
            val expected = parts.getOrElse(1) { "" }.trim()
            if (romaji.any { it !in 'a'..'z' && it != '-' } || expected.any { it in 'A'..'Z' || it in 'a'..'z' }) continue
            val positions = romaji.indices.filter { it >= 1 && romaji[it] in 'a'..'z' }
            if (positions.isEmpty()) continue
            val k = positions[Math.floorMod(romaji.hashCode(), positions.size)]
            val intended = romaji[k]
            val row = rows.first { intended in it }
            val ci = row.indexOf(intended)
            val wrong = if (Math.floorMod(romaji.hashCode() / 7, 2) == 0 && ci > 0) row[ci - 1]
                else if (ci + 1 < row.length) row[ci + 1] else row[ci - 1]
            val typed = romaji.substring(0, k) + wrong + romaji.substring(k + 1)
            val session = CompositionSession(dicts, engine, converter)
            for ((idx, ch) in typed.withIndex()) {
                session.appendChar(ch, null, if (idx == k) mapOf(intended to 0.6f) else emptyMap())
            }
            val snap = session.snapshot
            val chips = snap.candidates.filter { it.subtitle == "補正" }.map { it.text }
            sb.append("typo\t").append(romaji).append('\t').append(expected).append('\t')
                .append(typed).append('\t').append(snap.previewText).append('\t')
                .append(snap.previewText == expected).append('\t')
                .append(chips.contains(expected)).append('\t')
                .append(chips.joinToString("|")).append('\n')
        }

        // Context: the committed previous phrase should steer the next conversion.
        sb.append("\n#ctx\tprev\tnext\texpected\twith_ctx\twithout_ctx\n")
        val ctxCases = listOf(
            Triple("byouinni", "itta", "行った"),
            Triple("gakkoumade", "aruita", "歩いた"),
            Triple("senseini", "kiita", "聞いた"),
            Triple("kusuriwo", "nonda", "飲んだ"),
            Triple("eigawo", "mita", "見た"),
            Triple("denshani", "notta", "乗った"),
            Triple("kareha", "souitta", "そう言った"),
            Triple("ashitaha", "hareru", "晴れる")
        )
        for ((prev, next, expected) in ctxCases) {
            val first = CompositionSession(dicts, engine, converter)
            for (ch in prev) first.appendChar(ch)
            val prevSnap = first.snapshot
            val prevText = prevSnap.previewText
            val second = CompositionSession(dicts, engine, converter)
            second.rememberCommitted(prevSnap.clauses.map { Triple(it.reading, it.text, it.isEnglish) })
            for (ch in next) second.appendChar(ch, prevText)
            val plain = CompositionSession(dicts, engine, converter)
            for (ch in next) plain.appendChar(ch)
            sb.append("ctx\t").append(prev).append('\t').append(next).append('\t').append(expected).append('\t')
                .append(prevText).append('+').append(second.snapshot.previewText).append('\t')
                .append(plain.snapshot.previewText).append('\n')
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun applyLaunchIntentOptions(launchIntent: Intent?) {
        if (launchIntent == null) return
        if (launchIntent.hasExtra("glide_eval_logging")) {
            settings.glideEvalLoggingEnabled = launchIntent.getBooleanExtra("glide_eval_logging", false)
        }
        if (launchIntent.getBooleanExtra("glide_eval_reset_prompt", false)) {
            settings.activePromptIndex = 0
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTab.ordinal)
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
        if (::evalSummaryLabel.isInitialized) {
            evalPrompts = loadGlideEvalPrompts()
            bindActivePrompt(settings.activePromptIndex)
            syncEvalToggleVisual()
            refreshEvalSummaryAsync()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshSystemStatus()
            if (::evalSummaryLabel.isInitialized) {
                refreshEvalSummaryAsync()
            }
        }
    }

    private fun resolvePalette() {
        val dark = when (settings.themeMode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        isDarkPalette = dark
        if (dark) {
            cPage = Color.parseColor("#111317")
            cRail = Color.parseColor("#16191F")
            cSurface = Color.parseColor("#1B1E25")
            cSurfaceSubtle = Color.parseColor("#22262E")
            cText = Color.parseColor("#ECEEF0")
            cMuted = Color.parseColor("#8F96A0")
            cFaint = Color.parseColor("#606772")
            cBorder = Color.parseColor("#2C313A")
            cBorderStrong = Color.parseColor("#3B414D")
            cPrimary = Color.parseColor("#ECEEF0")
            cPrimaryText = Color.parseColor("#15181D")
            cAccent = Color.parseColor("#5C8AC4")
            cAccentTint = Color.argb(38, 92, 138, 196)
            cSuccess = Color.parseColor("#5E9E75")
            cWarning = Color.parseColor("#D49542")
        } else {
            cPage = Color.parseColor("#F5F6F7")
            cRail = Color.parseColor("#ECEEF1")
            cSurface = Color.parseColor("#FFFFFF")
            cSurfaceSubtle = Color.parseColor("#F7F8FA")
            cText = Color.parseColor("#202328")
            cMuted = Color.parseColor("#6D747E")
            cFaint = Color.parseColor("#969BA2")
            cBorder = Color.parseColor("#DFE2E6")
            cBorderStrong = Color.parseColor("#CFD4DA")
            cPrimary = Color.parseColor("#25282D")
            cPrimaryText = Color.parseColor("#FFFFFF")
            cAccent = Color.parseColor("#4A6F9E")
            cAccentTint = Color.argb(26, 74, 111, 158)
            cSuccess = Color.parseColor("#4C835F")
            cWarning = Color.parseColor("#A66B1F")
        }
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPage)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Architectural Quiet Header Bar (52dp)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cSurface)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        }

        topBar.addView(MeltypeLogoMarkView().apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
        })

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(TextView(this).apply {
            text = "Meltype"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        })
        titleRow.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(cMuted)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = boxBg(cSurfaceSubtle, cBorder, 4)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        })
        topBar.addView(titleRow)

        headerStatusPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            setOnClickListener { handleImeStatusAction() }
        }
        headerStatusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
        }
        headerStatusText = TextView(this).apply {
            text = "確認中"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        }
        headerStatusPill.addView(headerStatusDot)
        headerStatusPill.addView(headerStatusText)
        topBar.addView(headerStatusPill)
        outer.addView(topBar)
        outer.addView(hairline())

        // Conditional Setup Onboarding Bar (collapses completely once enabled & active)
        setupStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cAccentTint)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        setupHintText = TextView(this).apply {
            text = "Meltype を有効化して既定のキーボードに設定してください"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        setupEnableBtn = makeCompactChip("1. 有効化", filled = true) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginEnd = dp(6)
            }
        }
        setupSwitchBtn = makeCompactChip("2. 切替", filled = false) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        }
        setupStrip.addView(setupHintText)
        setupStrip.addView(setupEnableBtn)
        setupStrip.addView(setupSwitchBtn)
        outer.addView(setupStrip)

        // Precision 3-Segment Workspace Switcher Rail
        val railBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cRail)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        tabButtons.clear()
        for (tab in WorkspaceTab.entries) {
            val btn = TextView(this).apply {
                text = tab.label
                gravity = Gravity.CENTER
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (tab.ordinal < WorkspaceTab.entries.lastIndex) marginEnd = dp(4)
                }
                setOnClickListener { switchWorkspace(tab) }
            }
            tabButtons.add(btn)
            railBar.addView(btn)
        }
        outer.addView(railBar)
        outer.addView(hairline())

        // Main Workspace Scroll Viewport
        rootScroll = ScrollView(this).apply {
            setBackgroundColor(cPage)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val workspaceHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        rootScroll.addView(workspaceHost)
        outer.addView(rootScroll)

        workspaceContainers.clear()
        val studioPane = buildStudioWorkspace()
        val layoutPane = buildLayoutWorkspace()
        val lexiconPane = buildLexiconWorkspace()
        workspaceContainers.addAll(listOf(studioPane, layoutPane, lexiconPane))
        for (pane in workspaceContainers) {
            workspaceHost.addView(pane)
        }

        switchWorkspace(activeTab)
        refreshSystemStatus()

        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left, sys.top, sys.right, 0)
            workspaceHost.setPadding(dp(14), dp(12), dp(14), dp(24) + sys.bottom)
            insets
        }
        setContentView(outer)
        WindowCompat.getInsetsController(window, outer).apply {
            isAppearanceLightStatusBars = !isDarkPalette
            isAppearanceLightNavigationBars = !isDarkPalette
        }
    }

    private fun buildStudioWorkspace(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 0. Real-Input Glide Prompt Runner & Telemetry Recorder
        pane.addView(buildRealInputEvalCard())

        // 1. Live Conversion & Typo Repair Sandbox
        val sandboxCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        val sandboxHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        sandboxHead.addView(microHeading("LIVE CONVERSION SANDBOX").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        sandboxHead.addView(TextView(this).apply {
            text = "消去"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(cMuted)
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener {
                sandboxInput.setText("")
                probeResultBanner.visibility = View.GONE
                sandboxImagePreviewRow.visibility = View.GONE
                sandboxImageThumb.setImageDrawable(null)
            }
        })
        sandboxCard.addView(sandboxHead)

        sandboxInput = EditText(this).apply {
            hint = "ここをタップして文字キーのタップやグライド入力・画像貼付を試せます"
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString()?.trim().orEmpty()
                    if (raw.isEmpty()) {
                        probeResultBanner.visibility = View.GONE
                    }
                    if (settings.glideEvalLoggingEnabled && ::evalSummaryLabel.isInitialized) {
                        evalSummaryLabel.removeCallbacks(pendingSummaryRefresh)
                        evalSummaryLabel.postDelayed(pendingSummaryRefresh, 280L)
                    }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    settings.activePromptId = "sandbox"
                    settings.activePromptExpected = ""
                }
            }
        }
        ViewCompat.setOnReceiveContentListener(
            sandboxInput,
            arrayOf("image/*"),
            OnReceiveContentListener { _, payload ->
                val split = payload.partition { item -> item.uri != null }
                val uriContent = split.first
                if (uriContent != null && uriContent.clip.itemCount > 0) {
                    val uri = uriContent.clip.getItemAt(0).uri
                    if (uri != null) showSandboxReceivedImage(uri, uriContent.clip.description.getMimeType(0))
                }
                split.second
            }
        )
        sandboxCard.addView(sandboxInput)

        sandboxImagePreviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
        }
        sandboxImageThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(10) }
        }
        sandboxImageMeta = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        sandboxImagePreviewRow.addView(sandboxImageThumb)
        sandboxImagePreviewRow.addView(sandboxImageMeta)
        sandboxCard.addView(sandboxImagePreviewRow)

        // 1-tap sample probes to showcase live Japanese/English conversion + typo correction
        val sampleScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val sampleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val probes = listOf(
            "onegaishimsu" to "誤字: onegaishimsu",
            "arigatoi " to "隣接: arigatoi",
            "shumire-shon" to "誤綴: shumire-shon",
            "kyouhagoogledekensaku" to "日英混在: kyouha+google",
            "128*16" to "計算: 128*16"
        )
        for ((input, label) in probes) {
            val chip = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(cMuted)
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = boxBg(cSurfaceSubtle, cBorder, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(28)
                ).apply { marginEnd = dp(6) }
                setOnClickListener { runSandboxProbe(input) }
            }
            sampleRow.addView(chip)
        }
        sampleScroll.addView(sampleRow)
        sandboxCard.addView(sampleScroll)

        probeResultBanner = TextView(this).apply {
            visibility = View.GONE
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cAccent)
            setBackgroundColor(cAccentTint)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        sandboxCard.addView(probeResultBanner)
        pane.addView(sandboxCard)

        // 2. Interactive Glide Trace Lab
        val labCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        val labHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        labHead.addView(microHeading("GLIDE GEOMETRY LAB").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        labHead.addView(TextView(this).apply {
            text = "実線デコード実験"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(cMuted)
        })
        labCard.addView(labHead)

        glideLabView = GlideTraceLabView(this).apply {
            darkTheme = isDarkPalette
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
            onTraceSampled = { normPts, isFinal ->
                val dec = glideDecoder
                if (dec != null && normPts.size >= 2) {
                    val results = dec.decode(normPts, maxResults = 3)
                    val label = if (isFinal) "実指スワイプ解析" else "軌道を解析中..."
                    updateResultsOnly(label, results)
                }
            }
        }
        labCard.addView(glideLabView)

        val demoScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val demoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val demoWords = listOf("watashi", "arigatou", "konichiwa", "kakuninshimasu", "google")
        for (w in demoWords) {
            val chip = TextView(this).apply {
                text = "なぞる: $w"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(cText)
                gravity = Gravity.CENTER
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = boxBg(cSurfaceSubtle, cBorder, 4)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(28)
                ).apply { marginEnd = dp(6) }
                setOnClickListener { showGlideDemo(w) }
            }
            demoRow.addView(chip)
        }
        demoScroll.addView(demoRow)
        labCard.addView(demoScroll)
        pane.addView(labCard)

        // 3. Engine Telemetry & Collapsible Diagnostics Drawer
        val diagCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        val diagHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val diagInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        selfTestBadgeDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) }
            background = circleDrawable(cMuted)
        }
        selfTestBadgeText = TextView(this).apply {
            text = "エンジン準備中..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        }
        statusRow.addView(selfTestBadgeDot)
        statusRow.addView(selfTestBadgeText)
        diagInfo.addView(statusRow)

        engineSpecLabel = TextView(this).apply {
            text = "Mozc · 辞書ロード中..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(cMuted)
            setPadding(0, dp(2), 0, 0)
        }
        diagInfo.addView(engineSpecLabel)
        diagHeader.addView(diagInfo)

        val runTestBtn = makeCompactChip("検証実行", filled = false) {
            selfTestBadgeText.text = "検証中..."
            selfTestBadgeDot.background = circleDrawable(cAccent)
            executor.execute { runEngineSelfTest(writeFile = false) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                marginEnd = dp(6)
            }
        }
        selfTestToggleBtn = makeCompactChip("詳細 ▾", filled = false) {
            logExpanded = !logExpanded
            syncSelfTestDrawerVisibility()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
        }
        diagHeader.addView(runTestBtn)
        diagHeader.addView(selfTestToggleBtn)
        diagCard.addView(diagHeader)

        selfTestLogDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        selfTestLogDrawer.addView(hairline())
        selfTestLogView = TextView(this).apply {
            text = "待機中"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(cMuted)
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        selfTestLogDrawer.addView(selfTestLogView)
        diagCard.addView(selfTestLogDrawer)
        pane.addView(diagCard)

        return pane
    }

    private fun buildRealInputEvalCard(): LinearLayout {
        val card = makePanelFrame()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        titleCol.addView(microHeading("REAL-INPUT GLIDE EVAL"))
        evalPromptCounter = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(evalPromptCounter)
        head.addView(titleCol)

        evalToggleBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
            setOnClickListener {
                settings.glideEvalLoggingEnabled = !settings.glideEvalLoggingEnabled
                syncEvalToggleVisual()
                refreshEvalSummaryAsync()
            }
        }
        head.addView(evalToggleBtn)
        card.addView(head)

        evalTargetTextView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = boxBg(cAccentTint, cAccent, 5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
        }
        card.addView(evalTargetTextView)

        evalPromptInput = EditText(this).apply {
            hint = "上のお題を Meltype でグライド入力して「次のお題 →」を押してください"
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(8))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (settings.glideEvalLoggingEnabled && ::evalSummaryLabel.isInitialized) {
                        evalSummaryLabel.removeCallbacks(pendingSummaryRefresh)
                        evalSummaryLabel.postDelayed(pendingSummaryRefresh, 280L)
                    }
                }
            })
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    val prompt = evalPrompts.getOrNull(settings.activePromptIndex.coerceIn(0, (evalPrompts.size - 1).coerceAtLeast(0)))
                    if (prompt != null) {
                        settings.activePromptId = prompt.id
                        settings.activePromptExpected = prompt.text
                    }
                }
            }
        }
        card.addView(evalPromptInput)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val prevBtn = makeCompactChip("◀ 前", filled = false) {
            if (evalPrompts.isEmpty()) return@makeCompactChip
            val nextIdx = if (settings.activePromptIndex <= 0) evalPrompts.lastIndex else settings.activePromptIndex - 1
            evalPromptInput.setText("")
            bindActivePrompt(nextIdx)
            refreshEvalSummaryAsync()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(6)
            }
        }
        val retryBtn = makeCompactChip("やり直し", filled = false) {
            val prompt = evalPrompts.getOrNull(settings.activePromptIndex)
            val typed = evalPromptInput.text?.toString().orEmpty()
            if (settings.glideEvalLoggingEnabled && prompt != null && typed.isNotBlank()) {
                evalLogger.logPromptDone(prompt.id, prompt.category, prompt.text, typed, "retry")
            }
            evalPromptInput.setText("")
            evalPromptInput.requestFocus()
            refreshEvalSummaryAsync()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                marginEnd = dp(6)
            }
        }
        val nextBtn = makeCompactChip("次のお題 →", filled = true) {
            if (evalPrompts.isEmpty()) return@makeCompactChip
            val curIdx = settings.activePromptIndex.coerceIn(0, evalPrompts.lastIndex)
            val prompt = evalPrompts[curIdx]
            val typed = evalPromptInput.text?.toString().orEmpty()
            if (settings.glideEvalLoggingEnabled) {
                val action = if (typed.isBlank()) "skip" else "next"
                evalLogger.logPromptDone(prompt.id, prompt.category, prompt.text, typed, action)
            }
            evalPromptInput.setText("")
            bindActivePrompt((curIdx + 1) % evalPrompts.size)
            evalPromptInput.requestFocus()
            refreshEvalSummaryAsync()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
        }
        btnRow.addView(prevBtn)
        btnRow.addView(retryBtn)
        btnRow.addView(nextBtn)
        card.addView(btnRow)

        card.addView(hairline())

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        evalSummaryLabel = TextView(this).apply {
            text = "集計中..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTextColor(cMuted)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val refreshBtn = makeCompactChip("更新", filled = false) {
            refreshEvalSummaryAsync()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply {
                marginEnd = dp(6)
            }
        }
        evalClearBtn = makeCompactChip("消去", filled = false) {
            if (!clearConfirmPending) {
                clearConfirmPending = true
                evalClearBtn.text = "確定消去?"
                evalClearBtn.setTextColor(cWarning)
                evalClearBtn.postDelayed({
                    clearConfirmPending = false
                    if (::evalClearBtn.isInitialized) {
                        evalClearBtn.text = "消去"
                        evalClearBtn.setTextColor(cText)
                    }
                }, 3000L)
            } else {
                clearConfirmPending = false
                evalClearBtn.text = "消去"
                evalClearBtn.setTextColor(cText)
                executor.execute {
                    evalLogger.clear()
                    runOnUiThread { refreshEvalSummaryAsync() }
                }
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
        }
        footer.addView(evalSummaryLabel)
        footer.addView(refreshBtn)
        footer.addView(evalClearBtn)
        card.addView(footer)

        bindActivePrompt(settings.activePromptIndex)
        syncEvalToggleVisual()
        refreshEvalSummaryAsync()
        return card
    }

    private fun loadGlideEvalPrompts(): List<GlideEvalPrompt> {
        val file = File(filesDir, "glide_prompts.json")
        if (file.isFile) {
            val parsed = runCatching {
                val arr = JSONArray(file.readText(Charsets.UTF_8))
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id").ifBlank { "p${i + 1}" }
                        val cat = obj.optString("category").ifBlank { "実測" }
                        val txt = obj.optString("text").trim()
                        if (txt.isNotEmpty()) {
                            add(GlideEvalPrompt(id, cat, txt))
                        }
                    }
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return listOf(
            GlideEvalPrompt("sample_01", "標準例", "おはようございます"),
            GlideEvalPrompt("sample_02", "日英混在", "今日はGoogleで検索します"),
            GlideEvalPrompt("sample_03", "標準例", "ありがとうございます")
        )
    }

    private fun bindActivePrompt(rawIndex: Int) {
        if (evalPrompts.isEmpty()) return
        val idx = rawIndex.coerceIn(0, evalPrompts.lastIndex)
        settings.activePromptIndex = idx
        val prompt = evalPrompts[idx]
        settings.activePromptId = prompt.id
        settings.activePromptExpected = prompt.text
        evalPromptCounter.text = "お題 ${idx + 1} / ${evalPrompts.size} · ${prompt.category}"
        evalTargetTextView.text = prompt.text
    }

    private fun syncEvalToggleVisual() {
        val on = settings.glideEvalLoggingEnabled
        evalToggleBtn.text = if (on) "記録: ON" else "記録: OFF"
        evalToggleBtn.setTextColor(if (on) Color.WHITE else cMuted)
        evalToggleBtn.background = boxBg(
            if (on) cAccent else cSurfaceSubtle,
            if (on) cAccent else cBorder,
            5
        )
    }

    private fun refreshEvalSummaryAsync() {
        if (!::evalLogger.isInitialized || !::evalSummaryLabel.isInitialized) return
        executor.execute {
            val s = evalLogger.readSummary()
            val statusStr = if (settings.glideEvalLoggingEnabled) "記録中" else "停止中"
            val latestStr = s.recentGlides.lastOrNull()?.let { "\n最新: $it" }.orEmpty()
            val text = "$statusStr · ${s.glideCount}スワイプ / 戻し${s.glideUndoCount} / 候補${s.altPickCount} / 完了${s.promptDoneCount}件$latestStr"
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    evalSummaryLabel.text = text
                }
            }
        }
    }

    private fun buildLayoutWorkspace(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Real-Time Proportional Keyboard Blueprint + Free Height Control
        val previewCard = makePanelFrame()
        val pHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        pHead.addView(microHeading("PROPORTIONAL BLUEPRINT").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        previewCard.addView(pHead)

        blueprintView = KeyboardBlueprintView(this).apply {
            previewDark = isDarkPalette
            heightScale = settings.keyboardHeightScale
            sideMarginDp = settings.keyboardSideMarginDp
            showNumberRow = settings.showNumberRow
            showSubLabels = settings.showSubLabels
            oneHandedMode = settings.oneHandedMode
            punctuationStyle = settings.punctuationStyle
            bottomKeyMode = settings.bottomKeyMode
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(12), 0, dp(12), dp(6)) }
        }
        previewCard.addView(blueprintView)
        addFreeHeightControl(previewCard)
        addSideMarginControl(previewCard)
        pane.addView(previewCard)

        // 2. Segmented Appearance & Mode Controls
        val modeCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        addOptionGroup(
            modeCard,
            "外観テーマ",
            listOf(
                "自動" to (settings.themeMode == ThemeMode.SYSTEM),
                "ライト" to (settings.themeMode == ThemeMode.LIGHT),
                "ダーク" to (settings.themeMode == ThemeMode.DARK)
            )
        ) { idx ->
            settings.themeMode = ThemeMode.entries[idx]
            recreate()
        }
        modeCard.addView(hairline())
        addOptionGroup(
            modeCard,
            "片手配置",
            listOf(
                OneHandedMode.FULL.labelJa to (settings.oneHandedMode == OneHandedMode.FULL),
                OneHandedMode.LEFT.labelJa to (settings.oneHandedMode == OneHandedMode.LEFT),
                OneHandedMode.RIGHT.labelJa to (settings.oneHandedMode == OneHandedMode.RIGHT)
            )
        ) { idx ->
            val mode = OneHandedMode.entries[idx]
            settings.oneHandedMode = mode
            blueprintView.oneHandedMode = mode
        }
        modeCard.addView(hairline())
        addOptionGroup(
            modeCard,
            "句読点スタイル",
            listOf(
                PunctuationStyle.JAPANESE.labelJa to (settings.punctuationStyle == PunctuationStyle.JAPANESE),
                PunctuationStyle.WIDE_LATIN.labelJa to (settings.punctuationStyle == PunctuationStyle.WIDE_LATIN),
                PunctuationStyle.HALF.labelJa to (settings.punctuationStyle == PunctuationStyle.HALF)
            )
        ) { idx ->
            val style = PunctuationStyle.entries[idx]
            settings.punctuationStyle = style
            blueprintView.punctuationStyle = style
        }
        modeCard.addView(hairline())
        // Gboard's "絵文字キー" / "言語切り替えキー": the other function moves to a long-press.
        addOptionGroup(
            modeCard,
            "、の右のキー（他方は長押し）",
            listOf(
                "絵文字（Gboard標準）" to (settings.bottomKeyMode == BottomKeyMode.EMOJI),
                "日/英 言語切替" to (settings.bottomKeyMode == BottomKeyMode.LANGUAGE)
            )
        ) { idx ->
            val mode = BottomKeyMode.entries[idx]
            settings.bottomKeyMode = mode
            blueprintView.bottomKeyMode = mode
        }
        modeCard.addView(hairline())
        // One-tap presets: Gboard as shipped, or Gboard with the number row and language key turned on.
        addOptionGroup(
            modeCard,
            "配置プリセット",
            listOf(
                "Gboard標準" to (settings.bottomKeyMode == BottomKeyMode.EMOJI && !settings.showNumberRow),
                "数字段＋言語キー" to (settings.bottomKeyMode == BottomKeyMode.LANGUAGE && settings.showNumberRow)
            )
        ) { idx ->
            if (idx == 0) {
                settings.bottomKeyMode = BottomKeyMode.EMOJI
                settings.showNumberRow = false
            } else {
                settings.bottomKeyMode = BottomKeyMode.LANGUAGE
                settings.showNumberRow = true
            }
            recreate()
        }
        pane.addView(modeCard)

        // 3. Engine & Key Interaction Switch Matrix
        val switchCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        switchCard.addView(microHeading("INPUT & KEY BEHAVIOUR").apply {
            setPadding(dp(12), dp(10), dp(12), dp(4))
        })
        addSwitchRow(switchCard, "グライド入力（スワイプ入力）", "QWERTYキー上を滑らせて単語をデコード", settings.glideEnabled) {
            settings.glideEnabled = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "グライド入力を英語前提で判定", "ON推奨：日本語モード中でもグライドは英単語だけを候補にする", settings.glideEnglishOnly) {
            settings.glideEnglishOnly = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "ローマ字ミスタイプ自動補正", "打鍵ミス・隣接誤打・mb/mp→ん・カタカナ誤綴りを補正", settings.typoCorrectionEnabled) {
            settings.typoCorrectionEnabled = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "◀▶キーで入力中の文字全体を移動", "OFF: 文節の長さを変え、限界で次の文節へ移動／ON: 文字単位でカーソルを移動（長さ変更は「短」「長」ボタン）", settings.arrowKeysMoveCaret) {
            settings.arrowKeysMoveCaret = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "入力途中の定型文・よく使う語句の候補", "先頭の数文字で定型文と、よく確定した語句を候補に出す（学習は端末内のみ）", settings.phraseSuggestEnabled) {
            settings.phraseSuggestEnabled = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "数字段（1〜0）を常時表示", "最上段に1〜0列を固定（3段グライド領域と分離）", settings.showNumberRow) {
            settings.showNumberRow = it
            blueprintView.showNumberRow = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "キー右上の数字・記号ヒントを表示", "長押し・上下フリックで数字・記号を入力", settings.showSubLabels) {
            settings.showSubLabels = it
            blueprintView.showSubLabels = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "文字キーの上下フリック記号入力", "ON推奨：上下に大きくフリックして右上の数字・記号を入力（小さい傾きは無視、横はグライド）", settings.letterFlickEnabled) {
            settings.letterFlickEnabled = it
        }
        switchCard.addView(hairline())
        addSymbolSensitivityControl(switchCard)
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "記号入力後に文字キーへ自動復帰", "?123から1文字入力した直後にQWERTYへ戻る", settings.autoReturnFromSymbols) {
            settings.autoReturnFromSymbols = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "句読点（。、）で自動確定", "文末記号の入力と同時に現在の変換を確定", settings.autoCommitOnPunctuation) {
            settings.autoCommitOnPunctuation = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "クリップボード履歴", "コピーしたテキストを最大30件、端末内だけに保存（パスワード欄と機密指定のコピーは保存しない）。キーボードの「履歴」タブから貼り付け", settings.clipboardHistoryEnabled) {
            settings.clipboardHistoryEnabled = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "改善用の入力ログ", "確定・候補の選び直し・日/英の切替を端末内に記録（パスワード欄とシークレット入力は除外、外部送信なし）", settings.usageLoggingEnabled) {
            settings.usageLoggingEnabled = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "キータップ時の振動フィードバック", "打鍵とカーソルキー操作で短く振動", settings.hapticFeedback) {
            settings.hapticFeedback = it
        }
        switchCard.addView(hairline())
        addSwitchRow(switchCard, "Mozc 変換学習を有効にする", "選択した変換候補を端末内に記憶", settings.learningEnabled) {
            settings.learningEnabled = it
        }
        pane.addView(switchCard)

        return pane
    }

    private fun buildLexiconWorkspace(): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. User Dictionary Editor
        val dictCard = makePanelFrame()
        dictCard.addView(microHeading("USER DICTIONARY").apply {
            setPadding(dp(12), dp(10), dp(12), dp(6))
        })
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val readingInput = EditText(this).apply {
            hint = "よみ (例: める)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) }
        }
        val wordInput = EditText(this).apply {
            hint = "単語 (例: Meltype)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1.2f).apply { marginEnd = dp(6) }
        }
        val addDictBtn = makeCompactChip("+ 登録", filled = true) {
            val r = readingInput.text.toString().trim()
            val w = wordInput.text.toString().trim()
            if (r.isNotEmpty() && w.isNotEmpty()) {
                settings.addUserDictEntry(r, w)
                readingInput.setText("")
                wordInput.setText("")
                renderUserDictList()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(36))
        }
        addRow.addView(readingInput)
        addRow.addView(wordInput)
        addRow.addView(addDictBtn)
        dictCard.addView(addRow)

        userDictContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dictCard.addView(userDictContainer)
        renderUserDictList()
        pane.addView(dictCard)

        // 1b. User phrases (mail address, phone number, greetings) offered while typing
        val phraseCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        phraseCard.addView(microHeading("PHRASES (定型文)").apply {
            setPadding(dp(12), dp(10), dp(12), dp(2))
        })
        phraseCard.addView(TextView(this).apply {
            text = "メールアドレス・電話番号・挨拶など。「読み」か本文の先頭数文字、または「@」を打つと候補に出ます。端末内だけに保存されます。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(cMuted)
            setPadding(dp(12), 0, dp(12), dp(6))
        })
        val phraseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(10))
        }
        val triggerInput = EditText(this).apply {
            hint = "読み(任意)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 0.8f).apply { marginEnd = dp(6) }
        }
        val phraseInput = EditText(this).apply {
            hint = "挿入する文字列"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cFaint)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = boxBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1.4f).apply { marginEnd = dp(6) }
        }
        userPhraseContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val savePhraseBtn = makeCompactChip("+ 登録", filled = true) {
            val t = phraseInput.text.toString().trim()
            if (t.isNotEmpty()) {
                val old = editingPhraseText
                if (old != null) settings.updateUserPhrase(old, triggerInput.text.toString(), t)
                else settings.addUserPhrase(triggerInput.text.toString(), t)
                editingPhraseText = null
                triggerInput.setText("")
                phraseInput.setText("")
                renderUserPhraseList(triggerInput, phraseInput)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(36))
        }
        phraseRow.addView(triggerInput)
        phraseRow.addView(phraseInput)
        phraseRow.addView(savePhraseBtn)
        phraseCard.addView(phraseRow)
        phraseCard.addView(userPhraseContainer)
        renderUserPhraseList(triggerInput, phraseInput)
        phraseCard.addView(hairline())
        phraseCard.addView(makeCompactChip("よく使う語句の学習履歴を消去", filled = false) {
            settings.clearLearnedPhrases()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                setMargins(dp(12), dp(8), dp(12), dp(10))
            }
        })
        pane.addView(phraseCard)

        // 2. Gesture & Conversion Reference Table
        val refCard = makePanelFrame().apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        }
        refCard.addView(microHeading("GESTURES & ENGINE SHORTCUTS").apply {
            setPadding(dp(12), dp(10), dp(12), dp(6))
        })
        val shortcuts = listOf(
            "◀ / ▶（最下段）" to "変換中は注目文節の区切れを伸縮し、限界で次の文節へ移動（設定でカーソル移動に切替可）・編集や確定後はカーソル移動",
            "空白キー左右スライド" to "入力中の文字内・確定後のテキスト内でカーソルを移動",
            "⌫ 左スワイプ" to "入力中の文字や直前単語を一括クリア",
            "縦フリック / 長押し" to "右上の数字・記号を切り替えなしで入力",
            "mb / mp / mm" to "先行する m を自動的に撥音「ん」へ変換",
            "= なしの四則演算" to "例 128*16 で候補欄に計算結果 2048 を提示",
            "確定直後の「↩ 戻す」" to "誤確定した文字列を再び未確定の変換状態へ戻す"
        )
        for (item in shortcuts) {
            refCard.addView(hairline())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            row.addView(TextView(this).apply {
                text = item.first
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f).apply {
                    marginEnd = dp(8)
                }
            })
            row.addView(TextView(this).apply {
                text = item.second
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cMuted)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f)
            })
            refCard.addView(row)
        }
        pane.addView(refCard)

        return pane
    }

    private fun switchWorkspace(tab: WorkspaceTab) {
        activeTab = tab
        WorkspaceTab.entries.forEachIndexed { idx, item ->
            val selected = item == tab
            tabButtons[idx].apply {
                setTextColor(if (selected) cText else cMuted)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                background = if (selected) boxBg(cSurface, cBorder, 5) else null
            }
            workspaceContainers[idx].visibility = if (selected) View.VISIBLE else View.GONE
        }
        rootScroll.scrollTo(0, 0)
    }

    private fun handleImeStatusAction() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        if (!enabled) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } else {
            imm.showInputMethodPicker()
        }
    }

    private fun refreshSystemStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isDefault = defaultIme.startsWith("$packageName/")

        if (enabled && isDefault) {
            headerStatusDot.background = circleDrawable(cSuccess)
            headerStatusText.text = "Meltype 使用中"
            headerStatusText.setTextColor(cSuccess)
            setupStrip.visibility = View.GONE
        } else if (enabled) {
            headerStatusDot.background = circleDrawable(cWarning)
            headerStatusText.text = "IME切替"
            headerStatusText.setTextColor(cWarning)
            setupHintText.text = "ステップ2: 入力方法を Meltype に切り替えてください"
            setupEnableBtn.visibility = View.GONE
            setupSwitchBtn.visibility = View.VISIBLE
            setupStrip.visibility = View.VISIBLE
        } else {
            headerStatusDot.background = circleDrawable(cWarning)
            headerStatusText.text = "未有効化"
            headerStatusText.setTextColor(cWarning)
            setupHintText.text = "ステップ1: システム設定で Meltype を有効化してください"
            setupEnableBtn.visibility = View.VISIBLE
            setupSwitchBtn.visibility = View.VISIBLE
            setupStrip.visibility = View.VISIBLE
        }
    }

    private fun initEngineAsync(runSelfTest: Boolean) {
        executor.execute {
            val dicts = AppEngineHolder.getDictionaries(applicationContext)
            dictionaries = dicts
            val mozcOk = MozcRuntime.ensureInitialized(applicationContext)
            scoreEngine = ScoreEngine(dicts)
            converter = AndroidMozcConverter(dicts)
            val decoder = AppEngineHolder.getGlideDecoder(applicationContext)
            glideDecoder = decoder

            runOnUiThread {
                val verStr = if (mozcOk) MozcRuntime.dataVersion else "fallback"
                engineSpecLabel.text = "Mozc $verStr · ${dicts.loadedFileCount}辞書 / 英単語${dicts.englishAllWords.size}"
                if (!runSelfTest) {
                    selfTestBadgeDot.background = circleDrawable(if (mozcOk) cSuccess else cWarning)
                    selfTestBadgeText.text = if (mozcOk) "準備完了" else "フォールバック動作"
                    selfTestLogView.text = "テスト欄に入力するか「検証実行」で32項目の自己診断を実行できます。"
                }
                showGlideDemo("watashi")
            }

            if (runSelfTest) {
                runEngineSelfTest(writeFile = true)
            }
        }
    }

    private fun runSandboxProbe(sampleInput: String) {
        sandboxInput.setText(sampleInput)
        sandboxInput.setSelection(sampleInput.length)
        executor.execute {
            val dicts = dictionaries ?: AppEngineHolder.getDictionaries(applicationContext).also { dictionaries = it }
            MozcRuntime.ensureInitialized(applicationContext)
            val eng = scoreEngine ?: ScoreEngine(dicts).also { scoreEngine = it }
            val conv = converter ?: AndroidMozcConverter(dicts).also { converter = it }
            val sess = CompositionSession(dicts, eng, conv)
            for (ch in sampleInput) sess.appendChar(ch)
            val snap = sess.snapshot
            val clausesText = snap.clauses.joinToString(" | ") { it.text }
            val topCand = snap.candidates.firstOrNull()?.text.orEmpty()
            val outShown = if (clausesText.isNotEmpty()) clausesText else topCand
            runOnUiThread {
                probeResultBanner.visibility = View.VISIBLE
                probeResultBanner.text = "入力: $sampleInput\n変換: $outShown"
            }
        }
    }

    private fun showGlideDemo(word: String) {
        val trace = buildHumanTrace(word, scale = 0.94f, dx = 0.0f, dy = 0.0f)
        val dec = glideDecoder
        if (dec != null) {
            val res = dec.decode(trace, maxResults = 3)
            glideLabView.setDecodedState("例: $word", trace, res, highlightWord = word)
        } else {
            glideLabView.setDecodedState("辞書準備中: $word", trace, emptyList(), highlightWord = word)
        }
    }

    private fun buildHumanTrace(word: String, scale: Float, dx: Float, dy: Float): List<TouchPoint> {
        val centers = word.mapNotNull { KeyLayoutGeometry.centerOf(it) }
        if (centers.size <= 1) return centers.map { TouchPoint(it.x, it.y) }
        val rounded = ArrayList<TouchPoint>(centers.size)
        rounded.add(TouchPoint(centers.first().x, centers.first().y))
        for (i in 1 until centers.lastIndex) {
            val p = centers[i - 1]
            val c = centers[i]
            val n = centers[i + 1]
            val mx = (p.x + n.x) * 0.5f
            val my = (p.y + n.y) * 0.5f
            rounded.add(TouchPoint(c.x + (mx - c.x) * 0.24f, c.y + (my - c.y) * 0.24f))
        }
        rounded.add(TouchPoint(centers.last().x, centers.last().y))
        val cx = rounded.map { it.x }.average().toFloat()
        val cy = rounded.map { it.y }.average().toFloat()
        val scaled = rounded.map {
            TouchPoint(cx + (it.x - cx) * scale + dx, cy + (it.y - cy) * scale + dy)
        }
        val dense = ArrayList<TouchPoint>()
        for (i in 0 until scaled.lastIndex) {
            val a = scaled[i]
            val b = scaled[i + 1]
            for (step in 0 until 4) {
                val t = step / 4f
                dense.add(TouchPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        dense.add(scaled.last())
        return dense
    }

    private fun syncSelfTestDrawerVisibility() {
        selfTestLogDrawer.visibility = if (logExpanded) View.VISIBLE else View.GONE
        selfTestToggleBtn.text = if (logExpanded) "閉じる ▴" else "詳細 ▾"
    }

    private fun runEngineSelfTest(writeFile: Boolean) {
        val dicts = dictionaries ?: AppEngineHolder.getDictionaries(applicationContext).also { dictionaries = it }
        val mozcOk = MozcRuntime.ensureInitialized(applicationContext)
        val engine = ScoreEngine(dicts)
        val converter = AndroidMozcConverter(dicts)
        val decoder = glideDecoder ?: AppEngineHolder.getGlideDecoder(applicationContext).also { glideDecoder = it }

        val sb = StringBuilder()
        var allPassed = true
        var passCount = 0
        var totalCount = 0

        fun check(label: String, condition: Boolean, detail: String) {
            totalCount++
            if (condition) passCount++ else allPassed = false
            sb.append(if (condition) "[PASS] " else "[FAIL] ")
                .append(label).append(": ").append(detail).append('\n')
        }

        check("MozcRuntime", mozcOk && MozcRuntime.dataVersion.isNotEmpty(), "version=${MozcRuntime.dataVersion} err=${MozcRuntime.lastError}")

        val convCases = listOf(
            "kyouhagoogledekensaku" to "google",
            "ashitanomeetingwotsuika" to "meeting",
            "I want to go to the park" to "I want to go to the park"
        )
        for ((input, expectedSub) in convCases) {
            val s = CompositionSession(dicts, engine, converter)
            for (ch in input) s.appendChar(ch)
            val out = s.snapshot.previewText
            val ok = out.contains(expectedSub, ignoreCase = true) && (input.contains(' ') || out.any { it.code > 127 })
            check("LiveConvert($input)", ok, out)
        }

        val glideWords = listOf(
            "google" to "google",
            "meeting" to "meeting",
            "android" to "android",
            "kyouha" to "kyouha",
            "kensaku" to "kensaku",
            "arigatou" to "arigatou",
            "watashi" to "watashi",
            "watasi" to "watashi",
            "asita" to "ashita",
            "syasin" to "shashin",
            "tabete" to "tabete",
            "ikimasu" to "ikimasu",
            "takakute" to "takakute"
        )
        for ((traceWord, expectOut) in glideWords) {
            val path = KeyLayoutGeometry.syntheticPathForWord(traceWord, jitter = 0.02f)
            val res = decoder.decode(path, maxResults = 5)
            val top3 = res.take(3).map { it.word }
            val hit = expectOut in top3
            check("Glide($traceWord->$expectOut)", hit, "top3=$top3")
        }

        val combo = CompositionSession(dicts, engine, converter)
        val g1 = decoder.decode(KeyLayoutGeometry.syntheticPathForWord("kyouha")).firstOrNull()?.word ?: "kyouha"
        val g2 = decoder.decode(KeyLayoutGeometry.syntheticPathForWord("google")).firstOrNull()?.word ?: "google"
        combo.appendGlideResult(g1, false, listOf(g1 to false))
        combo.appendGlideResult(g2, true, listOf(g2 to true))
        for (ch in "dekensaku") combo.appendChar(ch)
        val comboText = combo.snapshot.previewText
        check("GlideCombo(kyouha+google+dekensaku)", comboText.contains("google", ignoreCase = true) && comboText.length >= 8, comboText)

        val undoSession = CompositionSession(dicts, engine, converter)
        undoSession.appendGlideResult("watashi", false, listOf("watashi" to false))
        val beforeUndoLen = undoSession.snapshot.rawInput.length
        undoSession.backspace()
        check("Glide1TapUndo", beforeUndoLen == 7 && undoSession.snapshot.rawInput.isEmpty(), "after='${undoSession.snapshot.rawInput}'")

        val resizeSession = CompositionSession(dicts, engine, converter)
        for (ch in "kyouhaiitenki") resizeSession.appendChar(ch)
        val initClauses = resizeSession.snapshot.clauses
        if (!resizeSession.snapshot.canShrinkClause) {
            resizeSession.focusClause(0)
        }
        val shrunkSnap = resizeSession.resizeFocusedClause(-1)
        check("ClauseResize", shrunkSnap.clauses.isNotEmpty() && initClauses.isNotEmpty(), "before=${initClauses.map { it.reading }} after=${shrunkSnap.clauses.map { it.reading }}")

        val savedScale = settings.keyboardHeightScale
        settings.keyboardHeightScale = 0.72f
        val lowOk = kotlin.math.abs(settings.keyboardHeightScale - 0.72f) < 0.01f
        settings.keyboardHeightScale = 1.42f
        val highOk = kotlin.math.abs(settings.keyboardHeightScale - 1.42f) < 0.01f
        settings.keyboardHeightScale = savedScale
        check("FreeHeightScale", lowOk && highOk, "range=0.65..1.55")

        val typoCases = listOf(
            "onegaishimsu" to listOf("お願いします", "おねがいします"),
            "sumimasne" to listOf("すみません"),
            "shimbun" to listOf("新聞", "しんぶん")
        )
        for ((raw, wants) in typoCases) {
            val s = CompositionSession(dicts, engine, converter)
            for (ch in raw) s.appendChar(ch)
            val prev = s.snapshot.previewText
            val cands = s.snapshot.candidates.map { it.text }
            val hit = wants.any { prev.contains(it) || cands.any { c -> c.contains(it) } }
            check("TypoFix($raw)", hit, "prev='$prev' cands=${cands.take(4)}")
        }
        val readableTypo = CompositionSession(dicts, engine, converter)
        for (ch in "arigatoi") readableTypo.appendChar(ch)
        val rPrev = readableTypo.snapshot.previewText
        val rCands = readableTypo.snapshot.candidates.map { it.text }
        check(
            "ReadableTypo(arigatoi)",
            rPrev.contains("ありがとう") || rPrev.contains("有り難う") || rCands.any { it.contains("ありがとう") || it.contains("有り難う") },
            "prev='$rPrev' cands=${rCands.take(4)}"
        )

        val misspellSess = CompositionSession(dicts, engine, converter)
        for (ch in "shumire-shon") misspellSess.appendChar(ch)
        val mPrev = misspellSess.snapshot.previewText
        val mCands = misspellSess.snapshot.candidates.map { it.text }
        check(
            "KatakanaMisspell",
            mPrev.contains("シミュレーション") || mCands.contains("シミュレーション"),
            "prev='$mPrev' cands=${mCands.take(4)}"
        )

        val curvedGlideCases = listOf(
            "watashi" to "watashi",
            "arigatou" to "arigatou",
            "konichiwa" to "konnichiwa",
            "kakuninshimasu" to "kakuninshimasu"
        )
        for ((trace, want) in curvedGlideCases) {
            val hPath = buildHumanTrace(trace, scale = 0.72f, dx = 0.10f, dy = -0.05f)
            val top5 = decoder.decode(hPath, maxResults = 5).map { it.word }
            check("HumanGlide($trace->$want)", want in top5.take(3), "top5=$top5")
        }

        check("BareCalc(128*16)", io.github.sp9r.meltype.core.UtilityConverter.evaluateCalculator("128*16") == "2048", "eval=2048")
        val uCommitSess = CompositionSession(dicts, engine, converter)
        uCommitSess.appendText("kyou")
        val uSaved = uCommitSess.commitAndLearn()
        val restored = uCommitSess.restoreFromUndo("kyou")
        check("UndoCommitRestore", uSaved.isNotEmpty() && restored.rawInput == "kyou" && restored.isComposing, "prev='${restored.previewText}'")

        val noFalseSplit = listOf("makenai", "homete", "tameninaru").all { word ->
            engine.segment(word).all { it.kind == io.github.sp9r.meltype.core.SegmentKind.JAPANESE }
        }
        check("JpNoFalseEngSplit", noFalseSplit, "makenai/homete/tameninaru=JP")

        val djSess = CompositionSession(dicts, engine, converter)
        djSess.appendText("denjiki")
        val djPrev = djSess.snapshot.previewText
        check(
            "MozcExact(denjiki)",
            djPrev == "電磁気" && !djPrev.contains("断食"),
            "prev='$djPrev' cands=${djSess.snapshot.candidates.take(4).map { it.text }}"
        )

        val sbCands = converter.candidates("しんぶん")
        check(
            "NoEmojiOutrank(shimbun)",
            sbCands.firstOrNull() == "新聞",
            "first='${sbCands.firstOrNull()}' top=${sbCands.take(4)}"
        )

        val jpCollisions = listOf("tsukaretanodekaeru", "kokodenohanashi", "mitemiro", "kamen").all { w ->
            engine.segment(w).all { it.kind == io.github.sp9r.meltype.core.SegmentKind.JAPANESE }
        }
        val ytOk = engine.segment("youtubewomiru").any {
            it.kind == io.github.sp9r.meltype.core.SegmentKind.ENGLISH && it.raw.equals("youtube", ignoreCase = true)
        }
        check("LowerProperGuard", jpCollisions && ytOk, "jpCollisions=$jpCollisions yt=$ytOk")

        val qStartSess = CompositionSession(dicts, engine, converter)
        qStartSess.appendText("qrigatou")
        val qStartPrev = qStartSess.snapshot.previewText
        val qVowelSess = CompositionSession(dicts, engine, converter)
        qVowelSess.appendText("qite")
        val qVowelCands = qVowelSess.snapshot.candidates.map { it.text }
        val sujiSess = CompositionSession(dicts, engine, converter)
        sujiSess.appendChar('s')
        sujiSess.appendChar('u')
        sujiSess.appendChar('j', spatialHints = mapOf('k' to 0.72f))
        sujiSess.appendChar('i')
        val sujiPrev = sujiSess.snapshot.previewText
        val sujiCands = sujiSess.snapshot.candidates.map { it.text }
        check(
            "QAndJSpatialTypo",
            (qStartPrev.contains("ありがとう") || qStartPrev.contains("有難") || qStartSess.snapshot.candidates.any { it.text.contains("ありがとう") }) &&
                (qVowelSess.snapshot.previewText == "相手" || qVowelCands.contains("相手") || qVowelCands.contains("あいて")) &&
                (sujiPrev == "筋" || sujiPrev == "すじ") && sujiCands.any { it == "好き" || it == "すき" },
            "qStart='$qStartPrev' qite='${qVowelSess.snapshot.previewText}' suji='$sujiPrev' sujiCands=${sujiCands.take(8)}"
        )

        val catalog = AppEngineHolder.getEmojiCatalog(applicationContext)
        check(
            "EmojiCatalogFull",
            catalog.allEntries.size >= 1800 && catalog.search("egao").isNotEmpty(),
            "count=${catalog.allEntries.size} egaoHits=${catalog.search("egao").take(4).map { it.emoji }}"
        )

        val pSess = CompositionSession(dicts, engine, converter)
        pSess.appendText("kyouhagoogledekensaku")
        pSess.focusClause(0)
        val pCut = pSess.commitFocusedPrefix()
        check(
            "PartialPrefixCommit",
            pCut.isNotEmpty() && pSess.snapshot.isComposing && pSess.snapshot.previewText.contains("google", ignoreCase = true),
            "cut='$pCut' rem='${pSess.snapshot.previewText}'"
        )

        val warmPath = KeyLayoutGeometry.syntheticPathForWord("arigatou", jitter = 0.02f)
        decoder.decode(warmPath, maxResults = 5, fastPreview = true)
        val t0 = System.nanoTime()
        val fastHits = decoder.decode(warmPath, maxResults = 5, fastPreview = false).map { it.word }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        check("GlideSpeedAndAccuracy", "arigatou" in fastHits.take(2) && ms < 80.0, "ms=${"%.1f".format(ms)} top=$fastHits")

        val appSettings = MeltypeSettings(applicationContext)
        check(
            "LetterFlickAndEnglishGlide",
            appSettings.glideEnglishOnly && appSettings.letterFlickEnabled,
            "glideEnglishOnly=${appSettings.glideEnglishOnly} letterFlickEnabled=${appSettings.letterFlickEnabled}"
        )

        // Real QwertyKeyboardView checks on main thread: dead-zone band, Shift-swapped number row, and 4-way arrow key swipe
        val movedDirs = ArrayList<Pair<Int, Int>>()
        val typedChars = ArrayList<Char>()
        val deadZoneChars = ArrayList<Char>()
        var dzGap = 0f
        var dzMidHit: String? = "INIT"
        var row0Normal: List<String> = emptyList()
        var row0Shifted: List<String> = emptyList()
        var row0AfterTap: List<String> = emptyList()
        var shiftTyped: List<Char> = emptyList()
        var arrowJitterDirs: List<Pair<Int, Int>> = emptyList()
        var flickSyms: List<Char> = emptyList()
        var rollChars: List<Char> = emptyList()
        var topBarTraceW = 0
        var topBarCandW = 0
        val uiLatch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            try {
                val probeKb = io.github.sp9r.meltype.ui.QwertyKeyboardView(this).apply {
                    showNumberRow = true
                    heightScale = 1.0f
                    hapticEnabled = false
                    listener = object : io.github.sp9r.meltype.ui.KeyboardActionListener {
                        override fun onCharacter(ch: Char, directCommit: Boolean, spatialHints: Map<Char, Float>) {
                            typedChars.add(ch)
                        }
                        override fun onPunctuation(isPeriod: Boolean) {}
                        override fun onBackspace() {}
                        override fun onBackspaceClearAll() {}
                        override fun onSpace() {}
                        override fun onEnter() {}
                        override fun onMoveCursor(delta: Int) {}
                        override fun onMoveCursor2D(dx: Int, dy: Int) { movedDirs.add(dx to dy) }
                        override fun onToggleLanguage() {}
                        override fun onToggleEmojiPanel() {}
                        override fun onOpenSettings() {}
                        override fun onGlidePreview(points: List<io.github.sp9r.meltype.glide.TouchPoint>, letterBounds: android.graphics.RectF) {}
                        override fun onGlideCommit(points: List<io.github.sp9r.meltype.glide.TouchPoint>, letterBounds: android.graphics.RectF) {}
                    }
                }
                val kbW = 1080
                val kbH = dp(probeKb.currentBaseHeightDp().toInt())
                probeKb.measure(
                    View.MeasureSpec.makeMeasureSpec(kbW, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(kbH, View.MeasureSpec.EXACTLY)
                )
                probeKb.layout(0, 0, kbW, kbH)
                val (dzTop, dzBot) = probeKb.deadZoneRangeForTest()
                dzGap = dzBot - dzTop
                val dzMidY = (dzTop + dzBot) * 0.5f
                dzMidHit = if (dzBot > dzTop) probeKb.hitTestLabelForTest(kbW * 0.5f, dzMidY) else "ERR"
                if (dzBot > dzTop) {
                    val tDz = android.os.SystemClock.uptimeMillis()
                    val evDown = android.view.MotionEvent.obtain(tDz, tDz, android.view.MotionEvent.ACTION_DOWN, kbW * 0.5f, dzMidY, 0)
                    val evUp = android.view.MotionEvent.obtain(tDz, tDz + 20L, android.view.MotionEvent.ACTION_UP, kbW * 0.5f, dzMidY, 0)
                    probeKb.onTouchEvent(evDown)
                    probeKb.onTouchEvent(evUp)
                    evDown.recycle()
                    evUp.recycle()
                }
                deadZoneChars.addAll(typedChars)
                typedChars.clear()

                row0Normal = probeKb.rowZeroLabelsForTest()
                probeKb.setShiftStateForTest(io.github.sp9r.meltype.ui.QwertyKeyboardView.ShiftState.ONCE)
                row0Shifted = probeKb.rowZeroLabelsForTest()
                val c1 = probeKb.firstKeyCenterForTest("!")
                if (c1 != null) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val evDown = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, c1.first, c1.second, 0)
                    val evUp = android.view.MotionEvent.obtain(t0, t0 + 20L, android.view.MotionEvent.ACTION_UP, c1.first, c1.second, 0)
                    probeKb.onTouchEvent(evDown)
                    probeKb.onTouchEvent(evUp)
                    evDown.recycle()
                    evUp.recycle()
                }
                row0AfterTap = probeKb.rowZeroLabelsForTest()
                shiftTyped = typedChars.toList()

                val arrowLeft = probeKb.firstKeyCenterForTest("◀")
                if (arrowLeft != null) {
                    val (ax, ay) = arrowLeft
                    val deltas = listOf(0f to -dp(48).toFloat(), 0f to dp(48).toFloat(), -dp(48).toFloat() to 0f, dp(48).toFloat() to 0f)
                    for ((dx, dy) in deltas) {
                        val t0 = android.os.SystemClock.uptimeMillis()
                        val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, ax, ay, 0)
                        val e2 = android.view.MotionEvent.obtain(t0, t0 + 20L, android.view.MotionEvent.ACTION_MOVE, ax + dx, ay + dy, 0)
                        val e3 = android.view.MotionEvent.obtain(t0, t0 + 40L, android.view.MotionEvent.ACTION_UP, ax + dx, ay + dy, 0)
                        probeKb.onTouchEvent(e1)
                        probeKb.onTouchEvent(e2)
                        probeKb.onTouchEvent(e3)
                        e1.recycle()
                        e2.recycle()
                        e3.recycle()
                    }
                }

                // Small vertical jitter on arrow key should stay horizontal tap, not vertical edge jump
                val jitterCountBefore = movedDirs.size
                if (arrowLeft != null) {
                    val (ax, ay) = arrowLeft
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, ax, ay, 0)
                    val e2 = android.view.MotionEvent.obtain(t0, t0 + 25L, android.view.MotionEvent.ACTION_MOVE, ax, ay + dp(14).toFloat(), 0)
                    val e3 = android.view.MotionEvent.obtain(t0, t0 + 50L, android.view.MotionEvent.ACTION_UP, ax, ay + dp(14).toFloat(), 0)
                    probeKb.onTouchEvent(e1)
                    probeKb.onTouchEvent(e2)
                    probeKb.onTouchEvent(e3)
                    e1.recycle()
                    e2.recycle()
                    e3.recycle()
                }
                arrowJitterDirs = movedDirs.subList(jitterCountBefore, movedDirs.size).toList()

                // Letter key vertical flick (up & down) requires deliberate ±dp(36) swipe; slight thumb rolls keep base letter
                probeKb.glideEnabled = true
                probeKb.letterFlickEnabled = true
                probeKb.flickSensitivity = 1.0f
                val symBefore = typedChars.size
                val qCenter = probeKb.firstKeyCenterForTest("q")
                val sCenter = probeKb.firstKeyCenterForTest("s")
                if (qCenter != null) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, qCenter.first, qCenter.second, 0)
                    val e2 = android.view.MotionEvent.obtain(t0, t0 + 30L, android.view.MotionEvent.ACTION_MOVE, qCenter.first, qCenter.second - dp(36).toFloat(), 0)
                    val e3 = android.view.MotionEvent.obtain(t0, t0 + 60L, android.view.MotionEvent.ACTION_UP, qCenter.first, qCenter.second - dp(36).toFloat(), 0)
                    probeKb.onTouchEvent(e1)
                    probeKb.onTouchEvent(e2)
                    probeKb.onTouchEvent(e3)
                    e1.recycle()
                    e2.recycle()
                    e3.recycle()
                }
                if (sCenter != null) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, sCenter.first, sCenter.second, 0)
                    val e2 = android.view.MotionEvent.obtain(t0, t0 + 30L, android.view.MotionEvent.ACTION_MOVE, sCenter.first, sCenter.second + dp(36).toFloat(), 0)
                    val e3 = android.view.MotionEvent.obtain(t0, t0 + 60L, android.view.MotionEvent.ACTION_UP, sCenter.first, sCenter.second + dp(36).toFloat(), 0)
                    probeKb.onTouchEvent(e1)
                    probeKb.onTouchEvent(e2)
                    probeKb.onTouchEvent(e3)
                    e1.recycle()
                    e2.recycle()
                    e3.recycle()
                }
                flickSyms = typedChars.subList(symBefore, typedChars.size).toList()

                val rollBefore = typedChars.size
                val aCenter = probeKb.firstKeyCenterForTest("a")
                val oCenter = probeKb.firstKeyCenterForTest("o")
                if (aCenter != null) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, aCenter.first, aCenter.second, 0)
                    val e2 = android.view.MotionEvent.obtain(t0, t0 + 25L, android.view.MotionEvent.ACTION_MOVE, aCenter.first, aCenter.second - dp(16).toFloat(), 0)
                    val e3 = android.view.MotionEvent.obtain(t0, t0 + 55L, android.view.MotionEvent.ACTION_UP, aCenter.first, aCenter.second - dp(16).toFloat(), 0)
                    probeKb.onTouchEvent(e1)
                    probeKb.onTouchEvent(e2)
                    probeKb.onTouchEvent(e3)
                    e1.recycle()
                    e2.recycle()
                    e3.recycle()
                }
                if (oCenter != null) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    val e1 = android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, oCenter.first, oCenter.second, 0)
                    val e2 = android.view.MotionEvent.obtain(t0, t0 + 30L, android.view.MotionEvent.ACTION_MOVE, oCenter.first - dp(8).toFloat(), oCenter.second + dp(16).toFloat(), 0)
                    val e3 = android.view.MotionEvent.obtain(t0, t0 + 65L, android.view.MotionEvent.ACTION_UP, oCenter.first - dp(8).toFloat(), oCenter.second + dp(16).toFloat(), 0)
                    probeKb.onTouchEvent(e1)
                    probeKb.onTouchEvent(e2)
                    probeKb.onTouchEvent(e3)
                    e1.recycle()
                    e2.recycle()
                    e3.recycle()
                }
                rollChars = typedChars.subList(rollBefore, typedChars.size).toList()

                val topBarProbe = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                }
                val probeTrace = TextView(this).apply {
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.START
                    maxWidth = minOf(dp(84), (1080 * 0.22f).toInt().coerceAtLeast(dp(64)))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(dp(6), dp(3), dp(6), dp(3))
                    text = "へんかんまえをたくさんいれるとへんかんこうほがほぼみえなくなる"
                }
                val probeCandScroll = HorizontalScrollView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                }
                val probeCandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                probeCandRow.addView(TextView(this).apply { text = "変換候補" })
                probeCandScroll.addView(probeCandRow)
                val probeEmojiBtn = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(32))
                }
                val probeExpandBtn = TextView(this).apply {
                    text = "▼"
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                }
                topBarProbe.addView(probeTrace)
                topBarProbe.addView(probeCandScroll)
                topBarProbe.addView(probeEmojiBtn)
                topBarProbe.addView(probeExpandBtn)
                topBarProbe.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(42), View.MeasureSpec.EXACTLY)
                )
                topBarProbe.layout(0, 0, 1080, dp(42))
                topBarTraceW = probeTrace.width
                topBarCandW = probeCandScroll.width
            } finally {
                uiLatch.countDown()
            }
        }
        uiLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)

        check("NumAlphaDeadZone", dzGap >= dp(10).toFloat() && dzMidHit == null && deadZoneChars.isEmpty(), "gapPx=${"%.1f".format(dzGap)} midHit=$dzMidHit dzChars=$deadZoneChars")
        check(
            "ShiftNumberSymbols",
            row0Normal == listOf("1","2","3","4","5","6","7","8","9","0") &&
                row0Shifted == listOf("!","@","#","$","%","&","*","(",")","-") &&
                shiftTyped == listOf('!') &&
                row0AfterTap == row0Normal,
            "shifted=$row0Shifted typed=$shiftTyped after=$row0AfterTap"
        )
        check(
            "ArrowKey4WaySwipe",
            movedDirs.containsAll(listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)),
            "dirs=$movedDirs"
        )
        check(
            "ArrowJitterNotVertical",
            arrowJitterDirs == listOf(-1 to 0),
            "jitterDirs=$arrowJitterDirs"
        )
        check(
            "LetterVerticalFlickUpDown",
            flickSyms == listOf('+', '#'),
            "flickSyms=$flickSyms"
        )
        check(
            "LetterRollNotSymbol",
            rollChars == listOf('a', 'o'),
            "rollChars=$rollChars"
        )

        val boundarySess = CompositionSession(dicts, engine, converter)
        for (c in "kyouhaiitenki") boundarySess.appendChar(c)
        boundarySess.focusClause(0)
        val snapSnap = boundarySess.setFocusedClauseLength(4)
        check(
            "DirectClauseCut",
            snapSnap.clauses.firstOrNull()?.reading == "きょうは" && snapSnap.focusedAdjustableLen == 4,
            "first=${snapSnap.clauses.firstOrNull()?.reading} adj=${snapSnap.focusedAdjustableKana}:${snapSnap.focusedAdjustableLen}"
        )

        val bsSess = CompositionSession(dicts, engine, converter)
        for (c in "kyouha") bsSess.appendChar(c)
        val bs1 = bsSess.backspace().rawInput
        val bs2 = bsSess.backspace().rawInput
        val bs3 = bsSess.backspace().rawInput
        val kattaSess = CompositionSession(dicts, engine, converter)
        for (c in "katta") kattaSess.appendChar(c)
        val kattaBack = kattaSess.backspace().rawInput
        val kannSess = CompositionSession(dicts, engine, converter)
        for (c in "kann") kannSess.appendChar(c)
        val kannBack = kannSess.backspace().rawInput
        val pendSess = CompositionSession(dicts, engine, converter)
        for (c in "kyouhash") pendSess.appendChar(c)
        val pendBack = pendSess.backspace().rawInput
        val enTailSess = CompositionSession(dicts, engine, converter)
        for (c in "kyouhagoogle") enTailSess.appendChar(c)
        val enTailBack = enTailSess.backspace().rawInput
        check(
            "JapaneseCharBackspace",
            bs1 == "kyou" && bs2 == "kyo" && bs3 == "ki" &&
                kattaBack == "kaltu" && kannBack == "ka" &&
                pendBack == "kyouhas" && enTailBack == "kyouhagoogl",
            "kyouha=[$bs1,$bs2,$bs3] katta=$kattaBack kann=$kannBack pend=$pendBack en=$enTailBack"
        )

        val idlePunctSess = CompositionSession(dicts, engine, converter)
        val idlePunctSnap = idlePunctSess.appendText("、")
        val ffSess = CompositionSession(dicts, engine, converter)
        for (c in "FF") ffSess.appendChar(c)
        check(
            "IdlePunctAndAcronym",
            idlePunctSnap.isComposing &&
                idlePunctSnap.candidates.map { it.text }.containsAll(listOf(",", "・")) &&
                ffSess.snapshot.previewText == "FF",
            "punctComposing=${idlePunctSnap.isComposing} ff=${ffSess.snapshot.previewText}"
        )

        check(
            "TopBarLongTraceCandidateWidth",
            topBarTraceW in 1..dp(90) && topBarCandW >= 600,
            "traceW=$topBarTraceW maxAllowed=${dp(90)} candW=$topBarCandW"
        )

        val denkyuSess = CompositionSession(dicts, engine, converter)
        for (c in "denkyuu") denkyuSess.appendChar(c)
        val denkyuCands = denkyuSess.snapshot.candidates
        val denkyuFirst = denkyuCands.firstOrNull()?.text
        val denkyuBulbIdx = denkyuCands.indexOfFirst { it.text == "💡" && it.kind == CandidateKind.EMOJI }
        val shimbunSess = CompositionSession(dicts, engine, converter)
        for (c in "shinbun") shimbunSess.appendChar(c)
        val shimbunFirst = shimbunSess.snapshot.candidates.firstOrNull()?.text
        val shimbunPaperIdx = shimbunSess.snapshot.candidates.indexOfFirst { it.text == "📰" && it.kind == CandidateKind.EMOJI }
        check(
            "EmojiCandidatePromotion",
            denkyuFirst == "電球" && denkyuBulbIdx in 1..4 && shimbunFirst == "新聞" && shimbunPaperIdx in 1..4,
            "denkyuFirst=$denkyuFirst bulbIdx=$denkyuBulbIdx shimbunFirst=$shimbunFirst paperIdx=$shimbunPaperIdx"
        )

        val testImgDir = File(cacheDir, "selftest_clipboard_images").apply { deleteRecursively() }
        val imgStore = ClipboardImageStore(testImgDir)
        val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val storedImg = imgStore.importBytes(fakePng, "image/png", width = 64, height = 64, thumbBytes = byteArrayOf(1, 2, 3))
        val mimePick = ClipboardImageStore.selectCompatibleMime("image/png", arrayOf("image/jpeg", "image/png"))
        val imgOk = storedImg != null && imgStore.list().size == 1 && mimePick == "image/png" && imgStore.delete(storedImg.id)
        testImgDir.deleteRecursively()
        check(
            "ClipboardImageStore",
            imgOk,
            "stored=${storedImg?.id} mime=$mimePick"
        )

        val header = if (allPassed) "PASS\n" else "FAIL\n"
        val report = header + sb.toString()
        if (writeFile) {
            File(filesDir, "selftest.txt").writeText(report, Charsets.UTF_8)
        }
        runOnUiThread {
            selfTestBadgeDot.background = circleDrawable(if (allPassed) cSuccess else cWarning)
            selfTestBadgeText.text = if (allPassed) "自己診断 ${passCount}/${totalCount} PASS" else "一部失敗 ${passCount}/${totalCount}"
            selfTestLogView.setTextColor(if (allPassed) cSuccess else cWarning)
            selfTestLogView.text = report.trimEnd()
            if (!allPassed || !writeFile) {
                logExpanded = true
                syncSelfTestDrawerVisibility()
            }
        }
    }

    private fun renderUserDictList() {
        userDictContainer.removeAllViews()
        val entries = settings.getUserDictionary()
        if (entries.isEmpty()) {
            userDictContainer.addView(hairline())
            userDictContainer.addView(TextView(this).apply {
                text = "登録された単語はありません"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(cMuted)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        for (entry in entries) {
            userDictContainer.addView(hairline())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labelCol.addView(TextView(this).apply {
                text = entry.word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText)
            })
            labelCol.addView(TextView(this).apply {
                text = entry.reading
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(cMuted)
            })
            val delBtn = makeCompactChip("削除", filled = false) {
                settings.removeUserDictEntry(entry.reading, entry.word)
                renderUserDictList()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(30))
            }
            row.addView(labelCol)
            row.addView(delBtn)
            userDictContainer.addView(row)
        }
    }

    private fun renderUserPhraseList(triggerInput: EditText, phraseInput: EditText) {
        userPhraseContainer.removeAllViews()
        val entries = settings.getUserPhrases()
        if (entries.isEmpty()) {
            userPhraseContainer.addView(hairline())
            userPhraseContainer.addView(TextView(this).apply {
                text = "登録された定型文はありません"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(cMuted)
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        for (entry in entries) {
            userPhraseContainer.addView(hairline())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labelCol.addView(TextView(this).apply {
                text = entry.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText)
            })
            if (entry.trigger.isNotEmpty()) {
                labelCol.addView(TextView(this).apply {
                    text = entry.trigger
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(cMuted)
                })
            }
            val editBtn = makeCompactChip("編集", filled = false) {
                editingPhraseText = entry.text
                triggerInput.setText(entry.trigger)
                phraseInput.setText(entry.text)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(30)).apply { marginEnd = dp(6) }
            }
            val delBtn = makeCompactChip("削除", filled = false) {
                settings.removeUserPhrase(entry.text)
                if (editingPhraseText == entry.text) editingPhraseText = null
                renderUserPhraseList(triggerInput, phraseInput)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(60), dp(30))
            }
            row.addView(labelCol)
            row.addView(editBtn)
            row.addView(delBtn)
            userPhraseContainer.addView(row)
        }
    }

    // --- Compact Rail Precision Components ---

    private fun makePanelFrame(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg(cSurface, cBorder, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun microHeading(label: String): TextView {
        return TextView(this).apply {
            text = label
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.05f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cMuted)
        }
    }

    private fun addSwitchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        initial: Boolean,
        onToggle: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(10), 0)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(cMuted)
        })
        val sw = SwitchCompat(this).apply {
            isChecked = initial
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cAccent, cBorderStrong)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.WHITE, cSurface)
            )
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
        row.setOnClickListener { sw.toggle() }
        row.addView(col)
        row.addView(sw)
        parent.addView(row)
    }

    private fun addOptionGroup(
        parent: LinearLayout,
        title: String,
        options: List<Pair<String, Boolean>>,
        onSelect: (Int) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
            setPadding(0, 0, 0, dp(8))
        })
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = boxBg(cRail, cBorder, 6)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val buttons = ArrayList<TextView>(options.size)

        fun applySelection(selectedIdx: Int) {
            buttons.forEachIndexed { idx, tv ->
                val sel = idx == selectedIdx
                tv.setTextColor(if (sel) cText else cMuted)
                tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                tv.background = if (sel) boxBg(cSurface, cBorder, 4) else null
            }
        }

        options.forEachIndexed { idx, (label, _) ->
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < options.lastIndex) marginEnd = dp(3)
                }
                setOnClickListener {
                    applySelection(idx)
                    onSelect(idx)
                }
            }
            buttons.add(btn)
            rail.addView(btn)
        }
        applySelection(options.indexOfFirst { it.second }.coerceAtLeast(0))
        container.addView(rail)
        parent.addView(container)
    }

    private fun addFreeHeightControl(parent: LinearLayout) {
        addSteppedPercentSlider(
            parent = parent,
            title = "高さスケール",
            minPct = 65,
            maxPct = 155,
            currentValue = { settings.keyboardHeightScale },
            applyValue = { next ->
                settings.keyboardHeightScale = next
                blueprintView.heightScale = settings.keyboardHeightScale
            },
            formatLabel = { value ->
                val pct = (value * 100f).roundToInt().coerceIn(65, 155)
                val baseDp = io.github.sp9r.meltype.core.ImeHeightSpec.keyboardBaseHeightDp(settings.showNumberRow)
                val approxDp = (baseDp * value).roundToInt()
                "${pct}% · ${approxDp}dp"
            },
            stepLabels = listOf("-5%", "-1%", "100%", "+1%", "+5%"),
            stepValues = listOf(-0.05f, -0.01f, null, +0.01f, +0.05f),
            resetValue = 1.0f
        )
    }

    private fun addSideMarginControl(parent: LinearLayout) {
        // 値は dp/100f で受け渡し(0〜40dp、2dp刻みで丸めて保存)
        addSteppedPercentSlider(
            parent = parent,
            title = "キーボード左右の余白",
            minPct = 0,
            maxPct = MeltypeSettings.MAX_SIDE_MARGIN_DP,
            currentValue = { settings.keyboardSideMarginDp / 100f },
            applyValue = { next ->
                settings.keyboardSideMarginDp = (next * 100f).roundToInt()
                blueprintView.sideMarginDp = settings.keyboardSideMarginDp
            },
            formatLabel = { value -> "${(value * 100f).roundToInt()}dp" },
            stepLabels = listOf("-4", "-2", "12dp", "+2", "+4"),
            stepValues = listOf(-0.04f, -0.02f, null, +0.02f, +0.04f),
            resetValue = MeltypeSettings.DEFAULT_SIDE_MARGIN_DP / 100f
        )
    }

    private fun addSymbolSensitivityControl(parent: LinearLayout) {
        addSteppedPercentSlider(
            parent = parent,
            title = "QWERTY記号感度（フリック・長押し）",
            minPct = 50,
            maxPct = 150,
            currentValue = { settings.flickSensitivity },
            applyValue = { next ->
                settings.flickSensitivity = next
            },
            formatLabel = { value ->
                val pct = (value * 100f).roundToInt().coerceIn(50, 150)
                val holdMs = (390f / value.coerceIn(0.50f, 1.50f)).roundToInt().coerceIn(260, 680)
                val tag = when {
                    pct < 95 -> "誤爆防止"
                    pct > 105 -> "高感度"
                    else -> "標準"
                }
                "${pct}% · ${tag} (長押し${holdMs}ms)"
            },
            stepLabels = listOf("-10%", "-5%", "100%", "+5%", "+10%"),
            stepValues = listOf(-0.10f, -0.05f, null, +0.05f, +0.10f),
            resetValue = 1.0f
        )
    }

    private fun addSteppedPercentSlider(
        parent: LinearLayout,
        title: String,
        minPct: Int,
        maxPct: Int,
        currentValue: () -> Float,
        applyValue: (Float) -> Unit,
        formatLabel: (Float) -> String,
        stepLabels: List<String>,
        stepValues: List<Float?>,
        resetValue: Float
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        topRow.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueLabel = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cAccent)
        }
        topRow.addView(valueLabel)
        container.addView(topRow)

        val seekBar = SeekBar(this).apply {
            max = maxPct - minPct
            progressTintList = ColorStateList.valueOf(cAccent)
            thumbTintList = ColorStateList.valueOf(cAccent)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(4), 0, dp(6))
            }
        }

        fun updateUi(value: Float, updateSeek: Boolean) {
            valueLabel.text = formatLabel(value)
            if (updateSeek) {
                val pct = (value * 100f).roundToInt().coerceIn(minPct, maxPct)
                val targetProg = (pct - minPct).coerceIn(0, maxPct - minPct)
                if (seekBar.progress != targetProg) {
                    seekBar.progress = targetProg
                }
            }
        }

        var updatingProgrammatically = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingProgrammatically) return
                val nextValue = (minPct + progress) / 100f
                applyValue(nextValue)
                updateUi(currentValue(), updateSeek = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(seekBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        stepLabels.forEachIndexed { idx, label ->
            val delta = stepValues[idx]
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(cText)
                background = boxBg(cSurfaceSubtle, cBorder, 4)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f).apply {
                    if (idx < stepLabels.lastIndex) marginEnd = dp(5)
                }
                setOnClickListener {
                    val target = if (delta == null) resetValue else (currentValue() + delta)
                    applyValue(target.coerceIn(minPct / 100f, maxPct / 100f))
                    updatingProgrammatically = true
                    updateUi(currentValue(), updateSeek = true)
                    updatingProgrammatically = false
                }
            }
            btnRow.addView(btn)
        }
        container.addView(btnRow)

        updatingProgrammatically = true
        applyValue(currentValue())
        updateUi(currentValue(), updateSeek = true)
        updatingProgrammatically = false
        parent.addView(container)
    }

    private fun makeCompactChip(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, if (filled) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (filled) cPrimaryText else cText)
            setPadding(dp(10), 0, dp(10), 0)
            background = boxBg(if (filled) cPrimary else cSurface, if (filled) cPrimary else cBorder, 5)
            setOnClickListener { onClick() }
        }
    }

    private fun hairline(): View = View(this).apply {
        setBackgroundColor(cBorder)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun boxBg(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun circleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private inner class MeltypeLogoMarkView : View(this@MainActivity) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val glyphPath = Path()

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val r = 5f * d
            bgPaint.color = cPrimary
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)
            strokePaint.color = cBorderStrong
            strokePaint.strokeWidth = 0.8f * d
            canvas.drawRoundRect(0.4f * d, 0.4f * d, width - 0.4f * d, height - 0.4f * d, r, r, strokePaint)

            // Crisp M-shaped glide curve inside keycap mark
            val pad = width * 0.23f
            glyphPath.reset()
            glyphPath.moveTo(pad, height - pad)
            glyphPath.lineTo(pad, pad + height * 0.05f)
            glyphPath.lineTo(width * 0.5f, height * 0.58f)
            glyphPath.lineTo(width - pad, pad + height * 0.05f)
            glyphPath.lineTo(width - pad, height - pad)
            glyphPaint.color = cAccent
            glyphPaint.strokeWidth = 2.4f * d
            canvas.drawPath(glyphPath, glyphPaint)

            bgPaint.color = cPrimaryText
            canvas.drawCircle(width - pad, height - pad, 1.8f * d, bgPaint)
        }
    }

    private fun showSandboxReceivedImage(uri: Uri, rawMime: String?) {
        val meta = runCatching {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val mime = rawMime ?: contentResolver.getType(uri) ?: "image/*"
                Triple(bmp, mime, bytes.size)
            }
        }.getOrNull() ?: return
        val (bmp, mime, sizeBytes) = meta
        if (bmp != null) {
            sandboxImageThumb.setImageBitmap(bmp)
            sandboxImageMeta.text = "画像を受信しました (${bmp.width}×${bmp.height}, $mime, ${sizeBytes / 1024} KB)"
        } else {
            sandboxImageThumb.setImageDrawable(null)
            sandboxImageMeta.text = "画像を受信しました ($mime, ${sizeBytes / 1024} KB)"
        }
        sandboxImagePreviewRow.visibility = View.VISIBLE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val KEY_ACTIVE_TAB = "meltype_active_tab"
    }
}
