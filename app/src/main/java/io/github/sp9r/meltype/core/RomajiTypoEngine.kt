package io.github.sp9r.meltype.core

import io.github.sp9r.meltype.glide.GlideDecoder
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.min

data class RomajiFix(
    val wrong: String,
    val right: String,
    val kana: String,
    val cost: Double = 0.0,
    val isSingleWholeWord: Boolean = false
)

/**
 * Corrects QWERTY romaji mistypes in Japanese input using:
 *  1) A DP kana-lattice segmenter backed by JMdict readings, inflected verb/adjective stems,
 *     and Japanese function words.
 *  2) Single-edit repair for unreadable consonant clusters (skipped vowel, adjacent key hit,
 *     transposition, stray adjacent key press), matching Meltype's typo tolerance model.
 *  3) Single-key neighbor correction for mistyped syllables that remain accidentally readable
 *     (e.g. "arigayou" -> "arigatou", "arigatoi" -> "arigatou", "watashu" -> "watashi").
 */
class RomajiTypoEngine(private val dictionaries: MeltypeDictionaries) {

    private val words = HashSet<String>(36000)
    private val functionWordSet = HashSet<String>(FUNCTION_WORDS.asList())
    private val sortedReadings: Array<String>
    private var maxWordLen = 1

    init {
        for (w in FUNCTION_WORDS) addWord(w)
        for (w in dictionaries.readingsSet) addWord(w)
        for (w in dictionaries.readingToPhrases.keys) addWord(w)
        for (w in dictionaries.extraCandidates.keys) addWord(w)
        for (w in dictionaries.kanaToBrandsAndLoanwords.keys) addWord(w)
        for (rom in dictionaries.japaneseCanonicalWords) {
            val h = RomajiDetector.analyze(rom).finalizedHiragana()
            if (h.isNotEmpty() && RomajiDetector.isHiraganaOnly(h)) addWord(h)
        }
        for ((base, tags) in dictionaries.readingWithTags) {
            addWord(base)
            for (tag in tags) {
                addConjugations(base, tag)
            }
        }
        sortedReadings = words.toTypedArray().also { Arrays.sort(it) }
    }

    private fun addWord(w: String) {
        if (w.isEmpty()) return
        if (words.add(w)) {
            if (w.length > maxWordLen) maxWordLen = w.length
        }
    }

    private fun addConjugations(word: String, tag: String) {
        if (word.length < 2) return
        val stem = word.dropLast(1)
        when (tag) {
            "v1" -> if (word.endsWith("る")) {
                addWord(stem)
                for (end in listOf("ます", "ました", "ません", "て", "た", "ない", "よう", "たい", "れば")) {
                    addWord(stem + end)
                }
            }
            "i" -> if (word.endsWith("い")) {
                for (end in listOf("く", "くて", "かっ", "かった", "くない", "けれ", "さ", "そう", "いです")) {
                    addWord(stem + end)
                }
                if (word == "いい") {
                    for (end in listOf("よく", "よくて", "よかっ", "よかった", "よけれ", "よくない")) addWord(end)
                }
            }
            "vk" -> if (word.endsWith("くる")) {
                val s = word.dropLast(2)
                for (f in listOf("き", "こ", "く", "くれ", "きます", "きました", "きて", "きた", "こない")) {
                    addWord(s + f)
                }
            }
            "vs" -> if (word.endsWith("する")) {
                val s = word.dropLast(2)
                for (f in listOf("し", "さ", "せ", "すれ", "しろ", "します", "しました", "して", "した", "しない")) {
                    addWord(s + f)
                }
            }
            else -> if (tag.startsWith("v5")) {
                val row = V5_ROWS[word.last()] ?: return
                for (kana in row) addWord(stem + kana)
                val onbin = when (tag) {
                    "v5k", "v5g" -> "い"
                    "v5k-s", "v5t", "v5r", "v5u", "v5aru", "v5r-i", "v5u-s" -> "っ"
                    "v5b", "v5m", "v5n" -> "ん"
                    else -> null
                }
                if (onbin != null) {
                    addWord(stem + onbin)
                    if (onbin == "い") {
                        addWord(stem + if (tag == "v5g") "いで" else "いて")
                        addWord(stem + if (tag == "v5g") "いだ" else "いた")
                    } else if (onbin == "っ") {
                        addWord("${stem}って")
                        addWord("${stem}った")
                    } else {
                        addWord("${stem}んで")
                        addWord("${stem}んだ")
                    }
                }
                val masuStem = stem + row[1]
                addWord("${masuStem}ます")
                addWord("${masuStem}ました")
                addWord("${masuStem}ません")
                addWord("${masuStem}たい")
                if (tag == "v5aru") addWord("${stem}い")
            }
        }
    }

