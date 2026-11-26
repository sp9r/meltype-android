package io.github.sp9r.meltype

import io.github.sp9r.meltype.core.ClipTextEntry
import io.github.sp9r.meltype.core.ClipboardHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {
    @Test
    fun addPutsNewestFirst() {
        val l = ClipboardHistory.add(ClipboardHistory.add(emptyList(), "a"), "b")
        assertEquals(listOf("b", "a"), l.map { it.text })
    }

    @Test
    fun duplicateMovesToFrontAndKeepsPin() {
        var l = ClipboardHistory.add(emptyList(), "a")
        l = ClipboardHistory.add(l, "b")
        l = ClipboardHistory.togglePin(l, "a")
        l = ClipboardHistory.add(l, "a")
        assertEquals(listOf("a", "b"), l.map { it.text })
        assertTrue(l[0].pinned)
        assertEquals(2, l.size)
    }

    @Test
    fun limitDropsOldestUnpinnedOnly() {
        var l = ClipboardHistory.add(emptyList(), "keep", max = 3)
        l = ClipboardHistory.togglePin(l, "keep")
        for (i in 1..5) l = ClipboardHistory.add(l, "t$i", max = 3)
        assertEquals(3, l.size)
        assertEquals(listOf("t5", "t4", "keep"), l.map { it.text })
        assertTrue(l.last().pinned)
    }

    @Test
    fun defaultLimitIs30() {
        var l = emptyList<ClipTextEntry>()
        for (i in 1..40) l = ClipboardHistory.add(l, "x$i")
        assertEquals(30, l.size)
        assertEquals("x40", l.first().text)
    }

    @Test
    fun rejectsBlankAndTooLong() {
        assertTrue(ClipboardHistory.add(emptyList(), "  ").isEmpty())
        assertTrue(ClipboardHistory.add(emptyList(), "a".repeat(501)).isEmpty())
        assertEquals(1, ClipboardHistory.add(emptyList(), "a".repeat(500)).size)
    }

    @Test
    fun sensitivePasswordAndDisabledAreNotStored() {
        assertTrue(ClipboardHistory.shouldStore("hello", sensitive = false, passwordField = false, enabled = true))
        assertFalse(ClipboardHistory.shouldStore("hello", sensitive = true, passwordField = false, enabled = true))
        assertFalse(ClipboardHistory.shouldStore("hello", sensitive = false, passwordField = true, enabled = true))
        assertFalse(ClipboardHistory.shouldStore("hello", sensitive = false, passwordField = false, enabled = false))
        assertFalse(ClipboardHistory.shouldStore(null, sensitive = false, passwordField = false, enabled = true))
    }

    @Test
    fun removeAndClear() {
        var l = ClipboardHistory.add(ClipboardHistory.add(emptyList(), "a"), "b")
        l = ClipboardHistory.togglePin(l, "a")
        assertEquals(listOf("a"), ClipboardHistory.clear(l, keepPinned = true).map { it.text })
        assertTrue(ClipboardHistory.clear(l, keepPinned = false).isEmpty())
        assertEquals(listOf("a"), ClipboardHistory.remove(l, "b").map { it.text })
    }

    @Test
    fun displayShowsPinnedFirst() {
        var l = ClipboardHistory.add(ClipboardHistory.add(ClipboardHistory.add(emptyList(), "a"), "b"), "c")
        l = ClipboardHistory.togglePin(l, "a")
        assertEquals(listOf("a", "c", "b"), ClipboardHistory.display(l).map { it.text })
    }
}
