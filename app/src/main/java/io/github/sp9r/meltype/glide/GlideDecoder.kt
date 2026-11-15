package io.github.sp9r.meltype.glide

import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

data class GlideCandidate(
    val word: String,
    val isEnglish: Boolean,
    val scoreCost: Float
)

/**
 * Dual-language (English + Japanese Romaji) QWERTY gesture/glide decoder.
 * Indexed by (firstChar, lastChar) buckets with start/end proximity, M=32 shape distance,
 * double-letter collapsed shape matching, arc-length fit, anchor key consistency, and frequency priors.
 */
class GlideDecoder(private val dictionaries: MeltypeDictionaries) {

    private data class LexiconEntry(
        val word: String,
        val isEnglish: Boolean,
        val freqBonus: Float,
        val template: FloatArray,
        val arcLen: Float,
        val collapsedTemplate: FloatArray?,
        val collapsedArcLen: Float
    )

    // 26 x 26 bucket index keyed by (firstChar - 'a') * 26 + (lastChar - 'a')
    private val buckets = Array<MutableList<LexiconEntry>>(26 * 26) { ArrayList() }

    init {
        val seen = HashMap<String, LexiconEntry>(32000)

        fun register(rawWord: String, isEnglish: Boolean, freqBonus: Float) {
            val w = rawWord.lowercase().trim()
            if (w.length < 2 || w.length > 18) return
            if (!w.all { it in 'a'..'z' }) return

            val existing = seen[w]
            if (existing != null) {
                if (freqBonus > existing.freqBonus) {
                    // Upgrade priority in-place by replacing entry in bucket
                    val idx = (w.first() - 'a') * 26 + (w.last() - 'a')
                    val list = buckets[idx]
                    val pos = list.indexOfFirst { it.word == w }
                    val updated = existing.copy(
                        freqBonus = freqBonus,
                        isEnglish = existing.isEnglish && isEnglish
                    )
                    if (pos >= 0) list[pos] = updated
                    seen[w] = updated
                }
                return
            }

            val (tpl, arc) = KeyLayoutGeometry.templateForWord(w)
            val collapsed = KeyLayoutGeometry.collapseConsecutiveLetters(w)
            val (cTpl, cArc) = if (collapsed != w && collapsed.length >= 2) {
                KeyLayoutGeometry.templateForWord(collapsed)
            } else {
                null to 0f
            }

            val entry = LexiconEntry(
                word = w,
                isEnglish = isEnglish,
                freqBonus = freqBonus,
                template = tpl,
                arcLen = arc,
                collapsedTemplate = cTpl,
                collapsedArcLen = cArc
            )
            seen[w] = entry
            val idx = (w.first() - 'a') * 26 + (w.last() - 'a')
            buckets[idx].add(entry)
        }

        // Tier 1: Core high-frequency Japanese romaji words, greetings & particles combinations
        for (jp in CORE_JAPANESE_ROMAJI) {
            register(jp, isEnglish = false, freqBonus = 0.28f)
        }
        for (jp in dictionaries.japaneseCanonicalWords) {
            register(jp, isEnglish = false, freqBonus = 0.24f)
            for (p in TOP_PARTICLES) {
                if (jp.length + p.length <= 14) {
                    register(jp + p, isEnglish = false, freqBonus = 0.17f)
                }
            }
        }
        // Core stem + particle compounds
        for (stem in CORE_PARTICLE_STEMS) {
            for (p in TOP_PARTICLES) {
                register(stem + p, isEnglish = false, freqBonus = 0.25f)
            }
        }

        // Expand readings.txt (2..6 hiragana chars) & phrases.txt into romaji glide vocabulary
        for (hira in dictionaries.readingToPhrases.keys) {
            kanaToRomaji(hira)?.let { r -> register(r, isEnglish = false, freqBonus = 0.16f) }
        }
        for (hira in dictionaries.readingsSet) {
            if (hira.length in 2..6) {
                kanaToRomaji(hira)?.let { r -> register(r, isEnglish = false, freqBonus = 0.11f) }
            }
        }

        // Tier 1 English: Proper nouns, brands, tech terms, common everyday words
        for (w in CORE_ENGLISH_WORDS) {
            register(w, isEnglish = true, freqBonus = 0.28f)
        }
        for (w in dictionaries.properNounCanonical.keys) {
            register(w, isEnglish = true, freqBonus = 0.26f)
        }
        for (w in dictionaries.commonLatinWordsForGlide) {
            val isJpOrigin = w in dictionaries.japaneseOriginEnglishWords
            register(w, isEnglish = !isJpOrigin, freqBonus = if (isJpOrigin) 0.10f else 0.20f)
        }

        // Tier 3 English: General 39k English dictionary (short-to-medium length words for glide)
        for (w in dictionaries.englishAllWords.allWords()) {
            if (w.length in 3..14) {
                register(w, isEnglish = true, freqBonus = 0.0f)
            }
        }
    }

