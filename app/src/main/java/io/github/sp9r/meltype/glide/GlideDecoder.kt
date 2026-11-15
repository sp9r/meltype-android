package io.github.sp9r.meltype.glide

import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class GlideCandidate(
    val word: String,
    val isEnglish: Boolean,
    val scoreCost: Float,
    val kanaHint: String? = null
)

/**
 * Dual-language (English + Japanese Romaji) QWERTY gesture/glide decoder.
 * Indexed by (firstChar, lastChar) buckets and scored in two stages:
 *  1) Endpoint distance + M=32 polyline shape + collapsed double-letter fit + arc length + priors
 *  2) Two-way inflection corner verification + monotonic waypoint alignment on top shortlist
 *
 * Japanese lexicon entries expand Hepburn + Kunrei ("si/ti/tu/hu/zi/sya/tya/zya") + shortened long
 * vowels ("ou->o") + JMdict conjugated verb/adjective stems, all resolving to canonical Hepburn output.
 */
class GlideDecoder(private val dictionaries: MeltypeDictionaries) {

    private data class LexiconEntry(
        val traceWord: String,
        val outputWord: String,
        val isEnglish: Boolean,
        val freqBonus: Float,
        val letterMask: Int,
        val kanaHint: String?,
        val template: FloatArray,
        val arcLen: Float,
        val collapsedTrace: String,
        val collapsedTemplate: FloatArray?,
        val collapsedArcLen: Float
    )

    // 26 x 26 bucket index keyed by (firstChar - 'a') * 26 + (lastChar - 'a')
    private val buckets = Array<MutableList<LexiconEntry>>(26 * 26) { ArrayList() }