    fun isKnownWord(hiragana: String): Boolean = hiragana in words

    fun hasKnownReadingPrefix(hiragana: String): Boolean {
        if (hiragana.isEmpty() || sortedReadings.isEmpty()) return false
        val idx = Arrays.binarySearch(sortedReadings, hiragana)
        if (idx >= 0) return true
        val ins = -idx - 1
        return ins < sortedReadings.size && sortedReadings[ins].startsWith(hiragana)
    }

    fun isKnownReading(kana: String): Boolean {
        if (kana.isEmpty()) return false
        val n = kana.length
        val known = BooleanArray(n + 1)
        known[0] = true
        for (end in 1..n) {
            val limit = min(end, maxWordLen)
            for (len in 1..limit) {
                if (!known[end - len]) continue
                val piece = kana.substring(end - len, end)
                if (piece in words && (len >= 2 || piece in functionWordSet)) {
                    known[end] = true
                    break
                }
            }
        }
        return known[n]
    }

    /**
     * Segments hiragana into known vocabulary & function words, returning (unnaturalnessCost, spanAtEditIsKnown).
     */
    fun segmentKana(kana: String, editKanaAt: Int = -1): Pair<Double, Boolean> {
        val n = kana.length
        if (n == 0) return 0.0 to false
        val cost = DoubleArray(n + 1) { Double.MAX_VALUE }
        val from = IntArray(n + 1) { -1 }
        cost[0] = 0.0

        for (i in 1..n) {
            val limit = min(i, maxWordLen)
            for (len in limit downTo 1) {
                val prevCost = cost[i - len]
                if (prevCost == Double.MAX_VALUE) continue
                val piece = kana.substring(i - len, i)
                val step = when {
                    piece in words -> when {
                        piece in functionWordSet -> 0.72
                        len >= 2 -> 1.0
                        else -> 1.5
                    }
                    len == 1 -> if (piece == "ー" || piece == "っ") 0.5 else 3.0
                    else -> continue
                }
                if (prevCost + step < cost[i]) {
                    cost[i] = prevCost + step
                    from[i] = i - len
                }
            }
        }

        var known = editKanaAt < 0
        var i = n
        while (i > 0 && !known) {
            val prev = from[i]
            if (prev < 0) break
            if (prev <= editKanaAt && editKanaAt < i) {
                val piece = kana.substring(prev, i)
                known = piece in words && (piece.length >= 2 || piece in functionWordSet)
                break
            }
            i = prev
        }
        return cost[n] to known
    }

    /**
     * Returns index in [letters] of the first unmapped consonant cluster or incomplete final consonant,
     * or -1 when the whole string is valid romaji.
     */
    fun firstUnreadableOffset(letters: String, final: Boolean): Int {
        val analysis = RomajiDetector.analyze(letters, strictForDetection = false)
        for (c in analysis.chunks) {
            if (c.rawRomaji.length == 1 && c.kana == c.rawRomaji && c.rawRomaji[0] in 'a'..'z') {
                return c.romajiStart
            }
        }
        if (final && analysis.pendingTail.isNotEmpty() && analysis.pendingTail != "n") {
            return letters.length - analysis.pendingTail.length
        }
        return -1
    }

