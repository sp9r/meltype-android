package io.github.sp9r.meltype.core

/**
 * Full Unicode RGI emoji catalog backed by bundled `dictionaries/emoji-catalog.txt`
 * paired with Japanese keyword search inverted from `emoji.txt` and `emoji-cldr.txt`.
 */
enum class EmojiCategory(
    val key: String,
    val tabIcon: String,
    val labelJa: String
) {
    RECENT("recent", "🕒", "最近使った絵文字"),
    SMILEYS("smileys", "😀", "スマイリー・感情"),
    PEOPLE("people", "👋", "人・ジェスチャー"),
    NATURE("nature", "🐶", "動物・自然"),
    FOOD("food", "🍜", "食べ物・飲み物"),
    TRAVEL("travel", "✈️", "旅行・場所"),
    ACTIVITIES("activities", "⚽", "スポーツ・活動"),
    OBJECTS("objects", "💡", "物・ツール"),
    SYMBOLS("symbols", "❤️", "ハート・記号"),
    FLAGS("flags", "🏁", "国旗");

    companion object {
        val CATALOG_CATEGORIES: List<EmojiCategory> = entries.filter { it != RECENT }

        fun fromKey(key: String): EmojiCategory? = entries.firstOrNull { it.key == key }
    }
}

data class EmojiCatalogEntry(
    val emoji: String,
    val category: EmojiCategory,
    val englishName: String,
    val jaReadings: List<String>
)

class EmojiCatalog(source: DictionarySource) {
    val entriesByCategory: Map<EmojiCategory, List<EmojiCatalogEntry>>
    val allEntries: List<EmojiCatalogEntry>

    init {
        val readingMap = HashMap<String, LinkedHashSet<String>>()
        for (fileName in listOf("emoji.txt", "emoji-cldr.txt")) {
            val text = source.readText(fileName) ?: continue
            text.lineSequence().forEach { rawLine ->
                if (rawLine.isBlank() || rawLine.startsWith("#")) return@forEach
                val parts = rawLine.split('\t')
                if (parts.size < 2) return@forEach
                val reading = parts[0].trim()
                if (reading.isEmpty()) return@forEach
                for (i in 1 until parts.size) {
                    val token = parts[i].trim()
                    if (token.isEmpty()) continue
                    val norm = normalizeEmojiKey(token)
                    readingMap.getOrPut(norm) { LinkedHashSet() }.add(reading)
                }
            }
        }

        val grouped = LinkedHashMap<EmojiCategory, MutableList<EmojiCatalogEntry>>()
        for (cat in EmojiCategory.CATALOG_CATEGORIES) {
            grouped[cat] = ArrayList()
        }
        val flat = ArrayList<EmojiCatalogEntry>(2000)

        val catalogText = source.readText("emoji-catalog.txt")
        if (catalogText != null) {
            catalogText.lineSequence().forEach { rawLine ->
                if (rawLine.isBlank() || rawLine.startsWith("#")) return@forEach
                val parts = rawLine.split('\t')
                if (parts.size < 2) return@forEach
                val cat = EmojiCategory.fromKey(parts[0].trim()) ?: return@forEach
                val emoji = parts[1].trim()
                if (emoji.isEmpty()) return@forEach
                val enName = parts.getOrNull(2)?.trim().orEmpty().lowercase()
                val norm = normalizeEmojiKey(emoji)
                val jaList = readingMap[norm]?.toList() ?: emptyList()
                val entry = EmojiCatalogEntry(
                    emoji = emoji,
                    category = cat,
                    englishName = enName,
                    jaReadings = jaList
                )
                grouped.getValue(cat).add(entry)
                flat.add(entry)
            }
        }

        entriesByCategory = grouped.mapValues { it.value.toList() }
        allEntries = flat
    }

    /**
     * Searches across all Unicode emojis using Japanese readings (hiragana/katakana/romaji) and CLDR English names.
     */
    fun search(rawQuery: String, limit: Int = 120): List<EmojiCatalogEntry> {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return allEntries.take(limit)

        val queries = LinkedHashSet<String>()
        queries.add(q)
        val analyzed = RomajiDetector.analyze(q, strictForDetection = false)
        val hira = analyzed.finalizedHiragana()
        if (hira.isNotEmpty() && !hira.all { it in 'a'..'z' }) {
            queries.add(hira)
            queries.add(RomajiDetector.toKatakana(hira))
        }
        val hiraToKata = RomajiDetector.toKatakana(q)
        if (hiraToKata != q) queries.add(hiraToKata)

        val scored = ArrayList<Pair<Int, EmojiCatalogEntry>>()
        for (entry in allEntries) {
            var bestTier = Int.MAX_VALUE
            for (candQ in queries) {
                for (kw in entry.jaReadings) {
                    val tier = when {
                        kw == candQ -> 0
                        kw.startsWith(candQ) -> 1
                        kw.contains(candQ) -> 2
                        else -> Int.MAX_VALUE
                    }
                    if (tier < bestTier) bestTier = tier
                }
                if (bestTier > 1 && entry.englishName.isNotEmpty()) {
                    val enTier = when {
                        entry.englishName == candQ -> 1
                        entry.englishName.split(' ').any { it.startsWith(candQ) } -> 3
                        entry.englishName.contains(candQ) -> 4
                        else -> Int.MAX_VALUE
                    }
                    if (enTier < bestTier) bestTier = enTier
                }
            }
            if (bestTier < Int.MAX_VALUE) {
                scored.add(bestTier to entry)
            }
        }

        scored.sortBy { it.first }
        return scored.take(limit).map { it.second }
    }

    companion object {
        fun normalizeEmojiKey(emoji: String): String = emoji.replace("\uFE0F", "")
    }
}
