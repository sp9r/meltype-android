package io.github.sp9r.meltype.core

data class RomajiChunk(
    val romajiStart: Int,
    val romajiEnd: Int,
    val rawRomaji: String,
    val kana: String
)

data class RomajiAnalysis(
    val isStrictValid: Boolean,
    val chunks: List<RomajiChunk>,
    val hiragana: String,
    val pendingTail: String,
    val invalidReason: String?,
    val strongYouon: Int,
    val tsuCount: Int,
    val sokuonCount: Int,
    val longVowels: Int
) {
    val displayKanaOrTail: String
        get() = hiragana + pendingTail

    fun finalizedHiragana(): String {
        val tail = if (pendingTail == "n") "ん" else pendingTail
        return hiragana + tail
    }
}

/**
 * Romaji-to-hiragana converter and Japanese/English phonological evaluator.
 */
object RomajiDetector {

    private val FULL_TABLE: LinkedHashMap<String, String> = linkedMapOf(
        // 4-char sequences
        "ltsu" to "っ",
        "xtsu" to "っ",
        "ccha" to "っちゃ",
        "cchu" to "っちゅ",
        "ccho" to "っちょ",
        "ssha" to "っしゃ",
        "sshu" to "っしゅ",
        "ssho" to "っしょ",
        "tchi" to "っち",

        // 3-char sequences
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ", "kyi" to "きぃ", "kye" to "きぇ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ", "gyi" to "ぎぃ", "gye" to "ぎぇ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ", "shi" to "し", "she" to "しぇ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ", "syi" to "しぃ", "sye" to "しぇ",
        "zha" to "じゃ", "zhu" to "じゅ", "zho" to "じょ", "zhe" to "じぇ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ", "zyi" to "じぃ", "zye" to "じぇ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ", "jyi" to "じぃ", "jye" to "じぇ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ", "chi" to "ち", "che" to "ちぇ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ", "tyi" to "ちぃ", "tye" to "ちぇ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cyo" to "ちょ", "cyi" to "ちぃ", "cye" to "ちぇ",
        "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
        "dha" to "でゃ", "dhi" to "でぃ", "dhu" to "でゅ", "dhe" to "でぇ", "dho" to "でょ",
        "dya" to "ぢゃ", "dyi" to "ぢぃ", "dyu" to "ぢゅ", "dye" to "ぢぇ", "dyo" to "ぢょ",
        "tsa" to "つぁ", "tsi" to "つぃ", "tsu" to "つ", "tse" to "つぇ", "tso" to "つぉ",
        "twa" to "とぁ", "twi" to "とぃ", "twu" to "とぅ", "twe" to "とぇ", "two" to "とぉ",
        "dwa" to "どぁ", "dwi" to "どぃ", "dwu" to "どぅ", "dwe" to "どぇ", "dwo" to "どぉ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ", "nyi" to "にぃ", "nye" to "にぇ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ", "hyi" to "ひぃ", "hye" to "ひぇ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ", "byi" to "びぃ", "bye" to "びぇ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ", "pyi" to "ぴぃ", "pye" to "ぴぇ",
        "fya" to "ふゃ", "fyu" to "ふゅ", "fyo" to "ふょ", "fyi" to "ふぃ", "fye" to "ふぇ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ", "myi" to "みぃ", "mye" to "みぇ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ", "ryi" to "りぃ", "rye" to "りぇ",
        "vya" to "ヴゃ", "vyu" to "ヴゅ", "vyo" to "ヴょ", "vyi" to "ヴぃ", "vye" to "ヴぇ",
        "wha" to "うぁ", "whi" to "うぃ", "whu" to "う", "whe" to "うぇ", "who" to "うぉ",
        "kwa" to "くぁ", "kwi" to "くぃ", "kwu" to "くぅ", "kwe" to "くぇ", "kwo" to "くぉ",
        "gwa" to "ぐぁ", "gwi" to "ぐぃ", "gwu" to "ぐぅ", "gwe" to "ぐぇ", "gwo" to "ぐぉ",
        "qwa" to "くぁ", "qwi" to "くぃ", "qwu" to "くぅ", "qwe" to "くぇ", "qwo" to "くぉ",
        "qya" to "くゃ", "qyu" to "くゅ", "qyo" to "くょ",
        "swa" to "すぁ", "swi" to "すぃ", "swu" to "すぅ", "swe" to "すぇ", "swo" to "すぉ",
        "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ", "lwa" to "ゎ", "lka" to "ヵ", "lke" to "ヶ", "ltu" to "っ",
        "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ", "xwa" to "ゎ", "xka" to "ヵ", "xke" to "ヶ", "xtu" to "っ",

        // 2-char sequences
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ca" to "か", "cu" to "く", "co" to "こ", "ci" to "し", "ce" to "せ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "sa" to "さ", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ja" to "じゃ", "ji" to "じ", "ju" to "じゅ", "je" to "じぇ", "jo" to "じょ",
        "ta" to "た", "ti" to "ち", "tu" to "つ", "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "fu" to "ふ", "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yi" to "い", "yu" to "ゆ", "ye" to "いぇ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "wa" to "わ", "wi" to "うぃ", "wu" to "う", "we" to "うぇ", "wo" to "を",
        "va" to "ヴぁ", "vi" to "ヴぃ", "vu" to "ヴ", "ve" to "ヴぇ", "vo" to "ヴぉ",
        "qa" to "くぁ", "qi" to "くぃ", "qu" to "く", "qe" to "くぇ", "qo" to "くぉ",
        "nn" to "ん", "xn" to "ん", "n'" to "ん",

        // 1-char vowels & punctuation
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "-" to "ー"
    )