    /**
     * Attempts automatic 1-edit recovery when [letters] has an unreadable consonant cluster or mistyped key.
     * Protected against rewriting valid English words or incomplete live typing prefixes.
     */
    fun fixUnreadable(
        letters: String,
        final: Boolean,
        spatialWeights: List<Map<Char, Float>> = emptyList()
    ): RomajiFix? {
        val lower = letters.lowercase()
        if (lower.length < 3 || !lower.all { it in 'a'..'z' }) return null
        if (dictionaries.englishAllWords.contains(lower) || dictionaries.properNounCanonical.containsKey(lower)) {
            return null
        }

        val first = firstUnreadableOffset(lower, final)
        if (first < 0) return null

        // Wait until >= 2 characters follow an unreadable consonant while still typing live.
        if (!final && lower.length - first - 1 < 2) return null

        // Trailing extra neighbor consonant (e.g. "yoroshikuy" where 'y' is next to 'u')
        val firstAnalysis = RomajiDetector.analyze(lower, strictForDetection = false)
        if (final && first == lower.length - 1 && firstAnalysis.pendingTail.length == 1) {
            val extra = lower.last()
            val prevChar = lower[lower.length - 2]
            val near = NEIGHBORS[extra].orEmpty()
            if (extra == prevChar || prevChar in near) {
                val trimmed = lower.dropLast(1)
                val kana = RomajiDetector.analyze(trimmed).finalizedHiragana()
                if (kana.length >= 2 && segmentKana(kana, kana.length - 1).second) {
                    return RomajiFix(letters, trimmed, kana, cost = 1.0, isSingleWholeWord = kana in words)
                }
            }
        }

        val candidates = HashMap<String, Pair<Double, Int>>()
        fun consider(fixed: String, editPos: Int, prior: Double) {
            if (firstUnreadableOffset(fixed, final) >= 0) return
            if (dictionaries.englishAllWords.contains(fixed)) return
            val existing = candidates[fixed]
            if (existing == null || prior < existing.first) {
                candidates[fixed] = prior to editPos
            }
        }

        val kStart = maxOf(0, first - 2)
        val kEnd = minOf(lower.length, first + 2)
        for (k in kStart..kEnd) {
            if (k < lower.length) {
                val c = lower[k]
                val near = NEIGHBORS[c]
                // 1. Neighbor key hit (e.g. arigatpu -> arigatou, watasji -> watashi, qrigatou -> arigatou)
                if (near != null) {
                    val spatialForIdx = spatialWeights.getOrNull(k)
                    for (n in near) {
                        val sb = (spatialForIdx?.get(n) ?: 0f).coerceIn(0f, 1f)
                        consider(lower.substring(0, k) + n + lower.substring(k + 1), k, 0.30 - sb * 0.20)
                    }
                }
                // 2. Accidental extra neighbor or doubled key press
                val prev = if (k > 0) lower[k - 1] else '\u0000'
                val next = if (k + 1 < lower.length) lower[k + 1] else '\u0000'
                if (c == prev || (near != null && (prev in near || next in near))) {
                    consider(lower.substring(0, k) + lower.substring(k + 1), k, 0.45)
                }
                // 3. Adjacent transposition (e.g. sumimasne -> sumimasen)
                if (k + 1 < lower.length && lower[k] != lower[k + 1]) {
                    consider(lower.substring(0, k) + lower[k + 1] + lower[k] + lower.substring(k + 2), k, 0.30)
                }
            }
            // 4. Omitted character, especially missing vowel (e.g. onegaishimsu -> onegaishimasu)
            for (n in 'a'..'z') {
                val prior = when (n) {
                    'a' -> 0.24
                    'i', 'u' -> 0.27
                    'e', 'o' -> 0.34
                    else -> 0.78
                }
                consider(lower.substring(0, k) + n + lower.substring(k), k, prior)
            }
        }
        if (candidates.isEmpty()) return null

        val ranked = ArrayList<RomajiFix>(candidates.size)
        for ((candLetters, info) in candidates) {
            val (prior, edit) = info
            val analysis = RomajiDetector.analyze(candLetters, strictForDetection = false)
            val kana = analysis.finalizedHiragana()
            if (kana.isEmpty() || !RomajiDetector.isHiraganaOnly(kana)) continue

            var pos = 0
            var kanaPos = 0
            var editKana = -1
            for (tok in analysis.chunks) {
                if (edit < pos + tok.romajiEnd - tok.romajiStart) {
                    editKana = kanaPos
                    break
                }
                pos += (tok.romajiEnd - tok.romajiStart)
                kanaPos += tok.kana.length
            }
            if (editKana < 0) editKana = (kana.length - 1).coerceAtLeast(0)

            val (segCost, known) = segmentKana(kana, editKana)
            if (!known) continue
            val wholeBonus = if (kana in words) -0.4 else 0.0
            ranked.add(RomajiFix(letters, candLetters, kana, cost = segCost + prior + wholeBonus, isSingleWholeWord = kana in words))
        }
        if (ranked.isEmpty()) return null
        ranked.sortBy { it.cost }

        // Avoid flipping when two distinct kana outcomes tie too closely
        if (ranked.size > 1 && ranked[1].kana != ranked[0].kana && ranked[1].cost - ranked[0].cost < 0.15) {
            return null
        }

        val rawKana = RomajiDetector.analyze(lower, strictForDetection = false).displayKanaOrTail
        val rawCost = rawSegmentPenalty(rawKana)
        if (ranked[0].cost >= rawCost) return null
        return ranked[0]
    }

