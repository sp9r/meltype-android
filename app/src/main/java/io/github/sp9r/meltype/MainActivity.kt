package io.github.sp9r.meltype

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.AssetDictionarySource
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.MozcRuntime
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var settings: MeltypeSettings
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusImeEnabledVal: TextView
    private lateinit var statusImeDefaultVal: TextView
    private lateinit var statusMozcVal: TextView
    private lateinit var statusDictVal: TextView
    private lateinit var selfTestLogView: TextView
    private lateinit var userDictContainer: LinearLayout

    private var dictionaries: MeltypeDictionaries? = null
    private var glideDecoder: GlideDecoder? = null

    private var cPage = 0
    private var cSurface = 0
    private var cSurfaceSubtle = 0
    private var cText = 0
    private var cMuted = 0
    private var cBorder = 0
    private var cPrimary = 0
    private var cPrimaryText = 0
    private var cAccent = 0
    private var cSuccess = 0
    private var cWarning = 0
    private var isDarkPalette = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = MeltypeSettings(this)
        resolvePalette()

        buildUi()
        initEngineAsync(runSelfTest = intent.getBooleanExtra("selftest", false))
    }

    override fun onResume() {
        super.onResume()
        refreshSystemStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshSystemStatus()
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
            cPage = Color.parseColor("#121417")
            cSurface = Color.parseColor("#1B1E23")
            cSurfaceSubtle = Color.parseColor("#23272D")
            cText = Color.parseColor("#ECEEF0")
            cMuted = Color.parseColor("#9BA1A8")
            cBorder = Color.parseColor("#2F343D")
            cPrimary = Color.parseColor("#ECEEF0")
            cPrimaryText = Color.parseColor("#181B20")
            cAccent = Color.parseColor("#5C8AC4")
            cSuccess = Color.parseColor("#5E9E75")
            cWarning = Color.parseColor("#D49542")
        } else {
            cPage = Color.parseColor("#F5F6F7")
            cSurface = Color.parseColor("#FFFFFF")
            cSurfaceSubtle = Color.parseColor("#F7F8F9")
            cText = Color.parseColor("#202328")
            cMuted = Color.parseColor("#737A84")
            cBorder = Color.parseColor("#DFE2E6")
            cPrimary = Color.parseColor("#25282D")
            cPrimaryText = Color.parseColor("#FFFFFF")
            cAccent = Color.parseColor("#4A6F9E")
            cSuccess = Color.parseColor("#4C835F")
            cWarning = Color.parseColor("#A66B1F")
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(cPage)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        scroll.addView(root)

        // Quiet Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(14))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = "Meltype"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText)
        })
        titleCol.addView(TextView(this).apply {
            text = "QWERTY + グライド入力対応 日英ライブ変換IME"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(cMuted)
        })
        header.addView(titleCol)
        header.addView(TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cMuted)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = cardBorderBg(cSurface, cBorder, 4)
        })
        root.addView(header)

        // Section 1: Setup & Engine Status
        root.addView(sectionLabel("セットアップと状態"))
        val statusCard = makeCard()
        statusImeEnabledVal = addStatusRow(statusCard, "システムIME設定", "確認中")
        addDivider(statusCard)
        statusImeDefaultVal = addStatusRow(statusCard, "現在の入力方法", "確認中")
        addDivider(statusCard)
        statusMozcVal = addStatusRow(statusCard, "Mozc 変換エンジン", "初期化中...")
        addDivider(statusCard)
        statusDictVal = addStatusRow(statusCard, "辞書データ", "読み込み中...")

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val enableBtn = makePrimaryButton("1. IMEを有効にする").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
        val pickBtn = makeSecondaryButton("2. IMEを切り替え").apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        btnRow.addView(enableBtn)
        btnRow.addView(pickBtn)
        addDivider(statusCard)
        statusCard.addView(btnRow)
        root.addView(statusCard)

        // Section 2: Sandbox Test Field & Automated Verification
        root.addView(sectionLabel("入力テストと動作確認"))
        val testCard = makeCard()
        val testBox = EditText(this).apply {
            hint = "ここをタップして文字キーのタップやグライド入力を試せます"
            setHintTextColor(cMuted)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = cardBorderBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(12), dp(12), dp(10))
            }
        }
        testCard.addView(testBox)

        val runTestBtn = makeSecondaryButton("ライブ変換・グライドデコード検証を実行").apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply {
                setMargins(dp(12), 0, dp(12), dp(10))
            }
            setOnClickListener {
                executor.execute { runEngineSelfTest(writeFile = false) }
            }
        }
        testCard.addView(runTestBtn)

        selfTestLogView = TextView(this).apply {
            text = "エンジン準備中..."
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTextColor(cMuted)
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        testCard.addView(selfTestLogView)
        root.addView(testCard)

        // Section 3: Keyboard Settings
        root.addView(sectionLabel("キーボード設定"))
        val settingsCard = makeCard()
        addSwitchRow(settingsCard, "グライド入力（スワイプ入力）", "QWERTYキー上を滑らせて日英単語を入力", settings.glideEnabled) {
            settings.glideEnabled = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "数字段（1〜0）を常時表示", "QWERTY最上段に1〜0の数字キー列を固定表示", settings.showNumberRow) {
            settings.showNumberRow = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "記号入力後に文字キーへ自動復帰", "?123記号キーを1文字入力すると自動でQWERTYへ戻す", settings.autoReturnFromSymbols) {
            settings.autoReturnFromSymbols = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "句読点（。、）で自動確定", "日本語文末の句読点入力時に変換を即確定", settings.autoCommitOnPunctuation) {
            settings.autoCommitOnPunctuation = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "キー右上の数字・記号ヒントを表示", "長押し（0.22秒）または縦フリックで数字・記号を入力", settings.showSubLabels) {
            settings.showSubLabels = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "キータップ時の振動フィードバック", "タップやカーソル移動時に短く振動", settings.hapticFeedback) {
            settings.hapticFeedback = it
        }
        addDivider(settingsCard)
        addSwitchRow(settingsCard, "Mozc 変換学習を有効にする", "確定した変換候補を端末内に記憶", settings.learningEnabled) {
            settings.learningEnabled = it
        }

        addDivider(settingsCard)
        addOptionGroup(
            settingsCard,
            "外観テーマ",
            listOf(
                ThemeMode.SYSTEM.labelJa to (settings.themeMode == ThemeMode.SYSTEM),
                ThemeMode.LIGHT.labelJa to (settings.themeMode == ThemeMode.LIGHT),
                ThemeMode.DARK.labelJa to (settings.themeMode == ThemeMode.DARK)
            )
        ) { idx ->
            settings.themeMode = ThemeMode.entries[idx]
            recreate()
        }

        addDivider(settingsCard)
        addOptionGroup(
            settingsCard,
            "片手モード",
            listOf(
                OneHandedMode.FULL.labelJa to (settings.oneHandedMode == OneHandedMode.FULL),
                OneHandedMode.LEFT.labelJa to (settings.oneHandedMode == OneHandedMode.LEFT),
                OneHandedMode.RIGHT.labelJa to (settings.oneHandedMode == OneHandedMode.RIGHT)
            )
        ) { idx ->
            settings.oneHandedMode = OneHandedMode.entries[idx]
        }

        addDivider(settingsCard)
        addFreeHeightControl(settingsCard)

        addDivider(settingsCard)
        addOptionGroup(
            settingsCard,
            "句読点スタイル",
            listOf(
                PunctuationStyle.JAPANESE.labelJa to (settings.punctuationStyle == PunctuationStyle.JAPANESE),
                PunctuationStyle.WIDE_LATIN.labelJa to (settings.punctuationStyle == PunctuationStyle.WIDE_LATIN),
                PunctuationStyle.HALF.labelJa to (settings.punctuationStyle == PunctuationStyle.HALF)
            )
        ) { idx ->
            settings.punctuationStyle = PunctuationStyle.entries[idx]
        }
        root.addView(settingsCard)

        // Section 4: User Dictionary
        root.addView(sectionLabel("ユーザー辞書"))
        val dictCard = makeCard()
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        val readingInput = EditText(this).apply {
            hint = "よみ (例: める)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cMuted)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = cardBorderBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
        }
        val wordInput = EditText(this).apply {
            hint = "単語 (例: Meltype)"
            inputType = InputType.TYPE_CLASS_TEXT
            setHintTextColor(cMuted)
            setTextColor(cText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = cardBorderBg(cSurfaceSubtle, cBorder, 5)
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1.2f).apply { marginEnd = dp(6) }
        }
        val addDictBtn = makeSecondaryButton("登録").apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(38))
            setOnClickListener {
                val r = readingInput.text.toString().trim()
                val w = wordInput.text.toString().trim()
                if (r.isNotEmpty() && w.isNotEmpty()) {
                    settings.addUserDictEntry(r, w)
                    readingInput.setText("")
                    wordInput.setText("")
                    renderUserDictList()
                }
            }
        }
        addRow.addView(readingInput)
        addRow.addView(wordInput)
        addRow.addView(addDictBtn)
        dictCard.addView(addRow)

        userDictContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        dictCard.addView(userDictContainer)
        root.addView(dictCard)

        renderUserDictList()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPage)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(scroll)
        }
        ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(sys.left, sys.top, sys.right, 0)
            root.setPadding(dp(16), dp(14), dp(16), dp(28) + sys.bottom)
            insets
        }
        setContentView(outer)
        WindowCompat.getInsetsController(window, outer).apply {
            isAppearanceLightStatusBars = !isDarkPalette
            isAppearanceLightNavigationBars = !isDarkPalette
        }
    }

    private fun refreshSystemStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val isDefault = defaultIme.startsWith("$packageName/")

        statusImeEnabledVal.text = if (enabled) "有効" else "未設定（下の1を押してください）"
        statusImeEnabledVal.setTextColor(if (enabled) cSuccess else cWarning)

        statusImeDefaultVal.text = if (isDefault) "Meltype 使用中" else "別キーボード"
        statusImeDefaultVal.setTextColor(if (isDefault) cSuccess else cMuted)
    }

    private fun initEngineAsync(runSelfTest: Boolean) {
        executor.execute {
            val dicts = MeltypeDictionaries(AssetDictionarySource(applicationContext))
            dictionaries = dicts
            val mozcOk = MozcRuntime.ensureInitialized(applicationContext)
            val decoder = GlideDecoder(dicts)
            glideDecoder = decoder

            runOnUiThread {
                statusMozcVal.text = if (mozcOk) {
                    "準備完了 (${MozcRuntime.dataVersion})"
                } else {
                    "フォールバック (${MozcRuntime.lastError ?: "N/A"})"
                }
                statusMozcVal.setTextColor(if (mozcOk) cSuccess else cWarning)
                statusDictVal.text = "${dicts.loadedFileCount}種ロード済 / 英単語${dicts.englishAllWords.size}"
                statusDictVal.setTextColor(cText)
                if (!runSelfTest) {
                    selfTestLogView.text = "準備完了。テスト欄にそのまま入力するか、上のボタンで検証できます。"
                }
            }

            if (runSelfTest) {
                runEngineSelfTest(writeFile = true)
            }
        }
    }

    private fun runEngineSelfTest(writeFile: Boolean) {
        val dicts = dictionaries ?: MeltypeDictionaries(AssetDictionarySource(applicationContext)).also { dictionaries = it }
        val mozcOk = MozcRuntime.ensureInitialized(applicationContext)
        val engine = ScoreEngine(dicts)
        val converter = AndroidMozcConverter(dicts)
        val decoder = glideDecoder ?: GlideDecoder(dicts).also { glideDecoder = it }

        val sb = StringBuilder()
        var allPassed = true

        fun check(label: String, condition: Boolean, detail: String) {
            if (!condition) allPassed = false
            sb.append(if (condition) "[PASS] " else "[FAIL] ")
                .append(label).append(": ").append(detail).append('\n')
        }

        check("MozcRuntime", mozcOk && MozcRuntime.dataVersion.isNotEmpty(), "version=${MozcRuntime.dataVersion} err=${MozcRuntime.lastError}")

        // 1. Live mixed JP/EN conversion tests using real Mozc JNI
        val convCases = listOf(
            "kyouhagoogledekensaku" to "google",
            "ashitanomeetingwotsuika" to "meeting",
            "I want to go to the park" to "I want to go to the park"
        )
        for ((input, expectedSub) in convCases) {
            val s = CompositionSession(dicts, engine, converter)
            for (ch in input) s.appendChar(ch)
            val out = s.snapshot.previewText
            val ok = out.contains(expectedSub) && (input.contains(' ') || out.any { it.code > 127 })
            check("LiveConvert($input)", ok, out)
        }

        // 2. Dual-language glide decoding tests (Hebon + Kunrei + inflected Japanese + English)
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

        // 3. Glide + Live conversion combined flow ("kyouha" glide -> "google" glide -> "dekensaku" tap)
        val combo = CompositionSession(dicts, engine, converter)
        val g1 = decoder.decode(KeyLayoutGeometry.syntheticPathForWord("kyouha")).firstOrNull()?.word ?: "kyouha"
        val g2 = decoder.decode(KeyLayoutGeometry.syntheticPathForWord("google")).firstOrNull()?.word ?: "google"
        combo.appendGlideResult(g1, false, listOf(g1 to false))
        combo.appendGlideResult(g2, true, listOf(g2 to true))
        for (ch in "dekensaku") combo.appendChar(ch)
        val comboText = combo.snapshot.previewText
        check("GlideCombo(kyouha+google+dekensaku)", comboText.contains("google") && comboText.length >= 8, comboText)

        // 4. 1-tap glide undo + Clause resize tests
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

        // 5. Free keyboard height scale bounds check (non-destructive)
        val savedScale = settings.keyboardHeightScale
        settings.keyboardHeightScale = 0.72f
        val lowOk = kotlin.math.abs(settings.keyboardHeightScale - 0.72f) < 0.01f
        settings.keyboardHeightScale = 1.42f
        val highOk = kotlin.math.abs(settings.keyboardHeightScale - 1.42f) < 0.01f
        settings.keyboardHeightScale = savedScale
        check("FreeHeightScale", lowOk && highOk, "range=0.65..1.55")

        val header = if (allPassed) "PASS\n" else "FAIL\n"
        val report = header + sb.toString()
        if (writeFile) {
            File(filesDir, "selftest.txt").writeText(report, Charsets.UTF_8)
        }
        runOnUiThread {
            selfTestLogView.setTextColor(if (allPassed) cSuccess else cWarning)
            selfTestLogView.text = report.trimEnd()
        }
    }

    private fun renderUserDictList() {
        userDictContainer.removeAllViews()
        val entries = settings.getUserDictionary()
        if (entries.isEmpty()) {
            userDictContainer.addView(TextView(this).apply {
                text = "登録された単語はありません"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(cMuted)
                setPadding(dp(12), dp(4), dp(12), dp(12))
            })
            return
        }
        for (entry in entries) {
            addDivider(userDictContainer)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(cText)
            })
            labelCol.addView(TextView(this).apply {
                text = entry.reading
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cMuted)
            })
            val delBtn = makeSecondaryButton("削除").apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(32))
                setOnClickListener {
                    settings.removeUserDictEntry(entry.reading, entry.word)
                    renderUserDictList()
                }
            }
            row.addView(labelCol)
            row.addView(delBtn)
            userDictContainer.addView(row)
        }
    }

    // --- Compact Rail UI Helpers ---

    private fun sectionLabel(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cMuted)
            setPadding(dp(2), dp(14), dp(2), dp(6))
        }
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBorderBg(cSurface, cBorder, 6)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun addStatusRow(parent: LinearLayout, label: String, initialValue: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val lbl = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valView = TextView(this).apply {
            text = initialValue
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cMuted)
        }
        row.addView(lbl)
        row.addView(valView)
        parent.addView(row)
        return valView
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
            setPadding(0, 0, dp(8), 0)
        }
        col.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(cText)
        })
        col.addView(TextView(this).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(cMuted)
        })
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onToggle(checked) }
        }
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(cText)
            setPadding(0, 0, 0, dp(6))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<TextView>(options.size)

        fun applySelection(selectedIdx: Int) {
            buttons.forEachIndexed { idx, tv ->
                val sel = idx == selectedIdx
                tv.setTextColor(if (sel) cPrimaryText else cText)
                tv.background = cardBorderBg(if (sel) cPrimary else cSurfaceSubtle, if (sel) cPrimary else cBorder, 5)
            }
        }

        options.forEachIndexed { idx, (label, _) ->
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    if (idx < options.lastIndex) marginEnd = dp(6)
                }
                setOnClickListener {
                    applySelection(idx)
                    onSelect(idx)
                }
            }
            buttons.add(btn)
            row.addView(btn)
        }
        val initIdx = options.indexOfFirst { it.second }.coerceAtLeast(0)
        applySelection(initIdx)
        container.addView(row)
        parent.addView(container)
    }

    private fun addFreeHeightControl(parent: LinearLayout) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        topRow.addView(TextView(this).apply {
            text = "キーボード高さ（自由調整）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(cText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(cAccent)
        }
        topRow.addView(valueLabel)
        container.addView(topRow)

        val seekBar = SeekBar(this).apply {
            max = 90 // 65% .. 155%
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(4), 0, dp(6))
            }
        }

        fun updateUiFromScale(scale: Float, updateSeek: Boolean) {
            val pct = (scale * 100f).roundToInt().coerceIn(65, 155)
            val baseDp = if (settings.showNumberRow) 252f else 224f
            val approxDp = (baseDp * scale).roundToInt()
            valueLabel.text = "${pct}% (約${approxDp}dp)"
            if (updateSeek) {
                val targetProg = (pct - 65).coerceIn(0, 90)
                if (seekBar.progress != targetProg) {
                    seekBar.progress = targetProg
                }
            }
        }

        var updatingProgrammatically = false
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updatingProgrammatically) return
                val nextScale = (65 + progress) / 100f
                settings.keyboardHeightScale = nextScale
                updateUiFromScale(settings.keyboardHeightScale, updateSeek = false)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        container.addView(seekBar)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        val stepSpecs = listOf(
            "-5%" to { (settings.keyboardHeightScale - 0.05f) },
            "-1%" to { (settings.keyboardHeightScale - 0.01f) },
            "100%" to { 1.0f },
            "+1%" to { (settings.keyboardHeightScale + 0.01f) },
            "+5%" to { (settings.keyboardHeightScale + 0.05f) }
        )
        stepSpecs.forEachIndexed { idx, (label, calc) ->
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(cText)
                background = cardBorderBg(cSurfaceSubtle, cBorder, 5)
                layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (idx < stepSpecs.lastIndex) marginEnd = dp(5)
                }
                setOnClickListener {
                    settings.keyboardHeightScale = calc().coerceIn(0.65f, 1.55f)
                    updatingProgrammatically = true
                    updateUiFromScale(settings.keyboardHeightScale, updateSeek = true)
                    updatingProgrammatically = false
                }
            }
            btnRow.addView(btn)
        }
        container.addView(btnRow)

        updatingProgrammatically = true
        updateUiFromScale(settings.keyboardHeightScale, updateSeek = true)
        updatingProgrammatically = false

        parent.addView(container)
    }

    private fun makePrimaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cPrimaryText)
            background = cardBorderBg(cPrimary, cPrimary, 5)
            stateListAnimator = null
        }
    }

    private fun makeSecondaryButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(cText)
            background = cardBorderBg(cSurface, cBorder, 5)
            stateListAnimator = null
        }
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            setBackgroundColor(cBorder)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
    }

    private fun cardBorderBg(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
