package io.github.sp9r.meltype

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.AppEngineHolder
import io.github.sp9r.meltype.core.CandidateItem
import io.github.sp9r.meltype.core.CandidateKind
import io.github.sp9r.meltype.core.ClipboardImageEntry
import io.github.sp9r.meltype.core.ClipboardImageStore
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.CompositionSync
import io.github.sp9r.meltype.core.CompositionSnapshot
import io.github.sp9r.meltype.core.EmojiCatalog
import io.github.sp9r.meltype.core.EmojiCategory
import io.github.sp9r.meltype.core.GlideEvalLogger
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import io.github.sp9r.meltype.core.MozcRuntime
import io.github.sp9r.meltype.core.PhraseSuggester
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.glide.GlideCandidate
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import io.github.sp9r.meltype.glide.TouchPoint
import io.github.sp9r.meltype.ui.KeyboardActionListener
import io.github.sp9r.meltype.ui.QwertyKeyboardView
import io.github.sp9r.meltype.ui.SmileyDrawable
import java.io.ByteArrayOutputStream
import java.io.File
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
    private val clipboardImageExecutor = Executors.newSingleThreadExecutor()
    private val glidePreviewSeq = AtomicLong(0L)
    private lateinit var clipboardImageStore: ClipboardImageStore

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
    /** Japanese/English input mode, switched by the 日/英 key like Gboard's language key. */
    private var englishMode: Boolean = false
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
        settings.migrateV170()
        settings.migrateV174()
        evalLogger = GlideEvalLogger(filesDir)
        clipboardImageStore = ClipboardImageStore(File(cacheDir, "clipboard_images"))
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.addPrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) {
        }
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
            typoCorrectionEnabled = { settings.typoCorrectionEnabled },
            phraseProvider = { raw, reading, preview ->
                if (!settings.phraseSuggestEnabled || isPasswordField) emptyList()
                else PhraseSuggester.suggest(
                    raw, reading,
                    settings.getUserPhrases(),
                    if (settings.learningEnabled) settings.getLearnedPhrases() else emptyList(),
                    preview
                )
            },
            onCommitted = { reading, text ->
                if (settings.learningEnabled && settings.phraseSuggestEnabled && !isPasswordField && !isIncognitoField) {
                    settings.recordCommittedPhrase(reading, text)
                }
            }
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
                commitFocusedPrefixAndRender("partial")
            }
        }
        langSwitchBtn = makeRailButton("日/英").apply {
            setOnClickListener { onToggleSegmentLanguage() }
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
                noteFix("resize"); renderSnapshot(session.resizeFocusedClause(-1, getSurroundingTextBefore()))
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
                noteFix("resize"); renderSnapshot(session.resizeFocusedClause(+1, getSurroundingTextBefore()))
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
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            setOnClickListener {
                if (session.isEmpty) return@setOnClickListener
                if (session.snapshot.isEditing) {
                    renderSnapshot(session.startConversion(getSurroundingTextBefore()))
                } else {
                    noteFix("caret", conversionFix = false)
                    renderSnapshot(session.moveCaret(-1, getSurroundingTextBefore()))
                }
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

        emojiBarBtn = makeRailButton("").apply {
            setCompoundDrawables(SmileyDrawable(dp(17)).also { it.setBounds(0, 0, dp(17), dp(17)) }, null, null, null)
            minWidth = dp(34)
            contentDescription = "絵文字"
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

    private var pillTapIdx = -1
    private var pillSingleTap: Runnable? = null
    private val pillHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pillTapTime = 0L

    private var isPasswordField: Boolean = false
    private var isIncognitoField: Boolean = false

    private fun isIncognito(info: EditorInfo?): Boolean =
        info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        isPasswordField = isPasswordInputType(attribute)
        isIncognitoField = isIncognito(attribute)
        if (::session.isInitialized) {
            // Also on restarting: the app re-created its input connection (e.g. after replacing the
            // composing text with a search suggestion), so the old composition no longer exists there.
            discardCompositionWithoutCommit()
        }
    }

    private var lastOwnEditUptime = 0L

    /** Drops the internal composition without inserting it into the editor. */
    private fun discardCompositionWithoutCommit() {
        if (!session.isEmpty) {
            session.clearAll()
        }
        clearUndoCommit()
        if (::keyboardView.isInitialized) renderSnapshot(session.snapshot)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!::session.isInitialized) return
        val snap = session.snapshot
        val discard = CompositionSync.shouldDiscardOnSelectionUpdate(
            hasComposition = !session.isEmpty,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            newSelStart = newSelStart,
            newSelEnd = newSelEnd,
            expectedLength = snap.previewText.length,
            msSinceOwnEdit = android.os.SystemClock.uptimeMillis() - lastOwnEditUptime
        )
        if (discard) discardCompositionWithoutCommit()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        isPasswordField = isPasswordInputType(info)
        isIncognitoField = isIncognito(info)
        isExpandedPanelOpen = false
        isEmojiSearchActive = false
        emojiSearchQuery.setLength(0)
        expandedTab = ExpandedTab.CANDIDATES
        captureClipboard()
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

    private fun captureClipboard() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount <= 0) return
            val desc = clip.description
            val sensitive = isSensitiveClip(desc)
            val item = clip.getItemAt(0)
            val uri = item?.uri
            val resolvedMime = resolveClipboardImageMime(desc, uri)
            if (uri != null && resolvedMime != null) {
                captureClipboardImage(uri, resolvedMime, desc, sensitive)
                return
            }
            val txt = item?.coerceToText(this)?.toString()?.trim()
            if (!txt.isNullOrEmpty() && txt.length <= 500 && !sensitive) {
                settings.addClipboardItem(txt)
            }
            if (!txt.isNullOrEmpty()) {
                pasteOfferImage = null
                offerPaste(txt, desc, sensitive)
            }
        } catch (_: Throwable) {
        }
    }

    private fun resolveClipboardImageMime(desc: ClipDescription?, uri: Uri?): String? {
        if (uri == null) return null
        if (desc != null) {
            for (i in 0 until desc.mimeTypeCount) {
                val m = desc.getMimeType(i)
                if (m.startsWith("image/")) return m
            }
        }
        val resolverType = runCatching { contentResolver.getType(uri) }.getOrNull()
        return resolverType?.takeIf { it.startsWith("image/") }
    }

    private fun captureClipboardImage(
        uri: Uri,
        rawMime: String,
        desc: ClipDescription?,
        sensitive: Boolean
    ) {
        val uriKey = "${uri}|${desc?.timestamp ?: 0L}|$rawMime"
        if (uriKey == lastCapturedImageUriKey) return
        lastCapturedImageUriKey = uriKey
        val now = SystemClock.elapsedRealtime()
        val ts = if (Build.VERSION.SDK_INT >= 26 && desc != null) desc.timestamp else 0L
        val age = when {
            ts <= 0L -> 0L
            ts > now + PASTE_OFFER_MS -> System.currentTimeMillis() - ts
            else -> now - ts
        }
        val offerFresh = age <= PASTE_OFFER_MS && !sensitive
        val copiedAt = now - age.coerceAtLeast(0L)
        clipboardImageExecutor.execute {
            val rawBytes = readBoundedBytes(uri, ClipboardImageStore.MAX_IMAGE_BYTES + 1) ?: return@execute
            if (rawBytes.size > ClipboardImageStore.MAX_IMAGE_BYTES) return@execute
            val prepared = prepareClipboardImagePayload(rawBytes, rawMime) ?: return@execute
            val entry = clipboardImageStore.importBytes(
                bytes = prepared.bytes,
                rawMimeType = prepared.mime,
                width = prepared.width,
                height = prepared.height,
                thumbBytes = prepared.thumbBytes
            ) ?: return@execute
            pillHandler.post {
                if (offerFresh && entry.id != lastHandledImageId) {
                    pasteOfferText = null
                    pasteOfferImage = entry
                    pasteOfferImageSince = copiedAt
                    pasteOfferImageSensitive = sensitive
                }
                if (::candidateRow.isInitialized && session.isEmpty) {
                    renderSnapshot(session.snapshot)
                }
                if (isExpandedPanelOpen && expandedTab == ExpandedTab.TEMPLATES) {
                    populateExpandedPanel(session.snapshot)
                }
            }
        }
    }

    private data class PreparedImagePayload(
        val bytes: ByteArray,
        val mime: String,
        val width: Int,
        val height: Int,
        val thumbBytes: ByteArray
    )

    private fun readBoundedBytes(uri: Uri, maxBytes: Int): ByteArray? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(16 * 1024)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull()
    }

    private fun prepareClipboardImagePayload(rawBytes: ByteArray, rawMime: String): PreparedImagePayload? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        val normMime = ClipboardImageStore.normalizeMimeType(rawMime) ?: "image/png"
        val rawLower = rawMime.substringBefore(';').trim().lowercase(Locale.US)
        val keepOriginal = rawLower in setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif")

        val finalBytes: ByteArray
        val finalMime: String
        if (keepOriginal) {
            finalBytes = rawBytes
            finalMime = normMime
        } else {
            val fullSample = computeSampleSize(srcW, srcH, 2048)
            val fullBmp = BitmapFactory.decodeByteArray(
                rawBytes,
                0,
                rawBytes.size,
                BitmapFactory.Options().apply { inSampleSize = fullSample }
            ) ?: return null
            val baos = ByteArrayOutputStream()
            fullBmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
            fullBmp.recycle()
            finalBytes = baos.toByteArray()
            finalMime = "image/png"
        }

        val thumbSample = computeSampleSize(srcW, srcH, 160)
        val thumbDecoded = BitmapFactory.decodeByteArray(
            rawBytes,
            0,
            rawBytes.size,
            BitmapFactory.Options().apply { inSampleSize = thumbSample }
        ) ?: return null
        val thumbBytes = encodeThumbnailJpeg(thumbDecoded, 160)
        thumbDecoded.recycle()

        return PreparedImagePayload(
            bytes = finalBytes,
            mime = finalMime,
            width = srcW,
            height = srcH,
            thumbBytes = thumbBytes
        )
    }

    private fun computeSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var curW = w
        var curH = h
        while (curW / 2 >= maxDim && curH / 2 >= maxDim) {
            curW /= 2
            curH /= 2
            sample *= 2
        }
        return sample
    }

    private fun encodeThumbnailJpeg(bmp: Bitmap, maxDim: Int): ByteArray {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height).coerceAtLeast(1))
        val targetW = (bmp.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bmp.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (targetW != bmp.width || targetH != bmp.height) {
            Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        } else {
            bmp
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled !== bmp) scaled.recycle()
        return out.toByteArray()
    }

    // ---- Password manager: inline autofill suggestions (Android 11+) ----
    // The autofill service (Bitwarden etc.) renders its own chips; they are shown in the idle candidate bar.
    private var inlineViews: List<View> = emptyList()
    private var inlineGen = 0

    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val dark = isDarkTheme()
            val bg = android.graphics.drawable.Icon.createWithResource(
                this, if (dark) R.drawable.inline_chip_bg_dark else R.drawable.inline_chip_bg
            )
            val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
            val subCol = if (dark) Color.parseColor("#9BA1A8") else Color.parseColor("#737A84")
            val chip = androidx.autofill.inline.common.ViewStyle.Builder()
                .setBackground(bg)
                .setPadding(dp(10), 0, dp(10), 0)
                .build()
            val style = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
                .setSingleIconChipStyle(chip)
                .setChipStyle(chip)
                .setTitleStyle(
                    androidx.autofill.inline.common.TextViewStyle.Builder()
                        .setTextColor(textCol).setTextSize(13f).build()
                )
                .setSubtitleStyle(
                    androidx.autofill.inline.common.TextViewStyle.Builder()
                        .setTextColor(subCol).setTextSize(11f).build()
                )
                .build()
            val styles = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(style).build()
            val spec = android.widget.inline.InlinePresentationSpec.Builder(
                android.util.Size(dp(80), dp(33)),
                android.util.Size(dp(300), dp(33))
            ).setStyle(styles).build()
            android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec))
                .setMaxSuggestionCount(6)
                .build()
        } catch (_: Throwable) {
            null
        }
    }

    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val list = response.inlineSuggestions
        val gen = ++inlineGen
        if (list.isEmpty()) {
            inlineViews = emptyList()
            if (::candidateRow.isInitialized) renderSnapshot(session.snapshot)
            return true
        }
        val views = arrayOfNulls<View>(list.size)
        var remaining = list.size
        for ((i, sug) in list.withIndex()) {
            try {
                sug.inflate(
                    this,
                    android.util.Size(ViewGroup.LayoutParams.WRAP_CONTENT, dp(33)),
                    mainExecutor
                ) { v ->
                    views[i] = v
                    remaining--
                    if (remaining == 0 && gen == inlineGen) {
                        inlineViews = views.filterNotNull()
                        if (::candidateRow.isInitialized) renderSnapshot(session.snapshot)
                    }
                }
            } catch (_: Throwable) {
                remaining--
            }
        }
        return true
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (::session.isInitialized && !session.isEmpty) discardCompositionWithoutCommit()
        inlineGen++
        inlineViews = emptyList()
    }

    /** The app behind the system autofill service (e.g. Bitwarden), for the fallback button. */
    private fun passwordManagerApp(): Pair<String, String>? {
        return try {
            val am = getSystemService(android.view.autofill.AutofillManager::class.java)
            val pkg = am?.autofillServiceComponentName?.packageName ?: return null
            val pm = packageManager
            if (pm.getLaunchIntentForPackage(pkg) == null) return null
            val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            pkg to label
        } catch (_: Throwable) {
            null
        }
    }

    private fun openPasswordManager(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    // Paste suggestion: text or image copied in another app shows up at the head of the idle candidate bar.
    private var pasteOfferText: String? = null
    private var pasteOfferSensitive = false
    private var pasteOfferSince = 0L
    private var lastHandledClip: String? = null
    private var pasteOfferImage: ClipboardImageEntry? = null
    private var pasteOfferImageSensitive = false
    private var pasteOfferImageSince = 0L
    private var lastHandledImageId: String? = null
    private var lastCapturedImageUriKey: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboard()
        if (::candidateRow.isInitialized && session.isEmpty) renderSnapshot(session.snapshot)
    }

    private fun isSensitiveClip(desc: android.content.ClipDescription?): Boolean {
        val extras = desc?.extras ?: return false
        return extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
    }

    private fun offerPaste(txt: String?, desc: android.content.ClipDescription?, sensitive: Boolean) {
        if (txt.isNullOrEmpty() || txt == lastHandledClip) return
        val now = SystemClock.elapsedRealtime()
        val ts = if (Build.VERSION.SDK_INT >= 26 && desc != null) desc.timestamp else 0L
        // The copy time is wall-clock on recent Android; accept either time base.
        val age = when {
            ts <= 0L -> 0L
            ts > now + PASTE_OFFER_MS -> System.currentTimeMillis() - ts
            else -> now - ts
        }
        // Only a fresh copy is offered; an old clip left on the clipboard is not.
        if (age > PASTE_OFFER_MS) return
        val copiedAt = now - age.coerceAtLeast(0L)
        if (txt != pasteOfferText) {
            pasteOfferText = txt
            pasteOfferSince = copiedAt
        }
        pasteOfferSensitive = sensitive
    }

    private fun currentPasteOffer(): String? {
        val t = pasteOfferText ?: return null
        if (SystemClock.elapsedRealtime() - pasteOfferSince > PASTE_OFFER_MS) {
            pasteOfferText = null
            return null
        }
        // A password copied from a password manager is offered only in password fields.
        if (pasteOfferSensitive && !isPasswordField) return null
        return t
    }

    private fun pasteOffered(text: String) {
        lastHandledClip = text
        pasteOfferText = null
        clearUndoCommit()
        currentInputConnection?.commitText(text, 1)
        if (shouldLogEval()) evalLogger.logEvent("paste", text.length.toString(), "")
        renderSnapshot(session.snapshot)
    }

    private fun currentPasteOfferImage(): ClipboardImageEntry? {
        val img = pasteOfferImage ?: return null
        if (SystemClock.elapsedRealtime() - pasteOfferImageSince > PASTE_OFFER_MS) {
            pasteOfferImage = null
            return null
        }
        if (pasteOfferImageSensitive || isPasswordField) return null
        if (!clipboardImageStore.resolveFile(img).isFile) {
            pasteOfferImage = null
            return null
        }
        return img
    }

    private fun commitClipboardImage(entry: ClipboardImageEntry) {
        val ic = currentInputConnection ?: return
        val editorInfo = currentCurrentInputEditorInfoFallback()
        val accepted = if (editorInfo != null) EditorInfoCompat.getContentMimeTypes(editorInfo) else emptyArray()
        val chosenMime = ClipboardImageStore.selectCompatibleMime(entry.mimeType, accepted) ?: entry.mimeType
        val sendFile = prepareImageFileForCommit(entry, chosenMime) ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", sendFile)
        }.getOrNull() ?: return

        var committed = false
        if (editorInfo != null) {
            val contentInfo = InputContentInfoCompat(
                uri,
                ClipDescription("クリップボード画像", arrayOf(chosenMime)),
                null
            )
            committed = runCatching {
                InputConnectionCompat.commitContent(
                    ic,
                    editorInfo,
                    contentInfo,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                    null
                )
            }.getOrDefault(false)
        }
        if (!committed) {
            // Fallback when the current primary clip itself is an image and the target supports generic paste.
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val curClip = cm?.primaryClip
            val curUri = curClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (curUri != null && resolveClipboardImageMime(curClip.description, curUri) != null) {
                committed = runCatching { ic.performContextMenuAction(android.R.id.paste) }.getOrDefault(false)
            }
        }
        if (committed) {
            lastHandledImageId = entry.id
            pasteOfferImage = null
            clearUndoCommit()
            if (isExpandedPanelOpen) {
                isExpandedPanelOpen = false
            }
            if (shouldLogEval()) evalLogger.logEvent("paste_image", chosenMime, "${entry.width}x${entry.height}")
            renderSnapshot(session.snapshot)
        } else {
            Toast.makeText(this, "この入力欄は画像の貼り付けに対応していません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentCurrentInputEditorInfoFallback(): EditorInfo? = currentInputEditorInfo

    private fun prepareImageFileForCommit(entry: ClipboardImageEntry, targetMime: String): File? {
        val srcFile = clipboardImageStore.resolveFile(entry)
        if (!srcFile.isFile) return null
        if (targetMime == entry.mimeType) return srcFile
        val ext = ClipboardImageStore.extensionForMime(targetMime)
        val outFile = File(srcFile.parentFile, "send_${entry.id}.$ext")
        val bmp = BitmapFactory.decodeFile(srcFile.absolutePath) ?: return srcFile
        return runCatching {
            outFile.outputStream().use { fos ->
                val fmt = if (targetMime == "image/jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                bmp.compress(fmt, 92, fos)
            }
            bmp.recycle()
            outFile
        }.getOrElse {
            bmp.recycle()
            srcFile
        }
    }

    private fun loadThumbnailBitmap(entry: ClipboardImageEntry): Bitmap? {
        val target = clipboardImageStore.resolveThumbFile(entry) ?: clipboardImageStore.resolveFile(entry)
        if (!target.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            val sample = computeSampleSize(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), 160)
            BitmapFactory.decodeFile(
                target.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
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
                    logCommitDetail(raw, committed, "finish_view")
                }
            }
        }
        renderSnapshot(session.snapshot)
    }

    override fun onDestroy() {
        try {
            (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.removePrimaryClipChangedListener(clipListener)
        } catch (_: Throwable) {
        }
        bgExecutor.shutdownNow()
        glidePreviewExecutor.shutdownNow()
        clipboardImageExecutor.shutdownNow()
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
        keyboardView.bottomKeyMode = settings.bottomKeyMode
        keyboardView.englishMode = englishMode
        session.englishMode = englishMode
        keyboardView.autoReturnFromSymbols = settings.autoReturnFromSymbols
        keyboardView.flickSensitivity = settings.flickSensitivity
        keyboardView.themeMode = settings.themeMode
        keyboardView.oneHandedMode = settings.oneHandedMode
        keyboardView.heightScale = settings.keyboardHeightScale
        keyboardView.sideMarginDp = settings.keyboardSideMarginDp
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
        (emojiBarBtn.compoundDrawables[0] as? SmileyDrawable)?.color = emojiBarBtn.currentTextColor
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
        ::settings.isInitialized && ::evalLogger.isInitialized && !isPasswordField && !isIncognitoField &&
            (settings.glideEvalLoggingEnabled || settings.usageLoggingEnabled)

    // Usage log: what Meltype proposed before the first manual fix, and the fixes made before commit.
    private var fixAutoPreview: String? = null
    private val fixActions = ArrayList<String>()
    private var lastClausesForLog = ""

    /** Records a manual fix. [conversionFix] marks fixes of the conversion (not of the typed letters). */
    private fun noteFix(action: String, conversionFix: Boolean = true) {
        if (!shouldLogEval() || session.isEmpty) return
        if (conversionFix && fixAutoPreview == null) fixAutoPreview = session.snapshot.previewText
        if (fixActions.size < 40) fixActions.add(action)
    }

    private fun logCommitDetail(raw: String, committed: String, via: String) {
        evalLogger.logCommit(
            settings.activePromptId, raw, committed, via,
            autoText = fixAutoPreview ?: committed,
            actions = fixActions.toList(),
            clauses = lastClausesForLog,
            englishMode = englishMode
        )
        fixAutoPreview = null
        fixActions.clear()
    }

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
        if (!session.isEmpty) {
            val zSnap = session.tryZSymbol(ch, getSurroundingTextBefore())
            if (zSnap != null) {
                clearUndoCommit()
                renderSnapshot(zSnap)
                return
            }
        }
        if (directCommit && session.isCaretInside) {
            clearUndoCommit()
            renderSnapshot(session.appendText(ch.toString(), getSurroundingTextBefore()))
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
                    logCommitDetail(raw, committed, "direct_char")
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
        if (session.isCaretInside) {
            clearUndoCommit()
            renderSnapshot(session.appendText(sym, getSurroundingTextBefore()))
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
        if (session.isCaretInside) {
            session.appendText(open + close, getSurroundingTextBefore())
            renderSnapshot(session.moveCaret(-close.length, getSurroundingTextBefore()))
            return
        }
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
        if (!session.isEmpty) {
            val zSnap = session.tryZSymbol(if (isPeriod) '.' else ',', getSurroundingTextBefore())
            if (zSnap != null) {
                clearUndoCommit()
                renderSnapshot(zSnap)
                return
            }
        }
        val snap = session.snapshot
        val lastClause = snap.clauses.lastOrNull()
        val lastIsAsciiAlnum = lastClause != null && lastClause.isEnglish &&
            lastClause.raw.lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() } == true
        val punct = when {
            englishMode -> if (isPeriod) "." else ","
            lastIsAsciiAlnum && settings.punctuationStyle != PunctuationStyle.HALF ->
                if (isPeriod) "." else ","
            else ->
                if (isPeriod) settings.punctuationStyle.period else settings.punctuationStyle.comma
        }
        if (snap.isComposing && session.isCaretInside) {
            clearUndoCommit()
            renderSnapshot(session.appendText(punct, getSurroundingTextBefore()))
            return
        }
        val shouldAutoCommit = englishMode || (settings.autoCommitOnPunctuation && !lastIsAsciiAlnum)
        if (shouldAutoCommit) {
            if (snap.isComposing) {
                val raw = snap.rawInput
                val committed = session.commitAndLearn(getSurroundingTextBefore())
                val out = committed + punct
                currentInputConnection?.commitText(out, 1)
                recordUndoCommit(raw, out)
                if (shouldLogEval()) {
                    logCommitDetail(raw, committed, "punct")
                }
                renderSnapshot(session.snapshot)
            } else {
                clearUndoCommit()
                currentInputConnection?.commitText(punct, 1)
                if (shouldLogEval()) {
                    evalLogger.logTap(settings.activePromptId, punct.firstOrNull() ?: ',', true, "")
                }
                renderSnapshot(session.snapshot)
            }
        } else {
            clearUndoCommit()
            val updated = session.appendText(punct, getSurroundingTextBefore())
            if (shouldLogEval()) {
                evalLogger.logTap(settings.activePromptId, punct.firstOrNull() ?: ',', false, updated.previewText)
            }
            renderSnapshot(updated)
        }
    }

    /** Commits the clauses up to the focused one and keeps the rest in composition. */
    private fun commitFocusedPrefixAndRender(logKind: String) {
        val rawBefore = session.snapshot.rawInput
        val committed = session.commitFocusedPrefix(getSurroundingTextBefore())
        if (committed.isEmpty()) return
        // Leave the composing region first so the committed part is not replaced by the remainder.
        currentInputConnection?.commitText(committed, 1)
        if (session.isEmpty) {
            recordUndoCommit(rawBefore, committed)
        } else {
            clearUndoCommit()
        }
        if (shouldLogEval()) {
            logCommitDetail(rawBefore, committed, logKind)
        }
        renderSnapshot(session.snapshot)
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
        if (englishMode) {
            // English mode behaves like Gboard's English layout: space commits the word with a space.
            val raw = snap.rawInput
            val out = session.commitAndLearn(getSurroundingTextBefore()) + " "
            currentInputConnection?.commitText(out, 1)
            recordUndoCommit(raw, out)
            renderSnapshot(session.snapshot)
            return
        }
        if (snap.isEditing) {
            // Kana view (caret editing or reverted by Backspace): Space converts again from the first clause.
            renderSnapshot(session.startConversion(getSurroundingTextBefore()))
            return
        }
        val singlePureEnglishWord = snap.clauses.size == 1 && snap.clauses[0].isEnglish &&
            snap.clauses[0].raw.all { it in 'a'..'z' || it in 'A'..'Z' || it == '\'' }
        val alreadyHasSpace = snap.rawInput.contains(' ')
        if (!snap.isConverting && (singlePureEnglishWord || alreadyHasSpace)) {
            val next = session.appendChar(' ', getSurroundingTextBefore())
            renderSnapshot(next)
            return
        }
        if (!snap.isConverting && snap.clauses.size > 1) {
            // First Space starts conversion at the first clause (Gboard style); later Spaces cycle candidates.
            renderSnapshot(session.startConversion(getSurroundingTextBefore()))
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
            noteFix("cycle:${currentClause.reading}→${nextCand.text}")
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
        val snapNow = session.snapshot
        if (snapNow.isConverting && snapNow.focusedClauseIndex < snapNow.clauses.lastIndex) {
            // Conversion state: Enter commits clause by clause from the first one.
            commitFocusedPrefixAndRender("enter_partial")
            return
        }
        if (!session.isEmpty) {
            val raw = session.snapshot.rawInput
            val committed = session.commitAndLearn(getSurroundingTextBefore())
            currentInputConnection?.commitText(committed, 1)
            recordUndoCommit(raw, committed)
            if (shouldLogEval()) {
                logCommitDetail(raw, committed, "enter")
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
        if (!session.isEmpty && session.snapshot.isEditing) {
            noteFix("caret", conversionFix = false)
            renderSnapshot(session.moveCaret(delta, getSurroundingTextBefore()))
            return
        }
        if (!session.isEmpty) {
            // While converting (or live-converting), ◀▶ shorten / lengthen the focused clause (including alphanumeric).
            // The focus moves with ‹ › on the clause bar or by tapping a clause; Spacebar slide / reading badge moves the caret.
            if (settings.arrowKeysMoveCaret) {
                noteFix("caret", conversionFix = false)
                renderSnapshot(session.moveCaret(delta, getSurroundingTextBefore()))
                return
            }
            noteFix("resize")
            renderSnapshot(session.stepThroughComposition(delta, getSurroundingTextBefore()))
        } else {
            clearUndoCommit()
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            sendDownUpKeyEvents(code)
            renderSnapshot(session.snapshot)
        }
    }

    override fun onSpaceSlideCursor(delta: Int) {
        if (!session.isEmpty) {
            noteFix("caret", conversionFix = false)
            renderSnapshot(session.moveCaret(delta, getSurroundingTextBefore()))
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
                // Up/down inside a composition: caret to its start/end; nothing is committed.
                renderSnapshot(session.moveCaretToEdge(start = dy < 0, surroundingContext = getSurroundingTextBefore()))
                return
            }
            clearUndoCommit()
            sendDownUpKeyEvents(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
        }
    }

    override fun onToggleLanguage() {
        if (!session.isEmpty) {
            onToggleSegmentLanguage()
            return
        }
        if (shouldLogEval()) {
            evalLogger.logEvent("mode", if (englishMode) "en->ja" else "ja->en", session.snapshot.previewText)
        }
        noteFix("mode", conversionFix = false)
        englishMode = !englishMode
        session.englishMode = englishMode
        if (::keyboardView.isInitialized) keyboardView.englishMode = englishMode
        showTransientTrace(if (englishMode) "English" else "日本語")
    }

    override fun onToggleSegmentLanguage() {
        if (!session.isEmpty) {
            val c = session.snapshot.clauses.getOrNull(session.snapshot.focusedClauseIndex)
            noteFix("lang:${c?.reading.orEmpty()}${if (c?.isEnglish == true) "(en->ja)" else "(ja->en)"}")
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
        val useEnglishGlide = englishMode || settings.glideEnglishOnly
        val preferJp = !useEnglishGlide && !inEnglishSentence
        val copiedPoints = points.toList()
        val boundsCopy = RectF(letterBounds)
        glidePreviewExecutor.execute {
            if (glidePreviewSeq.get() != seq) return@execute
            val norm = KeyLayoutGeometry.normalizeFromView(copiedPoints, boundsCopy)
            val cands = englishOnly(
                decoder.decode(norm, maxResults = if (useEnglishGlide) 8 else 3, preferJapanese = preferJp, fastPreview = true)
            ).take(3)
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
        val useEnglishGlide = englishMode || settings.glideEnglishOnly
        val preferJp = !useEnglishGlide && !inEnglishSentence
        val loggingEval = shouldLogEval()
        val debugRes = if (loggingEval) {
            decoder.decodeDebug(norm, maxResults = if (useEnglishGlide) 20 else 10, preferJapanese = preferJp)
        } else {
            null
        }
        val cands = englishOnly(
            if (debugRes != null) {
                debugRes.candidates.map { GlideCandidate(it.word, it.isEnglish, it.scoreCost, it.kanaHint) }
            } else {
                decoder.decode(norm, maxResults = if (useEnglishGlide) 20 else 8, preferJapanese = preferJp, fastPreview = false)
            }
        ).take(8)
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
        if (item.kind == CandidateKind.EMOJI && item.text.isNotEmpty()) {
            settings.addRecentEmoji(item.text)
        }
        val rawBefore = snap.rawInput
        val focusedClause = snap.clauses.getOrNull(snap.focusedClauseIndex)
        if (item.kind == CandidateKind.PHRASE) {
            val readingBefore = snap.clauses.joinToString("") { it.reading }
            noteFix("pick[phrase]:${rawBefore}→${item.text}", conversionFix = false)
            val text = session.takePhrase(item)
            currentInputConnection?.commitText(text, 1)
            recordUndoCommit(rawBefore, text)
            if (settings.learningEnabled && !isPasswordField && !isIncognitoField) settings.recordCommittedPhrase(readingBefore, text)
            isExpandedPanelOpen = false
            renderSnapshot(session.snapshot)
            return
        }
        if (item.kind == CandidateKind.CORRECTION) {
            // One tap on a correction confirms it (and the clauses before it).
            noteFix("pick[correction]:${focusedClause?.reading.orEmpty()}→${item.text}", conversionFix = false)
            session.applyCorrectionAndFocus(item, getSurroundingTextBefore())
            commitFocusedPrefixAndRender("correction")
            if (closeExpanded || session.isEmpty) isExpandedPanelOpen = false
            renderSnapshot(session.snapshot)
            return
        }
        noteFix(
            "pick[${item.kind.name.lowercase()}]:${focusedClause?.reading.orEmpty()}→${item.text}",
            conversionFix = item.kind != CandidateKind.CORRECTION
        )
        if (session.prepareCandidateCommit(item, getSurroundingTextBefore())) {
            // Picking a conversion candidate confirms it (and the clauses before it).
            if (shouldLogEval()) {
                evalLogger.logCandidatePicked(
                    promptId = settings.activePromptId,
                    clauseReading = focusedClause?.reading.orEmpty(),
                    pickedText = item.text,
                    kind = item.kind.name,
                    previewAfter = session.snapshot.previewText
                )
            }
            commitFocusedPrefixAndRender("candidate")
            if (closeExpanded || session.isEmpty) {
                isExpandedPanelOpen = false
                renderSnapshot(session.snapshot)
            }
            return
        }
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

    /** In English mode or when English-only glide is enabled, keep only English glide candidates (falling back if none). */
    private fun englishOnly(cands: List<GlideCandidate>): List<GlideCandidate> =
        if (englishMode || settings.glideEnglishOnly) cands.filter { it.isEnglish }.ifEmpty { cands } else cands

    private fun updateTraceBadgeMaxWidth(isEditing: Boolean, isTransient: Boolean = false) {
        val screenW = resources.displayMetrics.widthPixels
        val maxW = when {
            isTransient -> minOf(dp(180), (screenW * 0.45f).toInt().coerceAtLeast(dp(120)))
            isEditing -> minOf(dp(112), (screenW * 0.28f).toInt().coerceAtLeast(dp(84)))
            else -> minOf(dp(84), (screenW * 0.22f).toInt().coerceAtLeast(dp(64)))
        }
        traceBadge.maxWidth = maxW
    }

    private fun formatTraceBadge(snap: CompositionSnapshot): Pair<String, TextUtils.TruncateAt> {
        if (snap.isEditing) {
            val len = snap.editKana.length
            val c = snap.editKanaCaret.coerceIn(0, len)
            val left = if (c > 4) "…" + snap.editKana.substring(c - 4, c) else snap.editKana.substring(0, c)
            val right = if (len - c > 4) snap.editKana.substring(c, c + 4) + "…" else snap.editKana.substring(c)
            return "$left｜$right" to TextUtils.TruncateAt.MIDDLE
        }
        val full = snap.clauses.joinToString("│") { it.reading }.ifEmpty { snap.romajiTrace }
        if (full.length <= 7) return full to TextUtils.TruncateAt.START
        if (snap.focusedClauseIndex in 0 until snap.clauses.lastIndex) {
            val fc = snap.clauses[snap.focusedClauseIndex].reading
            val pre = if (snap.focusedClauseIndex > 0) "…" else ""
            return "$pre$fc…" to TextUtils.TruncateAt.END
        }
        return full to TextUtils.TruncateAt.START
    }

    private fun showTransientTrace(text: String) {
        if (!::traceBadge.isInitialized) return
        updateTraceBadgeMaxWidth(isEditing = false, isTransient = true)
        traceBadge.ellipsize = TextUtils.TruncateAt.END
        traceBadge.visibility = View.VISIBLE
        traceBadge.text = text
    }

    /** Puts the editor cursor [caret] characters into the composing text of length [len]. */
    private fun placeComposingCaret(ic: InputConnection, len: Int, caret: Int) {
        if (caret < 0 || caret >= len) return
        val end = try {
            if (Build.VERSION.SDK_INT >= 31) {
                ic.getSurroundingText(0, 0, 0)?.let { it.offset + it.selectionEnd }
            } else {
                null
            } ?: ic.getExtractedText(ExtractedTextRequest(), 0)?.let { it.startOffset + it.selectionEnd }
        } catch (_: Exception) {
            null
        } ?: return
        val pos = end - (len - caret)
        if (pos >= 0) ic.setSelection(pos, pos)
    }

    private fun renderSnapshot(snap: CompositionSnapshot) {
        if (snap.isComposing) {
            lastClausesForLog = snap.clauses.joinToString("|") {
                (if (it.isEnglish) "E:" else "") + it.reading + (if (it.text != it.reading) "→" + it.text else "")
            }
        } else if (fixActions.isNotEmpty() && session.isEmpty) {
            // Composition dropped without a commit (cleared); start fresh.
            fixAutoPreview = null
            fixActions.clear()
        }
        if (::keyboardView.isInitialized) {
            keyboardView.arrowResizeMode = snap.isComposing && !snap.isEditing
        }
        val dark = isDarkTheme()
        val accent = if (dark) Color.parseColor("#5C8AC4") else Color.parseColor("#4A6F9E")
        val focusBgSpan = if (dark) Color.argb(90, 92, 138, 196) else Color.argb(65, 74, 111, 158)

        val ic = currentInputConnection
        lastOwnEditUptime = android.os.SystemClock.uptimeMillis()
        if (snap.isComposing && snap.isEditing) {
            val spanned = SpannableString(snap.previewText)
            spanned.setSpan(UnderlineSpan(), 0, spanned.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ic?.setComposingText(spanned, 1)
            if (ic != null) placeComposingCaret(ic, snap.previewText.length, snap.caretOffset)
        }
        if (snap.isComposing) {
            val spanned = SpannableString(snap.previewText)
            var offset = 0
            for ((idx, clause) in snap.clauses.withIndex()) {
                if (snap.isEditing) break
                val end = (offset + clause.text.length).coerceAtMost(spanned.length)
                if (end > offset) {
                    spanned.setSpan(UnderlineSpan(), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (idx == snap.focusedClauseIndex) {
                        spanned.setSpan(BackgroundColorSpan(focusBgSpan), offset, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                offset = end
            }
            if (!snap.isEditing) ic?.setComposingText(spanned, 1)

            val (traceText, trunc) = formatTraceBadge(snap)
            updateTraceBadgeMaxWidth(isEditing = snap.isEditing)
            traceBadge.ellipsize = trunc
            traceBadge.visibility = View.VISIBLE
            traceBadge.text = traceText
            oneHandBtn.visibility = View.GONE
            settingsBtn.visibility = View.GONE

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
            oneHandBtn.visibility = View.VISIBLE
            settingsBtn.visibility = View.VISIBLE
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
                        noteFix("resize")
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

        var focusedPill: View? = null
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
                    val now = SystemClock.uptimeMillis()
                    val pending = pillSingleTap
                    if (pending != null && pillTapIdx == idx && now - pillTapTime < PILL_DOUBLE_TAP_MS) {
                        // Double tap on a clause: confirm up to it.
                        pillHandler.removeCallbacks(pending)
                        pillSingleTap = null
                        pillTapIdx = -1
                        noteFix("pill_double:$idx", conversionFix = false)
                        isExpandedPanelOpen = false
                        session.focusClause(idx)
                        commitFocusedPrefixAndRender("pill_double")
                        return@setOnClickListener
                    }
                    pending?.let { pillHandler.removeCallbacks(it) }
                    pillTapIdx = idx
                    pillTapTime = now
                    // The single-tap action waits for the double-tap window: acting at once would
                    // re-lay the bar and move the second tap onto another control.
                    val single = Runnable {
                        pillSingleTap = null
                        if (session.isEmpty || idx > session.snapshot.clauses.lastIndex) return@Runnable
                        if (session.snapshot.focusedClauseIndex == idx && session.snapshot.isConverting) {
                            expandedTab = ExpandedTab.CANDIDATES
                            isExpandedPanelOpen = !isExpandedPanelOpen
                            syncExpandedPanelState(session.snapshot)
                        } else {
                            renderSnapshot(session.focusClause(idx))
                        }
                    }
                    pillSingleTap = single
                    pillHandler.postDelayed(single, PILL_DOUBLE_TAP_MS)
                }
            }
            clausePillRow.addView(pill)
            if (focused) focusedPill = pill
        }
        focusedPill?.let { target ->
            clauseScroll.post {
                val targetX = (target.left - (clauseScroll.width - target.width) / 2).coerceAtLeast(0)
                clauseScroll.smoothScrollTo(targetX, 0)
            }
        }
    }

    private fun renderCandidateRow(snap: CompositionSnapshot, dark: Boolean, accent: Int) {
        candidateRow.removeAllViews()
        candidateScroll.scrollTo(0, 0)
        val chipBg = if (dark) Color.parseColor("#23272D") else Color.parseColor("#FFFFFF")
        val chipAltBg = if (dark) Color.parseColor("#1D232B") else Color.parseColor("#EDF2F7")
        val border = if (dark) Color.parseColor("#323741") else Color.parseColor("#DFE2E6")
        val textCol = if (dark) Color.parseColor("#ECEEF0") else Color.parseColor("#202328")
        val subCol = if (dark) Color.parseColor("#9BA1A8") else Color.parseColor("#737A84")

        if (!snap.isComposing && snap.candidates.isEmpty()) {
            // Password-manager suggestions first, then the fallback button in password fields.
            for (v in inlineViews) {
                (v.parent as? ViewGroup)?.removeView(v)
                v.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(33)
                ).apply { marginEnd = dp(5) }
                candidateRow.addView(v)
            }
            if (isPasswordField && inlineViews.isEmpty()) {
                passwordManagerApp()?.let { (pkg, label) ->
                    val btn = TextView(this).apply {
                        text = "\uD83D\uDD11 " + label
                        gravity = Gravity.CENTER
                        maxLines = 1
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(textCol)
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
                        contentDescription = "パスワード管理アプリを開く"
                        setOnClickListener { openPasswordManager(pkg) }
                    }
                    candidateRow.addView(btn)
                }
            }
            currentPasteOfferImage()?.let { imgEntry ->
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(3), dp(9), dp(3))
                    background = GradientDrawable().apply {
                        setColor(chipAltBg)
                        cornerRadius = dp(5).toFloat()
                        setStroke(dp(1), accent)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(33)
                    ).apply { marginEnd = dp(5) }
                    contentDescription = "コピーした画像を貼り付け"
                    setOnClickListener { commitClipboardImage(imgEntry) }
                    setOnLongClickListener {
                        lastHandledImageId = imgEntry.id
                        pasteOfferImage = null
                        renderSnapshot(session.snapshot)
                        true
                    }
                }
                loadThumbnailBitmap(imgEntry)?.let { bmp ->
                    val thumb = ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            marginEnd = dp(6)
                        }
                    }
                    chip.addView(thumb)
                }
                val label = TextView(this).apply {
                    text = "画像貼付"
                    maxLines = 1
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTextColor(textCol)
                }
                chip.addView(label)
                candidateRow.addView(chip)
            }
            currentPasteOffer()?.let { clip ->
                val oneLine = clip.replace(Regex("\\s+"), " ")
                val masked = isPasswordField || pasteOfferSensitive
                val chip = TextView(this).apply {
                    text = "\uD83D\uDCCB " + when {
                        masked -> "••••••"
                        oneLine.length > 16 -> oneLine.take(15) + "…"
                        else -> oneLine
                    }
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(textCol)
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
                    contentDescription = "コピーした文字を貼り付け"
                    setOnClickListener { pasteOffered(clip) }
                    setOnLongClickListener {
                        // Long-press dismisses the suggestion.
                        lastHandledClip = clip
                        pasteOfferText = null
                        renderSnapshot(session.snapshot)
                        true
                    }
                }
                candidateRow.addView(chip)
            }
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
            if (item.kind == CandidateKind.EMOJI && !canRenderEmoji(item.text)) {
                continue
            }
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(2), dp(10), dp(2))
                val isSelected = item.kind == CandidateKind.CONVERSION && item.text == focusedText && idx == 0
                val isGlide = item.kind == CandidateKind.GLIDE_ALT || item.kind == CandidateKind.CORRECTION
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.kind == CandidateKind.EMOJI) 16f else 14f)
                includeFontPadding = false
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

        fun addQuickStepRow(label: String, steps: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(mutedCol)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            for ((lbl, fn) in steps) {
                val b = makePanelButton(lbl, cardBg, textCol, border) { fn() }
                b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
                    marginStart = dp(4)
                }
                row.addView(b)
            }
            expandedContent.addView(row)
        }

        // Quick height and symbol-sensitivity adjustment strips inside keyboard
        val pct = (settings.keyboardHeightScale * 100f).roundToInt()
        fun adjustHeight(delta: Float?, exact: Float? = null) {
            val next = (exact ?: (settings.keyboardHeightScale + (delta ?: 0f))).coerceIn(0.65f, 1.55f)
            settings.keyboardHeightScale = next
            applySettingsToViews()
            populateExpandedPanel(session.snapshot)
        }
        addQuickStepRow(
            "高さ ${pct}%",
            listOf(
                "-5%" to { adjustHeight(-0.05f) },
                "-2%" to { adjustHeight(-0.02f) },
                "100%" to { adjustHeight(null, 1.0f) },
                "+2%" to { adjustHeight(+0.02f) },
                "+5%" to { adjustHeight(+0.05f) }
            )
        )

        val sensPct = (settings.flickSensitivity * 100f).roundToInt()
        fun adjustSymSensitivity(delta: Float?, exact: Float? = null) {
            val next = (exact ?: (settings.flickSensitivity + (delta ?: 0f))).coerceIn(0.50f, 1.50f)
            settings.flickSensitivity = next
            applySettingsToViews()
            populateExpandedPanel(session.snapshot)
        }
        addQuickStepRow(
            "記号感度 ${sensPct}%",
            listOf(
                "-10%" to { adjustSymSensitivity(-0.10f) },
                "-5%" to { adjustSymSensitivity(-0.05f) },
                "100%" to { adjustSymSensitivity(null, 1.0f) },
                "+5%" to { adjustSymSensitivity(+0.05f) },
                "+10%" to { adjustSymSensitivity(+0.10f) }
            )
        )

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
                noteFix("resize"); renderSnapshot(session.resizeFocusedClause(-1, getSurroundingTextBefore()))
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
                noteFix("resize"); renderSnapshot(session.resizeFocusedClause(+1, getSurroundingTextBefore()))
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
                    noteFix("variant:$value")
                    session.applyFocusedClauseSurface(
                        value,
                        getSurroundingTextBefore(),
                        advanceFocus = false
                    )
                    if (wasLast || snap.clauses.size <= 1) {
                        isExpandedPanelOpen = false
                    }
                    // Picking a script variant confirms it like a candidate.
                    commitFocusedPrefixAndRender("variant")
                    if (session.isEmpty) {
                        isExpandedPanelOpen = false
                        renderSnapshot(session.snapshot)
                    }
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
                            if (item.kind == CandidateKind.GLIDE_ALT || item.kind == CandidateKind.CORRECTION) accent else border
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
        captureClipboard()

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
            text = "クリップボード履歴（画像）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(mutedCol)
            setPadding(0, dp(6), 0, dp(4))
        })
        val imgClips = clipboardImageStore.list()
        if (imgClips.isEmpty()) {
            expandedContent.addView(TextView(this).apply {
                text = "コピーされた画像はまだありません（長押しで削除）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(mutedCol)
                setPadding(0, dp(2), 0, dp(6))
            })
        } else {
            val imgScroll = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val imgRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (img in imgClips) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    background = GradientDrawable().apply {
                        setColor(cardBg)
                        cornerRadius = dp(6).toFloat()
                        setStroke(dp(1), border)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        dp(76),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(6) }
                    setOnClickListener { commitClipboardImage(img) }
                    setOnLongClickListener {
                        clipboardImageStore.delete(img.id)
                        if (pasteOfferImage?.id == img.id) pasteOfferImage = null
                        populateExpandedPanel(session.snapshot)
                        true
                    }
                }
                val thumbView = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(border)
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                    loadThumbnailBitmap(img)?.let { setImageBitmap(it) }
                }
                card.addView(thumbView)
                val dimLabel = if (img.width > 0 && img.height > 0) "${img.width}×${img.height}" else img.mimeType.removePrefix("image/")
                card.addView(TextView(this).apply {
                    text = dimLabel
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                    setTextColor(mutedCol)
                    setPadding(0, dp(3), 0, 0)
                })
                imgRow.addView(card)
            }
            imgScroll.addView(imgRow)
            expandedContent.addView(imgScroll)
        }

        expandedContent.addView(TextView(this).apply {
            text = "クリップボード履歴（テキスト）"
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
                "◀ 左" to { onSpaceSlideCursor(-1) },
                "▼ 下" to {
                    clearUndoCommit()
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
                },
                "▶ 右" to { onSpaceSlideCursor(1) }
            )
        )
        addButtonRow(
            listOf(
                "全選択" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                },
                "コピー" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.copy)
                    captureClipboard()
                },
                "切取" to {
                    currentInputConnection?.performContextMenuAction(android.R.id.cut)
                    captureClipboard()
                },
                "貼付" to {
                    clearUndoCommit()
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val curClip = cm?.primaryClip
                    val curUri = curClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    val imgOffer = currentPasteOfferImage()
                    if (curUri != null && resolveClipboardImageMime(curClip.description, curUri) != null && imgOffer != null) {
                        commitClipboardImage(imgOffer)
                    } else {
                        currentInputConnection?.performContextMenuAction(android.R.id.paste)
                    }
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
        private const val PASTE_OFFER_MS = 5L * 60 * 1000
        private const val PILL_DOUBLE_TAP_MS = 280L
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