    private fun endsWithWordPlusParticle(kana: String): Boolean {
        if (kana.length < 3) return false
        for (pLen in 1..2) {
            if (kana.length <= pLen + 1) continue
            val tail = kana.substring(kana.length - pLen)
            if (tail in CORE_PARTICLE_TAILS) {
                val head = kana.substring(0, kana.length - pLen)
                if (head in words || isKnownReading(head)) return true
            }
        }
        return false
    }

    /**
     * Finds correction candidates even when [letters] is accidentally readable romaji
     * (e.g. "arigayou" -> "arigatou", "arigatoi" -> "arigatou", "watashu" -> "watashi", "ohayoi" -> "ohayou").
     */
    fun suggestReadableTypos(
        letters: String,
        currentHira: String,
        allowFinalTailTrim: Boolean,
        spatialWeights: List<Map<Char, Float>> = emptyList()
    ): List<RomajiFix> {
        val lower = letters.lowercase()
        if (lower.length < 4 || !lower.all { it in 'a'..'z' }) return emptyList()
        if (dictionaries.englishAllWords.contains(lower) || dictionaries.properNounCanonical.containsKey(lower)) {
            return emptyList()
        }
        if (currentHira in words || endsWithWordPlusParticle(currentHira)) return emptyList()
        if (!allowFinalTailTrim && hasKnownReadingPrefix(currentHira)) return emptyList()

        val (origCost, _) = segmentKana(currentHira, -1)
        val rawFullyKnown = isKnownReading(currentHira)
        val results = ArrayList<RomajiFix>()
        val seenKana = HashSet<String>()

        fun tryCand(candLetters: String, prior: Double) {
            if (candLetters == lower || firstUnreadableOffset(candLetters, final = true) >= 0) return
            val kana = RomajiDetector.analyze(candLetters, strictForDetection = false).finalizedHiragana()
            if (kana.length < 2 || !RomajiDetector.isHiraganaOnly(kana) || kana in seenKana) return
            val inVocab = kana in words
            val isCovered = inVocab || (!rawFullyKnown && isKnownReading(kana))
            if (!isCovered) return
            val (segCost, _) = segmentKana(kana, -1)
            val effectiveCost = segCost + prior - if (inVocab) 0.55 else 0.0
            val thresholdDelta = if (inVocab && !rawFullyKnown) 0.45 else 0.70
            if (effectiveCost + thresholdDelta < origCost) {
                seenKana.add(kana)
                results.add(RomajiFix(letters, candLetters, kana, effectiveCost, isSingleWholeWord = inVocab))
            }
        }

        // Single QWERTY neighbor replacement or neighbor deletion across the string
        for (i in lower.indices) {
            val c = lower[i]
            val near = NEIGHBORS[c]
            if (near != null) {
                val spatialForIdx = spatialWeights.getOrNull(i)
                for (n in near) {
                    val sb = (spatialForIdx?.get(n) ?: 0f).coerceIn(0f, 1f)
                    tryCand(lower.substring(0, i) + n + lower.substring(i + 1), 0.35 - sb * 0.25)
                }
            }
            // Also try deleting an accidentally doubled/neighbor vowel or trailing extra letter
            if (i > 0) {
                val prev = lower[i - 1]
                val next = if (i + 1 < lower.length) lower[i + 1] else '\u0000'
                if (c == prev || (near != null && (prev in near || next in near))) {
                    tryCand(lower.substring(0, i) + lower.substring(i + 1), 0.65)
                }
            }
        }

        // Dropped trailing 'n' (e.g. "sumimase" -> "sumimasen") or doubled char omission
        if (!lower.endsWith("n")) {
            tryCand("${lower}n", 0.40)
        }
        // Missed consonant before vowel inside short stems? (e.g. "watahi" -> "watashi" by inserting 's' after vowel)
        for (i in 1 until lower.length) {
            val subS = if (lower[i] in VOWELS || lower[i] == 'h') lower.substring(0, i) + 's' + lower.substring(i) else null
            if (subS != null) tryCand(subS, 0.55)
            val subT = if (lower[i] in VOWELS || lower[i] == 's') lower.substring(0, i) + 't' + lower.substring(i) else null
            if (subT != null) tryCand(subT, 0.55)
        }

        results.sortBy { it.cost }
        return results.take(5)
    }

