package io.github.sp9r.meltype.core

import io.github.sp9r.meltype.glide.GlideDecoder

enum class CandidateKind {
    CONVERSION,
    GLIDE_ALT,
    ENGLISH_CASE,
    UTILITY,
    EMOJI
}

data class CandidateItem(
    val text: String,
    val subtitle: String = "",
    val kind: CandidateKind = CandidateKind.CONVERSION,
    val targetClauseIndex: Int = -1
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
    val hasGlideUndo: Boolean = false
)

/**
 * Manages the active Meltype composition buffer, live JP/EN segmentation,
 * Mozc conversion, manual clause boundary resizing, 1-tap glide undo, and glide alternative switching.
 */
class CompositionSession(
    private val dictionaries: MeltypeDictionaries,
    private val scoreEngine: ScoreEngine,
    private val converter: KanjiConverter,
    private val typoCorrectionEnabled: () -> Boolean = { true }
) {
    private val buffer = StringBuilder()
    private val overrides = LinkedHashMap<IntRange, SegmentKind>()
    private val selectedClauseTextByKey = LinkedHashMap<String, String>()
    private val customClauseSplits = LinkedHashMap<Int, MutableList<Int>>()
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

    fun appendChar(ch: Char, surroundingContext: String? = null): CompositionSnapshot {
        clearLastGlideHint()
        buffer.append(ch)
        return recompute(surroundingContext)
    }

    fun appendText(text: String, surroundingContext: String? = null): CompositionSnapshot {
        clearLastGlideHint()
        buffer.append(text)
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
        var addedSpace = false
        if (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            val lastSeg = cachedSnapshot.clauses.lastOrNull()
            if (lastSeg != null && lastSeg.isEnglish && isPrimaryEnglish) {
                buffer.append(' ')
                addedSpace = true
            }
        }
        val start = buffer.length
        buffer.append(primaryWord)
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
        buffer.replace(range.first, range.last + 1, word)
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
        val glideSpan = lastGlideRange
        if (glideSpan != null && glideSpan.last == buffer.length - 1 && glideSpan.first >= 0) {
            val delStart = if (lastGlideLeadingSpace && glideSpan.first > 0 && buffer[glideSpan.first - 1] == ' ') {
                glideSpan.first - 1
            } else {
                glideSpan.first
            }
            overrides.keys.filter { it.last >= delStart }.toList().forEach { overrides.remove(it) }
            buffer.delete(delStart, buffer.length)
            clearLastGlideHint()
            return recompute(surroundingContext)
        }

        clearLastGlideHint()
        val removeAt = buffer.length - 1
        val toRemove = overrides.keys.filter { removeAt in it }
        toRemove.forEach { overrides.remove(it) }
        buffer.deleteCharAt(removeAt)
        return recompute(surroundingContext)
    }

    fun clearAll(): CompositionSnapshot {
        buffer.setLength(0)
        overrides.clear()
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        clearLastGlideHint()
        focusedClauseIdx = 0
        return recompute(null)
    }

    fun restoreFromUndo(rawInput: String, surroundingContext: String? = null): CompositionSnapshot {
        buffer.setLength(0)
        overrides.clear()
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
        clearLastGlideHint()
        focusedClauseIdx = 0
        if (rawInput.isNotEmpty()) {
            buffer.append(rawInput)
        }
        return recompute(surroundingContext)
    }

    fun toggleFocusedSegmentLanguage(surroundingContext: String? = null): CompositionSnapshot {
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
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
        clearLastGlideHint()
        return recompute(surroundingContext, keepFocus = true)
    }

    /**
     * Manually shrinks (deltaKana < 0) or extends (deltaKana > 0) the focused Japanese clause
     * by 1 hiragana character, locking prefix boundaries up to the focused clause while letting
     * Mozc naturally re-segment remaining characters (merging contiguous Japanese segments if needed).
     */
    fun resizeFocusedClause(deltaKana: Int, surroundingContext: String? = null): CompositionSnapshot {
        if (deltaKana == 0) return cachedSnapshot
        var clauses = cachedSnapshot.clauses
        var focused = clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
        if (focused.isEnglish) return cachedSnapshot

        // If extending from the end of a Japanese segment directly into a contiguous Japanese segment, merge first
        if (deltaKana > 0) {
            val segs = scoreEngine.segment(buffer.toString(), overrides)
            val segIdx = focused.segmentIndex
            val nextClause = clauses.getOrNull(focusedClauseIdx + 1)
            if (nextClause != null && !nextClause.isEnglish && nextClause.segmentIndex == segIdx + 1) {
                val segA = segs.getOrNull(segIdx)
                val segB = segs.getOrNull(segIdx + 1)
                if (segA != null && segB != null &&
                    segA.kind == SegmentKind.JAPANESE && segB.kind == SegmentKind.JAPANESE &&
                    segA.endIndex == segB.startIndex
                ) {
                    val segALens = clauses.filter { it.segmentIndex == segIdx && !it.isEnglish && RomajiDetector.isHiraganaOnly(it.reading) }.map { it.reading.length }
                    val mergedRange = segA.startIndex until segB.endIndex
                    overrides.keys.filter { it.first <= mergedRange.last && it.last >= mergedRange.first }.toList()
                        .forEach { overrides.remove(it) }
                    overrides[mergedRange] = SegmentKind.JAPANESE
                    customClauseSplits.remove(segIdx)
                    customClauseSplits.remove(segIdx + 1)
                    if (segALens.isNotEmpty()) {
                        customClauseSplits[segIdx] = segALens.toMutableList()
                    }
                    recompute(surroundingContext, keepFocus = true)
                    clauses = cachedSnapshot.clauses
                    focused = clauses.getOrNull(focusedClauseIdx) ?: return cachedSnapshot
                }
            }
        }

        val segIdx = focused.segmentIndex
        val segClauses = clauses.filter { it.segmentIndex == segIdx && !it.isEnglish && RomajiDetector.isHiraganaOnly(it.reading) }
        if (segClauses.isEmpty()) return cachedSnapshot

        val firstGlobalIdx = clauses.indexOfFirst { it.segmentIndex == segIdx }
        val relIdx = focusedClauseIdx - firstGlobalIdx
        if (relIdx !in segClauses.indices) return cachedSnapshot

        val prefixLens = segClauses.take(relIdx + 1).map { it.reading.length }.toMutableList()
        val totalSegKana = segClauses.sumOf { it.reading.length }
        if (deltaKana < 0) {
            if (prefixLens[relIdx] <= 1) return cachedSnapshot
            prefixLens[relIdx] -= 1
        } else {
            val usedIncludingFocus = prefixLens.sum()
            if (usedIncludingFocus >= totalSegKana) return cachedSnapshot
            prefixLens[relIdx] += 1
        }

        customClauseSplits[segIdx] = prefixLens
        val changedOffset = segClauses[relIdx].readingOffset
        val prefix = "$segIdx:"
        selectedClauseTextByKey.keys.filter { key ->
            if (!key.startsWith(prefix)) return@filter false
            val afterSeg = key.removePrefix(prefix)
            val off = afterSeg.substringBefore(':').toIntOrNull() ?: 0
            off >= changedOffset
        }.toList().forEach { selectedClauseTextByKey.remove(it) }
        clearLastGlideHint()
        return recompute(surroundingContext, keepFocus = true)
    }

    fun selectCandidate(
        item: CandidateItem,
        surroundingContext: String? = null,
        advanceFocus: Boolean = false
    ): CompositionSnapshot {
        if (item.kind == CandidateKind.GLIDE_ALT) {
            val isEn = item.subtitle.startsWith("英")
            return replaceLastGlideAlternative(item.text, isEn, surroundingContext)
        }
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
        val idx = (if (item.targetClauseIndex in clauses.indices) item.targetClauseIndex else focusedClauseIdx)
            .coerceIn(0, clauses.lastIndex)
        val clause = clauses[idx]
        selectedClauseTextByKey[clauseKey(clause)] = item.text
        if (advanceFocus && idx < clauses.lastIndex) {
            focusedClauseIdx = idx + 1
        }
        return recompute(surroundingContext, keepFocus = true)
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
        focusedClauseIdx = (focusedClauseIdx + delta).coerceIn(0, count - 1)
        return recompute(null, keepFocus = true)
    }

    fun focusClause(index: Int): CompositionSnapshot {
        val count = cachedSnapshot.clauses.size
        if (index in 0 until count) {
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
        val mozcClauses = snap.clauses
            .filter { !it.isEnglish && RomajiDetector.isHiraganaOnly(it.reading) }
            .map { ConversionClause(it.reading, it.text) }
        if (mozcClauses.isNotEmpty()) {
            converter.learn(surroundingContext, mozcClauses)
        }
        clearAll()
        return committed
    }

    /**
     * Commits clauses [0..focusedClauseIndex] cleanly and leaves remaining clauses in composition.
     */
    fun commitFocusedPrefix(surroundingContext: String? = null): String {
        val snap = cachedSnapshot
        val clauses = snap.clauses
        if (clauses.isEmpty()) return ""
        val cutIdx = snap.focusedClauseIndex.coerceIn(0, clauses.lastIndex)
        if (cutIdx == clauses.lastIndex) {
            return commitAndLearn(surroundingContext)
        }

        val committedClauses = clauses.subList(0, cutIdx + 1)
        val committed = committedClauses.joinToString("") { it.text }
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

        buffer.setLength(0)
        buffer.append(newRaw)
        overrides.clear()
        overrides.putAll(nextOverrides)
        selectedClauseTextByKey.clear()
        customClauseSplits.clear()
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

    private fun clauseKey(c: DisplayClause): String = "${c.segmentIndex}:${c.readingOffset}:${c.raw}:${c.reading}"

    private fun recompute(surroundingContext: String?, keepFocus: Boolean = false): CompositionSnapshot {
        val raw = buffer.toString()
        if (raw.isEmpty()) {
            cachedSnapshot = CompositionSnapshot("", "", "", emptyList(), 0, emptyList(), false)
            return cachedSnapshot
        }

        val segments = scoreEngine.segment(raw, overrides)
        val displayClauses = ArrayList<DisplayClause>()
        val segContextBuf = StringBuilder(surroundingContext ?: "")

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
                    val cands = linkedSetOf(seg.raw)
                    if (proper != null) cands.add(proper)
                    cands.add(title)
                    cands.add(upper)
                    if (hiraVersion.isNotEmpty()) {
                        cands.add(hiraVersion)
                        cands.add(kataVersion)
                    }
                    val tempKey = "$segIdx:0:${seg.raw}:${seg.raw}"
                    val chosen = selectedClauseTextByKey[tempKey] ?: seg.raw
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
                    val calc = UtilityConverter.evaluateCalculator(seg.raw)
                    if (calc != null) cands.add(calc)
                    cands.add(seg.raw)
                    cands.addAll(UtilityConverter.numericCandidates(seg.raw))
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

                    if (useTypoFix && seg.raw.all { it in 'a'..'z' || it in 'A'..'Z' }) {
                        val hasUnreadable = typoEngine.firstUnreadableOffset(rawLower, final = false) >= 0
                        if (hasUnreadable) {
                            val fix = typoEngine.fixUnreadable(rawLower, final = false)
                            if (fix != null) {
                                effectiveRaw = fix.right
                                rawTypoFallback = RomajiDetector.analyze(rawLower, strictForDetection = false).finalizedHiragana()
                                unreadableFixApplied = true
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
                            allowFinalTailTrim = (segIdx < segments.lastIndex)
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
                            val rawNotCohesive = !typoEngine.isKnownReading(convertibleHira) || rawSegCost >= 1.95
                            if (top.isSingleWholeWord &&
                                rawLower.length >= 5 &&
                                marginOk &&
                                !typoEngine.isKnownWord(convertibleHira) &&
                                !typoEngine.hasKnownReadingPrefix(convertibleHira) &&
                                rawNotCohesive
                            ) {
                                rawTypoFallback = convertibleHira
                                activeHira = top.kana
                            }
                            for (rf in readableFixes.take(2)) {
                                converter.convertClauses(rf.kana, surroundingContext)?.joinToString("") { it.text }?.let {
                                    didyouMeanForWholeSeg.add(it)
                                }
                                didyouMeanForWholeSeg.add(rf.kana)
                            }
                        }
                    }

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
                            converter.convertClauses(activeHira, segContext)
                                ?: listOf(ConversionClause(activeHira, activeHira))
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
                            val allCands = (
                                (if (isExactMisspelling) misspellingCands else emptyList()) +
                                    (if (isLockedPrefixClause) listOf(preferredText) else emptyList()) +
                                    utilCands +
                                    rawCands +
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
            for (cand in focused.candidates.take(36)) {
                val isEmoji = dictionaries.emojiCandidates[focused.reading]?.contains(cand) == true
                val isMisspellFix = cand in focusedMisspellings
                barCandidates.add(
                    CandidateItem(
                        text = cand,
                        subtitle = when {
                            isMisspellFix -> "もしかして"
                            isEmoji -> "絵文字"
                            focused.isEnglish && dictionaries.properNounCanonical.containsValue(cand) -> "固有名"
                            else -> ""
                        },
                        kind = if (isEmoji) CandidateKind.EMOJI else CandidateKind.CONVERSION,
                        targetClauseIndex = focusedClauseIdx
                    )
                )
            }
            // Also surface prefix-matched emoji suggestions when typing 2+ kana
            if (!focused.isEnglish && focused.reading.length >= 2) {
                var addedPrefixEmoji = 0
                for ((rKey, eList) in dictionaries.emojiCandidates) {
                    if (rKey != focused.reading && rKey.startsWith(focused.reading) && rKey.length <= focused.reading.length + 3) {
                        for (e in eList.take(2)) {
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
                                if (addedPrefixEmoji >= 4) break
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

        val scriptVariants = buildScriptVariants(focused, displayClauses.count { it.segmentIndex == focused?.segmentIndex })
        val nextClause = displayClauses.getOrNull(focusedClauseIdx + 1)
        val canShrink = focused != null && !focused.isEnglish && RomajiDetector.isHiraganaOnly(focused.reading) && focused.reading.length >= 2
        val canExtendSameOrAdj = if (focused != null && !focused.isEnglish && nextClause != null && !nextClause.isEnglish && RomajiDetector.isHiraganaOnly(nextClause.reading)) {
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
        val canExtend = canExtendSameOrAdj
        val hasGlideUndo = lastGlideRange != null && lastGlideRange!!.last == raw.length - 1

        val preview = displayClauses.joinToString("") { it.text }
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
            hasGlideUndo = hasGlideUndo
        )
        return cachedSnapshot
    }

    private fun buildScriptVariants(focused: DisplayClause?, clausesInSeg: Int): List<Pair<String, String>> {
        if (focused == null || focused.reading.isEmpty()) return emptyList()
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