    private val STRICT_DISALLOWED_SINGLE_LETTERS = setOf('l', 'q', 'v', 'x')
    private val VOWELS = setOf('a', 'i', 'u', 'e', 'o')

    private val STRICT_YOUON_PREFIXES = setOf(
        "kya", "kyu", "kyo", "gya", "gyu", "gyo",
        "sha", "shu", "sho", "sya", "syu", "syo",
        "cha", "chu", "cho", "tya", "tyu", "tyo", "cya", "cyu", "cyo",
        "nya", "nyu", "nyo", "hya", "hyu", "hyo",
        "bya", "byu", "byo", "pya", "pyu", "pyo",
        "mya", "myu", "myo", "rya", "ryu", "ryo",
        "ja", "ju", "jo", "jya", "jyu", "jyo", "zya", "zyu", "zyo"
    )

    private val ALL_KEYS_PREFIX_SET: Set<String> by lazy {
        buildSet {
            for (k in FULL_TABLE.keys) {
                for (len in 1..k.length) {
                    add(k.substring(0, len))
                }
            }
        }
    }

    /**
     * Converts romaji input into hiragana chunks with offset tracking.
     * Supports standard Hepburn, Kunrei, small kana, sokuon, n->ん, and trailing partial consonant.
     */
    fun analyze(input: String, strictForDetection: Boolean = false): RomajiAnalysis {
        val raw = input.lowercase()
        val chunks = ArrayList<RomajiChunk>()
        val kanaOut = StringBuilder()
        var strictValid = true
        var invalidReason: String? = null
        var strongYouon = 0
        var tsuCount = 0
        var sokuonCount = 0
        var longVowels = 0

        var i = 0
        while (i < raw.length) {
            val ch = raw[i]

            if (strictForDetection && ch in STRICT_DISALLOWED_SINGLE_LETTERS) {
                strictValid = false
                invalidReason = "ローマ字に通常使わない文字 ($ch)"
            }

            // Explicit n' / nn / xn
            if (i + 1 < raw.length && raw.startsWith("n'", i)) {
                chunks.add(RomajiChunk(i, i + 2, raw.substring(i, i + 2), "ん"))
                kanaOut.append("ん")
                i += 2
                continue
            }

            // Long table match (4 -> 3 -> 2 -> 1)
            var matchedLen = 0
            var matchedKana: String? = null
            val maxLen = minOf(4, raw.length - i)
            for (len in maxLen downTo 1) {
                val sub = raw.substring(i, i + len)
                val kana = FULL_TABLE[sub]
                if (kana != null) {
                    if (strictForDetection && sub.firstOrNull() in STRICT_DISALLOWED_SINGLE_LETTERS && sub != "-") {
                        strictValid = false
                        invalidReason = "英語・記号寄り綴り ($sub)"
                    }
                    matchedLen = len
                    matchedKana = kana
                    break
                }
            }

            if (matchedKana != null) {
                val sub = raw.substring(i, i + matchedLen)
                // Handle single 'n' when followed by something not matching n* table:
                // Actually FULL_TABLE only has nn/n'/na/ni/nu/ne/no/nya/nyu/nyo/nyi/nye.
                // So a single 'n' won't match FULL_TABLE directly!
                if (sub in STRICT_YOUON_PREFIXES) strongYouon++
                if (sub == "tsu") tsuCount++
                chunks.add(RomajiChunk(i, i + matchedLen, sub, matchedKana))
                kanaOut.append(matchedKana)
                if (kanaOut.length >= 2) {
                    val last2 = kanaOut.substring(kanaOut.length - 2)
                    if (last2 in setOf("おう", "こう", "そう", "とう", "のう", "ほう", "もう", "よう", "ろう", "ごう", "ぞう", "どう", "ぼう", "ぽう", "うう", "くう", "すう", "つう", "ぬう", "ふう", "むう", "ゆう", "るう", "きゅう", "しゅう", "ちゅう", "にゅう", "ひゅう", "みゅう", "りゅう", "じゅう", "ぎゅう", "びゅう", "ぴゅう", "きょう", "しょう", "ちょう", "にょう", "ひょう", "みょう", "りょう", "じょう", "ぎょう", "びょう", "ぴょう")) {
                        longVowels++
                    }
                }
                i += matchedLen
                continue
            }

            // Special rule for 'n': followed by a consonant (not vowel, not 'y', not 'n', not '\'')
            if (ch == 'n') {
                if (i + 1 >= raw.length) {
                    // Trailing single 'n': keep as pendingTail
                    break
                }
                val next = raw[i + 1]
                if (next !in VOWELS && next != 'y' && next != 'n' && next != '\'') {
                    if (next in 'a'..'z') {
                        chunks.add(RomajiChunk(i, i + 1, "n", "ん"))
                        kanaOut.append("ん")
                        i += 1
                        continue
                    } else {
                        chunks.add(RomajiChunk(i, i + 1, "n", "ん"))
                        kanaOut.append("ん")
                        i += 1
                        continue
                    }
                }
            }

            // Bilabial 'm' before b/p/m acts as moraic nasal 'ん' (e.g. shimbun, sampo, tempura, ammari)
            if (ch == 'm' && i + 1 < raw.length && raw[i + 1] in charArrayOf('b', 'p', 'm')) {
                chunks.add(RomajiChunk(i, i + 1, "m", "ん"))
                kanaOut.append("ん")
                i += 1
                continue
            }

            // Sokuon (double consonant like kk, tt, ss, pp, cc, tch)
            if (i + 1 < raw.length) {
                val next = raw[i + 1]
                if (ch in 'a'..'z' && ch !in VOWELS && ch != 'n' && (ch == next || (ch == 't' && raw.startsWith("tch", i)))) {
                    chunks.add(RomajiChunk(i, i + 1, ch.toString(), "っ"))
                    kanaOut.append("っ")
                    sokuonCount++
                    i += 1
                    continue
                }
            }

            // Could this be an incomplete prefix at the end of string? (e.g. "k", "sh", "ts", "ch", "ky")
            val rem = raw.substring(i)
            if (rem.length <= 3 && rem in ALL_KEYS_PREFIX_SET) {
                return RomajiAnalysis(
                    isStrictValid = strictValid,
                    chunks = chunks,
                    hiragana = kanaOut.toString(),
                    pendingTail = rem,
                    invalidReason = invalidReason,
                    strongYouon = strongYouon,
                    tsuCount = tsuCount,
                    sokuonCount = sokuonCount,
                    longVowels = longVowels
                )
            }

            // Invalid consonant cluster or non-romaji character
            strictValid = false
            if (invalidReason == null) {
                invalidReason = "ローマ字として成立しない並び ($rem)"
            }
            // Keep consuming unmapped char verbatim as chunk so offsets remain monotonic
            chunks.add(RomajiChunk(i, i + 1, ch.toString(), ch.toString()))
            kanaOut.append(ch)
            i += 1
        }

        val pending = if (i < raw.length) raw.substring(i) else ""
        return RomajiAnalysis(
            isStrictValid = strictValid,
            chunks = chunks,
            hiragana = kanaOut.toString(),
            pendingTail = pending,
            invalidReason = invalidReason,
            strongYouon = strongYouon,
            tsuCount = tsuCount,
            sokuonCount = sokuonCount,
            longVowels = longVowels
        )
    }

