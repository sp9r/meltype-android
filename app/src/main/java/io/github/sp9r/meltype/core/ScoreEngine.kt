package io.github.sp9r.meltype.core

enum class SegmentKind {
    JAPANESE,
    ENGLISH,
    LITERAL
}

data class ScoredSegment(
    val kind: SegmentKind,
    val raw: String,
    val startIndex: Int,
    val endIndex: Int,
    val hiragana: String = "",
    val canonicalEnglish: String? = null
)

/**
 * Meltype hybrid Japanese/English detector and boundary segmenter.
 * Segments mixed strings such as "kyouhagoogledekensaku" -> [JP(きょうは), EN(google), JP(でけんさく)]
 * while preserving full English sentences, model numbers (RTX4090), /commands, @mentions, and $vars.
 */
class ScoreEngine(
    private val dictionaries: MeltypeDictionaries,
    private val typoCorrectionEnabled: () -> Boolean = { true }
) {
    val typoEngine: RomajiTypoEngine = RomajiTypoEngine(dictionaries)

    fun segment(
        input: String,
        overrides: Map<IntRange, SegmentKind> = emptyMap()
    ): List<ScoredSegment> {
        if (input.isEmpty()) return emptyList()

        // 1. Check whole-input English sentence heuristic when spaces exist
        if (input.contains(' ') && isLikelyEnglishSentence(input) && overrides.isEmpty()) {
            return listOf(ScoredSegment(SegmentKind.LITERAL, input, 0, input.length))
        }

        // 2. Partition input into coarse runs: whitespace/punctuation, literal tokens (/cmd, @user, $var, URL, model numbers), or alpha runs
        val out = ArrayList<ScoredSegment>()
        var i = 0
        while (i < input.length) {
            val ch = input[i]

            // Check explicit user override covering position i
            val overrideEntry = overrides.entries.firstOrNull { it.key.first == i }
            if (overrideEntry != null) {
                val range = overrideEntry.key
                val sub = input.substring(range.first, range.last + 1)
                when (overrideEntry.value) {
                    SegmentKind.JAPANESE -> {
                        val analysis = RomajiDetector.analyze(sub, strictForDetection = false)
                        out.add(
                            ScoredSegment(
                                kind = SegmentKind.JAPANESE,
                                raw = sub,
                                startIndex = range.first,
                                endIndex = range.last + 1,
                                hiragana = analysis.displayKanaOrTail
                            )
                        )
                    }
                    SegmentKind.ENGLISH -> {
                        out.add(
                            ScoredSegment(
                                kind = SegmentKind.ENGLISH,
                                raw = sub,
                                startIndex = range.first,
                                endIndex = range.last + 1,
                                canonicalEnglish = dictionaries.properNounCanonical[sub.lowercase()]
                            )
                        )
                    }
                    SegmentKind.LITERAL -> {
                        out.add(ScoredSegment(SegmentKind.LITERAL, sub, range.first, range.last + 1))
                    }
                }
                i = range.last + 1
                continue
            }

            // URL prefix or sigil tokens (/cmd, @mention, $var)
            if (ch == '/' || ch == '@' || ch == '$') {
                var end = i + 1
                while (end < input.length && !input[end].isWhitespace()) end++
                out.add(ScoredSegment(SegmentKind.LITERAL, input.substring(i, end), i, end))
                i = end
                continue
            }
            if (input.startsWith("http://", i, ignoreCase = true) ||
                input.startsWith("https://", i, ignoreCase = true) ||
                input.startsWith("www.", i, ignoreCase = true)
            ) {
                var end = i
                while (end < input.length && !input[end].isWhitespace()) end++
                out.add(ScoredSegment(SegmentKind.LITERAL, input.substring(i, end), i, end))
                i = end
                continue
            }

            // Model numbers / alphanumerics like RTX4090, iPhone16, M4, 128*16=
            if (isAsciiAlpha(ch) || ch.isDigit()) {
                val modelEnd = detectModelNumberSpan(input, i)
                if (modelEnd > i) {
                    out.add(
                        ScoredSegment(
                            kind = SegmentKind.ENGLISH,
                            raw = input.substring(i, modelEnd),
                            startIndex = i,
                            endIndex = modelEnd
                        )
                    )
                    i = modelEnd
                    continue
                }
            }

            if (isAsciiAlpha(ch)) {
                var end = i
                while (end < input.length && isAsciiAlpha(input[end])) {
                    if (overrides.keys.any { it.first == end }) break
                    if (end > i && detectModelNumberSpan(input, end) > end) break
                    end++
                }
                val alphaRun = input.substring(i, end)
                val followedByDelimiter = end < input.length && (input[end].isWhitespace() || input[end] in "。、.,!?！？")
                out.addAll(segmentAlphaRun(alphaRun, i, endedByDelimiter = followedByDelimiter))
                i = end
                continue
            }

            // Existing Hiragana/Katakana/Kanji or '-' / 'ー' continuing a Japanese segment
            val prevIsJpSeg = out.lastOrNull()?.let { it.kind == SegmentKind.JAPANESE && it.endIndex == i } == true
            if (isJapaneseScript(ch) || ch == 'ー' || (ch == '-' && prevIsJpSeg)) {
                var end = i
                while (end < input.length && (isJapaneseScript(input[end]) || input[end] == 'ー' || input[end] == '-')) end++
                val sub = input.substring(i, end)
                val hiraSub = sub.replace('-', 'ー')
                out.add(ScoredSegment(SegmentKind.JAPANESE, sub, i, end, hiragana = hiraSub))
                i = end
                continue
            }

            // Other punctuation, digits, whitespace
            var end = i + 1
            while (end < input.length && !isAsciiAlpha(input[end]) && !isJapaneseScript(input[end]) &&
                input[end] !in charArrayOf('/', '@', '$') && !input[end].isDigit()
            ) {
                if (overrides.keys.any { it.first == end }) break
                end++
            }
            out.add(ScoredSegment(SegmentKind.LITERAL, input.substring(i, end), i, end))
            i = end
        }

        // Merge consecutive JAPANESE segments cleanly
        val merged = ArrayList<ScoredSegment>(out.size)
        for (seg in out) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.kind == SegmentKind.JAPANESE && seg.kind == SegmentKind.JAPANESE &&
                prev.endIndex == seg.startIndex
            ) {
                val combinedRaw = prev.raw + seg.raw
                merged[merged.lastIndex] = ScoredSegment(
                    kind = SegmentKind.JAPANESE,
                    raw = combinedRaw,
                    startIndex = prev.startIndex,
                    endIndex = seg.endIndex,
                    hiragana = prev.hiragana + seg.hiragana
                )
            } else {
                merged.add(seg)
            }
        }
        return merged
    }

    /**
     * Evaluates whether a whitespace-separated multi-word string is predominantly an English sentence.
     */
    fun isLikelyEnglishSentence(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return false
        var englishVotes = 0
        var romajiVotes = 0
        for (tok in tokens) {
            val clean = tok.trim(',', '.', '!', '?', ';', ':', '"', '\'', '(', ')').lowercase()
            if (clean.isEmpty()) continue
            if (clean == "i" || clean == "a") {
                englishVotes++
                continue
            }
            val inEng = dictionaries.englishAllWords.contains(clean) || dictionaries.properNounCanonical.containsKey(clean)
            val analysis = RomajiDetector.analyze(RomajiDetector.readCRow(clean), strictForDetection = true)
            val isJp = dictionaries.japaneseWords.contains(clean) ||
                (clean.length >= 4 && analysis.isStrictValid && analysis.pendingTail.isEmpty() && !inEng)
            if (inEng && (!analysis.isStrictValid || clean !in dictionaries.japaneseOriginEnglishWords)) {
                englishVotes++
            } else if (isJp) {
                romajiVotes++
            }
        }
        return englishVotes >= 2 && englishVotes > romajiVotes
    }

    /**
     * Viterbi dynamic programming segmentation of a contiguous ASCII letter run into JAPANESE and ENGLISH segments.
     */
    private fun segmentAlphaRun(run: String, baseOffset: Int, endedByDelimiter: Boolean = false): List<ScoredSegment> {
        val n = run.length
        if (n == 0) return emptyList()

        // Single-char shortcut (allow trailing 'k', 's', 'n' as pending Japanese, except pure l/q/v/x -> English)
        if (n == 1) {
            val lower = run.lowercase()
            if (lower[0] in charArrayOf('l', 'q', 'v', 'x')) {
                return listOf(ScoredSegment(SegmentKind.ENGLISH, run, baseOffset, baseOffset + 1))
            }
            val a = RomajiDetector.analyze(RomajiDetector.readCRow(lower), strictForDetection = false)
            return listOf(
                ScoredSegment(
                    SegmentKind.JAPANESE,
                    run,
                    baseOffset,
                    baseOffset + 1,
                    hiragana = a.displayKanaOrTail
                )
            )
        }

        // Preserve deliberate TitleCase / UPPERCASE runs when they look English or CamelCase brand
        val lowerRun = run.lowercase()
        if (run.any { it.isUpperCase() } && (dictionaries.englishAllWords.contains(lowerRun) || dictionaries.properNounCanonical.containsKey(lowerRun))) {
            return listOf(
                ScoredSegment(
                    SegmentKind.ENGLISH,
                    run,
                    baseOffset,
                    baseOffset + n,
                    canonicalEnglish = dictionaries.properNounCanonical[lowerRun]
                )
            )
        }

        data class Choice(
            val score: Double,
            val prevIdx: Int,
            val kind: SegmentKind,
            val hiragana: String = ""
        )

        val dp = Array<Choice?>(n + 1) { null }
        dp[0] = Choice(0.0, -1, SegmentKind.JAPANESE)

        for (i in 0 until n) {
            val cur = dp[i] ?: continue
            val maxEnd = minOf(n, i + 32)
            for (j in (i + 1)..maxEnd) {
                val sub = lowerRun.substring(i, j)
                val isAtEnd = (j == n)

                // Option A: JAPANESE segment (keep clean intra-DP free of mid-syllable typo mutations)
                val jpEval = scoreJapaneseSpan(
                    sub = sub,
                    allowPendingTail = isAtEnd && !endedByDelimiter,
                    prevWasEnglish = (i > 0 && cur.kind == SegmentKind.ENGLISH)
                )
                if (jpEval != null) {
                    val mergeBonus = if (i > 0 && cur.kind == SegmentKind.JAPANESE) -2.0 else 0.0
                    val candidateScore = cur.score + jpEval.first + mergeBonus
                    val existing = dp[j]
                    if (existing == null || candidateScore > existing.score) {
                        dp[j] = Choice(candidateScore, i, SegmentKind.JAPANESE, jpEval.second)
                    }
                }

                // Option B: ENGLISH segment (avoid 1-char splits unless invalid romaji like q/l/v/x at boundary)
                val enScore = scoreEnglishSpan(sub, run.substring(i, j), prevIdx = i, nextPos = j, totalLen = n, fullRun = lowerRun)
                if (enScore != null) {
                    val candidateScore = cur.score + enScore
                    val existing = dp[j]
                    if (existing == null || candidateScore > existing.score) {
                        dp[j] = Choice(candidateScore, i, SegmentKind.ENGLISH)
                    }
                }
            }
        }

        // Fallback if DP failed
        if (dp[n] == null) {
            val a = RomajiDetector.analyze(lowerRun, strictForDetection = false)
            return listOf(ScoredSegment(SegmentKind.JAPANESE, run, baseOffset, baseOffset + n, hiragana = a.displayKanaOrTail))
        }

        val reversed = ArrayList<ScoredSegment>()
        var cursor = n
        while (cursor > 0) {
            val ch = dp[cursor]!!
            val prev = ch.prevIdx
            val rawSlice = run.substring(prev, cursor)
            val lowerSlice = rawSlice.lowercase()
            reversed.add(
                ScoredSegment(
                    kind = ch.kind,
                    raw = rawSlice,
                    startIndex = baseOffset + prev,
                    endIndex = baseOffset + cursor,
                    hiragana = ch.hiragana,
                    canonicalEnglish = if (ch.kind == SegmentKind.ENGLISH) dictionaries.properNounCanonical[lowerSlice] else null
                )
            )
            cursor = prev
        }
        reversed.reverse()
        return if (typoCorrectionEnabled()) {
            rescueUnreadableJapaneseSlices(reversed, run, baseOffset, endedByDelimiter)
        } else {
            reversed
        }
    }

    private fun isWeakEnglishFragment(
        segments: List<ScoredSegment>,
        idx: Int
    ): Boolean {
        val seg = segments[idx]
        if (seg.kind != SegmentKind.ENGLISH) return false
        if (seg.raw.any { it.isUpperCase() }) return false
        val lower = seg.raw.lowercase()
        if (!dictionaries.englishAllWords.contains(lower)) return true
        if (lower.length <= 2) {
            val nextSeg = segments.getOrNull(idx + 1)
            val prevSeg = segments.getOrNull(idx - 1)
            val nextTinyJp = nextSeg != null && nextSeg.kind == SegmentKind.JAPANESE && nextSeg.raw.length <= 2
            val prevTinyJp = prevSeg != null && prevSeg.kind == SegmentKind.JAPANESE && prevSeg.raw.length <= 2
            if (!dictionaries.englishFocusedWords.contains(lower) || nextTinyJp || prevTinyJp) {
                return true
            }
        }
        return false
    }

    private fun rescueUnreadableJapaneseSlices(
        segments: List<ScoredSegment>,
        fullRun: String,
        baseOffset: Int,
        endedByDelimiter: Boolean
    ): List<ScoredSegment> {
        var hasWeak = false
        for (idx in segments.indices) {
            val s = segments[idx]
            if (isWeakEnglishFragment(segments, idx) ||
                (s.kind == SegmentKind.JAPANESE &&
                    typoEngine.firstUnreadableOffset(
                        s.raw.lowercase(),
                        final = endedByDelimiter || idx < segments.lastIndex
                    ) >= 0)
            ) {
                hasWeak = true
                break
            }
        }
        if (!hasWeak) return segments

        val out = ArrayList<ScoredSegment>(segments.size)
        var i = 0
        while (i < segments.size) {
            val s = segments[i]
            val strongEnglish = (s.kind == SegmentKind.ENGLISH && !isWeakEnglishFragment(segments, i))
            if (strongEnglish) {
                out.add(s)
                i++
                continue
            }
            var j = i + 1
            while (j < segments.size) {
                val next = segments[j]
                if (next.kind == SegmentKind.ENGLISH && !isWeakEnglishFragment(segments, j)) break
                j++
            }
            val startOff = segments[i].startIndex - baseOffset
            val endOff = segments[j - 1].endIndex - baseOffset
            val sliceRaw = fullRun.substring(startOff, endOff)
            val sliceLower = sliceRaw.lowercase()
            val isFinal = endedByDelimiter || j < segments.size
            val fix = typoEngine.fixUnreadable(sliceLower, final = isFinal)
            if (fix != null) {
                out.add(
                    ScoredSegment(
                        kind = SegmentKind.JAPANESE,
                        raw = sliceRaw,
                        startIndex = baseOffset + startOff,
                        endIndex = baseOffset + endOff,
                        hiragana = fix.kana
                    )
                )
            } else {
                for (k in i until j) out.add(segments[k])
            }
            i = j
        }
        return out
    }

    private fun scoreJapaneseSpan(
        sub: String,
        allowPendingTail: Boolean,
        prevWasEnglish: Boolean,
        allowTypoRecovery: Boolean = false,
        allowFinalTypoTrim: Boolean = false
    ): Pair<Double, String>? {
        var effectiveSub = sub
        var cRead = RomajiDetector.readCRow(sub)
        var analysis = RomajiDetector.analyze(cRead, strictForDetection = true)
        var typoPenalty = 0.0

        if (!analysis.isStrictValid || (analysis.pendingTail.isNotEmpty() && !allowPendingTail)) {
            if (allowTypoRecovery && sub.length >= 4 && !dictionaries.englishAllWords.contains(sub)) {
                val fix = typoEngine.fixUnreadable(sub, final = allowFinalTypoTrim)
                if (fix != null) {
                    effectiveSub = fix.right
                    cRead = RomajiDetector.readCRow(fix.right)
                    analysis = RomajiDetector.analyze(cRead, strictForDetection = true)
                    typoPenalty = if (fix.isSingleWholeWord) 0.25 else 1.4
                }
            }
        }

        if (!analysis.isStrictValid) return null
        if (analysis.pendingTail.isNotEmpty() && !allowPendingTail) return null

        val hira = analysis.displayKanaOrTail
        var score = effectiveSub.length * 1.15 - typoPenalty

        // Strong Japanese features boost
        score += analysis.strongYouon * 3.2
        score += analysis.tsuCount * 2.8
        score += analysis.sokuonCount * 1.6
        score += analysis.longVowels * 1.4

        if (dictionaries.japaneseWords.contains(effectiveSub) || dictionaries.japaneseWords.contains(cRead)) {
            score += 4.5
        }
        val finalizedHira = analysis.finalizedHiragana()
        if (finalizedHira.length >= 2 && (dictionaries.readingsSet.contains(finalizedHira) || dictionaries.readingToPhrases.containsKey(finalizedHira))) {
            score += 3.8
        } else if (typoPenalty > 0.0 && typoPenalty <= 0.3 && finalizedHira.length >= 2 && typoEngine.isKnownWord(finalizedHira)) {
            score += 3.0
        }

        // Particle transitions at boundaries (e.g. "kyouha", "dekensaku", "wotsuika")
        val leadingParticle = PARTICLES.firstOrNull { effectiveSub.startsWith(it) && effectiveSub.length > it.length }
        if (leadingParticle != null) {
            val restHira = RomajiDetector.analyze(effectiveSub.substring(leadingParticle.length)).finalizedHiragana()
            if (dictionaries.readingsSet.contains(restHira) || dictionaries.japaneseWords.contains(effectiveSub.substring(leadingParticle.length))) {
                score += if (prevWasEnglish) 5.5 else 3.2
            } else if (prevWasEnglish) {
                score += 2.2
            }
        }
        val trailingParticle = PARTICLES.firstOrNull { effectiveSub.endsWith(it) && effectiveSub.length > it.length }
        if (trailingParticle != null) {
            val stemHira = RomajiDetector.analyze(effectiveSub.dropLast(trailingParticle.length)).finalizedHiragana()
            if (dictionaries.readingsSet.contains(stemHira) || dictionaries.japaneseWords.contains(effectiveSub.dropLast(trailingParticle.length))) {
                score += 4.2
            }
        }

        // Penalize long random-looking romaji only if it's ALSO a real English word
        if (effectiveSub.length >= 2 && dictionaries.englishAllWords.contains(effectiveSub) &&
            !dictionaries.japaneseWords.contains(effectiveSub) &&
            effectiveSub !in dictionaries.japaneseOriginEnglishWords
        ) {
            if (dictionaries.properNounCanonical.containsKey(effectiveSub) || dictionaries.englishFocusedWords.contains(effectiveSub)) {
                score -= 3.5
            }
        }

        // Penalize incomplete pending tail slightly when an alternative complete split exists
        if (analysis.pendingTail.isNotEmpty() && analysis.pendingTail != "n") {
            score -= 0.8
        }

        return score to hira
    }

    private fun scoreEnglishSpan(
        sub: String,
        originalSlice: String,
        prevIdx: Int,
        nextPos: Int,
        totalLen: Int,
        fullRun: String
    ): Double? {
        val len = sub.length
        val analysisStrict = RomajiDetector.analyze(sub, strictForDetection = true)
        val isPureStrictRomaji = analysisStrict.isStrictValid
        val hasUppercase = originalSlice.any { it.isUpperCase() }

        // Single unmapped letter fallback (l, q, v, x or invalid char at end like "g" after space, etc.)
        if (len == 1) {
            return if (sub[0] in charArrayOf('l', 'q', 'v', 'x')) -1.5 else null
        }

        val isProper = dictionaries.properNounCanonical.containsKey(sub)
        val isFocused = dictionaries.englishFocusedWords.contains(sub)
        val isWord = dictionaries.englishAllWords.contains(sub)
        val isReadable = dictionaries.englishReadableWords.contains(sub)
        val isJpOrigin = sub in dictionaries.japaneseOriginEnglishWords

        // If sub is a standalone prefix typing of an English word at the end of input (length >= 3 & non-romaji)
        if (!isWord && !isFocused && !isProper) {
            if (!isPureStrictRomaji) {
                val isPrefix = len >= 2 && (dictionaries.englishFocusedWords.hasPrefix(sub) || dictionaries.englishAllWords.hasPrefix(sub))
                val nextIsBoundary = (nextPos == totalLen) || PARTICLES.any { fullRun.startsWith(it, nextPos) }
                if (isPrefix && nextIsBoundary) {
                    return len * 1.35 + 2.5
                }
                // Fallback for unknown non-romaji token (e.g., typo or code identifier)
                if (nextPos == totalLen || len >= 3) {
                    return len * 0.7 - 3.0
                }
                return null
            }
            return null
        }

        // Known English word!
        if (isJpOrigin && isPureStrictRomaji && !hasUppercase) {
            // Word like "sushi", "karaoke", "anime": favor Japanese unless surrounded by English context
            return 0.5
        }

        // Very short 2-3 char romaji-valid English words ("to", "ha", "no", "go", "de", "in", "at", "he", "me", "so", "do", "ma", "ai")
        // should NOT split Japanese strings unless typed with uppercase or surrounded by particle/space boundaries.
        if (len <= 3 && isPureStrictRomaji && !hasUppercase) {
            val midRunWithoutParticles = prevIdx > 0 && nextPos < totalLen &&
                !PARTICLES.any { fullRun.substring(0, prevIdx).endsWith(it) } &&
                !PARTICLES.any { fullRun.substring(nextPos).startsWith(it) }
            if (midRunWithoutParticles && !isProper) {
                return -6.0
            }
            if (isReadable || sub in SHORT_AMBIGUOUS_STOPWORDS || isProper) {
                return -4.0
            }
            if (!isFocused && (prevIdx > 0 || nextPos < totalLen)) {
                return -5.5
            }
            return 1.2
        }

        var score = len * 1.45

        if (isProper) {
            score += 9.0
        } else if (isFocused) {
            score += 6.0
        } else if (!isPureStrictRomaji) {
            // Contains non-romaji sequence (th, l, v, x, ng, ck, ld, st, or trailing consonant like meet/meeting) + in English dict => strong signal
            score += 7.0
        } else {
            // Valid pure romaji AND in broad English dictionary (e.g. "time", "banana", "radio", "camera", "hero").
            val precededByParticle = prevIdx > 0 && PARTICLES.any { fullRun.substring(0, prevIdx).endsWith(it) }
            val fullAnalysis = RomajiDetector.analyze(fullRun, strictForDetection = true)
            val fullIsPureJp = fullAnalysis.isStrictValid && fullAnalysis.pendingTail.isEmpty()
            if (!isFocused && !isProper && !hasUppercase && ((prevIdx > 0 && !precededByParticle) || (prevIdx == 0 && nextPos < totalLen && fullIsPureJp))) {
                return -4.0
            }
            score += if (isReadable) 1.5 else 3.2
            if (dictionaries.japaneseWords.contains(sub) ||
                dictionaries.readingsSet.contains(analysisStrict.finalizedHiragana())
            ) {
                score -= 2.8
            }
        }

        // Guard against focused/broad romaji-valid English words ("make", "home", "tame", "same", "hare") chopping
        // contiguous lowercase Japanese stems ("makenai", "homete", "tameninaru", "samenai", "hareta") without clean boundaries.
        if (isPureStrictRomaji && analysisStrict.pendingTail.isEmpty() && !isProper && !hasUppercase && (prevIdx > 0 || nextPos < totalLen)) {
            val fullAnalysis = RomajiDetector.analyze(fullRun, strictForDetection = true)
            val fullIsPureJp = fullAnalysis.isStrictValid && fullAnalysis.pendingTail.isEmpty()
            if (fullIsPureJp) {
                val precededByParticle = prevIdx > 0 && PARTICLES.any { fullRun.substring(0, prevIdx).endsWith(it) }
                val followedByParticle = nextPos < totalLen && CASE_PARTICLES.any { fullRun.startsWith(it, nextPos) }
                val subKana = analysisStrict.finalizedHiragana()
                val isCommonJpReading = subKana.length >= 2 &&
                    (dictionaries.japaneseWords.contains(sub) || dictionaries.readingsSet.contains(subKana))
                val missingBoundary = (prevIdx > 0 && !precededByParticle) ||
                    (nextPos < totalLen && !followedByParticle) ||
                    (len <= 4 && isCommonJpReading)
                if (missingBoundary) {
                    return -5.0
                }
            }
        }

        if (hasUppercase) score += 4.0

        // Context boundary check: preceded by boundary/particle, followed by end/particle
        if (prevIdx > 0) {
            val prevPrefix = fullRun.substring(0, prevIdx)
            if (PARTICLES.any { prevPrefix.endsWith(it) }) score += 2.0
        }
        if (nextPos == totalLen) {
            score += 1.0
        } else {
            val remaining = fullRun.substring(nextPos)
            if (PARTICLES.any { remaining.startsWith(it) } || (!isPureStrictRomaji && dictionaries.japaneseWords.hasPrefix(remaining))) {
                score += 2.2
            } else {
                // Followed by consonant/non-particle -> slightly less likely clean split unless itself is English
                score -= 0.5
            }
        }

        return score
    }

    private fun detectModelNumberSpan(input: String, start: Int): Int {
        var idx = start
        var hasLetter = false
        var hasDigit = false
        while (idx < input.length) {
            val c = input[idx]
            if (isAsciiAlpha(c)) {
                // Stop if we already saw digits followed by lowercase Japanese particle like "4090wo" -> split after 4090
                if (hasDigit && c.isLowerCase()) {
                    val rest = input.substring(idx).lowercase()
                    if (PARTICLES.any { rest.startsWith(it) } || rest.startsWith("de") || rest.startsWith("kara")) {
                        break
                    }
                }
                hasLetter = true
                idx++
            } else if (c.isDigit()) {
                hasDigit = true
                idx++
            } else if ((c == '-' || c == '_') && idx + 1 < input.length && (isAsciiAlpha(input[idx + 1]) || input[idx + 1].isDigit())) {
                idx++
            } else {
                break
            }
        }
        return if (hasLetter && hasDigit) idx else -1
    }

    companion object {
        private val PARTICLES = listOf("kara", "made", "ha", "ga", "wo", "ni", "de", "to", "no", "mo", "wa", "he", "ya")
        private val CASE_PARTICLES = listOf("kara", "made", "ha", "ga", "wo", "ni", "de", "mo")

        private val SHORT_AMBIGUOUS_STOPWORDS = setOf(
            "a", "an", "as", "at", "be", "by", "do", "go", "ha", "he", "hi", "in", "is", "it",
            "ma", "me", "mo", "my", "na", "ne", "no", "oh", "ok", "on", "or", "pa", "so", "to",
            "up", "us", "we", "yo", "ya", "are", "ate", "ban", "bar", "bin", "bit", "bun", "bus",
            "can", "dan", "den", "die", "don", "era", "fan", "fun", "gen", "gin", "gun", "han",
            "hat", "hen", "her", "hit", "hon", "hot", "inn", "ion", "ire", "its", "kin", "man",
            "mar", "mat", "men", "met", "min", "mom", "mon", "nan", "net", "nin", "non", "not",
            "now", "nun", "oat", "one", "ore", "our", "out", "pan", "pen", "per", "pin", "pun",
            "ran", "rat", "red", "ren", "rim", "rot", "run", "san", "sat", "see", "sen", "set",
            "she", "sin", "sir", "sit", "son", "sue", "sum", "sun", "tan", "ten", "the", "tie",
            "tin", "toe", "ton", "too", "top", "tor", "tot", "two", "use", "wan", "war", "was",
            "way", "wee", "who", "win", "wit", "won", "woo", "zen"
        )

        private fun isAsciiAlpha(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

        private fun isJapaneseScript(c: Char): Boolean {
            return c in '\u3041'..'\u309F' || // Hiragana
                c in '\u30A1'..'\u30FF' ||    // Katakana
                c in '\u4E00'..'\u9FFF'       // Common Kanji
        }
    }
}
