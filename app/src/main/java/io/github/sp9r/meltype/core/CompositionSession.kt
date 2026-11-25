package io.github.sp9r.meltype.core

import io.github.sp9r.meltype.glide.GlideDecoder

enum class CandidateKind {
    CONVERSION,
    GLIDE_ALT,
    ENGLISH_CASE,
    UTILITY,
    EMOJI,
    /** Typo correction ("補正") or the literal "そのまま" reading of an auto-corrected segment. */
    CORRECTION,
    /** Prefix completion from user-defined phrases or frequently committed phrases. */
    PHRASE
}

data class CandidateItem(
    val text: String,
    val subtitle: String = "",
    val kind: CandidateKind = CandidateKind.CONVERSION,
    val targetClauseIndex: Int = -1,
    /** CORRECTION: replace buffer[replaceStart, replaceEnd) with [replaceRaw]. */
    val replaceStart: Int = -1,
    val replaceEnd: Int = -1,
    val replaceRaw: String? = null,
    /** CORRECTION: keep the segment starting here exactly as typed (disable auto correction). */
    val keepLiteralStart: Int = -1
)

data class DisplayClause(
    val segmentIndex: Int,
    val raw: String,
    val reading: String,
    val text: String,
    val isEnglish: Boolean,
    val candidates: List<String>,
    val readingOffset: Int = 0
)

data class CompositionSnapshot(
    val rawInput: String,
    val previewText: String,
    val romajiTrace: String,
    val clauses: List<DisplayClause>,
    val focusedClauseIndex: Int,
    val candidates: List<CandidateItem>,
    val isComposing: Boolean,
    val scriptVariants: List<Pair<String, String>> = emptyList(),
    val canShrinkClause: Boolean = false,
    val canExtendClause: Boolean = false,
    val hasGlideUndo: Boolean = false,
    val focusedAdjustableKana: String = "",
    val focusedAdjustableLen: Int = 0,
    /** Conversion state (after Space / clause focus): Enter commits clause by clause from the first. */
    val isConverting: Boolean = false,
    /** Editing view: a caret sits inside the composition (or Backspace reverted it to kana). */
    val isEditing: Boolean = false,
    /** Caret offset in [previewText] while [isEditing]; -1 otherwise. */
    val caretOffset: Int = -1,
    /** The composition is shown unconverted (Backspace during conversion). */
    val isKanaView: Boolean = false,
    /** The composition as kana and the caret offset in it, for the reading badge while [isEditing]. */
    val editKana: String = "",
    val editKanaCaret: Int = -1
)

/**
 * Manages the active Meltype composition buffer, live JP/EN segmentation,
 * Mozc conversion, manual clause boundary resizing, 1-tap glide undo, and glide alternative switching.
 */
