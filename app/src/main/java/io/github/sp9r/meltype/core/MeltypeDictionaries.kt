package io.github.sp9r.meltype.core

import android.content.Context
import java.io.File
import java.text.Normalizer
import java.util.Arrays

interface DictionarySource {
    fun readText(fileName: String): String?
}

class AssetDictionarySource(private val context: Context) : DictionarySource {
    override fun readText(fileName: String): String? {
        return runCatching {
            context.assets.open("dictionaries/$fileName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }
}

class FileDictionarySource(private val dir: File) : DictionarySource {
    override fun readText(fileName: String): String? {
        val f = File(dir, fileName)
        return if (f.isFile) f.readText(Charsets.UTF_8) else null
    }
}

/**
 * Compact sorted string array with O(log N) exact lookup and prefix lookup.
 */
class SortedWordList(words: Collection<String>) {
    private val items: Array<String> = words
        .asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
        .toList()
        .toTypedArray()

    val size: Int get() = items.size

    fun allWords(): Array<String> = items

    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        return Arrays.binarySearch(items, word.lowercase()) >= 0
    }

    fun hasPrefix(prefix: String): Boolean {
        if (prefix.isEmpty() || items.isEmpty()) return false
        val target = prefix.lowercase()
        val idx = Arrays.binarySearch(items, target)
        if (idx >= 0) return true
        val insert = -idx - 1
        return insert < items.size && items[insert].startsWith(target)
    }
}

data class PhraseEntry(
    val reading: String,
    val surface: String,
    val headOnly: Boolean,
    val clauses: List<ConversionClause>
)

class MeltypeDictionaries(source: DictionarySource) {
    val englishFocusedWords: SortedWordList
    val englishAllWords: SortedWordList
    val englishReadableWords: SortedWordList
    val englishCommonWords: Set<String>
    val englishTechAndAcronymWords: Set<String>
    val glideBrandLoanwords: Set<String>
    val countryWords: Set<String>
    val countryWordList: SortedWordList
    val englishGlossFreq: Map<String, Int>
    val japaneseWords: SortedWordList
    val japaneseCanonicalWords: List<String>
    val japaneseOriginEnglishWords: Set<String>
    val japaneseNameReadings: Set<String>
    val properNounCanonical: Map<String, String>
    val kanaToBrandsAndLoanwords: Map<String, List<String>>
    val phraseEntries: Map<String, PhraseEntry>
    val readingToPhrases: Map<String, String>
    val extraCandidates: Map<String, List<String>>
    val emojiCandidates: Map<String, List<String>>
    val englishEmojiCandidates: Map<String, List<String>>
    val kaomojiList: List<String>
    val misspellingExact: Map<String, String>
    val misspellingRightSet: Set<String>
    val misspellingByShape: Map<String, List<String>>
    val readingsSet: Set<String>
    val readingWithTags: List<Pair<String, List<String>>>
    val commonLatinWordsForGlide: List<String>
    val loadedFileCount: Int

    init {
        var count = 0

        fun readLines(name: String): List<String> {
            val txt = source.readText(name) ?: return emptyList()
            count++
            return txt.lines()
        }

        // 1. english.txt (space-separated, plus section category tracking)
        val engFocused = HashSet<String>()
        val jpOrigin = HashSet<String>()
        val engCommon = HashSet<String>()
        val engTechOrAcronym = HashSet<String>()
        var inJpOrigin = false
        var inTechOrAcronym = false
        for (rawLine in readLines("english.txt")) {
            if (rawLine.startsWith("# ---")) {
                inJpOrigin = rawLine.contains("日本語由来")
                inTechOrAcronym = rawLine.contains("技術用語") ||
                    rawLine.contains("略語・ゲームの名前") ||
                    rawLine.contains("テスターのチャット")
                continue
            }
            val line = stripHashComment(rawLine)
            if (line.isEmpty()) {
                if (inJpOrigin) inJpOrigin = false
                continue
            }
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                engFocused.add(lower)
                if (inJpOrigin) {
                    jpOrigin.add(lower)
                } else if (inTechOrAcronym) {
                    engTechOrAcronym.add(lower)
                } else {
                    engCommon.add(lower)
                }
            }
        }
        japaneseOriginEnglishWords = jpOrigin
        englishCommonWords = engCommon
        englishTechAndAcronymWords = engTechOrAcronym

        // 2. propernouns.txt (case-preserved)
        val properMap = LinkedHashMap<String, String>()
        for (rawLine in readLines("propernouns.txt")) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                if (lower.isNotEmpty()) {
                    properMap.putIfAbsent(lower, tok)
                    engFocused.add(lower)
                    val stripped = lower.filterNot { it in "-.'/" }
                    if (stripped != lower && stripped.length in 3..18 && stripped.all { it in 'a'..'z' }) {
                        properMap.putIfAbsent(stripped, tok)
                        engFocused.add(stripped)
                    }
                }
            }
        }
        // 2a. countries.txt (CLDR country/region words; skip romaji-valid ones like Oman/San from proper-noun forcing)
        val countrySet = LinkedHashSet<String>()
        for (rawLine in readLines("countries.txt")) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) {
                if (tok.length < 3) continue
                val asciiTok = Normalizer.normalize(tok, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                if (!asciiTok.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) continue
                val lower = asciiTok.lowercase()
                if (lower.all { it in 'a'..'z' } && lower.length in 3..18) {
                    countrySet.add(lower)
                }
                val rom = RomajiDetector.analyze(lower, strictForDetection = true)
                val validRomaji = rom.isStrictValid && (rom.pendingTail.isEmpty() || rom.pendingTail == "n")
                if (!validRomaji) {
                    if (tok == asciiTok) {
                        properMap.putIfAbsent(lower, tok)
                    }
                    engFocused.add(lower)
                }
            }
        }
        countryWords = countrySet
        countryWordList = SortedWordList(countrySet)
        // Hyphenated canonical forms that users type without the hyphen.
        properMap.putIfAbsent("wifi", "Wi-Fi")
        properNounCanonical = properMap
        englishFocusedWords = SortedWordList(engFocused)

        // 3. english-words.txt (broad English spellcheck dictionary)
        val allEng = HashSet<String>(engFocused)
        allEng.addAll(countrySet)
        for (rawLine in readLines("english-words.txt")) {
            val line = stripHashComment(rawLine)
            if (line.isNotEmpty()) {
                allEng.add(line.lowercase())
            }
        }
        englishAllWords = SortedWordList(allEng)

        // 4. english-readable.txt
        val readable = HashSet<String>()
        for (rawLine in readLines("english-readable.txt")) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) readable.add(tok.lowercase())
        }
        englishReadableWords = SortedWordList(readable)

        // 5. japanese.txt (canonical Hepburn romaji -> expanded variants)
        val jpCanon = ArrayList<String>()
        val jpVariants = HashSet<String>()
        for (rawLine in readLines("japanese.txt")) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                if (lower.isNotEmpty()) {
                    jpCanon.add(lower)
                    jpVariants.addAll(RomajiDetector.spellingVariants(lower))
                }
            }
        }
        japaneseCanonicalWords = jpCanon
        japaneseWords = SortedWordList(jpVariants)

        // 6. brands.txt + loanwords.txt (reading cand1 cand2 ...)
        val latinCandMap = LinkedHashMap<String, MutableList<String>>()
        val glideBrands = LinkedHashSet<String>()
        for (file in listOf("brands.txt", "loanwords.txt")) {
            for (rawLine in readLines(file)) {
                val line = stripHashComment(rawLine)
                val parts = line.splitWhitespace()
                if (parts.size >= 2) {
                    val reading = parts[0]
                    val list = latinCandMap.getOrPut(reading) { ArrayList() }
                    for (idx in 1 until parts.size) {
                        val w = parts[idx]
                        if (w !in list) list.add(w)
                        val cleanAlpha = w.lowercase().filterNot { it in "-.'/" }
                        if (cleanAlpha.all { it in 'a'..'z' } && cleanAlpha.length >= 2) {
                            glideBrands.add(cleanAlpha)
                        }
                    }
                }
            }
        }
        kanaToBrandsAndLoanwords = latinCandMap

        // 7. phrases.txt ([^]reading1|reading2\tsurface1|surface2)
        val phraseEntryMap = LinkedHashMap<String, PhraseEntry>()
        val phraseMap = LinkedHashMap<String, String>()
        for (rawLine in readLines("phrases.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val tab = rawLine.indexOf('\t')
            if (tab > 0) {
                var rRaw = rawLine.substring(0, tab).trim()
                val wRaw = rawLine.substring(tab + 1).trim()
                if (rRaw.isEmpty() || wRaw.isEmpty()) continue
                val headOnly = rRaw.startsWith('^')
                if (headOnly) rRaw = rRaw.substring(1)
                if (rRaw.isEmpty()) continue
                val clauses = if ('|' in rRaw || '|' in wRaw) {
                    val rParts = rRaw.split('|')
                    val wParts = wRaw.split('|')
                    if (rParts.size == wParts.size && rParts.all { it.isNotEmpty() } && wParts.all { it.isNotEmpty() }) {
                        rParts.indices.map { idx -> ConversionClause(rParts[idx], wParts[idx]) }
                    } else {
                        continue
                    }
                } else {
                    listOf(ConversionClause(rRaw, wRaw))
                }
                val plainR = rRaw.replace("|", "")
                val plainW = wRaw.replace("|", "")
                if (plainR.isNotEmpty() && plainW.isNotEmpty()) {
                    phraseEntryMap.putIfAbsent(plainR, PhraseEntry(plainR, plainW, headOnly, clauses))
                    phraseMap.putIfAbsent(plainR, plainW)
                }
            }
        }
        phraseEntries = phraseEntryMap
        readingToPhrases = phraseMap

        // 8. candidates.txt (reading cand1 cand2 ...)
        val candMap = LinkedHashMap<String, MutableList<String>>()
        for (rawLine in readLines("candidates.txt")) {
            val line = stripHashComment(rawLine)
            val parts = line.splitWhitespace()
            if (parts.size >= 2) {
                val r = parts[0]
                val list = candMap.getOrPut(r) { ArrayList() }
                for (idx in 1 until parts.size) {
                    val w = parts[idx]
                    if (w !in list) list.add(w)
                }
            }
        }
        extraCandidates = candMap

        // 9. emoji.txt + emoji-cldr.txt (reading\temoji1\temoji2...)
        val emojiMap = LinkedHashMap<String, MutableList<String>>()
        val kaomojis = LinkedHashSet<String>()
        for (file in listOf("emoji.txt", "emoji-cldr.txt")) {
            val isCuratedEmojiFile = (file == "emoji.txt")
            for (rawLine in readLines(file)) {
                if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
                val parts = rawLine.split('\t')
                if (parts.size >= 2) {
                    val r = parts[0].trim()
                    if (r.isEmpty()) continue
                    val list = emojiMap.getOrPut(r) { ArrayList() }
                    for (idx in 1 until parts.size) {
                        val e = parts[idx].trim()
                        if (e.isNotEmpty() && e !in list && list.size < 16) {
                            list.add(e)
                        }
                        if (isCuratedEmojiFile && e.isNotEmpty() && looksLikeKaomoji(e)) {
                            kaomojis.add(e)
                        }
                    }
                }
            }
        }
        emojiCandidates = emojiMap
        kaomojiList = kaomojis.toList()

        // 9b. Build lightweight English keyword -> emoji map from emoji-catalog.txt
        val engEmojiMap = LinkedHashMap<String, MutableList<String>>()
        fun addEngEmoji(word: String, emoji: String) {
            if (word.length < 2) return
            val list = engEmojiMap.getOrPut(word) { ArrayList(4) }
            if (emoji !in list && list.size < 4) list.add(emoji)
        }
        for (rawLine in readLines("emoji-catalog.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val parts = rawLine.split('\t')
            if (parts.size < 3) continue
            val emoji = parts[1].trim()
            val name = parts[2].trim().lowercase()
            if (emoji.isEmpty() || name.isEmpty()) continue
            val tokens = name.split(' ', '-', '_').filter { it.length >= 2 && it.all { c -> c in 'a'..'z' } }
            if (tokens.size == 1) {
                addEngEmoji(tokens[0], emoji)
            } else if (tokens.size >= 2) {
                val joined = tokens.joinToString("")
                if (joined.length in 3..16) addEngEmoji(joined, emoji)
                tokens.last().let { lastTok ->
                    if (lastTok !in setOf("face", "with", "and", "of", "the", "in", "on", "person")) {
                        addEngEmoji(lastTok, emoji)
                    }
                }
            }
        }
        mapOf(
            "bulb" to "💡",
            "idea" to "💡",
            "fire" to "🔥",
            "heart" to "❤️",
            "star" to "⭐",
            "check" to "✅",
            "ok" to "👌",
            "smile" to "😊",
            "laugh" to "😂",
            "cry" to "😭",
            "thanks" to "🙏",
            "coffee" to "☕",
            "beer" to "🍺",
            "pizza" to "🍕",
            "ramen" to "🍜",
            "car" to "🚗",
            "train" to "🚃",
            "phone" to "📱",
            "music" to "🎵"
        ).forEach { (w, e) ->
            val list = engEmojiMap.getOrPut(w) { ArrayList(4) }
            list.remove(e)
            list.add(0, e)
            if (list.size > 4) list.subList(4, list.size).clear()
        }
        englishEmojiCandidates = engEmojiMap

        // 10. misspellings.txt (wrong\tright or right-only)
        val misExact = LinkedHashMap<String, String>()
        val misRight = HashSet<String>()
        val misShape = LinkedHashMap<String, MutableList<String>>()
        fun registerRightMisspelling(rightKata: String) {
            if (rightKata.isEmpty()) return
            misRight.add(rightKata)
            if (rightKata.length >= 5) {
                val shape = katakanaShapeKey(rightKata)
                val bucket = misShape.getOrPut(shape) { ArrayList(2) }
                if (rightKata !in bucket) bucket.add(rightKata)
            }
        }
        for (rawLine in readLines("misspellings.txt")) {
            val line = stripHashComment(rawLine)
            if (line.isEmpty()) continue
            val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size == 1) {
                registerRightMisspelling(RomajiDetector.toKatakana(parts[0]))
            } else if (parts.size >= 2) {
                val wrongKata = RomajiDetector.toKatakana(parts[0])
                val rightKata = RomajiDetector.toKatakana(parts[1])
                if (wrongKata.isNotEmpty() && rightKata.isNotEmpty()) {
                    misExact.putIfAbsent(wrongKata, rightKata)
                    registerRightMisspelling(rightKata)
                }
            }
        }
        misspellingExact = misExact
        misspellingRightSet = misRight
        misspellingByShape = misShape

        // 11. readings.txt (reading[\tpos])
        val rSet = HashSet<String>()
        val rTagged = ArrayList<Pair<String, List<String>>>()
        for (rawLine in readLines("readings.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val tab = rawLine.indexOf('\t')
            val r = (if (tab >= 0) rawLine.substring(0, tab) else rawLine).trim()
            if (r.isNotEmpty()) {
                rSet.add(r)
                val tags = if (tab >= 0) {
                    rawLine.substring(tab + 1).trim().split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    emptyList()
                }
                if (tags.isNotEmpty()) {
                    rTagged.add(r to tags)
                }
            }
        }
        readingsSet = rSet
        readingWithTags = rTagged

        // 11b. names.txt (Japanese surname/given-name readings without long vowels for TitleCase detection)
        val nameSet = HashSet<String>(24576)
        for (rawLine in readLines("names.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val tab = rawLine.indexOf('\t')
            val r = (if (tab >= 0) rawLine.substring(0, tab) else rawLine).trim()
            if (r.isNotEmpty()) {
                nameSet.add(withoutLongVowels(r))
            }
        }
        japaneseNameReadings = nameSet

        // 11. Extract lowercase English word frequencies from JMdict translations.txt
        val glossFreq = HashMap<String, Int>(20000)
        for (rawLine in readLines("translations.txt")) {
            if (rawLine.isEmpty() || rawLine[0] == '#') continue
            val tab1 = rawLine.indexOf('\t')
            if (tab1 < 0) continue
            val tab2 = rawLine.indexOf('\t', tab1 + 1)
            if (tab2 < 0 || tab2 + 1 >= rawLine.length) continue
            val sb = StringBuilder(16)
            var invalid = false
            for (idx in (tab2 + 1) until rawLine.length) {
                val c = rawLine[idx]
                when {
                    c in 'a'..'z' -> if (!invalid) sb.append(c)
                    c in 'A'..'Z' -> if (!invalid) sb.append((c.code + 32).toChar())
                    c in '0'..'9' -> invalid = true
                    else -> {
                        if (!invalid && sb.length in 2..16) {
                            val w = sb.toString()
                            glossFreq[w] = (glossFreq[w] ?: 0) + 1
                        }
                        sb.setLength(0)
                        invalid = false
                    }
                }
            }
            if (!invalid && sb.length in 2..16) {
                val w = sb.toString()
                glossFreq[w] = (glossFreq[w] ?: 0) + 1
            }
        }
        englishGlossFreq = glossFreq
        glideBrandLoanwords = glideBrands

        // 12. Build priority English glide vocabulary list (preserved for callers expecting combined list)
        val glideEng = LinkedHashSet<String>()
        glideEng.addAll(properMap.keys)
        glideEng.addAll(engFocused)
        glideEng.addAll(glideBrands)
        for (rawLine in (source.readText("english-phrases.txt")?.lines() ?: emptyList())) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                if (lower.all { it in 'a'..'z' } && lower.length in 2..18) {
                    glideEng.add(lower)
                }
            }
        }
        commonLatinWordsForGlide = glideEng.toList()
        loadedFileCount = count
    }

    /**
     * Resolves common katakana/loanword misspellings (e.g. しゅみれーしょん -> シミュレーション,
     * ぶれすれっど -> ブレスレット) from misspellings.txt.
     */
    fun resolveMisspelling(readingHiragana: String): List<String> {
        if (readingHiragana.length < 3) return emptyList()
        val kata = RomajiDetector.toKatakana(readingHiragana)
        val out = LinkedHashSet<String>(2)
        misspellingExact[kata]?.let { out.add(it) }
        if (kata.length >= 4 && kata !in misspellingRightSet) {
            val shape = katakanaShapeKey(kata)
            misspellingByShape[shape]?.forEach { cand ->
                if (cand != kata) out.add(cand)
            }
        }
        return out.toList()
    }

    fun isJapaneseNameReading(kana: String): Boolean =
        kana.isNotEmpty() && japaneseNameReadings.contains(withoutLongVowels(kana))

    companion object {
        /** Strips long-vowel trailing う/お after お-row and う after う-row (さとう -> さと, ゆうき -> ゆき). */
        fun withoutLongVowels(kana: String): String {
            val sb = StringBuilder(kana.length)
            for (c in kana) {
                val prev = if (sb.isNotEmpty()) sb[sb.length - 1] else '\u0000'
                val oRow = prev in "おこそとのほもよろをごぞどぼぽょぉ"
                val uRow = prev in "うくすつぬふむゆるぐずづぶぷゅぅ"
                if ((c == 'う' && (oRow || uRow)) || (c == 'お' && oRow)) continue
                sb.append(c)
            }
            return sb.toString()
        }

        internal fun looksLikeKaomoji(token: String): Boolean {
            if (token.length <= 1) return false
            return token.any { ch ->
                ch in ' '..'~' || ch in listOf('ω', '∀', 'Д', 'ﾟ', '▽', '≧', '≦', '・', '｀', '´', 'ノ', '￣', '∩', '•', 'ᴥ')
            }
        }

        internal fun katakanaShapeKey(katakana: String): String {
            val decomposed = Normalizer.normalize(katakana, Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                if (ch == 'ッ' || ch == 'ー' || Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(
                    when (ch) {
                        'ァ' -> 'ア'
                        'ィ' -> 'イ'
                        'ゥ' -> 'ウ'
                        'ェ' -> 'エ'
                        'ォ' -> 'オ'
                        'ャ' -> 'ヤ'
                        'ュ' -> 'ユ'
                        'ョ' -> 'ヨ'
                        'ヮ' -> 'ワ'
                        else -> ch
                    }
                )
            }
            return sb.toString()
        }

        private fun String.lstripStartsWithHash(): Boolean = trimStart().startsWith('#')

        private fun stripHashComment(line: String): String {
            val idx = line.indexOf('#')
            return (if (idx >= 0) line.substring(0, idx) else line).trim()
        }

        private fun String.splitWhitespace(): List<String> {
            if (isEmpty()) return emptyList()
            val out = ArrayList<String>()
            var start = -1
            for (i in indices) {
                if (this[i].isWhitespace()) {
                    if (start >= 0) {
                        out.add(substring(start, i))
                        start = -1
                    }
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) out.add(substring(start))
            return out
        }
    }
}
