package io.github.sp9r.meltype.core

/** A fixed phrase registered by the user (mail address, phone number, greeting ...). [trigger] is an optional reading. */
data class UserPhrase(val trigger: String, val text: String)

/** A phrase the user has committed before; [count] is how often, [lastUsed] epoch millis. */
data class LearnedPhrase(val reading: String, val text: String, val count: Int, val lastUsed: Long)

data class PhraseSuggestion(val text: String, val subtitle: String)

/**
 * Prefix completion for the candidate bar (tab-completion style): user-defined phrases first, then
 * phrases the user committed often. Pure logic so it can be unit tested; storage lives in MeltypeSettings.
 */
object PhraseSuggester {
    const val MIN_LEARNED_COUNT = 2
    const val MAX_LEARNED = 300
    const val MAX_SUGGESTIONS = 4

    private fun isSymbolOnly(s: String) = s.isNotEmpty() && s.none { it.isLetterOrDigit() }

    fun suggest(
        raw: String,
        reading: String,
        userPhrases: List<UserPhrase>,
        learned: List<LearnedPhrase>,
        currentPreview: String = "",
        limit: Int = MAX_SUGGESTIONS
    ): List<PhraseSuggestion> {
        val rawKey = raw.trim().lowercase()
        if (rawKey.isEmpty()) return emptyList()
        val readKey = reading.trim().lowercase()
        val out = ArrayList<PhraseSuggestion>()
        val seen = HashSet<String>()
        fun add(text: String, subtitle: String) {
            if (text.isEmpty() || text == currentPreview || !seen.add(text)) return
            out.add(PhraseSuggestion(text, subtitle))
        }

        for (p in userPhrases) {
            val t = p.text.lowercase()
            val trig = p.trigger.trim().lowercase()
            val byText = t.startsWith(rawKey) && t.length > rawKey.length
            // A lone symbol such as "@" finds phrases containing it (mail addresses).
            val bySymbol = isSymbolOnly(rawKey) && t.contains(rawKey)
            val byTrigger = trig.isNotEmpty() && (
                (trig.startsWith(rawKey)) ||
                    (readKey.isNotEmpty() && RomajiDetector.toKatakana(trig).let { it.startsWith(RomajiDetector.toKatakana(readKey)) })
                )
            if (byText || bySymbol || byTrigger) add(p.text, "定型")
        }

        val frequent = learned
            .filter { it.count >= MIN_LEARNED_COUNT }
            .sortedWith(compareByDescending<LearnedPhrase> { it.count }.thenByDescending { it.lastUsed })
        for (l in frequent) {
            val r = l.reading.lowercase()
            val byReading = readKey.length >= 2 && r.startsWith(readKey) && r.length > readKey.length
            val byRaw = rawKey.length >= 2 && l.text.lowercase().startsWith(rawKey) && l.text.length > rawKey.length
            if (byReading || byRaw) add(l.text, "よく使う")
        }
        return out.take(limit)
    }

    /** Records one commit; returns the new list capped to [MAX_LEARNED] (least used / oldest dropped). */
    fun recordCommit(
        learned: List<LearnedPhrase>,
        reading: String,
        text: String,
        now: Long
    ): List<LearnedPhrase> {
        val r = reading.trim().lowercase()
        val t = text.trim()
        if (r.length < 2 || t.length < 2 || t.length > 120) return learned
        val existing = learned.firstOrNull { it.text == t && it.reading == r }
        val next = if (existing != null) {
            learned.map { if (it === existing) it.copy(count = it.count + 1, lastUsed = now) else it }
        } else {
            learned + LearnedPhrase(r, t, 1, now)
        }
        return if (next.size <= MAX_LEARNED) next
        else next.sortedWith(compareByDescending<LearnedPhrase> { it.count }.thenByDescending { it.lastUsed }).take(MAX_LEARNED)
    }
}
