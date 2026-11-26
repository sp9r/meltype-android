package io.github.sp9r.meltype.core

data class ClipTextEntry(val text: String, val pinned: Boolean = false)

/**
 * Pure logic for the text clipboard history (no Android dependencies).
 * Order: most recent first. Pinned entries are never evicted by the size limit.
 */
object ClipboardHistory {
    const val MAX_ENTRIES = 30
    const val MAX_TEXT_LENGTH = 500

    /** True when a copied text may be stored. */
    fun shouldStore(text: String?, sensitive: Boolean, passwordField: Boolean, enabled: Boolean): Boolean {
        if (!enabled || sensitive || passwordField) return false
        if (text.isNullOrBlank()) return false
        return text.length <= MAX_TEXT_LENGTH
    }

    fun add(list: List<ClipTextEntry>, text: String, max: Int = MAX_ENTRIES): List<ClipTextEntry> {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return list
        val existing = list.firstOrNull { it.text == text }
        val head = ClipTextEntry(text, existing?.pinned ?: false)
        val merged = listOf(head) + list.filterNot { it.text == text }
        return trim(merged, max)
    }

    /** Drops the oldest unpinned entries until the list fits; pinned entries are always kept. */
    fun trim(list: List<ClipTextEntry>, max: Int = MAX_ENTRIES): List<ClipTextEntry> {
        var toDrop = list.size - max
        if (toDrop <= 0) return list
        val result = ArrayList<ClipTextEntry>(list)
        var i = result.size - 1
        while (i >= 0 && toDrop > 0) {
            if (!result[i].pinned) {
                result.removeAt(i)
                toDrop--
            }
            i--
        }
        return result
    }

    fun togglePin(list: List<ClipTextEntry>, text: String): List<ClipTextEntry> =
        list.map { if (it.text == text) it.copy(pinned = !it.pinned) else it }

    fun remove(list: List<ClipTextEntry>, text: String): List<ClipTextEntry> =
        list.filterNot { it.text == text }

    /** Clears everything, or only unpinned entries when keepPinned is true. */
    fun clear(list: List<ClipTextEntry>, keepPinned: Boolean): List<ClipTextEntry> =
        if (keepPinned) list.filter { it.pinned } else emptyList()

    /** Pinned entries first (keeping relative order), then the rest. */
    fun display(list: List<ClipTextEntry>): List<ClipTextEntry> =
        list.filter { it.pinned } + list.filterNot { it.pinned }
}