    /**
     * Suggests non-destructive alternate readings for taps that landed near an adjacent key edge
     * (e.g. 'q' near 'a', or 'j' near 'k') without ever overwriting valid words.
     */
    fun suggestSpatialAlternatives(
        letters: String,
        currentHira: String,
        spatialWeights: List<Map<Char, Float>>
    ): List<RomajiFix> {
        val lower = letters.lowercase()
        if (lower.length < 3 || spatialWeights.isEmpty() || !lower.all { it in 'a'..'z' }) return emptyList()
        val results = ArrayList<RomajiFix>()
        val seenKana = HashSet<String>()
        seenKana.add(currentHira)

        val limit = min(lower.length, spatialWeights.size)
        for (i in 0 until limit) {
            val weights = spatialWeights[i]
            if (weights.isEmpty()) continue
            val near = NEIGHBORS[lower[i]] ?: continue
            for ((n, weight) in weights) {
                if (weight < 0.40f || n !in near) continue
                val candLetters = lower.substring(0, i) + n + lower.substring(i + 1)
                if (firstUnreadableOffset(candLetters, final = true) >= 0) continue
                val kana = RomajiDetector.analyze(candLetters, strictForDetection = false).finalizedHiragana()
                if (kana.length < 2 || !RomajiDetector.isHiraganaOnly(kana) || kana in seenKana) continue
                val inVocab = kana in words
                if (!inVocab && !isKnownReading(kana)) continue
                val (segCost, _) = segmentKana(kana, -1)
                if (!inVocab && segCost > 2.2) continue
                seenKana.add(kana)
                results.add(
                    RomajiFix(
                        wrong = letters,
                        right = candLetters,
                        kana = kana,
                        cost = segCost - weight.toDouble() - if (inVocab) 0.5 else 0.0,
                        isSingleWholeWord = inVocab
                    )
                )
            }
        }
        results.sortBy { it.cost }
        return results.take(3)
    }