    init {
        val seen = HashMap<String, LexiconEntry>(48000)
        val japaneseTraceSet = HashSet<String>(36000)

        fun registerTemplate(
            rawTrace: String,
            rawOutput: String,
            isEnglish: Boolean,
            freqBonus: Float,
            kanaHint: String? = null
        ) {
            val trace = rawTrace.lowercase().trim()
            val output = rawOutput.lowercase().trim()
            if (trace.length < 2 || trace.length > 18) return
            if (output.length < 2 || output.length > 20) return
            if (!trace.all { it in 'a'..'z' } || !output.all { it in 'a'..'z' }) return

            if (!isEnglish) {
                japaneseTraceSet.add(trace)
            }

            val dedupKey = "$trace|$output|${if (isEnglish) "E" else "J"}"
            val existing = seen[dedupKey]
            if (existing != null) {
                if (freqBonus > existing.freqBonus || (existing.kanaHint == null && kanaHint != null)) {
                    val idx = (trace.first() - 'a') * 26 + (trace.last() - 'a')
                    val list = buckets[idx]
                    val pos = list.indexOfFirst { it === existing }
                    val updated = existing.copy(
                        freqBonus = max(freqBonus, existing.freqBonus),
                        kanaHint = existing.kanaHint ?: kanaHint
                    )
                    if (pos >= 0) list[pos] = updated
                    seen[dedupKey] = updated
                }
                return
            }

            val (tpl, arc) = KeyLayoutGeometry.templateForWord(trace)
            val collapsed = KeyLayoutGeometry.collapseConsecutiveLetters(trace)
            val (cTpl, cArc) = if (collapsed != trace && collapsed.length >= 2) {
                KeyLayoutGeometry.templateForWord(collapsed)
            } else {
                null to 0f
            }

            val entry = LexiconEntry(
                traceWord = trace,
                outputWord = output,
                isEnglish = isEnglish,
                freqBonus = freqBonus,
                letterMask = KeyLayoutGeometry.letterMask(trace),
                kanaHint = kanaHint,
                template = tpl,
                arcLen = arc,
                collapsedTrace = collapsed,
                collapsedTemplate = cTpl,
                collapsedArcLen = cArc
            )
            seen[dedupKey] = entry
            val idx = (trace.first() - 'a') * 26 + (trace.last() - 'a')
            buckets[idx].add(entry)
        }

        fun registerJapaneseHira(hira: String, freqBonus: Float, explicitRomaji: String? = null) {
            val canonical = explicitRomaji ?: kanaToRomaji(hira, kunrei = false) ?: return
            if (canonical.length !in 2..18) return
            registerTemplate(canonical, canonical, isEnglish = false, freqBonus = freqBonus, kanaHint = hira)

            // Kunrei-shiki swipe trace (si, ti, tu, hu, zi, sya, tya, zya) mapped to canonical Hepburn output
            val kunrei = kanaToRomaji(hira, kunrei = true)
            if (kunrei != null && kunrei != canonical && kunrei.length in 2..18) {
                registerTemplate(kunrei, canonical, isEnglish = false, freqBonus = freqBonus - 0.01f, kanaHint = hira)
            }

            // Long vowel shorthand (arigato -> arigatou, tokyo -> toukyou, futsu -> futsuu)
            if (canonical.contains("ou") || canonical.contains("uu")) {
                val shortVowel = canonical.replace("ou", "o").replace("uu", "u")
                if (shortVowel != canonical && shortVowel.length >= 2) {
                    registerTemplate(shortVowel, canonical, isEnglish = false, freqBonus = freqBonus - 0.025f, kanaHint = hira)
                }
            }
            if (kunrei != null && kunrei != canonical && (kunrei.contains("ou") || kunrei.contains("uu"))) {
                val shortKunrei = kunrei.replace("ou", "o").replace("uu", "u")
                if (shortKunrei != kunrei && shortKunrei.length >= 2) {
                    registerTemplate(shortKunrei, canonical, isEnglish = false, freqBonus = freqBonus - 0.03f, kanaHint = hira)
                }
            }
        }

        // Tier 1: Core everyday Japanese romaji & particles
        for ((rom, hiraHint) in CORE_JAPANESE_PAIRS) {
            registerJapaneseHira(hiraHint, freqBonus = 0.28f, explicitRomaji = rom)
            if (!rom.endsWith("ha") && !rom.endsWith("wo")) {
                // Keep explicit kunrei/vowel variants generated cleanly from hiraHint when consistent
                registerJapaneseHira(hiraHint, freqBonus = 0.27f)
            }
        }
        for (jp in dictionaries.japaneseCanonicalWords) {
            val hira = RomajiDetector.analyze(jp).finalizedHiragana()
            registerJapaneseHira(hira, freqBonus = 0.24f, explicitRomaji = jp)
            for (p in TOP_PARTICLES) {
                if (jp.length + p.length <= 15) {
                    val pKana = RomajiDetector.analyze(p).finalizedHiragana()
                    registerJapaneseHira(hira + pKana, freqBonus = 0.17f, explicitRomaji = jp + p)
                }
            }
        }
        for (stem in CORE_PARTICLE_STEMS) {
            val stemHira = RomajiDetector.analyze(stem).finalizedHiragana()
            for (p in TOP_PARTICLES) {
                val pHira = RomajiDetector.analyze(p).finalizedHiragana()
                registerJapaneseHira(stemHira + pHira, freqBonus = 0.24f, explicitRomaji = stem + p)
            }
        }

        // Tier 1.5: Expand phrases, curated candidates, and JMdict readings + inflections
        for (hira in dictionaries.readingToPhrases.keys) {
            if (hira.length in 2..12) {
                registerJapaneseHira(hira, freqBonus = 0.19f)
            }
        }
        for (hira in dictionaries.extraCandidates.keys) {
            if (hira.length in 2..10) {
                registerJapaneseHira(hira, freqBonus = 0.17f)
            }
        }
        for ((baseHira, tags) in dictionaries.readingWithTags) {
            if (baseHira.length !in 2..8) continue
            for (tag in tags) {
                for (inflected in expandInflections(baseHira, tag)) {
                    if (inflected.length in 2..11) {
                        registerJapaneseHira(inflected, freqBonus = 0.14f)
                    }
                }
            }
        }
        for (hira in dictionaries.readingsSet) {
            if (hira.length in 2..9) {
                registerJapaneseHira(hira, freqBonus = 0.12f)
            }
        }

        // Tier 1 English: Core everyday English, proper nouns, curated English list
        for (w in CORE_ENGLISH_WORDS) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.26f)
        }
        for (w in dictionaries.properNounCanonical.keys) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.24f)
        }
        for (w in dictionaries.commonLatinWordsForGlide) {
            val isJpOrigin = w in dictionaries.japaneseOriginEnglishWords
            registerTemplate(w, w, isEnglish = !isJpOrigin, freqBonus = if (isJpOrigin) 0.10f else 0.18f)
        }
        for (w in dictionaries.englishReadableWords.allWords()) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.10f)
        }

        // Tier 3 English (39k SCOWL): only allow medium-long words (>= 5 chars) that do NOT look like
        // valid Japanese romaji, with negative prior so rare English words never hijack Japanese glides.
        for (w in dictionaries.englishAllWords.allWords()) {
            if (w.length in 5..14 && w !in japaneseTraceSet && !dictionaries.japaneseWords.contains(w)) {
                val rom = RomajiDetector.analyze(w, strictForDetection = true)
                val looksLikeJp = rom.isStrictValid && rom.pendingTail.isEmpty() && rom.chunks.size >= 2
                if (!looksLikeJp) {
                    registerTemplate(w, w, isEnglish = true, freqBonus = -0.22f)
                }
            }
        }
    }

    private data class Stage1Hit(
        val entry: LexiconEntry,
        val baseCost: Float
    )

    /**
     * Decodes normalized isotropic touch points into ranked dual-language glide candidates.
     */
    fun decode(
        normalizedPoints: List<TouchPoint>,
        maxResults: Int = 8,
        preferJapanese: Boolean = true
    ): List<GlideCandidate> {
        if (normalizedPoints.size < 2) return emptyList()

        val (path, pathLen) = KeyLayoutGeometry.resamplePolyline(normalizedPoints)
        if (pathLen < 0.08f) return emptyList()

        val startX = normalizedPoints.first().x
        val startY = normalizedPoints.first().y
        val endX = normalizedPoints.last().x
        val endY = normalizedPoints.last().y

        val startKeys = KeyLayoutGeometry.nearestKeys(startX, startY, limit = 3)
        val endKeys = KeyLayoutGeometry.nearestKeys(endX, endY, limit = 3)

        val stage1 = ArrayList<Stage1Hit>(48)
        val n = KeyLayoutGeometry.RESAMPLE_N
        val nFloat = n.toFloat()

        for ((sChar, sDist) in startKeys) {
            if (sDist > 0.68f && startKeys.first().first != sChar) continue
            val sIdx = (sChar - 'a') * 26
            for ((eChar, eDist) in endKeys) {
                if (eDist > 0.68f && endKeys.first().first != eChar) continue
                val bucket = buckets[sIdx + (eChar - 'a')]
                for (entry in bucket) {
                    val lenDiff = abs(pathLen - entry.arcLen)
                    val maxLenBound = maxOf(pathLen, entry.arcLen) * 0.72f + 0.55f
                    if (lenDiff > maxLenBound) continue

                    val endpointCost = sDist * 1.32f + eDist * 1.32f

                    var sumD = 0f
                    val tpl = entry.template
                    var j = 0
                    while (j < n * 2) {
                        val dx = path[j] - tpl[j]
                        val dy = path[j + 1] - tpl[j + 1]
                        sumD += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        j += 2
                    }
                    var shapeDist = sumD / nFloat
                    var effArcDiff = lenDiff

                    val cTpl = entry.collapsedTemplate
                    if (cTpl != null) {
                        var cSum = 0f
                        var cj = 0
                        while (cj < n * 2) {
                            val dx = path[cj] - cTpl[cj]
                            val dy = path[cj + 1] - cTpl[cj + 1]
                            cSum += hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            cj += 2
                        }
                        val cShape = cSum / nFloat + 0.015f
                        if (cShape < shapeDist) {
                            shapeDist = cShape
                            effArcDiff = min(effArcDiff, abs(pathLen - entry.collapsedArcLen) + 0.04f)
                        }
                    }

                    val langPrior = if (preferJapanese && !entry.isEnglish) 0.13f else 0f
                    val baseCost = endpointCost + shapeDist * 2.05f + effArcDiff * 0.24f - entry.freqBonus - langPrior
                    if (baseCost < 2.05f) {
                        insertTopStage1(stage1, Stage1Hit(entry, baseCost), limit = 56)
                    }
                }
            }
        }

        // Stage 2 reranking: corner-anchor hit verification + monotonic waypoint pass check on top shortlist
        val anchors = KeyLayoutGeometry.extractAnchorPoints(normalizedPoints)
        val interiorAnchors = anchors.filter { !it.isEndpoint }.map { a ->
            val near2 = KeyLayoutGeometry.nearestKeys(a.x, a.y, limit = 2)
            var nearMask = 0
            for ((kch, d) in near2) {
                if (d < 0.34f) {
                    nearMask = nearMask or (1 shl (kch - 'a'))
                }
            }
            a to nearMask
        }

        val candidates = ArrayList<GlideCandidate>(stage1.size + 4)
        for (hit in stage1) {
            val e = hit.entry
            var cornerAdj = 0f
            for ((anchor, nearMask) in interiorAnchors) {
                if (nearMask != 0) {
                    if ((e.letterMask and nearMask) == 0) {
                        cornerAdj += 0.12f * anchor.sharpness
                    } else {
                        cornerAdj -= 0.03f * anchor.sharpness
                    }
                }
            }
            val waypointPenalty = computeWaypointPenalty(e.collapsedTrace, path, n)
            val finalCost = hit.baseCost + cornerAdj + waypointPenalty * 0.55f
            candidates.add(GlideCandidate(e.outputWord, e.isEnglish, finalCost, e.kanaHint))
        }

        // Syllable lattice fallback if trajectory anchors themselves form strict valid romaji
        if (anchors.size >= 2) {
            val dedupChars = ArrayList<Char>(anchors.size)
            for (a in anchors) {
                if (dedupChars.lastOrNull() != a.nearestChar) dedupChars.add(a.nearestChar)
            }
            if (dedupChars.size >= 2) {
                val anchorStr = dedupChars.joinToString("")
                val analysis = RomajiDetector.analyze(anchorStr, strictForDetection = true)
                if (analysis.isStrictValid && analysis.pendingTail.isEmpty() && candidates.none { it.word == anchorStr }) {
                    candidates.add(
                        GlideCandidate(
                            word = anchorStr,
                            isEnglish = false,
                            scoreCost = 1.42f,
                            kanaHint = analysis.finalizedHiragana()
                        )
                    )
                }
            }
        }

        candidates.sortBy { it.scoreCost }
        return candidates.distinctBy { it.word }.take(maxResults)
    }

    private fun computeWaypointPenalty(trace: String, resampledPath: FloatArray, n: Int): Float {
        val len = trace.length
        if (len <= 2) return 0f
        var sumMinDist = 0f
        var maxDist = 0f
        var minSearchIdx = 0
        for (k in 0 until len) {
            val kc = KeyLayoutGeometry.centerOf(trace[k]) ?: continue
            val expectedIdx = if (len > 1) (k * (n - 1)) / (len - 1) else 0
            val lo = max(minSearchIdx, expectedIdx - 6).coerceIn(0, n - 1)
            val hi = (expectedIdx + 7).coerceIn(lo, n - 1)
            var bestD = Float.MAX_VALUE
            var bestIdx = lo
            for (pIdx in lo..hi) {
                val dx = resampledPath[pIdx * 2] - kc.x
                val dy = resampledPath[pIdx * 2 + 1] - kc.y
                val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (d < bestD) {
                    bestD = d
                    bestIdx = pIdx
                }
            }
            minSearchIdx = max(minSearchIdx, bestIdx - 2)
            sumMinDist += bestD
            if (bestD > maxDist) maxDist = bestD
        }
        val avg = sumMinDist / len.toFloat()
        return avg * 0.65f + maxDist * 0.35f
    }

    private fun insertTopStage1(list: MutableList<Stage1Hit>, item: Stage1Hit, limit: Int) {
        if (list.size < limit) {
            list.add(item)
            return
        }
        var maxIdx = 0
        var maxCost = list[0].baseCost
        for (i in 1 until list.size) {
            if (list[i].baseCost > maxCost) {
                maxCost = list[i].baseCost
                maxIdx = i
            }
        }
        if (item.baseCost < maxCost) {
            list[maxIdx] = item
        }
    }

    companion object {
        private val HEPBURN_MAP = mapOf(
            "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
            "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
            "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
            "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
            "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
            "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
            "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
            "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
            "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
            "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
            "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
            "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
            "てぃ" to "thi", "でぃ" to "dhi", "うぃ" to "wi", "うぇ" to "we",
            "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
            "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
            "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
            "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
            "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
            "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
            "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
            "や" to "ya", "ゆ" to "yu", "よ" to "yo",
            "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
            "わ" to "wa", "を" to "wo",
            "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
            "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
            "だ" to "da", "ぢ" to "di", "づ" to "du", "で" to "de", "ど" to "do",
            "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
            "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po"
        )

        private val KUNREI_OVERRIDES = mapOf(
            "し" to "si",
            "ち" to "ti",
            "つ" to "tu",
            "ふ" to "hu",
            "じ" to "zi",
            "しゃ" to "sya", "しゅ" to "syu", "しょ" to "syo",
            "ちゃ" to "tya", "ちゅ" to "tyu", "ちょ" to "tyo",
            "じゃ" to "zya", "じゅ" to "zyu", "じょ" to "zyo"
        )

        fun kanaToRomaji(hira: String, kunrei: Boolean = false): String? {
            if (hira.isEmpty()) return null
            val sb = StringBuilder(hira.length * 2)
            var i = 0
            while (i < hira.length) {
                val ch = hira[i]
                if (ch == 'っ') {
                    val nextPair = if (i + 2 < hira.length) hira.substring(i + 1, i + 3) else null
                    val nextRom = when {
                        nextPair != null && (if (kunrei) KUNREI_OVERRIDES[nextPair] else null) != null -> KUNREI_OVERRIDES[nextPair]
                        nextPair != null && HEPBURN_MAP[nextPair] != null -> HEPBURN_MAP[nextPair]
                        i + 1 < hira.length -> {
                            val s = hira[i + 1].toString()
                            (if (kunrei) KUNREI_OVERRIDES[s] else null) ?: HEPBURN_MAP[s]
                        }
                        else -> null
                    }
                    val c = nextRom?.firstOrNull() ?: return null
                    if (c !in charArrayOf('a', 'i', 'u', 'e', 'o', 'n')) {
                        sb.append(c)
                    }
                    i++
                    continue
                }
                if (ch == 'ん') {
                    val nextChar = if (i + 1 < hira.length) hira[i + 1] else null
                    val needsDoubleN = nextChar != null && nextChar in "あいうえおやゆよなにぬねの"
                    sb.append(if (needsDoubleN) "nn" else "n")
                    i++
                    continue
                }
                if (i + 1 < hira.length) {
                    val pair = hira.substring(i, i + 2)
                    val rom = (if (kunrei) KUNREI_OVERRIDES[pair] else null) ?: HEPBURN_MAP[pair]
                    if (rom != null) {
                        sb.append(rom)
                        i += 2
                        continue
                    }
                }
                val singleKey = ch.toString()
                val single = (if (kunrei) KUNREI_OVERRIDES[singleKey] else null) ?: HEPBURN_MAP[singleKey] ?: return null
                sb.append(single)
                i++
            }
            return sb.toString()
        }

        private fun expandInflections(hira: String, tag: String): List<String> {
            val out = ArrayList<String>(8)
            when (tag) {
                "v1" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ます", "${stem}ました", "${stem}ません", "${stem}て", "${stem}た", "${stem}ない", "${stem}よう", "${stem}たい"))
                }
                "v5r", "v5r-i" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ります", "${stem}りました", "${stem}りません", "${stem}って", "${stem}った", "${stem}らない", "${stem}りたい"))
                }
                "v5u" -> if (hira.endsWith("う")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}います", "${stem}いました", "${stem}いません", "${stem}って", "${stem}った", "${stem}わない", "${stem}いたい"))
                }
                "v5t" -> if (hira.endsWith("つ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ちます", "${stem}ちました", "${stem}って", "${stem}った", "${stem}たない"))
                }
                "v5k" -> if (hira.endsWith("く")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}いて", "${stem}いた", "${stem}かない", "${stem}きたい"))
                }
                "v5k-s" -> if (hira.endsWith("く")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}って", "${stem}った", "${stem}かない", "${stem}きたい"))
                }
                "v5g" -> if (hira.endsWith("ぐ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ぎます", "${stem}ぎました", "${stem}いで", "${stem}いだ", "${stem}がない"))
                }
                "v5s" -> if (hira.endsWith("す")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}します", "${stem}しました", "${stem}して", "${stem}した", "${stem}さない", "${stem}したい"))
                }
                "v5m" -> if (hira.endsWith("む")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}みます", "${stem}みました", "${stem}んで", "${stem}んだ", "${stem}まない", "${stem}みたい"))
                }
                "v5b" -> if (hira.endsWith("ぶ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}びます", "${stem}びました", "${stem}んで", "${stem}んだ", "${stem}ばない"))
                }
                "v5n" -> if (hira.endsWith("ぬ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}にます", "${stem}んで", "${stem}んだ", "${stem}なない"))
                }
                "v5aru" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}います", "${stem}いました", "${stem}いません"))
                }
                "vk" -> if (hira.endsWith("くる")) {
                    val stem = hira.dropLast(2)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}きて", "${stem}きた", "${stem}こない", "${stem}きたい"))
                }
                "vs" -> if (hira.endsWith("する")) {
                    val stem = hira.dropLast(2)
                    out.addAll(listOf("${stem}します", "${stem}しました", "${stem}して", "${stem}した", "${stem}しない", "${stem}したい"))
                }
                "i" -> if (hira.endsWith("い") && hira != "いい") {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}く", "${stem}くて", "${stem}かった", "${stem}くない", "${stem}いです"))
                }
            }
            return out
        }

        private val TOP_PARTICLES = listOf("ha", "ga", "wo", "ni", "de", "to", "no", "mo", "kara", "made")

        private val CORE_PARTICLE_STEMS = listOf(
            "kyou", "ashita", "kinou", "watashi", "kore", "sore", "are", "koko", "soko", "asoko",
            "dare", "nani", "itsu", "doko", "kaigi", "kadai", "jugyou", "kensaku", "tsuika",
            "kakunin", "henkou", "henshin", "soushin", "hozon", "sakujo", "nihongo", "eigo",
            "denwa", "densha", "jikan", "basho", "shigoto", "kenkyuu", "kaihatsu", "jissou",
            "pasokon", "sumaho", "apuri", "meiru", "meeru", "minna", "sensei", "tomodachi",
            "ie", "eki", "daigaku", "heya", "machi", "tenki", "yoru", "asa", "hiru"
        )

        private val CORE_JAPANESE_PAIRS = listOf(
            "kyou" to "きょう", "kyouha" to "きょうは", "kyoumo" to "きょうも", "kyouno" to "きょうの",
            "ashita" to "あした", "ashitaha" to "あしたは", "ashitano" to "あしたの",
            "kinou" to "きのう", "konshuu" to "こんしゅう", "raishuu" to "らいしゅう", "senshuu" to "せんしゅう",
            "ima" to "いま", "ato" to "あと", "mae" to "まえ", "tsugi" to "つぎ", "saisho" to "さいしょ",
            "saigo" to "さいご", "itsumo" to "いつも", "tokidoki" to "ときどき", "tabun" to "たぶん",
            "kitto" to "きっと", "mousugu" to "もうすぐ", "yappari" to "やっぱり", "yahari" to "やはり",
            "watashi" to "わたし", "watashiha" to "わたしは", "boku" to "ぼく", "jibun" to "じぶん",
            "anata" to "あなた", "kare" to "かれ", "kanojo" to "かのじょ", "minna" to "みんな", "hito" to "ひと",
            "arigatou" to "ありがとう", "ohayou" to "おはよう",
            "konnichiwa" to "こんにちは", "konnichiha" to "こんにちは",
            "konbanwa" to "こんばんは", "konbanha" to "こんばんは",
            "oyasumi" to "おやすみ", "sumimasen" to "すみません", "gomennasai" to "ごめんなさい",
            "onegai" to "おねがい", "onegaishimasu" to "おねがいします",
            "yoroshiku" to "よろしく", "otsukaresama" to "おつかれさま", "daijoubu" to "だいじょうぶ",
            "shitsurei" to "しつれい", "mochiron" to "もちろん", "tashikani" to "たしかに", "naruhodo" to "なるほど",
            "desu" to "です", "masu" to "ます", "mashita" to "ました", "masen" to "ません", "deshita" to "でした",
            "suru" to "する", "shita" to "した", "shimasu" to "します", "shite" to "して", "shimasen" to "しません",
            "dekimasu" to "できます", "dekiru" to "できる", "dekita" to "できた",
            "arimasu" to "あります", "arimashita" to "ありました", "imasu" to "います", "imashita" to "いました",
            "narimasu" to "なります", "narimashita" to "なりました", "naru" to "なる", "natta" to "なった",
            "wakarimashita" to "わかりました", "wakarimasu" to "わかります", "wakatta" to "わかった", "wakaru" to "わかる",
            "omoimasu" to "おもいます", "omou" to "おもう", "omotta" to "おもった",
            "kensaku" to "けんさく", "dekensaku" to "でけんさく", "tsuika" to "ついか", "wotsuika" to "をついか",
            "kakunin" to "かくにん", "henkou" to "へんこう", "shuusei" to "しゅうせい",
            "sakujo" to "さくじょ", "hozon" to "ほぞん", "koushin" to "こうしん",
            "soushin" to "そうしん", "henshin" to "へんしん", "renraku" to "れんらく",
            "shousai" to "しょうさい", "yotei" to "よてい", "kaigi" to "かいぎ", "shiryou" to "しりょう",
            "kadai" to "かだい", "teishutsu" to "ていしゅつ", "shimekiri" to "しめきり",
            "jugyou" to "じゅぎょう", "daigaku" to "だいがく", "kenkyuu" to "けんきゅう",
            "kaihatsu" to "かいはつ", "jissou" to "じっそう", "settei" to "せってい",
            "jikkou" to "じっこう", "saikidou" to "さいきどう", "kanryou" to "かんりょう",
            "nihongo" to "にほんご", "eigo" to "えいご", "henkan" to "へんかん", "nyuuryoku" to "にゅうりょく",
            "kiiboodo" to "きーぼーど", "sumaho" to "すまほ", "pasokon" to "ぱそこん", "apuri" to "あぷり",
            "gamen" to "がめん", "botan" to "ぼたん", "rireki" to "りれき", "takasa" to "たかさ",
            "suuji" to "すうじ", "kigou" to "きごう", "seido" to "せいど",
            "kono" to "この", "sono" to "その", "ano" to "あの", "dono" to "どの",
            "kore" to "これ", "sore" to "それ", "are" to "あれ", "dore" to "どれ",
            "koko" to "ここ", "soko" to "そこ", "asoko" to "あそこ", "doko" to "どこ",
            "nan" to "なん", "nani" to "なに", "dare" to "だれ", "itsu" to "いつ", "naze" to "なぜ", "dou" to "どう",
            "doushite" to "どうして", "dokohe" to "どこへ", "nandesu" to "なんです",
            "hontou" to "ほんとう", "chotto" to "ちょっと", "sukoshi" to "すこし", "takusan" to "たくさん",
            "zenbu" to "ぜんぶ", "issyo" to "いっしょ", "issho" to "いっしょ", "futsuu" to "ふつう",
            "tokuni" to "とくに", "kanari" to "かなり", "chanto" to "ちゃんと", "shikkari" to "しっかり",
            "sugoi" to "すごい", "yabai" to "やばい", "kawaii" to "かわいい", "tanoshii" to "たのしい",
            "ureshii" to "うれしい", "kanashii" to "かなしい", "omoshiroi" to "おもしろい",
            "muzukashii" to "むずかしい", "kantan" to "かんたん", "benri" to "べんり",
            "tsukareta" to "つかれた", "nemui" to "ねむい", "atatakai" to "あたたかい", "samui" to "さむい", "atsui" to "あつい",
            "onaka" to "おなか", "gohan" to "ごはん", "ryouri" to "りょうり", "kaimono" to "かいもの",
            "taberu" to "たべる", "tabete" to "たべて", "tabeta" to "たべた", "tabemasu" to "たべます",
            "nomu" to "のむ", "nonde" to "のんで", "kaeru" to "かえる", "kaette" to "かえって",
            "iku" to "いく", "itte" to "いって", "ikimasu" to "いきます", "itta" to "いった",
            "kuru" to "くる", "kite" to "きて", "kimasu" to "きます", "kita" to "きた",
            "miru" to "みる", "mite" to "みて", "mita" to "みた", "mimasu" to "みます",
            "kiku" to "きく", "kiite" to "きいて", "kaku" to "かく", "kaite" to "かいて",
            "yomu" to "よむ", "yonde" to "よんで", "hanasu" to "はなす", "hanashite" to "はなして",
            "oshieru" to "おしえる", "oshiete" to "おしえて", "shiraberu" to "しらべる", "shirabete" to "しらべて",
            "tsukuru" to "つくる", "tsukutte" to "つくって", "tsukau" to "つかう", "tsukatte" to "つかって",
            "motsu" to "もつ", "motte" to "もって", "kau" to "かう", "katte" to "かって",
            "omou" to "おもう", "kangaeru" to "かんがえる", "kangaete" to "かんがえて",
            "matsu" to "まつ", "matte" to "まって", "tetsudau" to "てつだう", "naosu" to "なおす", "naoshite" to "なおして",
            "ha" to "は", "ga" to "が", "wo" to "を", "ni" to "に", "de" to "で", "to" to "と", "no" to "の", "mo" to "も",
            "he" to "へ", "kara" to "から", "made" to "まで", "yori" to "より", "dake" to "だけ",
            "nado" to "など", "toka" to "とか", "demo" to "でも", "kedo" to "けど", "node" to "ので",
            "noni" to "のに", "tame" to "ため", "youni" to "ように", "desuka" to "ですか", "desune" to "ですね", "desuyo" to "ですよ"
        )

        private val CORE_ENGLISH_WORDS = listOf(
            "the", "be", "to", "of", "and", "in", "that", "have", "it", "for", "not", "on", "with",
            "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say",
            "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can",
            "like", "time", "no", "just", "him", "know", "take", "people", "into", "year", "your",
            "good", "some", "could", "them", "see", "other", "than", "then", "now", "look", "only",
            "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our",
            "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give",
            "day", "most", "us", "need", "try", "ask", "feel", "become", "leave", "put", "mean",
            "keep", "let", "begin", "seem", "help", "talk", "turn", "start", "might", "show", "hear",
            "play", "run", "move", "live", "believe", "hold", "bring", "happen", "write", "provide",
            "sit", "stand", "lose", "pay", "meet", "meeting", "include", "continue", "set", "learn",
            "change", "lead", "understand", "watch", "follow", "stop", "create", "speak", "read",
            "allow", "add", "spend", "grow", "open", "walk", "win", "offer", "remember", "love",
            "consider", "appear", "buy", "wait", "serve", "die", "send", "expect", "build", "stay",
            "fall", "cut", "reach", "kill", "remain", "suggest", "raise", "pass", "sell", "require",
            "report", "decide", "pull", "today", "tomorrow", "yesterday", "tonight", "morning",
            "afternoon", "evening", "week", "month", "world", "hello", "thanks", "thank", "please",
            "sorry", "welcome", "friend", "home", "house", "room", "school", "office", "park",
            "google", "android", "github", "meltype", "keyboard", "glide", "swipe", "search",
            "project", "code", "test", "commit", "push", "pull", "branch", "merge", "build",
            "app", "application", "system", "data", "file", "folder", "window", "screen", "button",
            "check", "update", "install", "version", "release", "error", "warning", "debug", "log",
            "message", "chat", "email", "mail", "calendar", "schedule", "task", "todo", "note",
            "notes", "music", "video", "photo", "camera", "phone", "mobile", "laptop", "desktop",
            "server", "client", "network", "wifi", "bluetooth", "password", "account", "user",
            "settings", "config", "theme", "light", "dark", "layout", "design", "style", "input",
            "output", "text", "word", "words", "japanese", "english", "romaji", "hiragana", "katakana",
            "kanji", "mozc", "linux", "windows", "macos", "python", "kotlin", "java", "gradle"
        )
    }
}