    /**
     * Decodes normalized isotropic touch points into ranked dual-language glide candidates.
     */
    fun decode(normalizedPoints: List<TouchPoint>, maxResults: Int = 8): List<GlideCandidate> {
        if (normalizedPoints.size < 2) return emptyList()

        val (path, pathLen) = KeyLayoutGeometry.resamplePolyline(normalizedPoints)
        if (pathLen < 0.08f) return emptyList()

        val startX = normalizedPoints.first().x
        val startY = normalizedPoints.first().y
        val endX = normalizedPoints.last().x
        val endY = normalizedPoints.last().y

        val startKeys = KeyLayoutGeometry.nearestKeys(startX, startY, limit = 3)
        val endKeys = KeyLayoutGeometry.nearestKeys(endX, endY, limit = 3)

        val candidates = ArrayList<GlideCandidate>(24)
        val n = KeyLayoutGeometry.RESAMPLE_N
        val nFloat = n.toFloat()

        for ((sChar, sDist) in startKeys) {
            if (sDist > 0.65f && startKeys.first().first != sChar) continue
            val sIdx = (sChar - 'a') * 26
            for ((eChar, eDist) in endKeys) {
                if (eDist > 0.65f && endKeys.first().first != eChar) continue
                val bucket = buckets[sIdx + (eChar - 'a')]
                for (entry in bucket) {
                    // Quick length sanity filter: don't match 12-char zig-zag to short straight swipe and vice versa
                    val lenDiff = abs(pathLen - entry.arcLen)
                    val maxLenBound = maxOf(pathLen, entry.arcLen) * 0.72f + 0.55f
                    if (lenDiff > maxLenBound) continue

                    val endpointCost = sDist * 1.35f + eDist * 1.35f

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

                    // Also compare collapsed double-letter shape so fast glides through 'oo'/'kk'/'ee' still win
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
                            effArcDiff = min(effArcDiff, abs(pathLen - entry.collapsedArcLen) + 0.05f)
                        }
                    }

                    val totalCost = endpointCost + shapeDist * 2.15f + effArcDiff * 0.25f - entry.freqBonus
                    if (totalCost < 1.85f) {
                        insertTopK(candidates, GlideCandidate(entry.word, entry.isEnglish, totalCost), limit = 32)
                    }
                }
            }
        }

        // Syllable lattice fallback: if trajectory anchors form valid romaji not present in lexicon, offer as fallback
        val anchors = KeyLayoutGeometry.extractAnchorKeys(normalizedPoints)
        if (anchors.size >= 2) {
            val anchorStr = anchors.joinToString("")
            val analysis = RomajiDetector.analyze(anchorStr, strictForDetection = true)
            if (analysis.isStrictValid && analysis.pendingTail.isEmpty() && candidates.none { it.word == anchorStr }) {
                candidates.add(GlideCandidate(anchorStr, isEnglish = false, scoreCost = 1.45f))
            }
        }

        candidates.sortBy { it.scoreCost }
        return candidates.distinctBy { it.word }.take(maxResults)
    }

    private fun insertTopK(list: MutableList<GlideCandidate>, item: GlideCandidate, limit: Int) {
        if (list.size < limit) {
            list.add(item)
            return
        }
        var maxIdx = 0
        var maxCost = list[0].scoreCost
        for (i in 1 until list.size) {
            if (list[i].scoreCost > maxCost) {
                maxCost = list[i].scoreCost
                maxIdx = i
            }
        }
        if (item.scoreCost < maxCost) {
            list[maxIdx] = item
        }
    }

    companion object {
        private val KANA_TO_ROMAJI = mapOf(
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

        fun kanaToRomaji(hira: String): String? {
            if (hira.isEmpty()) return null
            val sb = StringBuilder(hira.length * 2)
            var i = 0
            while (i < hira.length) {
                val ch = hira[i]
                if (ch == 'っ') {
                    val nextRom = if (i + 1 < hira.length) {
                        val two = if (i + 2 < hira.length) KANA_TO_ROMAJI[hira.substring(i + 1, i + 3)] else null
                        two ?: KANA_TO_ROMAJI[hira[i + 1].toString()]
                    } else null
                    val c = nextRom?.firstOrNull() ?: return null
                    if (c !in listOf('a', 'i', 'u', 'e', 'o', 'n')) {
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
                    val rom = KANA_TO_ROMAJI[pair]
                    if (rom != null) {
                        sb.append(rom)
                        i += 2
                        continue
                    }
                }
                val single = KANA_TO_ROMAJI[ch.toString()] ?: return null
                sb.append(single)
                i++
            }
            return sb.toString()
        }

        private val TOP_PARTICLES = listOf("ha", "ga", "wo", "ni", "de", "to", "no", "mo")

        private val CORE_PARTICLE_STEMS = listOf(
            "kyou", "ashita", "kinou", "watashi", "kore", "sore", "are", "koko", "soko", "asoko",
            "dare", "nani", "itsu", "doko", "kaigi", "kadai", "jugyou", "kensaku", "tsuika",
            "kakunin", "henkou", "henshin", "soushin", "hozon", "sakujo", "nihongo", "eigo",
            "denwa", "densha", "jikan", "basho", "shigoto", "kenkyuu", "kaihatsu", "jissou",
            "pasokon", "sumaho", "apuri", "meiru", "meeru", "minna", "sensei", "tomodachi"
        )

        private val CORE_JAPANESE_ROMAJI = listOf(
            "kyou", "kyouha", "ashita", "ashitaha", "ashitano", "kinou", "ima", "ato", "mae", "tsugi",
            "watashi", "watashiha", "anata", "kare", "kanojo", "minna", "hito",
            "arigatou", "ohayou", "konnichiwa", "konbanwa", "oyasumi", "sumimasen", "gomennasai",
            "onegai", "onegaishimasu", "yoroshiku", "otsukaresama", "daijoubu", "shitsurei",
            "desu", "masu", "mashita", "masen", "deshita", "suru", "shita", "shimasu", "shite",
            "dekimasu", "dekiru", "arimasu", "imasu", "narimasu", "wakarimashita", "wakarimasu",
            "kensaku", "dekensaku", "tsuika", "wotsuika", "kakunin", "henkou", "sakujo", "hozon",
            "soushin", "henshin", "renraku", "shousai", "yotei", "kaigi", "shiryou", "kadai",
            "jugyou", "daigaku", "kenkyuu", "kaihatsu", "jissou", "settei", "jikkou", "saikidou",
            "nihongo", "eigo", "henkan", "nyuuryoku", "kiiboodo", "sumaho", "pasokon", "apuri",
            "gamen", "botan", "rireki", "kono", "sono", "ano", "dono", "kore", "sore", "are", "dore",
            "koko", "soko", "asoko", "doko", "nan", "nani", "dare", "itsu", "naze", "dou",
            "hontou", "sugoi", "yabai", "kawaii", "tanoshii", "ureshii", "tsukareta", "nemui",
            "onaka", "gohan", "taberu", "nomu", "kaeru", "iku", "kuru", "miru", "kiku", "kaku", "yomu",
            "hanasu", "oshieru", "shiraberu", "tsukuru", "tsukau", "motsu", "katsu", "kau", "omou",
            "ha", "ga", "wo", "ni", "de", "to", "no", "mo", "he", "kara", "made", "yori", "dake",
            "nado", "toka", "demo", "kedo", "node", "noni", "tame", "youni"
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
