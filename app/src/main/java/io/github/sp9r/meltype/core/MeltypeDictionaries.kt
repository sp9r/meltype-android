package io.github.sp9r.meltype.core

import android.content.Context
import java.io.File
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

class MeltypeDictionaries(source: DictionarySource) {
    val englishFocusedWords: SortedWordList
    val englishAllWords: SortedWordList
    val englishReadableWords: SortedWordList
    val japaneseWords: SortedWordList
    val japaneseCanonicalWords: List<String>
    val japaneseOriginEnglishWords: Set<String>
    val properNounCanonical: Map<String, String>
    val kanaToBrandsAndLoanwords: Map<String, List<String>>
    val readingToPhrases: Map<String, String>
    val extraCandidates: Map<String, List<String>>
    val emojiCandidates: Map<String, List<String>>
    val readingsSet: Set<String>
    val commonLatinWordsForGlide: List<String>
    val loadedFileCount: Int

    init {
        var count = 0

        fun readLines(name: String): List<String> {
            val txt = source.readText(name) ?: return emptyList()
            count++
            return txt.lines()
        }

        // 1. english.txt (space-separated, plus Japanese-origin section tracking)
        val engFocused = HashSet<String>()
        val jpOrigin = HashSet<String>()
        var inJpOrigin = false
        for (rawLine in readLines("english.txt")) {
            if (rawLine.startsWith("# ---")) {
                inJpOrigin = rawLine.contains("日本語由来")
                continue
            }
            val line = stripHashComment(rawLine)
            if (inJpOrigin && line.isEmpty()) {
                inJpOrigin = false
                continue
            }
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                engFocused.add(lower)
                if (inJpOrigin) jpOrigin.add(lower)
            }
        }
        japaneseOriginEnglishWords = jpOrigin

        // 2. propernouns.txt (case-preserved)
        val properMap = LinkedHashMap<String, String>()
        for (rawLine in readLines("propernouns.txt")) {
            val line = stripHashComment(rawLine)
            for (tok in line.splitWhitespace()) {
                val lower = tok.lowercase()
                if (lower.isNotEmpty()) {
                    properMap.putIfAbsent(lower, tok)
                    engFocused.add(lower)
                }
            }
        }
        properNounCanonical = properMap
        englishFocusedWords = SortedWordList(engFocused)

        // 3. english-words.txt (broad English spellcheck dictionary)
        val allEng = HashSet<String>(engFocused)
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
                        if (w.all { it in 'a'..'z' || it in 'A'..'Z' } && w.length >= 2) {
                            glideBrands.add(w.lowercase())
                        }
                    }
                }
            }
        }
        kanaToBrandsAndLoanwords = latinCandMap

        // 7. phrases.txt (reading\tphrase)
        val phraseMap = LinkedHashMap<String, String>()
        for (rawLine in readLines("phrases.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val tab = rawLine.indexOf('\t')
            if (tab > 0) {
                val r = rawLine.substring(0, tab).trim()
                val w = rawLine.substring(tab + 1).trim()
                if (r.isNotEmpty() && w.isNotEmpty()) phraseMap.putIfAbsent(r, w)
            }
        }
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
        for (file in listOf("emoji.txt", "emoji-cldr.txt")) {
            for (rawLine in readLines(file)) {
                if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
                val parts = rawLine.split('\t')
                if (parts.size >= 2) {
                    val r = parts[0].trim()
                    if (r.isEmpty()) continue
                    val list = emojiMap.getOrPut(r) { ArrayList() }
                    for (idx in 1 until parts.size) {
                        val e = parts[idx].trim()
                        if (e.isNotEmpty() && e !in list && list.size < 16) list.add(e)
                    }
                }
            }
        }
        emojiCandidates = emojiMap

        // 10. readings.txt (reading[\tpos])
        val rSet = HashSet<String>()
        for (rawLine in readLines("readings.txt")) {
            if (rawLine.isBlank() || rawLine.lstripStartsWithHash()) continue
            val tab = rawLine.indexOf('\t')
            val r = (if (tab >= 0) rawLine.substring(0, tab) else rawLine).trim()
            if (r.isNotEmpty()) rSet.add(r)
        }
        readingsSet = rSet

        // 11. Build priority English glide vocabulary list
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

    companion object {
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
