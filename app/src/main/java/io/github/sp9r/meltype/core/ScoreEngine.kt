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
class ScoreEngine(private val dictionaries: MeltypeDictionaries) {

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
                out.addAll(segmentAlphaRun(alphaRun, i))
                i = end
                continue
            }

            // Existing Hiragana/Katakana/Kanji or '-' between kana
            if (isJapaneseScript(ch) || (ch == '-' && i > 0 && isJapaneseScript(input[i - 1]))) {
                var end = i
                while (end < input.length && (isJapaneseScript(input[end]) || input[end] == 'ー')) end++
                val sub = input.substring(i, end)
                out.add(ScoredSegment(SegmentKind.JAPANESE, sub, i, end, hiragana = sub))
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
    private fun segmentAlphaRun(run: String, baseOffset: Int): List<ScoredSegment> {
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

                // Option A: JAPANESE segment
                val jpEval = scoreJapaneseSpan(sub, allowPendingTail = isAtEnd, prevWasEnglish = (i > 0 && cur.kind == SegmentKind.ENGLISH))
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
        return reversed
    }

    private fun scoreJapaneseSpan(
        sub: String,
        allowPendingTail: Boolean,
        prevWasEnglish: Boolean
    ): Pair<Double, String>? {
        val cRead = RomajiDetector.readCRow(sub)
        val analysis = RomajiDetector.analyze(cRead, strictForDetection = true)
        if (!analysis.isStrictValid) return null
        if (analysis.pendingTail.isNotEmpty() && !allowPendingTail) return null

        val hira = analysis.displayKanaOrTail
        var score = sub.length * 1.15

        // Strong Japanese features boost
        score += analysis.strongYouon * 3.2
        score += analysis.tsuCount * 2.8
        score += analysis.sokuonCount * 1.6
        score += analysis.longVowels * 1.4

        if (dictionaries.japaneseWords.contains(sub) || dictionaries.japaneseWords.contains(cRead)) {
            score += 4.5
        }
        val finalizedHira = analysis.finalizedHiragana()
        if (finalizedHira.length >= 2 && (dictionaries.readingsSet.contains(finalizedHira) || dictionaries.readingToPhrases.containsKey(finalizedHira))) {
            score += 3.8
        }

        // Particle transitions at boundaries (e.g. "kyouha", "dekensaku", "wotsuika")
        val leadingParticle = PARTICLES.firstOrNull { sub.startsWith(it) && sub.length > it.length }
        if (leadingParticle != null) {
            val restHira = RomajiDetector.analyze(sub.substring(leadingParticle.length)).finalizedHiragana()
            if (dictionaries.readingsSet.contains(restHira) || dictionaries.japaneseWords.contains(sub.substring(leadingParticle.length))) {
                score += if (prevWasEnglish) 5.5 else 3.2
            } else if (prevWasEnglish) {
                score += 2.2
            }
        }
        val trailingParticle = PARTICLES.firstOrNull { sub.endsWith(it) && sub.length > it.length }
        if (trailingParticle != null) {
            val stemHira = RomajiDetector.analyze(sub.dropLast(trailingParticle.length)).finalizedHiragana()
            if (dictionaries.readingsSet.contains(stemHira) || dictionaries.japaneseWords.contains(sub.dropLast(trailingParticle.length))) {
                score += 4.2
            }
        }

        // Penalize long random-looking romaji only if it's ALSO a real English word
        if (sub.length >= 2 && dictionaries.englishAllWords.contains(sub) &&
            !dictionaries.japaneseWords.contains(sub) &&
            sub !in dictionaries.japaneseOriginEnglishWords
        ) {
            if (dictionaries.properNounCanonical.containsKey(sub) || dictionaries.englishFocusedWords.contains(sub)) {
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
            if (!analysisStrict.isStrictValid) {
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
        if (isJpOrigin && analysisStrict.isStrictValid && !hasUppercase) {
            // Word like "sushi", "karaoke", "anime": favor Japanese unless surrounded by English context
            return 0.5
        }

        // Very short 2-3 char romaji-valid English words ("to", "ha", "no", "go", "de", "in", "at", "he", "me", "so", "do", "ma", "ai")
        // should NOT split Japanese strings unless typed with uppercase or surrounded by spaces.
        if (len <= 3 && analysisStrict.isStrictValid && !hasUppercase) {
            if (isReadable || sub in SHORT_AMBIGUOUS_STOPWORDS || isProper) {
                return -4.0
            }
            return 1.2
        }

        var score = len * 1.45

        if (isProper) {
            score += 9.0
        } else if (isFocused) {
            score += 6.0
        } else if (!analysisStrict.isStrictValid) {
            // Contains non-romaji sequence (th, l, v, x, ng, ck, ld, st, etc.) + in English dict => strong signal
            score += 7.0
        } else {
            // Valid as both romaji and broad English dictionary (e.g. "time", "banana", "radio", "camera", "hero")
            score += if (isReadable) 1.5 else 3.2
            if (dictionaries.japaneseWords.contains(sub) ||
                dictionaries.readingsSet.contains(RomajiDetector.analyze(sub).finalizedHiragana())
            ) {
                score -= 2.8
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
            if (PARTICLES.any { remaining.startsWith(it) } || dictionaries.japaneseWords.hasPrefix(remaining)) {
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