    /**
     * Converts c-row (ca/cu/co -> ka/ku/ko) for dictionary lookups while preserving raw input for English check.
     */
    fun readCRow(letters: String): String {
        val sb = StringBuilder(letters.length)
        var i = 0
        while (i < letters.length) {
            val c = letters[i]
            if (c == 'c' && i + 1 < letters.length && letters[i + 1] in charArrayOf('a', 'u', 'o')) {
                sb.append('k')
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun toKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length)
        for (ch in hiragana) {
            if (ch in '\u3041'..'\u3096') {
                sb.append((ch.code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun toHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in '\u30A1'..'\u30F6') {
                sb.append((ch.code - 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun isHiraganaOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        return text.all { it in '\u3041'..'\u309F' || it == 'ー' }
    }

    /**
     * Generates common Hepburn/Kunrei spelling variants for dictionary words (shi/si, chi/ti, tsu/tu, fu/hu, ji/zi).
     */
    fun spellingVariants(hepburn: String): List<String> {
        val base = hepburn.lowercase().trim()
        if (base.isEmpty()) return emptyList()
        val variants = linkedSetOf(base)

        fun applyAll(src: String, target: String, repl: String): List<String> {
            val out = mutableListOf(src)
            if (src.contains(target)) out.add(src.replace(target, repl))
            return out
        }

        val queue = mutableListOf(base)
        val rules = listOf(
            "shi" to "si",
            "chi" to "ti",
            "tsu" to "tu",
            "fu" to "hu",
            "ji" to "zi",
            "sha" to "sya",
            "shu" to "syu",
            "sho" to "syo",
            "cha" to "tya",
            "chu" to "tyu",
            "cho" to "tyo",
            "ja" to "zya",
            "ju" to "zyu",
            "jo" to "zyo"
        )
        for ((from, to) in rules) {
            val snap = variants.toList()
            for (v in snap) {
                for (nv in applyAll(v, from, to)) {
                    if (variants.size < 16) variants.add(nv)
                }
            }
        }
        // Single n before consonant -> also allow nn variant
        if (base.contains("nn")) {
            variants.add(base.replace("nn", "n"))
        }
        return variants.toList()
    }
}