class CompositionSession(
    private val dictionaries: MeltypeDictionaries,
    private val scoreEngine: ScoreEngine,
    private val converter: KanjiConverter,
    private val typoCorrectionEnabled: () -> Boolean = { true },
    /** Prefix completions for (raw input, kana reading); empty by default. */
    private val phraseProvider: (raw: String, reading: String, preview: String) -> List<PhraseSuggestion> = { _, _, _ -> emptyList() },
    /** Called with (reading, committed text) when a whole composition is committed; used for phrase learning. */
    private val onCommitted: (reading: String, text: String) -> Unit = { _, _ -> }
) {
    private val buffer = StringBuilder()
    private val bufferSpatialHints = ArrayList<Map<Char, Float>>()
    private val overrides = LinkedHashMap<IntRange, SegmentKind>()
    private val selectedClauseTextByKey = LinkedHashMap<String, String>()
    private val customClauseSplits = LinkedHashMap<Int, MutableList<Int>>()
    /** Segment start offsets the user asked to keep literal ("そのまま"). */
    private val noTypoFixStarts = HashSet<Int>()
    /** Correction chips per segment index, rebuilt on every recompute. */
    private val segmentCorrections = HashMap<Int, MutableList<CandidateItem>>()
    /** Last committed Japanese clauses (reading + surface), used as conversion context. */
    private val committedHistory = ArrayDeque<ConversionClause>()
    private var focusedClauseIdx = 0

    // Tracks last inserted glide span for 1-tap alternative switching and 1-tap backspace undo
    private var lastGlideRange: IntRange? = null
    private var lastGlideLeadingSpace: Boolean = false
    private var lastGlideAlternatives: List<Pair<String, Boolean>> = emptyList()

    private var cachedSnapshot: CompositionSnapshot = CompositionSnapshot(
        rawInput = "",
        previewText = "",
        romajiTrace = "",
        clauses = emptyList(),
        focusedClauseIndex = 0,
        candidates = emptyList(),
        isComposing = false
    )

    val snapshot: CompositionSnapshot get() = cachedSnapshot

    val isEmpty: Boolean get() = buffer.isEmpty()

    /** Raw buffer index of the editing caret; null means the caret is at the end (normal typing). */
    private var caretRaw: Int? = null
    /** Backspace during conversion returned the composition to its unconverted kana form. */
    private var kanaRevert = false
    private var converting = false

    /** True when the caret sits inside the composition, so new input is inserted there. */
    val isCaretInside: Boolean get() = caretRaw != null

    private data class EditUnit(val rawStart: Int, val rawEnd: Int, val text: String)

    /**
     * English input mode (Gboard's language key). Typed letters stay English instead of being read as
     * romaji, and z sequences are off. Text composed before the switch keeps its own language.
     */
    var englishMode: Boolean = false

    fun appendChar(
        ch: Char,
        surroundingContext: String? = null,
        spatialHints: Map<Char, Float> = emptyMap()
    ): CompositionSnapshot {
        clearLastGlideHint()
        converting = false
        val englishChar = englishMode && (ch.isLetterOrDigit() || ch == '\'')
        val at = caretRaw
        if (at != null) {
            insertRaw(at, ch.toString(), listOf(spatialHints))
            if (englishChar) markWordEnglishAround(at)
            return recompute(surroundingContext)
        }
        kanaRevert = false
        buffer.append(ch)
        bufferSpatialHints.add(spatialHints)
        if (englishChar) markWordEnglishAround(buffer.length - 1)
        return recompute(surroundingContext)
    }

    /** Pins the word containing [pos] as English, merging with the English word it extends. */
    private fun markWordEnglishAround(pos: Int) {
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '\''
        var start = pos
        while (start > 0 && isWord(buffer[start - 1])) start--
        var end = pos
        while (end + 1 < buffer.length && isWord(buffer[end + 1])) end++
        overrides.keys.filter { it.first <= end && it.last >= start }.toList().forEach { overrides.remove(it) }
        overrides[start..end] = SegmentKind.ENGLISH
    }

    /** Inserts [text] at raw index [at], shifting overrides and hints, and leaves the caret after it. */
    private fun insertRaw(at: Int, text: String, hints: List<Map<Char, Float>> = emptyList()) {
        val shifted = LinkedHashMap<IntRange, SegmentKind>()
        for ((r, k) in overrides) {
            val nr = when {
                r.last < at -> r
                r.first >= at -> (r.first + text.length)..(r.last + text.length)
                else -> r.first..(r.last + text.length)
            }
            shifted[nr] = k
        }
        overrides.clear()
        overrides.putAll(shifted)
        val starts = noTypoFixStarts.map { if (it >= at) it + text.length else it }
        noTypoFixStarts.clear()
        noTypoFixStarts.addAll(starts)
        buffer.insert(at, text)
        while (bufferSpatialHints.size < at) bufferSpatialHints.add(emptyMap())
        for (k in text.indices) bufferSpatialHints.add(at + k, hints.getOrNull(k) ?: emptyMap())
        while (bufferSpatialHints.size > buffer.length) bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        val next = at + text.length
        caretRaw = if (next >= buffer.length) null else next
    }

    /** Replaces raw [from, to) with [replacement], shifting overrides, typo locks, and spatial hints. */
    private fun replaceRaw(from: Int, to: Int, replacement: String) {
        if (to < from || from < 0 || to > buffer.length) return
        if (to == from && replacement.isEmpty()) return
        val delta = replacement.length - (to - from)
        val shifted = LinkedHashMap<IntRange, SegmentKind>()
        for ((r, k) in overrides) {
            val nr = when {
                r.last < from -> r
                r.first >= to -> (r.first + delta)..(r.last + delta)
                else -> {
                    val nf = if (r.first < from) r.first else from
                    val nl = if (r.last >= to) r.last + delta else from + replacement.length - 1
                    if (nl >= nf) nf..nl else null
                }
            }
            if (nr != null) shifted[nr] = k
        }
        overrides.clear()
        overrides.putAll(shifted)
        val starts = noTypoFixStarts.mapNotNull {
            when {
                it < from -> it
                it >= to -> it + delta
                else -> null
            }
        }
        noTypoFixStarts.clear()
        noTypoFixStarts.addAll(starts)
        buffer.replace(from, to, replacement)
        val delCount = to - from
        for (k in 0 until delCount) {
            if (from < bufferSpatialHints.size) bufferSpatialHints.removeAt(from)
        }
        val insertAt = from.coerceAtMost(bufferSpatialHints.size)
        for (k in replacement.indices) {
            bufferSpatialHints.add(insertAt + k, emptyMap())
        }
        while (bufferSpatialHints.size < buffer.length) bufferSpatialHints.add(emptyMap())
        while (bufferSpatialHints.size > buffer.length) bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
    }

    /** Deletes raw [from, to), shifting overrides and hints. Does not move the caret. */
    private fun deleteRaw(from: Int, to: Int) {
        replaceRaw(from, to, "")
    }

    /**
     * Deletes one display unit before raw index [pos]: one Japanese kana when the preceding
     * characters form converted Japanese kana, one romaji letter while still typing an
     * incomplete consonant prefix, or one ASCII/literal character elsewhere.
     * Returns the new raw cursor position after deletion.
     */
    private fun deleteOneUnitBefore(pos: Int): Int {
        if (pos <= 0) return 0
        val raw = buffer.toString()
        val segments = scoreEngine.segment(raw, overrides)
        val seg = segments.firstOrNull { pos > it.startIndex && pos <= it.endIndex }
        if (seg == null || seg.kind != SegmentKind.JAPANESE) {
            replaceRaw(pos - 1, pos, "")
            return pos - 1
        }
        val analysis = RomajiDetector.analyze(raw.substring(seg.startIndex, pos), strictForDetection = false)
        if (analysis.pendingTail.isNotEmpty() || analysis.chunks.isEmpty()) {
            replaceRaw(pos - 1, pos, "")
            return pos - 1
        }
        val last = analysis.chunks.last()
        val chunkStart = seg.startIndex + last.romajiStart
        val chunkEnd = seg.startIndex + last.romajiEnd
        if (last.kana.length >= 2) {
            val remainKana = last.kana.dropLast(1)
            val replacement = KANA_PREFIX_TO_ROMAJI[remainKana] ?: remainKana
            replaceRaw(chunkStart, chunkEnd, replacement)
            return chunkStart + replacement.length
        }
        val prev = analysis.chunks.getOrNull(analysis.chunks.size - 2)
        if (prev != null && prev.kana == "っ" && prev.rawRomaji.length == 1 && prev.rawRomaji[0] in 'a'..'z') {
            val pStart = seg.startIndex + prev.romajiStart
            replaceRaw(pStart, chunkEnd, "ltu")
            return pStart + 3
        }
        if (prev != null && prev.kana == "ん" && (prev.rawRomaji == "m" || prev.rawRomaji == "n")) {
            val pStart = seg.startIndex + prev.romajiStart
            replaceRaw(pStart, chunkEnd, "nn")
            return pStart + 2
        }
        replaceRaw(chunkStart, chunkEnd, "")
        return chunkStart
    }

    /**
     * The composition split into the units the editing caret steps over: one kana (or kana group such
     * as きょ) per romaji chunk in Japanese segments, one character elsewhere.
     */
    private fun editUnits(): List<EditUnit> {
        val raw = buffer.toString()
        val out = ArrayList<EditUnit>()
        var pos = 0
        fun chars(until: Int) {
            while (pos < until && pos < raw.length) {
                out.add(EditUnit(pos, pos + 1, raw[pos].toString()))
                pos++
            }
        }
        for (seg in scoreEngine.segment(raw, overrides)) {
            if (seg.startIndex < pos) continue
            chars(seg.startIndex)
            if (seg.kind == SegmentKind.JAPANESE) {
                val analysis = RomajiDetector.analyze(raw.substring(seg.startIndex, seg.endIndex), strictForDetection = false)
                for (c in analysis.chunks) {
                    val s = seg.startIndex + c.romajiStart
                    val e = seg.startIndex + c.romajiEnd
                    if (s < pos || e > seg.endIndex) continue
                    chars(s)
                    out.add(EditUnit(s, e, c.kana))
                    pos = e
                }
            }
            chars(seg.endIndex)
        }
        chars(raw.length)
        return out
    }

    private fun leaveEditing() {
        caretRaw = null
        kanaRevert = false
    }

    /**
     * Moves the editing caret by [delta] kana units inside the composition. The conversion stays
     * on screen; the caret is mapped into the converted text.
     */
    fun moveCaret(delta: Int, surroundingContext: String? = null): CompositionSnapshot {
        if (buffer.isEmpty() || delta == 0) return cachedSnapshot
        clearLastGlideHint()
        converting = false
        val units = editUnits()
        var p = caretRaw ?: buffer.length
        repeat(kotlin.math.abs(delta)) {
            p = if (delta < 0) {
                units.lastOrNull { it.rawStart < p }?.rawStart ?: p
            } else {
                units.firstOrNull { it.rawEnd > p }?.rawEnd ?: p
            }
        }
        caretRaw = if (p >= buffer.length) null else p
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Up/down arrow inside a composition: caret to its start or end (no commit). */
    fun moveCaretToEdge(start: Boolean, surroundingContext: String? = null): CompositionSnapshot {
        if (buffer.isEmpty()) return cachedSnapshot
        clearLastGlideHint()
        converting = false
        caretRaw = if (start) 0 else null
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Starts conversion review (Gboard's first Space): focus goes to the first clause. */
    fun startConversion(surroundingContext: String? = null): CompositionSnapshot {
        if (buffer.isEmpty()) return cachedSnapshot
        leaveEditing()
        converting = true
        focusedClauseIdx = 0
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Backspace during conversion: drops the chosen conversions and shows the composition as kana. */
    private fun revertToKana() {
        converting = false
        caretRaw = null
        kanaRevert = true
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        clearLastGlideHint()
    }

    /**
     * Google IME style "z" sequences (z. -> …, zh -> ← etc.). Applies only when the trailing "z"
     * is a pending romaji prefix of a readable run, so English words like "quiz." stay intact.
     * Returns null when [key] does not complete a z sequence.
     */
    fun tryZSymbol(key: Char, surroundingContext: String? = null): CompositionSnapshot? {
        val sym = Z_SYMBOLS[key] ?: return null
        if (englishMode) return null
        val pos = caretRaw ?: buffer.length
        if (pos <= 0 || buffer[pos - 1] != 'z') return null
        val zIdx = pos - 1
        if (overrides.keys.any { zIdx in it }) return null
        var s = zIdx
        while (s > 0 && buffer[s - 1] in 'a'..'z') s--
        while (s > 0 && buffer[s - 1] in 'A'..'Z') s--
        val wordWithZ = buffer.substring(s, pos).lowercase()
        if (wordWithZ.length >= 2 &&
            (dictionaries.englishAllWords.contains(wordWithZ) || dictionaries.properNounCanonical.containsKey(wordWithZ))
        ) {
            return null
        }
        if (key in 'a'..'z' &&
            (dictionaries.englishAllWords.hasPrefix(wordWithZ + key) || dictionaries.englishFocusedWords.hasPrefix(wordWithZ + key))
        ) {
            return null
        }
        val prefixBeforeZ = buffer.substring(s, zIdx).lowercase()
        val canTrigger = if (prefixBeforeZ.isEmpty()) {
            true
        } else {
            val a = RomajiDetector.analyze(prefixBeforeZ, strictForDetection = false)
            val prefixLooksValid = a.isStrictValid && (a.pendingTail.isEmpty() || a.pendingTail == "n")
            val prefixLooksEnglish = dictionaries.englishFocusedWords.contains(prefixBeforeZ) ||
                dictionaries.englishAllWords.contains(prefixBeforeZ) ||
                dictionaries.properNounCanonical.containsKey(prefixBeforeZ)
            prefixLooksValid || prefixLooksEnglish
        }
        if (!canTrigger) return null
        deleteRaw(zIdx, pos)
        if (caretRaw != null) {
            caretRaw = zIdx
        }
        return appendText(sym, surroundingContext)
    }

    fun appendText(text: String, surroundingContext: String? = null): CompositionSnapshot {
        clearLastGlideHint()
        converting = false
        val at = caretRaw
        if (at != null) {
            insertRaw(at, text)
            return recompute(surroundingContext)
        }
        kanaRevert = false
        buffer.append(text)
        repeat(text.length) { bufferSpatialHints.add(emptyMap()) }
        return recompute(surroundingContext)
    }

    /**
     * Inserts the top decoded glide candidate into the active composition.
     * Automatically adds a space only when appending an English word directly onto an existing English word.
     */
    fun appendGlideResult(
        primaryWord: String,
        isPrimaryEnglish: Boolean,
        alternatives: List<Pair<String, Boolean>>,
        surroundingContext: String? = null
    ): CompositionSnapshot {
        converting = false
        val at = caretRaw
        if (at != null) {
            clearLastGlideHint()
            insertRaw(at, primaryWord)
            if (primaryWord.isNotEmpty()) {
                val range = at until (at + primaryWord.length)
                overrides.keys.filter { it.first <= range.last && it.last >= range.first }.toList()
                    .forEach { overrides.remove(it) }
                overrides[range] = if (isPrimaryEnglish) SegmentKind.ENGLISH else SegmentKind.JAPANESE
            }
            return recompute(surroundingContext)
        }
        kanaRevert = false
        var addedSpace = false
        if (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            val lastSeg = cachedSnapshot.clauses.lastOrNull()
            val lastEndsWithAlnum = lastSeg?.raw?.lastOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() } == true
            if (lastSeg != null && lastSeg.isEnglish && lastEndsWithAlnum && isPrimaryEnglish) {
                buffer.append(' ')
                bufferSpatialHints.add(emptyMap())
                addedSpace = true
            }
        }
        val start = buffer.length
        buffer.append(primaryWord)
        repeat(primaryWord.length) { bufferSpatialHints.add(emptyMap()) }
        val end = buffer.length - 1
        lastGlideRange = if (primaryWord.isNotEmpty()) start..end else null
        lastGlideLeadingSpace = addedSpace
        lastGlideAlternatives = alternatives
        if (start <= end) {
            overrides[start..end] = if (isPrimaryEnglish) SegmentKind.ENGLISH else SegmentKind.JAPANESE
        }
        return recompute(surroundingContext)
    }

    fun replaceLastGlideAlternative(
        word: String,
        isEnglish: Boolean,
        surroundingContext: String? = null
    ): CompositionSnapshot {
        val range = lastGlideRange ?: return cachedSnapshot
        if (range.first < 0 || range.last >= buffer.length) return cachedSnapshot
        overrides.remove(range)
        customClauseSplits.clear()
        noTypoFixStarts.clear()
        buffer.replace(range.first, range.last + 1, word)
        while (bufferSpatialHints.size > buffer.length) {
            bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
        }
        while (bufferSpatialHints.size < buffer.length) {
            bufferSpatialHints.add(emptyMap())
        }
        val newRange = range.first until (range.first + word.length)
        lastGlideRange = if (word.isNotEmpty()) newRange else null
        if (newRange.first <= newRange.last) {
            overrides[newRange] = if (isEnglish) SegmentKind.ENGLISH else SegmentKind.JAPANESE
        }
        return recompute(surroundingContext)
    }

    /**
     * Deletes the entire last glided word in 1 tap when pressed immediately after a glide,
     * otherwise deletes the single trailing character.
     */
    fun backspace(surroundingContext: String? = null): CompositionSnapshot {
        if (buffer.isEmpty()) return cachedSnapshot
        if (converting) {
            revertToKana()
            return recompute(surroundingContext)
        }
        val caret = caretRaw
        if (caret != null || kanaRevert) {
            // Kana view: delete one display unit (kana or character) before the caret.
            clearLastGlideHint()
            val p = caret ?: buffer.length
            val nextPos = deleteOneUnitBefore(p)
            if (buffer.isEmpty()) return clearAll()
            caretRaw = if (caret == null || nextPos >= buffer.length) null else nextPos
            return recompute(surroundingContext, keepFocus = true)
        }
        val glideSpan = lastGlideRange
        if (glideSpan != null && glideSpan.last == buffer.length - 1 && glideSpan.first >= 0) {
            val delStart = if (lastGlideLeadingSpace && glideSpan.first > 0 && buffer[glideSpan.first - 1] == ' ') {
                glideSpan.first - 1
            } else {
                glideSpan.first
            }
            overrides.keys.filter { it.last >= delStart }.toList().forEach { overrides.remove(it) }
            buffer.delete(delStart, buffer.length)
            while (bufferSpatialHints.size > buffer.length) {
                bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
            }
            clearLastGlideHint()
            return recompute(surroundingContext)
        }

        clearLastGlideHint()
        deleteOneUnitBefore(buffer.length)
        if (buffer.isEmpty()) return clearAll()
        return recompute(surroundingContext)
    }

    fun clearAll(): CompositionSnapshot {
        buffer.setLength(0)
        bufferSpatialHints.clear()
        overrides.clear()
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        noTypoFixStarts.clear()
        clearLastGlideHint()
        focusedClauseIdx = 0
        caretRaw = null
        kanaRevert = false
        converting = false
        return recompute(null)
    }

    fun restoreFromUndo(rawInput: String, surroundingContext: String? = null): CompositionSnapshot {
        buffer.setLength(0)
        bufferSpatialHints.clear()
        overrides.clear()
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        noTypoFixStarts.clear()
        clearLastGlideHint()
        focusedClauseIdx = 0
        caretRaw = null
        kanaRevert = false
        converting = false
        if (rawInput.isNotEmpty()) {
            buffer.append(rawInput)
            repeat(rawInput.length) { bufferSpatialHints.add(emptyMap()) }
        }
        return recompute(surroundingContext)
    }

    fun toggleFocusedSegmentLanguage(surroundingContext: String? = null): CompositionSnapshot {
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
        leaveEditing()
        val idx = focusedClauseIdx.coerceIn(0, clauses.lastIndex)
        val targetSegIdx = clauses[idx].segmentIndex
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val targetSeg = segs.getOrNull(targetSegIdx) ?: return cachedSnapshot
        val range = targetSeg.startIndex until targetSeg.endIndex
        overrides.keys.filter { it.first < range.last + 1 && it.last >= range.first }.toList()
            .forEach { overrides.remove(it) }
        customClauseSplits.remove(targetSegIdx)
        val nextKind = if (clauses[idx].isEnglish) SegmentKind.JAPANESE else SegmentKind.ENGLISH
        overrides[range] = nextKind
        converting = true
        clearLastGlideHint()
        return recompute(surroundingContext, keepFocus = true)
    }

    /**
     * Manually shrinks (deltaKana < 0) or extends (deltaKana > 0) the focused clause.
     * Japanese clauses step by 1 hiragana character; English/numeric clauses step by 1 character.
     */
    fun resizeFocusedClause(deltaKana: Int, surroundingContext: String? = null): CompositionSnapshot {
        if (deltaKana == 0) return cachedSnapshot
        leaveEditing()
        val focused = cachedSnapshot.clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
        if (focused.isEnglish) return resizeNonJapaneseClause(focused, deltaKana, surroundingContext)
        val before = cachedSnapshot
        val resized = updateFocusedClauseSplit(
            targetLenProvider = { currentLen, _ -> currentLen + deltaKana },
            surroundingContext = surroundingContext
        )
        if (resized === before && deltaKana > 0) {
            extendJapaneseIntoNextSegment(focused, surroundingContext)?.let { return it }
        }
        return resized
    }

    private fun isResizableTailChar(c: Char): Boolean = !c.isWhitespace() && c != '。' && c != '、'

    private fun segmentKindForAlnumSlice(slice: String): SegmentKind =
        if (slice.any { it in 'a'..'z' || it in 'A'..'Z' }) SegmentKind.ENGLISH else SegmentKind.LITERAL

    private fun isResizableJapaneseReading(reading: String): Boolean =
        reading.isNotEmpty() && reading.any { it in '\u3041'..'\u309F' || it == 'ー' }

    private fun clearOverridesIn(first: Int, last: Int) {
        overrides.keys.filter { it.first <= last && it.last >= first }.toList().forEach { overrides.remove(it) }
    }

    /** Forgets clause splits and picked conversions from segment [segIdx] on (their indices may shift). */
    private fun dropClauseStateFrom(segIdx: Int) {
        customClauseSplits.keys.filter { it >= segIdx }.toList().forEach { customClauseSplits.remove(it) }
        selectedClauseTextByKey.keys.filter { (it.substringBefore(':').toIntOrNull() ?: 0) >= segIdx }
            .toList().forEach { selectedClauseTextByKey.remove(it) }
    }

    private fun resizeNonJapaneseClause(
        focused: DisplayClause,
        delta: Int,
        surroundingContext: String?
    ): CompositionSnapshot {
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val seg = segs.getOrNull(focused.segmentIndex) ?: return cachedSnapshot
        if (seg.kind != SegmentKind.ENGLISH && seg.kind != SegmentKind.LITERAL) return cachedSnapshot
        val targetLen = (seg.endIndex - seg.startIndex) + delta
        return resizeNonJapaneseClauseTo(focused, targetLen, surroundingContext)
    }

    private fun resizeNonJapaneseClauseTo(
        focused: DisplayClause,
        targetLen: Int,
        surroundingContext: String?
    ): CompositionSnapshot {
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val seg = segs.getOrNull(focused.segmentIndex) ?: return cachedSnapshot
        if (seg.kind != SegmentKind.ENGLISH && seg.kind != SegmentKind.LITERAL) return cachedSnapshot
        val start = seg.startIndex
        val curEnd = seg.endIndex
        var maxEnd = curEnd
        while (maxEnd < buffer.length && isResizableTailChar(buffer[maxEnd])) maxEnd++
        val newEnd = (start + targetLen).coerceIn(start + 1, maxEnd)
        if (newEnd == curEnd) return cachedSnapshot
        val next = segs.getOrNull(focused.segmentIndex + 1)?.takeIf { it.startIndex == curEnd }
        if (newEnd < curEnd) {
            val released = buffer.substring(newEnd, curEnd)
            val leftKind = segmentKindForAlnumSlice(buffer.substring(start, newEnd))
            val releasedIsLetters = released.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '\u3041'..'\u309F' || it in '\u30A0'..'\u30FF' || it == 'ー'
            }
            if (releasedIsLetters) {
                val jpEnd = if (next != null && next.kind == SegmentKind.JAPANESE) next.endIndex else curEnd
                clearOverridesIn(start, jpEnd - 1)
                overrides[start until newEnd] = leftKind
                overrides[newEnd until jpEnd] = SegmentKind.JAPANESE
            } else {
                val litEnd = if (next != null && next.kind == SegmentKind.LITERAL &&
                    next.raw.firstOrNull()?.let { it.isDigit() || it == '.' } == true
                ) {
                    next.endIndex
                } else {
                    curEnd
                }
                clearOverridesIn(start, litEnd - 1)
                overrides[start until newEnd] = leftKind
                overrides[newEnd until litEnd] = SegmentKind.LITERAL
            }
        } else {
            val lastTouched = segs.lastOrNull { it.startIndex < newEnd && it.endIndex > curEnd } ?: return cachedSnapshot
            val coverEnd = lastTouched.endIndex
            clearOverridesIn(start, coverEnd - 1)
            overrides[start until newEnd] = segmentKindForAlnumSlice(buffer.substring(start, newEnd))
            if (newEnd < coverEnd) {
                overrides[newEnd until coverEnd] = lastTouched.kind
            }
        }
        dropClauseStateFrom(focused.segmentIndex)
        converting = true
        clearLastGlideHint()
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Raw letters at the head of [raw] that make one whole kana (e.g. "ha", "tte"), or 0. */
    private fun leadingKanaRawLength(raw: String): Int {
        for (n in 1..minOf(4, raw.length)) {
            val a = RomajiDetector.analyze(raw.substring(0, n), strictForDetection = false)
            if (a.pendingTail.isEmpty() && RomajiDetector.isHiraganaOnly(a.hiragana)) return n
        }
        return 0
    }

    private fun extendJapaneseIntoNextSegment(focused: DisplayClause, surroundingContext: String?): CompositionSnapshot? {
        val clauses = cachedSnapshot.clauses
        if (focused.isEnglish || !isResizableJapaneseReading(focused.reading)) return null
        val nextClause = clauses.getOrNull(focusedClauseIdx + 1) ?: return null
        if (!nextClause.isEnglish || nextClause.segmentIndex != focused.segmentIndex + 1) return null
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val a = segs.getOrNull(focused.segmentIndex) ?: return null
        val b = segs.getOrNull(focused.segmentIndex + 1) ?: return null
        if (a.kind != SegmentKind.JAPANESE || a.endIndex != b.startIndex) return null
        val bCanYield = b.kind == SegmentKind.ENGLISH ||
            (b.kind == SegmentKind.LITERAL && b.raw.isNotEmpty() && isResizableTailChar(b.raw[0]))
        if (!bCanYield) return null
        val take = leadingKanaRawLength(b.raw).let { if (it > 0) it else 1 }.coerceAtMost(b.raw.length)
        if (take <= 0) return null
        val prevTotalHira = RomajiDetector.analyze(a.raw, strictForDetection = false).finalizedHiragana().length
        val newHeadHira = RomajiDetector.analyze(buffer.substring(a.startIndex, a.endIndex + take), strictForDetection = false).finalizedHiragana().length
        val gained = (newHeadHira - prevTotalHira).coerceAtLeast(1)
        val lens = clauses.filter { it.segmentIndex == focused.segmentIndex }.map { it.reading.length }.toMutableList()
        if (lens.isEmpty()) return null
        lens[lens.lastIndex] = lens.last() + gained

        val consumedAllOfB = (a.endIndex + take >= b.endIndex)
        val c = segs.getOrNull(focused.segmentIndex + 2)
        val mergeTrailingJp = consumedAllOfB && c != null && c.kind == SegmentKind.JAPANESE && c.startIndex == b.endIndex
        val newJpEnd = if (mergeTrailingJp) c!!.endIndex else a.endIndex + take

        clearOverridesIn(a.startIndex, (if (mergeTrailingJp) c!!.endIndex else b.endIndex) - 1)
        overrides[a.startIndex until newJpEnd] = SegmentKind.JAPANESE
        if (!consumedAllOfB) {
            overrides[(a.endIndex + take) until b.endIndex] = b.kind
        }
        dropClauseStateFrom(focused.segmentIndex)
        customClauseSplits[focused.segmentIndex] = lens
        converting = true
        clearLastGlideHint()
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Directly snaps the focused clause boundary to [targetKanaLen] characters. */
    fun setFocusedClauseLength(targetKanaLen: Int, surroundingContext: String? = null): CompositionSnapshot {
        if (targetKanaLen <= 0) return cachedSnapshot
        leaveEditing()
        val focused = cachedSnapshot.clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
        if (focused.isEnglish) return resizeNonJapaneseClauseTo(focused, targetKanaLen, surroundingContext)
        return updateFocusedClauseSplit(
            targetLenProvider = { _, _ -> targetKanaLen },
            surroundingContext = surroundingContext
        )
    }

    private fun updateFocusedClauseSplit(
        targetLenProvider: (currentLen: Int, totalAvailableInSeg: Int) -> Int,
        surroundingContext: String?
    ): CompositionSnapshot {
        var clauses = cachedSnapshot.clauses
        var focused = clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
        if (focused.isEnglish || !isResizableJapaneseReading(focused.reading)) return cachedSnapshot

        var guard = 0
        while (guard++ < 6) {
            val segIdx = focused.segmentIndex
            val segClauses = clauses.filter { it.segmentIndex == segIdx && !it.isEnglish && isResizableJapaneseReading(it.reading) }
            if (segClauses.isEmpty()) return cachedSnapshot
            val firstGlobalIdx = clauses.indexOfFirst { it.segmentIndex == segIdx }
            val relIdx = focusedClauseIdx - firstGlobalIdx
            if (relIdx !in segClauses.indices) return cachedSnapshot

            val prefixSumBefore = segClauses.take(relIdx).sumOf { it.reading.length }
            val totalSegKana = segClauses.sumOf { it.reading.length }
            val maxInCurrentSeg = (totalSegKana - prefixSumBefore).coerceAtLeast(1)
            val currentLen = segClauses[relIdx].reading.length
            val desiredLen = targetLenProvider(currentLen, maxInCurrentSeg)

            if (desiredLen > maxInCurrentSeg && mergeNextContiguousJapaneseSegment(segIdx, segClauses, surroundingContext)) {
                clauses = cachedSnapshot.clauses
                focused = clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
                continue
            }

            val finalLen = desiredLen.coerceIn(1, maxInCurrentSeg)
            if (finalLen == currentLen) return cachedSnapshot

            val prefixLens = segClauses.take(relIdx + 1).map { it.reading.length }.toMutableList()
            prefixLens[relIdx] = finalLen
            customClauseSplits[segIdx] = prefixLens

            val changedOffset = segClauses[relIdx].readingOffset
            val prefix = "$segIdx:"
            selectedClauseTextByKey.keys.filter { key ->
                if (!key.startsWith(prefix)) return@filter false
                val afterSeg = key.removePrefix(prefix)
                val off = afterSeg.substringBefore(':').toIntOrNull() ?: 0
                off >= changedOffset
            }.toList().forEach { selectedClauseTextByKey.remove(it) }
            converting = true
            clearLastGlideHint()
            return recompute(surroundingContext, keepFocus = true)
        }
        return cachedSnapshot
    }

    private fun mergeNextContiguousJapaneseSegment(
        segIdx: Int,
        segClauses: List<DisplayClause>,
        surroundingContext: String?
    ): Boolean {
        val clauses = cachedSnapshot.clauses
        val nextSegIdx = segIdx + 1
        val nextHasJp = clauses.any { it.segmentIndex == nextSegIdx && !it.isEnglish && isResizableJapaneseReading(it.reading) }
        if (!nextHasJp) return false
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val segA = segs.getOrNull(segIdx) ?: return false
        val segB = segs.getOrNull(nextSegIdx) ?: return false
        if (segA.kind != SegmentKind.JAPANESE || segB.kind != SegmentKind.JAPANESE || segA.endIndex != segB.startIndex) {
            return false
        }
        val segALens = segClauses.map { it.reading.length }
        val mergedRange = segA.startIndex until segB.endIndex
        overrides.keys.filter { it.first <= mergedRange.last && it.last >= mergedRange.first }.toList()
            .forEach { overrides.remove(it) }
        overrides[mergedRange] = SegmentKind.JAPANESE
        customClauseSplits.remove(segIdx)
        customClauseSplits.remove(nextSegIdx)
        if (segALens.isNotEmpty()) {
            customClauseSplits[segIdx] = segALens.toMutableList()
        }
        recompute(surroundingContext, keepFocus = true)
        return true
    }

    /**
     * Candidate tap: applies [item] and focuses its clause so the caller can commit up to it
     * (Gboard style: picking a conversion confirms it). Returns false when [item] is not one to commit.
     */
    fun prepareCandidateCommit(item: CandidateItem, surroundingContext: String? = null): Boolean {
        if (item.kind == CandidateKind.GLIDE_ALT || item.kind == CandidateKind.CORRECTION || item.kind == CandidateKind.PHRASE) return false
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return false
        val idx = (if (item.targetClauseIndex in clauses.indices) item.targetClauseIndex else focusedClauseIdx)
            .coerceIn(0, clauses.lastIndex)
        leaveEditing()
        selectedClauseTextByKey[clauseKey(clauses[idx])] = item.text
        converting = true
        focusedClauseIdx = idx
        recompute(surroundingContext, keepFocus = true)
        return true
    }

    fun selectCandidate(
        item: CandidateItem,
        surroundingContext: String? = null,
        advanceFocus: Boolean = false
    ): CompositionSnapshot {
        leaveEditing()
        if (item.kind == CandidateKind.GLIDE_ALT) {
            val isEn = item.subtitle.startsWith("英")
            return replaceLastGlideAlternative(item.text, isEn, surroundingContext)
        }
        if (item.kind == CandidateKind.CORRECTION) {
            return applyCorrection(item, surroundingContext)
        }
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
        val idx = (if (item.targetClauseIndex in clauses.indices) item.targetClauseIndex else focusedClauseIdx)
            .coerceIn(0, clauses.lastIndex)
        val clause = clauses[idx]
        selectedClauseTextByKey[clauseKey(clause)] = item.text
        if (item.kind == CandidateKind.CONVERSION) {
            converting = true
        }
        if (advanceFocus && idx < clauses.lastIndex) {
            focusedClauseIdx = idx + 1
        }
        return recompute(surroundingContext, keepFocus = true)
    }

    /**
     * Correction chip tap: applies the correction and focuses the clause it produced, so the caller can
     * commit up to it with one tap (no second confirmation step).
     */
    fun applyCorrectionAndFocus(item: CandidateItem, surroundingContext: String? = null): CompositionSnapshot {
        leaveEditing()
        val snap = applyCorrection(item, surroundingContext)
        val anchor = if (item.keepLiteralStart >= 0) item.keepLiteralStart else item.replaceStart
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val segIdx = segs.indexOfFirst { anchor >= it.startIndex && anchor < it.endIndex }
        val idx = snap.clauses.indexOfLast { it.segmentIndex == segIdx }
        if (idx < 0) return snap
        converting = true
        focusedClauseIdx = idx
        return recompute(surroundingContext, keepFocus = true)
    }

    /** Phrase chip tap: drops the composition and returns the phrase to insert instead. */
    fun takePhrase(item: CandidateItem): String {
        clearAll()
        return item.text
    }

    private fun applyCorrection(item: CandidateItem, surroundingContext: String?): CompositionSnapshot {
        if (item.keepLiteralStart >= 0) {
            noTypoFixStarts.add(item.keepLiteralStart)
            selectedClauseTextByKey.clear()
            customClauseSplits.clear()
            return recompute(surroundingContext)
        }
        val newRaw = item.replaceRaw ?: return cachedSnapshot
        val start = item.replaceStart
        val end = item.replaceEnd
        if (start < 0 || end > buffer.length || start >= end) return cachedSnapshot
        clearLastGlideHint()
        val oldLen = end - start
        buffer.replace(start, end, newRaw)
        val keptHints = if (newRaw.length == oldLen) bufferSpatialHints.subList(start, end).toList() else List(newRaw.length) { emptyMap() }
        for (k in 0 until oldLen) if (start < bufferSpatialHints.size) bufferSpatialHints.removeAt(start)
        bufferSpatialHints.addAll(start.coerceAtMost(bufferSpatialHints.size), keptHints)
        while (bufferSpatialHints.size < buffer.length) bufferSpatialHints.add(emptyMap())
        while (bufferSpatialHints.size > buffer.length) bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
        if (newRaw.length != oldLen) overrides.clear()
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        return recompute(surroundingContext)
    }

    fun applyFocusedClauseSurface(
        surface: String,
        surroundingContext: String? = null,
        advanceFocus: Boolean = true
    ): CompositionSnapshot {
        val clauses = cachedSnapshot.clauses
        val clause = clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
        selectedClauseTextByKey[clauseKey(clause)] = surface
        if (advanceFocus && focusedClauseIdx < clauses.lastIndex) {
            focusedClauseIdx += 1
        }
        return recompute(surroundingContext, keepFocus = true)
    }

    fun moveFocus(delta: Int): CompositionSnapshot {
        val count = cachedSnapshot.clauses.size
        if (count <= 0) return cachedSnapshot
        leaveEditing()
        converting = true
        focusedClauseIdx = (focusedClauseIdx + delta).coerceIn(0, count - 1)
        return recompute(null, keepFocus = true)
    }

    /**
     * Left/right arrow during a composition: resizes the focused clause; when the clause cannot move
     * any further that way, focus carries on to the neighbouring clause, so repeated presses reach
     * every part of the composition instead of stopping at one conversion unit.
     */
    fun stepThroughComposition(delta: Int, surroundingContext: String? = null): CompositionSnapshot {
        if (delta == 0 || cachedSnapshot.clauses.isEmpty()) return cachedSnapshot
        val before = cachedSnapshot
        val focusBefore = focusedClauseIdx
        val resized = resizeFocusedClause(delta, surroundingContext)
        if (resized !== before) return resized
        val target = (focusBefore + if (delta < 0) -1 else 1).coerceIn(0, cachedSnapshot.clauses.lastIndex)
        if (target == focusBefore) return cachedSnapshot
        return focusClause(target)
    }

    fun focusClause(index: Int): CompositionSnapshot {
        val count = cachedSnapshot.clauses.size
        if (index in 0 until count) {
            leaveEditing()
            converting = true
            focusedClauseIdx = index
        }
        return recompute(null, keepFocus = true)
    }

    /**
     * Commits current composition text, optionally triggers Mozc learning, and clears buffer.
     */
    fun commitAndLearn(surroundingContext: String? = null): String {
        val snap = cachedSnapshot
        val committed = snap.previewText
        if (snap.isKanaView) {
            // The kana view is committed as shown; it is not a conversion result to learn from.
            committedHistory.clear()
            clearAll()
            return committed
        }
        val mozcClauses = snap.clauses
            .filter { !it.isEnglish && RomajiDetector.isHiraganaOnly(it.reading) }
            .map { ConversionClause(it.reading, it.text) }
        if (mozcClauses.isNotEmpty()) {
            converter.learn(surroundingContext, mozcClauses)
        }
        rememberCommitted(snap.clauses.map { Triple(it.reading, it.text, it.isEnglish) })
        onCommitted(snap.clauses.joinToString("") { it.reading }, committed)
        clearAll()
        return committed
    }

    /**
     * Records committed clauses so the next composition can be converted with them as context
     * (e.g. committed 学校に + typed "itta" -> 行った rather than 言った).
     */
    fun rememberCommitted(clauses: List<Triple<String, String, Boolean>>) {
        for ((reading, text, isEnglish) in clauses) {
            if (isEnglish || reading.isEmpty() || !RomajiDetector.isHiraganaOnly(reading)) {
                committedHistory.clear()
            } else {
                committedHistory.addLast(ConversionClause(reading, text))
            }
        }
        while (committedHistory.size > 2) committedHistory.removeFirst()
    }

    /** Returns (reading, surface) of the recent committed clauses that the text before the cursor still ends with. */
    private fun historyPrefix(context: String?): Pair<String, String>? {
        if (context.isNullOrEmpty() || committedHistory.isEmpty()) return null
        val items = committedHistory.toList()
        for (take in items.size downTo 1) {
            val tail = items.takeLast(take)
            val reading = tail.joinToString("") { it.reading }
            val text = tail.joinToString("") { it.text }
            if (reading.length in 2..10 && context.endsWith(text)) return reading to text
        }
        return null
    }

    /**
     * Converts [hira] with the committed clauses prepended as reading, so Mozc sees the real
     * sentence. The result is used only when Mozc reproduces the committed text exactly with a
     * clause boundary at the join; otherwise falls back to converting [hira] alone.
     */
    private fun convertWithHistory(hira: String, context: String?): List<ConversionClause>? {
        val prefix = historyPrefix(context)
        if (prefix != null && hira.isNotEmpty()) {
            val (pReading, pText) = prefix
            val outerContext = context?.dropLast(pText.length)?.ifEmpty { null }
            val full = converter.convertClauses(pReading + hira, outerContext)
            if (full != null) {
                var acc = 0
                var idx = 0
                val sb = StringBuilder()
                while (idx < full.size && acc < pReading.length) {
                    acc += full[idx].reading.length
                    sb.append(full[idx].text)
                    idx++
                }
                if (acc == pReading.length && idx < full.size && sb.toString() == pText) {
                    return full.subList(idx, full.size)
                }
            }
        }
        return converter.convertClauses(hira, context)
    }

    /**
     * Commits clauses [0..focusedClauseIndex] cleanly and leaves remaining clauses in composition.
     */
    fun commitFocusedPrefix(surroundingContext: String? = null): String {
        val snap = cachedSnapshot
        val clauses = snap.clauses
        if (clauses.isEmpty()) return ""
        if (snap.isEditing) return commitAndLearn(surroundingContext)
        val cutIdx = snap.focusedClauseIndex.coerceIn(0, clauses.lastIndex)
        if (cutIdx == clauses.lastIndex) {
            return commitAndLearn(surroundingContext)
        }

        val committedClauses = clauses.subList(0, cutIdx + 1)
        val committed = committedClauses.joinToString("") { it.text }
        rememberCommitted(committedClauses.map { Triple(it.reading, it.text, it.isEnglish) })
        val mozcClauses = committedClauses
            .filter { !it.isEnglish && RomajiDetector.isHiraganaOnly(it.reading) }
            .map { ConversionClause(it.reading, it.text) }
        if (mozcClauses.isNotEmpty()) {
            converter.learn(surroundingContext, mozcClauses)
        }

        val cutClause = clauses[cutIdx]
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val targetSeg = segs.getOrNull(cutClause.segmentIndex)
        if (targetSeg == null) {
            clearAll()
            return committed
        }

        val remInSameSeg = clauses.drop(cutIdx + 1)
            .filter { it.segmentIndex == cutClause.segmentIndex }
            .joinToString("") { it.reading }
        val tailAfterSeg = if (targetSeg.endIndex < buffer.length) buffer.substring(targetSeg.endIndex) else ""
        val newRaw = remInSameSeg + tailAfterSeg
        val shift = targetSeg.endIndex - remInSameSeg.length

        val nextOverrides = LinkedHashMap<IntRange, SegmentKind>()
        for ((range, kind) in overrides) {
            if (range.first >= targetSeg.endIndex) {
                val ns = (range.first - shift).coerceAtLeast(0)
                val ne = (range.last - shift).coerceAtLeast(ns)
                nextOverrides[ns..ne] = kind
            }
        }

        val tailSpatial = if (targetSeg.endIndex < bufferSpatialHints.size) {
            bufferSpatialHints.subList(targetSeg.endIndex, bufferSpatialHints.size).toList()
        } else {
            emptyList()
        }
        buffer.setLength(0)
        buffer.append(newRaw)
        bufferSpatialHints.clear()
        repeat(remInSameSeg.length) { bufferSpatialHints.add(emptyMap()) }
        bufferSpatialHints.addAll(tailSpatial)
        while (bufferSpatialHints.size < buffer.length) bufferSpatialHints.add(emptyMap())
        while (bufferSpatialHints.size > buffer.length) bufferSpatialHints.removeAt(bufferSpatialHints.lastIndex)
        overrides.clear()
        overrides.putAll(nextOverrides)
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        noTypoFixStarts.clear()
        clearLastGlideHint()
        focusedClauseIdx = 0
        recompute((surroundingContext ?: "") + committed, keepFocus = true)
        return committed
    }

    private fun clearLastGlideHint() {
        lastGlideRange = null
        lastGlideLeadingSpace = false
        lastGlideAlternatives = emptyList()
    }

    /** True when [fixed] differs from [typed] by substitutions whose taps landed near the substituted key. */
    private fun spatiallySupported(typed: String, fixed: String, hints: List<Map<Char, Float>>): Boolean {
        if (typed.length != fixed.length || hints.isEmpty()) return false
        var diffs = 0
        for (k in typed.indices) {
            if (typed[k] == fixed[k]) continue
            diffs++
            val w = hints.getOrNull(k)?.get(fixed[k]) ?: return false
            if (w < 0.25f) return false
        }
        return diffs in 1..2
    }

    private fun clauseKey(c: DisplayClause): String = "${c.segmentIndex}:${c.readingOffset}:${c.raw}:${c.reading}"

    private fun recompute(surroundingContext: String?, keepFocus: Boolean = false): CompositionSnapshot {
        val raw = buffer.toString()
        if (raw.isEmpty()) {
            caretRaw = null
            kanaRevert = false
            converting = false
            cachedSnapshot = CompositionSnapshot("", "", "", emptyList(), 0, emptyList(), false)
            return cachedSnapshot
        }

        val segments = scoreEngine.segment(raw, overrides)
        val displayClauses = ArrayList<DisplayClause>()
        val segContextBuf = StringBuilder(surroundingContext ?: "")
        segmentCorrections.clear()

        for ((segIdx, seg) in segments.withIndex()) {
            val segContext = segContextBuf.toString().ifEmpty { null }
            val segClauseStart = displayClauses.size
            when (seg.kind) {
                SegmentKind.ENGLISH -> {
                    val lower = seg.raw.lowercase()
                    val proper = seg.canonicalEnglish ?: dictionaries.properNounCanonical[lower]
                    val title = lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val upper = seg.raw.uppercase()
                    val hiraVersion = RomajiDetector.analyze(lower, strictForDetection = false).finalizedHiragana()
                    val kataVersion = RomajiDetector.toKatakana(hiraVersion)
                    // Lowercase-typed brand/proper nouns show their canonical casing (google -> Google);
                    // the literal lowercase spelling stays as the next candidate.
                    val preferProper = proper != null && seg.raw == lower && proper != lower
                    val cands = if (preferProper) linkedSetOf(proper!!, seg.raw) else linkedSetOf(seg.raw)
                    if (proper != null) cands.add(proper)
                    cands.add(title)
                    cands.add(upper)
                    if (hiraVersion.isNotEmpty()) {
                        cands.add(hiraVersion)
                        cands.add(kataVersion)
                    }
                    val tempKey = "$segIdx:0:${seg.raw}:${seg.raw}"
                    val chosen = selectedClauseTextByKey[tempKey] ?: cands.first()
                    displayClauses.add(
                        DisplayClause(
                            segmentIndex = segIdx,
                            raw = seg.raw,
                            reading = seg.raw,
                            text = chosen,
                            isEnglish = true,
                            candidates = cands.toList(),
                            readingOffset = 0
                        )
                    )
                }

                SegmentKind.LITERAL -> {
                    val cands = mutableListOf<String>()
                    val canon = seg.canonicalEnglish
                    if (canon != null && canon != seg.raw) {
                        cands.add(canon)
                    }
                    val calc = UtilityConverter.evaluateCalculator(seg.raw)
                    if (calc != null && seg.raw.endsWith("=")) {
                        cands.add(calc)
                        cands.add(seg.raw)
                    } else {
                        cands.add(seg.raw)
                        if (calc != null) cands.add(calc)
                    }
                    cands.addAll(UtilityConverter.numericCandidates(seg.raw))
                    cands.addAll(UtilityConverter.punctuationCandidates(seg.raw))
                    val tempKey = "$segIdx:0:${seg.raw}:${seg.raw}"
                    val chosen = selectedClauseTextByKey[tempKey] ?: cands.first()
                    displayClauses.add(
                        DisplayClause(
                            segmentIndex = segIdx,
                            raw = seg.raw,
                            reading = seg.raw,
                            text = chosen,
                            isEnglish = true,
                            candidates = cands.distinct(),
                            readingOffset = 0
                        )
                    )
                }

                SegmentKind.JAPANESE -> {
                    val typoEngine = scoreEngine.typoEngine
                    val useTypoFix = typoCorrectionEnabled()
                    val rawLower = seg.raw.lowercase()
                    var effectiveRaw = seg.raw
                    var rawTypoFallback: String? = null
                    var unreadableFixApplied = false

                    val segSpatial = if (seg.startIndex >= 0 && seg.endIndex <= bufferSpatialHints.size) {
                        bufferSpatialHints.subList(seg.startIndex, seg.endIndex)
                    } else {
                        emptyList()
                    }
                    val literalLocked = seg.startIndex in noTypoFixStarts
                    val corrections = ArrayList<CandidateItem>()
                    val correctionKana = HashSet<String>()
                    fun addCorrection(fixedRaw: String, kana: String) {
                        if (kana.isEmpty() || !correctionKana.add(kana) || corrections.size >= 3) return
                        val text = converter.convertClauses(kana, segContext)?.joinToString("") { it.text } ?: kana
                        corrections.add(
                            CandidateItem(
                                text = text,
                                subtitle = "補正",
                                kind = CandidateKind.CORRECTION,
                                replaceStart = seg.startIndex,
                                replaceEnd = seg.endIndex,
                                replaceRaw = fixedRaw
                            )
                        )
                    }
                    if (useTypoFix && seg.raw.all { it in 'a'..'z' || it in 'A'..'Z' }) {
                        val hasUnreadable = typoEngine.firstUnreadableOffset(rawLower, final = false) >= 0
                        if (hasUnreadable) {
                            val fixes = typoEngine.suggestUnreadableFixes(rawLower, final = false, spatialWeights = segSpatial)
                            val fix = fixes.firstOrNull()
                            if (fix != null) {
                                if (literalLocked) {
                                    for (f in fixes) addCorrection(f.right, f.kana)
                                } else {
                                    effectiveRaw = fix.right
                                    rawTypoFallback = RomajiDetector.analyze(rawLower, strictForDetection = false).finalizedHiragana()
                                    unreadableFixApplied = true
                                    for (alt in fixes.drop(1)) addCorrection(alt.right, alt.kana)
                                }
                            }
                        }
                    }

                    val analysis = RomajiDetector.analyze(effectiveRaw, strictForDetection = false)
                    val hiraReady = analysis.hiragana
                    val pendingTail = analysis.pendingTail
                    val convertibleHira = if (pendingTail == "n") hiraReady + "ん" else hiraReady

                    var activeHira = convertibleHira
                    val didyouMeanForWholeSeg = LinkedHashSet<String>()
                    if (useTypoFix && !unreadableFixApplied && convertibleHira.isNotEmpty() &&
                        effectiveRaw.all { it in 'a'..'z' || it in 'A'..'Z' } &&
                        (pendingTail.isEmpty() || pendingTail == "n")
                    ) {
                        val readableFixes = typoEngine.suggestReadableTypos(
                            rawLower,
                            convertibleHira,
                            allowFinalTailTrim = (segIdx < segments.lastIndex),
                            spatialWeights = segSpatial
                        )
                        if (readableFixes.isNotEmpty()) {
                            val top = readableFixes.first()
                            val (rawSegCost, _) = typoEngine.segmentKana(convertibleHira, -1)
                            val marginOk = readableFixes.size == 1 ||
                                readableFixes[1].kana == top.kana ||
                                readableFixes[1].cost - top.cost >= 0.22 ||
                                (top.right.length == rawLower.length &&
                                    readableFixes[1].right.length != rawLower.length &&
                                    readableFixes[1].cost - top.cost >= 0.12)
                            val hasRareQCluster = convertibleHira.contains("くぁ") ||
                                convertibleHira.contains("くぃ") ||
                                convertibleHira.contains("くぇ") ||
                                convertibleHira.contains("くぉ")
                            val rawNotCohesive = !typoEngine.isKnownReading(convertibleHira) || rawSegCost >= 1.95
                            val minLenOk = rawLower.length >= 5 || (hasRareQCluster && rawLower.length >= 4)
                            val wordRewrite = top.isSingleWholeWord &&
                                minLenOk &&
                                marginOk &&
                                !typoEngine.isKnownWord(convertibleHira) &&
                                !typoEngine.hasKnownReadingPrefix(convertibleHira) &&
                                rawNotCohesive &&
                                (hasRareQCluster || spatiallySupported(rawLower, top.right, segSpatial))
                            // Sentence-level rewrite: the typed reading contains an unknown piece, the fix
                            // makes the whole reading known, and the tap actually landed near the fixed key.
                            val sentenceRewrite = !wordRewrite &&
                                rawLower.length >= 6 &&
                                marginOk &&
                                !typoEngine.isKnownReading(convertibleHira) &&
                                typoEngine.isKnownReading(top.kana) &&
                                spatiallySupported(rawLower, top.right, segSpatial)
                            val canConsiderRewrite = !literalLocked && (wordRewrite || sentenceRewrite)
                            if (canConsiderRewrite) {
                                // Double-check whether Mozc already converts the raw reading cleanly into Kanji (e.g. 電磁気 for でんじき).
                                val rawMozc = converter.convertClauses(convertibleHira, segContext)
                                val rawMozcCohesive = rawMozc != null &&
                                    rawMozc.isNotEmpty() &&
                                    rawMozc.size <= 2 &&
                                    rawMozc.all { cl ->
                                        cl.reading.length >= 2 &&
                                            cl.text.any { ch -> ch in '\u4E00'..'\u9FFF' }
                                    }
                                if (!rawMozcCohesive) {
                                    rawTypoFallback = convertibleHira
                                    activeHira = top.kana
                                }
                            }
                            for (rf in readableFixes.take(3)) {
                                if (rf.kana != activeHira) addCorrection(rf.right, rf.kana)
                            }
                        }
                        // Spatial border suggestions (tap landed near the edge of a neighbor key).
                        val spatialAlts = typoEngine.suggestSpatialAlternatives(rawLower, activeHira, segSpatial)
                        for (alt in spatialAlts) {
                            if (alt.kana != activeHira) addCorrection(alt.right, alt.kana)
                        }
                    }
                    val literalHira = rawTypoFallback
                    if (literalHira != null && activeHira.isNotEmpty() && literalHira != activeHira) {
                        // Auto-corrected: offer the literal reading as "そのまま".
                        val literalText = converter.convertClauses(literalHira, segContext)?.joinToString("") { it.text }
                            ?: literalHira
                        corrections.add(
                            0,
                            CandidateItem(
                                text = literalText,
                                subtitle = "そのまま",
                                kind = CandidateKind.CORRECTION,
                                keepLiteralStart = seg.startIndex
                            )
                        )
                    }
                    if (corrections.isNotEmpty()) segmentCorrections[segIdx] = corrections

                    if (activeHira.isNotEmpty()) {
                        val lockedLens = customClauseSplits[segIdx]
                        val usingCustomSplit = lockedLens != null &&
                            lockedLens.isNotEmpty() &&
                            lockedLens.all { it >= 1 } &&
                            lockedLens.sum() <= activeHira.length
                        val clauses: List<ConversionClause> = if (usingCustomSplit) {
                            var offset = 0
                            var prefixCtx = segContext
                            val lockedClauses = lockedLens!!.map { sliceLen ->
                                val sliceOffset = offset
                                val sliceReading = activeHira.substring(sliceOffset, sliceOffset + sliceLen)
                                val sliceKey = "$segIdx:$sliceOffset:${seg.raw}:$sliceReading"
                                offset += sliceLen
                                val multiBest = converter.convertClauses(sliceReading, prefixCtx)?.joinToString("") { it.text }
                                val candBest = converter.candidates(sliceReading).firstOrNull()
                                val bestCand = when {
                                    multiBest != null && (multiBest != sliceReading || candBest == null) -> multiBest
                                    candBest != null -> candBest
                                    else -> sliceReading
                                }
                                val chosenForCtx = selectedClauseTextByKey[sliceKey] ?: bestCand
                                prefixCtx = (prefixCtx ?: "") + chosenForCtx
                                ConversionClause(sliceReading, bestCand)
                            }
                            val remainder = activeHira.substring(offset)
                            val tailClauses = if (remainder.isNotEmpty()) {
                                converter.convertClauses(remainder, prefixCtx)
                                    ?: listOf(ConversionClause(remainder, remainder))
                            } else {
                                emptyList()
                            }
                            lockedClauses + tailClauses
                        } else {
                            if (lockedLens != null) customClauseSplits.remove(segIdx)
                            val converted = if (segIdx == 0) {
                                convertWithHistory(activeHira, segContext)
                            } else {
                                converter.convertClauses(activeHira, segContext)
                            }
                            val rawClauses = converted ?: listOf(ConversionClause(activeHira, activeHira))
                            val phrased = applyPhrasesAtClauseBoundaries(activeHira, rawClauses, segContext)
                            coalesceColloquialSokuonLongVowels(phrased)
                        }

                        var readingCursor = 0
                        for ((cIdx, c) in clauses.withIndex()) {
                            val clauseOffset = readingCursor
                            readingCursor += c.reading.length
                            val rawCands = converter.candidates(c.reading)
                            val utilCands = UtilityConverter.utilityCandidates(c.reading, seg.raw)
                            val misspellingCands = if (useTypoFix) dictionaries.resolveMisspelling(c.reading) else emptyList()
                            val clauseRom = if (clauses.size == 1) effectiveRaw else (GlideDecoder.kanaToRomaji(c.reading) ?: effectiveRaw)
                            val kata = RomajiDetector.toKatakana(c.reading)
                            val halfKata = toHalfWidthKatakana(kata)
                            val isExactMisspelling = misspellingCands.isNotEmpty() &&
                                (c.text == RomajiDetector.toKatakana(c.reading) || c.text == c.reading) &&
                                dictionaries.misspellingExact.containsKey(RomajiDetector.toKatakana(c.reading))
                            val preferredText = if (isExactMisspelling) misspellingCands.first() else c.text
                            val typoList = if (cIdx == 0) didyouMeanForWholeSeg.toList() else emptyList()
                            val rawFbList = if (cIdx == 0 && rawTypoFallback != null) listOf(rawTypoFallback) else emptyList()

                            val isLockedPrefixClause = usingCustomSplit && cIdx < (lockedLens?.size ?: 0)
                            val primaryRaw = rawCands.take(3)
                            val tailRaw = rawCands.drop(3)
                            val allCands = (
                                (if (isExactMisspelling) misspellingCands else emptyList()) +
                                    (if (isLockedPrefixClause) listOf(preferredText) else emptyList()) +
                                    primaryRaw +
                                    utilCands +
                                    tailRaw +
                                    misspellingCands +
                                    typoList +
                                    listOf(preferredText, c.text, c.reading, kata, halfKata) +
                                    rawFbList +
                                    listOf(clauseRom)
                                )
                                .filter { it.isNotEmpty() }
                                .distinct()
                            val tempKey = "$segIdx:$clauseOffset:${seg.raw}:${c.reading}"
                            val isLastSubClause = (cIdx == clauses.lastIndex)
                            val tailSuffix = if (isLastSubClause && pendingTail != "n") pendingTail else ""
                            val chosen = (selectedClauseTextByKey[tempKey] ?: preferredText) + tailSuffix
                            displayClauses.add(
                                DisplayClause(
                                    segmentIndex = segIdx,
                                    raw = seg.raw,
                                    reading = c.reading,
                                    text = chosen,
                                    isEnglish = false,
                                    candidates = allCands,
                                    readingOffset = clauseOffset
                                )
                            )
                        }
                    } else if (pendingTail.isNotEmpty()) {
                        displayClauses.add(
                            DisplayClause(
                                segmentIndex = segIdx,
                                raw = seg.raw,
                                reading = pendingTail,
                                text = pendingTail,
                                isEnglish = false,
                                candidates = listOf(pendingTail),
                                readingOffset = 0
                            )
                        )
                    }
                }
            }
            for (k in segClauseStart until displayClauses.size) {
                segContextBuf.append(displayClauses[k].text)
            }
        }

        focusedClauseIdx = if (!keepFocus) {
            (displayClauses.size - 1).coerceAtLeast(0)
        } else {
            focusedClauseIdx.coerceIn(0, (displayClauses.size - 1).coerceAtLeast(0))
        }

        val focused = displayClauses.getOrNull(focusedClauseIdx)
        val barCandidates = ArrayList<CandidateItem>()
        if (focused != null) {
            val focusedMisspellings = if (!focused.isEnglish) dictionaries.resolveMisspelling(focused.reading).toSet() else emptySet()
            val exactEmojiRaw = if (focused.isEnglish) {
                dictionaries.englishEmojiCandidates[focused.reading.lowercase()].orEmpty()
            } else {
                dictionaries.emojiCandidates[focused.reading].orEmpty()
            }
            val exactEmojiList = exactEmojiRaw
                .filterNot { MeltypeDictionaries.looksLikeKaomoji(it) }
                .distinct()
            val exactEmojiSet = exactEmojiList.toSet()

            for (cand in focused.candidates.take(40)) {
                if (cand in exactEmojiSet) continue
                val isMisspellFix = cand in focusedMisspellings
                barCandidates.add(
                    CandidateItem(
                        text = cand,
                        subtitle = when {
                            isMisspellFix -> "もしかして"
                            focused.isEnglish && dictionaries.properNounCanonical.containsValue(cand) -> "固有名"
                            else -> ""
                        },
                        kind = CandidateKind.CONVERSION,
                        targetClauseIndex = focusedClauseIdx
                    )
                )
            }

            // Promote top exact-match emojis right after the top text candidates while keeping index 0 as text.
            if (exactEmojiList.isNotEmpty()) {
                val topEmojiItems = exactEmojiList.take(3).map { emoji ->
                    CandidateItem(
                        text = emoji,
                        subtitle = "絵文字",
                        kind = CandidateKind.EMOJI,
                        targetClauseIndex = focusedClauseIdx
                    )
                }
                val insertAt = minOf(2, barCandidates.size)
                barCandidates.addAll(insertAt, topEmojiItems)
                for (emoji in exactEmojiList.drop(3).take(12)) {
                    if (barCandidates.none { it.text == emoji }) {
                        barCandidates.add(
                            CandidateItem(
                                text = emoji,
                                subtitle = "絵文字",
                                kind = CandidateKind.EMOJI,
                                targetClauseIndex = focusedClauseIdx
                            )
                        )
                    }
                }
            }

            // Also surface prefix-matched emoji suggestions when typing 2+ kana
            if (!focused.isEnglish && focused.reading.length >= 2) {
                var addedPrefixEmoji = 0
                for ((rKey, eList) in dictionaries.emojiCandidates) {
                    if (rKey != focused.reading && rKey.startsWith(focused.reading) && rKey.length <= focused.reading.length + 3) {
                        for (e in eList) {
                            if (MeltypeDictionaries.looksLikeKaomoji(e)) continue
                            if (barCandidates.none { it.text == e }) {
                                barCandidates.add(
                                    CandidateItem(
                                        text = e,
                                        subtitle = "絵文字($rKey)",
                                        kind = CandidateKind.EMOJI,
                                        targetClauseIndex = focusedClauseIdx
                                    )
                                )
                                addedPrefixEmoji++
                                break
                            }
                        }
                    }
                    if (addedPrefixEmoji >= 4) break
                }
            }
        }

        // Prepend glide alternatives after the primary candidate (using fast cache/lookup to keep commit lag-free)
        if (lastGlideAlternatives.isNotEmpty()) {
            val glideChips = lastGlideAlternatives.drop(1).take(4).map { (word, isEn) ->
                val displayLabel = if (isEn) {
                    dictionaries.properNounCanonical[word.lowercase()] ?: word
                } else {
                    val h = RomajiDetector.analyze(word).finalizedHiragana()
                    dictionaries.readingToPhrases[h]?.firstOrNull()
                        ?: converter.candidates(h).firstOrNull()
                        ?: h
                }
                CandidateItem(
                    text = word,
                    subtitle = if (isEn) "英:$displayLabel" else "日:$displayLabel",
                    kind = CandidateKind.GLIDE_ALT
                )
            }
            if (glideChips.isNotEmpty()) {
                val insertAt = if (barCandidates.isNotEmpty()) 1 else 0
                barCandidates.addAll(insertAt, glideChips)
            }
        }

        // Typo corrections ("補正") come right after the current conversion so they are visible
        // without scrolling; the literal conversion stays first and on the space key.
        val focusedCorrections = focused?.let { segmentCorrections[it.segmentIndex] }.orEmpty()
            .filter { c -> c.text != focused?.text }
            .distinctBy { it.text }
        if (focusedCorrections.isNotEmpty()) {
            val insertAt = if (barCandidates.isNotEmpty()) 1 else 0
            barCandidates.addAll(insertAt, focusedCorrections)
        }

        // As-typed English spelling: for a Japanese reading that has no real conversion (new words,
        // brand names such as "temu"), always offer the typed letters right after the top candidate.
        if (focused != null && !focused.isEnglish && !converting && !kanaRevert && caretRaw == null) {
            val typed = focused.raw
            val isPlainKana = focused.text == focused.reading || focused.text == RomajiDetector.toKatakana(focused.reading)
            if (typed.length >= 3 && typed.all { it in 'a'..'z' } && isPlainKana &&
                !dictionaries.japaneseWords.contains(typed.lowercase()) &&
                displayClauses.count { it.segmentIndex == focused.segmentIndex } == 1
            ) {
                barCandidates.removeAll { it.text == typed && it.kind == CandidateKind.CONVERSION }
                barCandidates.add(
                    minOf(1, barCandidates.size),
                    CandidateItem(text = typed, subtitle = "英字そのまま", kind = CandidateKind.CONVERSION, targetClauseIndex = focusedClauseIdx)
                )
            }
        }

        // Prefix completion (user phrases / frequently committed phrases), shown while typing.
        if (!converting && !kanaRevert && caretRaw == null && raw.isNotEmpty()) {
            val previewNow = displayClauses.joinToString("") { it.text }
            val phrases = phraseProvider(raw, displayClauses.joinToString("") { it.reading }, previewNow)
            if (phrases.isNotEmpty()) {
                val insertAt = minOf(2, barCandidates.size)
                barCandidates.addAll(
                    insertAt,
                    phrases.map { CandidateItem(text = it.text, subtitle = it.subtitle, kind = CandidateKind.PHRASE) }
                )
            }
        }

        val scriptVariants = buildScriptVariants(focused, displayClauses.count { it.segmentIndex == focused?.segmentIndex })
        val nextClause = displayClauses.getOrNull(focusedClauseIdx + 1)
        val focusedSeg = focused?.let { segments.getOrNull(it.segmentIndex) }
        val segAfterFocused = focused?.let { segments.getOrNull(it.segmentIndex + 1) }
            ?.takeIf { focusedSeg != null && it.startIndex == focusedSeg.endIndex }
        val alnumFocused = focused != null && focused.isEnglish &&
            (focusedSeg?.kind == SegmentKind.ENGLISH ||
                (focusedSeg?.kind == SegmentKind.LITERAL && focusedSeg.raw.any { it.isLetterOrDigit() }))
        val canShrink = if (alnumFocused) {
            focusedSeg!!.endIndex - focusedSeg.startIndex >= 2
        } else {
            focused != null && !focused.isEnglish && isResizableJapaneseReading(focused.reading) && focused.reading.length >= 2
        }
        val canExtendSameOrAdj = if (focused != null && !focused.isEnglish && nextClause != null && !nextClause.isEnglish && isResizableJapaneseReading(nextClause.reading)) {
            if (nextClause.segmentIndex == focused.segmentIndex) {
                true
            } else if (nextClause.segmentIndex == focused.segmentIndex + 1) {
                val sA = segments.getOrNull(focused.segmentIndex)
                val sB = segments.getOrNull(nextClause.segmentIndex)
                sA != null && sB != null && sA.kind == SegmentKind.JAPANESE && sB.kind == SegmentKind.JAPANESE && sA.endIndex == sB.startIndex
            } else {
                false
            }
        } else {
            false
        }
        val canExtend = if (alnumFocused) {
            segAfterFocused != null && focusedSeg!!.endIndex < raw.length && isResizableTailChar(raw[focusedSeg.endIndex])
        } else {
            canExtendSameOrAdj || (
                focused != null && !focused.isEnglish && isResizableJapaneseReading(focused.reading) &&
                    nextClause != null && nextClause.isEnglish && nextClause.segmentIndex == focused.segmentIndex + 1 &&
                    focusedSeg?.kind == SegmentKind.JAPANESE && segAfterFocused != null && (
                        segAfterFocused.kind == SegmentKind.ENGLISH ||
                            (segAfterFocused.kind == SegmentKind.LITERAL && segAfterFocused.raw.isNotEmpty() && isResizableTailChar(segAfterFocused.raw[0]))
                    )
                )
        }
        val hasGlideUndo = lastGlideRange != null && lastGlideRange!!.last == raw.length - 1
        val adjustableBuf = StringBuilder()
        var focusedAdjLen = 0
        if (alnumFocused) {
            val s = focusedSeg!!
            adjustableBuf.append(s.raw)
            focusedAdjLen = s.raw.length
            var pos = s.endIndex
            while (pos < raw.length && isResizableTailChar(raw[pos]) && adjustableBuf.length < 24) {
                adjustableBuf.append(raw[pos])
                pos++
            }
        } else if (focused != null && !focused.isEnglish && isResizableJapaneseReading(focused.reading)) {
            adjustableBuf.append(focused.reading)
            focusedAdjLen = focused.reading.length
            var prevSegIdx = focused.segmentIndex
            for (k in (focusedClauseIdx + 1) until displayClauses.size) {
                val dc = displayClauses[k]
                if (dc.isEnglish || !isResizableJapaneseReading(dc.reading)) break
                if (dc.segmentIndex != prevSegIdx) {
                    val segA = segments.getOrNull(prevSegIdx)
                    val segB = segments.getOrNull(dc.segmentIndex)
                    val contiguousJp = segA != null && segB != null &&
                        dc.segmentIndex == prevSegIdx + 1 &&
                        segA.kind == SegmentKind.JAPANESE &&
                        segB.kind == SegmentKind.JAPANESE &&
                        segA.endIndex == segB.startIndex
                    if (!contiguousJp) break
                    prevSegIdx = dc.segmentIndex
                }
                adjustableBuf.append(dc.reading)
            }
        }

        val editing = caretRaw != null || kanaRevert
        var preview = displayClauses.joinToString("") { it.text }
        var caretOffset = -1
        var editKana = ""
        var editKanaCaret = -1
        if (editing) {
            val units = editUnits()
            editKana = units.joinToString("") { it.text }
            val p = caretRaw ?: raw.length
            editKanaCaret = units.filter { it.rawStart < p }.sumOf { it.text.length }
            if (kanaRevert) {
                preview = editKana
                caretOffset = editKanaCaret
            } else {
                caretOffset = previewCaretFor(p, segments, displayClauses, units).coerceIn(0, preview.length)
            }
        }
        val trace = segments.joinToString("|") { it.raw }
        cachedSnapshot = CompositionSnapshot(
            rawInput = raw,
            previewText = preview,
            romajiTrace = trace,
            clauses = displayClauses,
            focusedClauseIndex = focusedClauseIdx,
            candidates = barCandidates.distinctBy { "${it.kind}:${it.text}" },
            isComposing = raw.isNotEmpty(),
            scriptVariants = scriptVariants,
            canShrinkClause = canShrink,
            canExtendClause = canExtend,
            hasGlideUndo = hasGlideUndo,
            focusedAdjustableKana = adjustableBuf.toString(),
            focusedAdjustableLen = focusedAdjLen,
            isConverting = converting,
            isEditing = editing,
            caretOffset = caretOffset,
            isKanaView = editing && kanaRevert,
            editKana = editKana,
            editKanaCaret = editKanaCaret
        )
        return cachedSnapshot
    }

    /**
     * Applies multi-char bundled phrases (e.g. こうかいあか->公開垢, めんへら->メンヘラ)
     * when a phrase starts at a Mozc clause boundary inside a longer sentence.
     */
    private fun applyPhrasesAtClauseBoundaries(
        reading: String,
        clauses: List<ConversionClause>,
        baseContext: String?
    ): List<ConversionClause> {
        val phrases = dictionaries.phraseEntries
        if (phrases.isEmpty() || reading.length < 4 || clauses.isEmpty()) return clauses

        val out = ArrayList<ConversionClause>(clauses.size + 2)
        var idx = 0
        var readingPos = 0
        val ctx = StringBuilder(baseContext ?: "")

        while (idx < clauses.size && readingPos < reading.length) {
            var matchedKey: String? = null
            var matchedEntry: PhraseEntry? = null
            val remaining = reading.substring(readingPos)
            val prevEndsSokuon = idx > 0 && clauses[idx - 1].reading.endsWith('っ')
            for ((k, entry) in phrases) {
                if (k.length >= 4 && remaining.startsWith(k)) {
                    if (entry.headOnly && readingPos > 0 && prevEndsSokuon) continue
                    if (matchedKey == null || k.length > matchedKey.length) {
                        matchedKey = k
                        matchedEntry = entry
                    }
                }
            }
            if (matchedKey != null && matchedEntry != null) {
                // Check if Mozc already produced the exact phrase surface at this position
                var coverLen = 0
                var coverIdx = idx
                val coverText = StringBuilder()
                while (coverIdx < clauses.size && coverLen < matchedKey.length) {
                    coverLen += clauses[coverIdx].reading.length
                    coverText.append(clauses[coverIdx].text)
                    coverIdx++
                }
                if (coverLen == matchedKey.length && coverText.toString() == matchedEntry.surface && matchedEntry.clauses.size == 1) {
                    while (idx < coverIdx) {
                        out.add(clauses[idx])
                        ctx.append(clauses[idx].text)
                        readingPos += clauses[idx].reading.length
                        idx++
                    }
                    continue
                }
                out.addAll(matchedEntry.clauses)
                ctx.append(matchedEntry.surface)
                readingPos += matchedKey.length
                val tailAfter = reading.substring(readingPos)
                if (tailAfter.isEmpty()) break
                val tailClauses = converter.convertClauses(tailAfter, ctx.toString().ifEmpty { null })
                    ?: listOf(ConversionClause(tailAfter, tailAfter))
                out.addAll(applyPhrasesAtClauseBoundaries(tailAfter, tailClauses, ctx.toString().ifEmpty { null }))
                return out
            }
            val c = clauses[idx]
            out.add(c)
            ctx.append(c.text)
            readingPos += c.reading.length
            idx++
        }
        return out
    }

    /**
     * Prevents Mozc from splitting colloquial emphatic forms like "さっむー" / "あっつー"
     * (pattern: [kana]っ[kana]ー) into unnatural kanji + katakana chunks (e.g. "察" + "ムー").
     */
    private fun coalesceColloquialSokuonLongVowels(clauses: List<ConversionClause>): List<ConversionClause> {
        if (clauses.size < 2) return clauses
        fun isColloquialEmphatic(r: String): Boolean =
            r.length == 4 &&
                r[0] in '\u3041'..'\u3096' &&
                r[1] == 'っ' &&
                r[2] in '\u3041'..'\u3096' &&
                r[3] == 'ー'

        val out = ArrayList<ConversionClause>(clauses.size)
        var i = 0
        while (i < clauses.size) {
            if (i + 1 < clauses.size) {
                val c1 = clauses[i]
                val c2 = clauses[i + 1]
                val combined = c1.reading + c2.reading
                if (isColloquialEmphatic(combined)) {
                    out.add(ConversionClause(combined, combined))
                    i += 2
                    continue
                }
            }
            out.add(clauses[i])
            i++
        }
        return out
    }

    /** Maps the raw caret [p] to an offset in the converted preview (clause boundaries are exact). */
    private fun previewCaretFor(
        p: Int,
        segments: List<ScoredSegment>,
        clauses: List<DisplayClause>,
        units: List<EditUnit>
    ): Int {
        val starts = IntArray(clauses.size)
        var off = 0
        for (i in clauses.indices) {
            starts[i] = off
            off += clauses[i].text.length
        }
        val total = off
        fun startOfSegmentsFrom(pos: Int): Int {
            val ci = clauses.indices.firstOrNull { (segments.getOrNull(clauses[it].segmentIndex)?.startIndex ?: -1) >= pos }
            return if (ci == null) total else starts[ci]
        }
        val segIdx = segments.indexOfFirst { p >= it.startIndex && p < it.endIndex }
        if (segIdx < 0) return startOfSegmentsFrom(p)
        val seg = segments[segIdx]
        val segClauses = clauses.indices.filter { clauses[it].segmentIndex == segIdx }
        if (segClauses.isEmpty()) return startOfSegmentsFrom(p)
        if (seg.kind != SegmentKind.JAPANESE) {
            val ci = segClauses.first()
            return starts[ci] + mapInClause(clauses[ci], p - seg.startIndex)
        }
        val k = units.filter { it.rawStart >= seg.startIndex && it.rawEnd <= p }.sumOf { it.text.length }
        for (ci in segClauses) {
            val c = clauses[ci]
            if (k < c.readingOffset + c.reading.length) {
                return starts[ci] + mapInClause(c, k - c.readingOffset)
            }
        }
        val last = segClauses.last()
        return starts[last] + clauses[last].text.length
    }

    /** Kana offset [i] in a clause's reading -> offset in its text (okurigana kept, kanji runs scaled). */
    private fun mapInClause(c: DisplayClause, i: Int): Int {
        val r = RomajiDetector.toKatakana(c.reading)
        val t = RomajiDetector.toKatakana(c.text)
        if (i <= 0) return 0
        if (i >= r.length) return c.text.length
        if (r == t) return i
        var pre = 0
        while (pre < r.length && pre < t.length && r[pre] == t[pre]) pre++
        var suf = 0
        while (suf < r.length - pre && suf < t.length - pre && r[r.length - 1 - suf] == t[t.length - 1 - suf]) suf++
        if (i <= pre) return i
        if (i >= r.length - suf) return c.text.length - (r.length - i)
        val rm = r.length - pre - suf
        val tm = t.length - pre - suf
        if (rm <= 0) return pre
        return (pre + ((i - pre) * tm + rm / 2) / rm).coerceIn(0, c.text.length)
    }

    private fun buildScriptVariants(focused: DisplayClause?, clausesInSeg: Int): List<Pair<String, String>> {
        if (focused == null || focused.reading.isEmpty()) return emptyList()
        if (focused.isEnglish && focused.raw.none { it.isLetterOrDigit() }) return emptyList()
        val list = ArrayList<Pair<String, String>>(6)
        val seen = HashSet<String>()
        fun add(label: String, text: String) {
            if (text.isNotEmpty() && seen.add(text)) {
                list.add(label to text)
            }
        }
        if (focused.isEnglish) {
            val lower = focused.reading.lowercase()
            val title = lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val upper = focused.reading.uppercase()
            val hira = RomajiDetector.analyze(lower, strictForDetection = false).finalizedHiragana()
            val kata = RomajiDetector.toKatakana(hira)
            add("abc", lower)
            add("Abc", title)
            add("ABC", upper)
            if (hira.any { it.code > 127 }) {
                add("あ", hira)
                add("ア", kata)
            }
        } else {
            val hira = focused.reading
            val kata = RomajiDetector.toKatakana(hira)
            val halfKata = toHalfWidthKatakana(kata)
            val rom = if (clausesInSeg == 1) focused.raw else (GlideDecoder.kanaToRomaji(hira) ?: "")
            add("あ", hira)
            add("ア", kata)
            add("ｱ", halfKata)
            if (rom.isNotEmpty()) {
                add("abc", rom.lowercase())
                add("Abc", rom.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() })
                add("ABC", rom.uppercase())
            }
        }
        return list
    }

    companion object {
        private val KANA_PREFIX_TO_ROMAJI = mapOf(
            "っち" to "tchi", "っし" to "sshi", "っ" to "ltu",
            "き" to "ki", "ぎ" to "gi", "し" to "shi", "じ" to "ji",
            "ち" to "chi", "て" to "te", "で" to "de", "ぢ" to "di",
            "つ" to "tsu", "と" to "to", "ど" to "do", "に" to "ni",
            "ひ" to "hi", "び" to "bi", "ぴ" to "pi", "ふ" to "fu",
            "み" to "mi", "り" to "ri", "う" to "u", "く" to "ku",
            "ぐ" to "gu", "す" to "su", "ヴ" to "ヴ"
        )

        /** Google IME (Mozc) z-table. */
        val Z_SYMBOLS = mapOf(
            '.' to "…", ',' to "‥", '/' to "・", '-' to "〜", '[' to "『", ']' to "』",
            'h' to "←", 'j' to "↓", 'k' to "↑", 'l' to "→"
        )

        private val FULL_TO_HALF_KATA = mapOf(
            'ア' to "ｱ", 'イ' to "ｲ", 'ウ' to "ｳ", 'エ' to "ｴ", 'オ' to "ｵ",
            'カ' to "ｶ", 'キ' to "ｷ", 'ク' to "ｸ", 'ケ' to "ｹ", 'コ' to "ｺ",
            'サ' to "ｻ", 'シ' to "ｼ", 'ス' to "ｽ", 'セ' to "ｾ", 'ソ' to "ｿ",
            'タ' to "ﾀ", 'チ' to "ﾁ", 'ツ' to "ﾂ", 'テ' to "ﾃ", 'ト' to "ﾄ",
            'ナ' to "ﾅ", 'ニ' to "ﾆ", 'ヌ' to "ﾇ", 'ネ' to "ﾈ", 'ノ' to "ﾉ",
            'ハ' to "ﾊ", 'ヒ' to "ﾋ", 'フ' to "ﾌ", 'ヘ' to "ﾍ", 'ホ' to "ﾎ",
            'マ' to "ﾏ", 'ミ' to "ﾐ", 'ム' to "ﾑ", 'メ' to "ﾒ", 'モ' to "ﾓ",
            'ヤ' to "ﾔ", 'ユ' to "ﾕ", 'ヨ' to "ﾖ",
            'ラ' to "ﾗ", 'リ' to "ﾘ", 'ル' to "ﾙ", 'レ' to "ﾚ", 'ロ' to "ﾛ",
            'ワ' to "ﾜ", 'ヲ' to "ｦ", 'ン' to "ﾝ",
            'ァ' to "ｧ", 'ィ' to "ｨ", 'ゥ' to "ｩ", 'ェ' to "ｪ", 'ォ' to "ｫ",
            'ャ' to "ｬ", 'ュ' to "ｭ", 'ョ' to "ｮ", 'ッ' to "ｯ", 'ー' to "ｰ",
            'ガ' to "ｶﾞ", 'ギ' to "ｷﾞ", 'グ' to "ｸﾞ", 'ゲ' to "ｹﾞ", 'ゴ' to "ｺﾞ",
            'ザ' to "ｻﾞ", 'ジ' to "ｼﾞ", 'ズ' to "ｽﾞ", 'ゼ' to "ｾﾞ", 'ゾ' to "ｿﾞ",
            'ダ' to "ﾀﾞ", 'ヂ' to "ﾁﾞ", 'ヅ' to "ﾂﾞ", 'デ' to "ﾃﾞ", 'ド' to "ﾄﾞ",
            'バ' to "ﾊﾞ", 'ビ' to "ﾋﾞ", 'ブ' to "ﾌﾞ", 'ベ' to "ﾍﾞ", 'ボ' to "ﾎﾞ",
            'パ' to "ﾊﾟ", 'ピ' to "ﾋﾟ", 'プ' to "ﾌﾟ", 'ペ' to "ﾍﾟ", 'ポ' to "ﾎﾟ",
            'ヴ' to "ｳﾞ", '。' to "｡", '、' to "､", '「' to "｢", '」' to "｣", '・' to "･"
        )

        fun toHalfWidthKatakana(fullKatakana: String): String {
            val sb = StringBuilder(fullKatakana.length)
            for (ch in fullKatakana) {
                sb.append(FULL_TO_HALF_KATA[ch] ?: ch)
            }
            return sb.toString()
        }
    }
}