    private fun rawSegmentPenalty(rawText: String): Double {
        if (rawText.isEmpty()) return 0.0
        var asciiUnmapped = 0
        val hiraOnly = StringBuilder(rawText.length)
        for (ch in rawText) {
            if (ch in '\u3041'..'\u309F' || ch == 'ー') {
                hiraOnly.append(ch)
            } else {
                asciiUnmapped++
            }
        }
        val (hiraCost, _) = segmentKana(hiraOnly.toString(), -1)
        return hiraCost + asciiUnmapped * 3.0
    }

    companion object {
        private val VOWELS = setOf('a', 'i', 'u', 'e', 'o')

        private val NEIGHBORS = mapOf(
            'q' to "wa", 'w' to "qeas", 'e' to "wrsd", 'r' to "etdf", 't' to "ryfg",
            'y' to "tugh", 'u' to "yihj", 'i' to "uojk", 'o' to "ipkl", 'p' to "ol",
            'a' to "qwsz", 's' to "weadzx", 'd' to "erfsxc", 'f' to "rtdgcv", 'g' to "tyfhvb",
            'h' to "yugjbn", 'j' to "uihknm", 'k' to "iojlm", 'l' to "opk",
            'z' to "asx", 'x' to "zsdc", 'c' to "xdfv", 'v' to "cfgb", 'b' to "vghn",
            'n' to "bhjm", 'm' to "njk"
        )

        private val V5_ROWS = mapOf(
            'う' to "わいえお", 'く' to "かきけこ", 'ぐ' to "がぎげご", 'す' to "さしせそ",
            'つ' to "たちてと", 'ぬ' to "なにねの", 'ぶ' to "ばびべぼ", 'む' to "まみめも",
            'る' to "らりれろ"
        )

        private val CORE_PARTICLE_TAILS = setOf(
            "は", "が", "を", "に", "で", "と", "も", "へ", "の", "や", "か", "ね", "よ", "な", "わ", "から", "まで", "より", "です", "ます"
        )

        private val FUNCTION_WORDS = arrayOf(
            "は", "が", "を", "に", "で", "と", "も", "へ", "の", "や", "か", "ね", "よ", "な", "わ", "さ", "ぞ", "ぜ",
            "だ", "です", "でし", "でしょ", "ます", "まし", "ませ", "ましょ", "た", "て", "だっ", "ない", "なかっ", "なく", "なけれ",
            "たい", "たく", "たかっ", "れる", "れ", "られ", "られる", "せる", "せ", "させ", "させる", "ば", "う", "よう", "ん",
            "し", "する", "しろ", "すれ", "して", "した", "しない", "します", "しました", "しません", "しましょう",
            "こと", "もの", "ので", "から", "まで", "より", "けど", "けれど",
            "ながら", "って", "という", "でも", "ください", "くださ", "ちゃ", "じゃ", "じゃない", "ちゃう",
            "いる", "い", "いた", "いて", "います", "いました", "いない",
            "ある", "あり", "あっ", "おり", "おる", "あります", "ありました",
            "なる", "なり", "なっ", "なります", "なりました", "できる", "でき", "できます", "できました",
            "ござい", "ございます", "だけ", "しか", "ほど", "くらい", "ぐらい", "など", "なら",
            "たら", "だら", "たり", "だり", "ずつ", "そう", "らしい", "みたい", "ほう", "とき", "ところ", "ため", "はず", "わけ",
            "ました", "ません", "ましょう", "ませんでした", "でした", "でしょう", "ている", "ていた", "てい", "てる", "てた", "ちゃった",
            "なかった", "たかった", "ないで", "なくて", "られた", "れた", "でしょうか", "ですか", "ますか", "ですね", "ますね", "ですよ", "ますよ"
        )
    }
}
