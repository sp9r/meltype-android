package io.github.sp9r.meltype.core

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
    val candidates: List<String>
)

data class CompositionSnapshot(
    val rawInput: String,
    val previewText: String,
    val romajiTrace: String,
    val clauses: List<DisplayClause>,
    val focusedClauseIndex: Int,
    val candidates: List<CandidateItem>,
    val isComposing: Boolean
)

/**
 * Manages the active Meltype composition buffer, live JP/EN segmentation,
 * Mozc conversion, clause boundary adjustment, and glide alternative switching.
 */
class CompositionSession(
    private val dictionaries: MeltypeDictionaries,
    private val scoreEngine: ScoreEngine,
    private val converter: KanjiConverter
) {
    private val buffer = StringBuilder()
    private val overrides = LinkedHashMap<IntRange, SegmentKind>()
    private val selectedClauseTextByKey = LinkedHashMap<String, String>()
    private var focusedClauseIdx = 0

    // Tracks last inserted glide span so users can tap an alternative glide candidate in the bar
    private var lastGlideRange: IntRange? = null
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
        if (buffer.isNotEmpty() && !buffer.last().isWhitespace()) {
            val lastSeg = cachedSnapshot.clauses.lastOrNull()
            if (lastSeg != null && lastSeg.isEnglish && isPrimaryEnglish) {
                buffer.append(' ')
            }
        }
        val start = buffer.length
        buffer.append(primaryWord)
        val end = buffer.length - 1
        lastGlideRange = start..end
        lastGlideAlternatives = alternatives
        overrides[start..end] = if (isPrimaryEnglish) SegmentKind.ENGLISH else SegmentKind.JAPANESE
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
        buffer.replace(range.first, range.last + 1, word)
        val newRange = range.first until (range.first + word.length)
        lastGlideRange = if (word.isNotEmpty()) newRange else null
        if (newRange.first <= newRange.last) {
            overrides[newRange] = if (isEnglish) SegmentKind.ENGLISH else SegmentKind.JAPANESE
        }
        return recompute(surroundingContext)
    }

    fun backspace(surroundingContext: String? = null): CompositionSnapshot {
        if (buffer.isEmpty()) return cachedSnapshot
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
        clearLastGlideHint()
        focusedClauseIdx = 0
        return recompute(null)
    }

    fun toggleFocusedSegmentLanguage(surroundingContext: String? = null): CompositionSnapshot {
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
        val idx = focusedClauseIdx.coerceIn(0, clauses.lastIndex)
        val segs = scoreEngine.segment(buffer.toString(), overrides)
        val targetSeg = segs.getOrNull(clauses[idx].segmentIndex) ?: return cachedSnapshot
        val range = targetSeg.startIndex until targetSeg.endIndex
        overrides.keys.filter { it.first < range.last + 1 && it.last >= range.first }.toList()
            .forEach { overrides.remove(it) }
        val nextKind = if (clauses[idx].isEnglish) SegmentKind.JAPANESE else SegmentKind.ENGLISH
        overrides[range] = nextKind
        clearLastGlideHint()
        return recompute(surroundingContext)
    }

    fun selectCandidate(item: CandidateItem, surroundingContext: String? = null): CompositionSnapshot {
        if (item.kind == CandidateKind.GLIDE_ALT) {
            // Stored in subtitle whether "EN" or "JP"
            val isEn = item.subtitle.startsWith("英")
            return replaceLastGlideAlternative(item.text, isEn, surroundingContext)
        }
        val clauses = cachedSnapshot.clauses
        if (clauses.isEmpty()) return cachedSnapshot
        val idx = (if (item.targetClauseIndex in clauses.indices) item.targetClauseIndex else focusedClauseIdx)
            .coerceIn(0, clauses.lastIndex)
        val clause = clauses[idx]
        selectedClauseTextByKey[clauseKey(clause)] = item.text
        return recompute(surroundingContext)
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

    private fun clearLastGlideHint() {
        lastGlideRange = null
        lastGlideAlternatives = emptyList()
    }

    private fun clauseKey(c: DisplayClause): String = "${c.segmentIndex}:${c.raw}:${c.reading}"

    private fun recompute(surroundingContext: String?, keepFocus: Boolean = false): CompositionSnapshot {
        val raw = buffer.toString()
        if (raw.isEmpty()) {
            cachedSnapshot = CompositionSnapshot("", "", "", emptyList(), 0, emptyList(), false)
            return cachedSnapshot
        }

        val segments = scoreEngine.segment(raw, overrides)
        val displayClauses = ArrayList<DisplayClause>()

        for ((segIdx, seg) in segments.withIndex()) {
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
                    val tempKey = "$segIdx:${seg.raw}:${seg.raw}"
                    val chosen = selectedClauseTextByKey[tempKey] ?: seg.raw
                    displayClauses.add(
                        DisplayClause(
                            segmentIndex = segIdx,
                            raw = seg.raw,
                            reading = seg.raw,
                            text = chosen,
                            isEnglish = true,
                            candidates = cands.toList()
                        )
                    )
                }

                SegmentKind.LITERAL -> {
                    val cands = mutableListOf(seg.raw)
                    val calc = UtilityConverter.evaluateCalculator(seg.raw)
                    if (calc != null) cands.add(0, calc)
                    val tempKey = "$segIdx:${seg.raw}:${seg.raw}"
                    val chosen = selectedClauseTextByKey[tempKey] ?: cands.first()
                    displayClauses.add(
                        DisplayClause(
                            segmentIndex = segIdx,
                            raw = seg.raw,
                            reading = seg.raw,
                            text = chosen,
                            isEnglish = true,
                            candidates = cands.distinct()
                        )
                    )
                }

                SegmentKind.JAPANESE -> {
                    val analysis = RomajiDetector.analyze(seg.raw, strictForDetection = false)
                    val hiraReady = analysis.hiragana
                    val pendingTail = analysis.pendingTail

                    val convertibleHira = if (pendingTail == "n") hiraReady + "ん" else hiraReady
                    if (convertibleHira.isNotEmpty()) {
                        val clauses = converter.convertClauses(convertibleHira, surroundingContext)
                            ?: listOf(ConversionClause(convertibleHira, convertibleHira))
                        for ((cIdx, c) in clauses.withIndex()) {
                            val rawCands = converter.candidates(c.reading)
                            val utilCands = UtilityConverter.utilityCandidates(c.reading, seg.raw)
                            val allCands = (utilCands + rawCands + listOf(c.text, c.reading, RomajiDetector.toKatakana(c.reading), seg.raw))
                                .filter { it.isNotEmpty() }
                                .distinct()
                            val tempKey = "$segIdx:${seg.raw}:${c.reading}"
                            val isLastSubClause = (cIdx == clauses.lastIndex)
                            val tailSuffix = if (isLastSubClause && pendingTail != "n") pendingTail else ""
                            val chosen = (selectedClauseTextByKey[tempKey] ?: c.text) + tailSuffix
                            displayClauses.add(
                                DisplayClause(
                                    segmentIndex = segIdx,
                                    raw = seg.raw,
                                    reading = c.reading,
                                    text = chosen,
                                    isEnglish = false,
                                    candidates = allCands
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
                                candidates = listOf(pendingTail)
                            )
                        )
                    }
                }
            }
        }

        if (!keepFocus) {
            // Focus the last meaningful clause (preferring conversion clause over trailing single-char literal/tail)
            focusedClauseIdx = (displayClauses.size - 1).coerceAtLeast(0)
        } else {
            focusedClauseIdx = focusedClauseIdx.coerceIn(0, (displayClauses.size - 1).coerceAtLeast(0))
        }

        val focused = displayClauses.getOrNull(focusedClauseIdx)
        val barCandidates = ArrayList<CandidateItem>()
        if (focused != null) {
            for (cand in focused.candidates.take(24)) {
                val isEmoji = dictionaries.emojiCandidates[focused.reading]?.contains(cand) == true
                barCandidates.add(
                    CandidateItem(
                        text = cand,
                        subtitle = when {
                            isEmoji -> "絵文字"
                            focused.isEnglish && dictionaries.properNounCanonical.containsValue(cand) -> "固有名"
                            else -> ""
                        },
                        kind = if (isEmoji) CandidateKind.EMOJI else CandidateKind.CONVERSION,
                        targetClauseIndex = focusedClauseIdx
                    )
                )
            }
        }

        // Prepend glide alternatives after the primary candidate so user can switch 1-tap between JP and EN glide decodes
        if (lastGlideAlternatives.isNotEmpty()) {
            val glideChips = lastGlideAlternatives.drop(1).take(5).map { (word, isEn) ->
                val displayLabel = if (isEn) {
                    dictionaries.properNounCanonical[word.lowercase()] ?: word
                } else {
                    val h = RomajiDetector.analyze(word).finalizedHiragana()
                    converter.convertClauses(h)?.joinToString("") { it.text } ?: h
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

        val preview = displayClauses.joinToString("") { it.text }
        val trace = segments.joinToString("|") { it.raw }
        cachedSnapshot = CompositionSnapshot(
            rawInput = raw,
            previewText = preview,
            romajiTrace = trace,
            clauses = displayClauses,
            focusedClauseIndex = focusedClauseIdx,
            candidates = barCandidates.distinctBy { "${it.kind}:${it.text}" },
            isComposing = raw.isNotEmpty()
        )
        return cachedSnapshot
    }
}
