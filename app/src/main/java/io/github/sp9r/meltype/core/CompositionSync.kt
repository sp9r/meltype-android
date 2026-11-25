package io.github.sp9r.meltype.core

/**
 * Decides when the editor no longer shows our composing text, so the internal composition must be
 * dropped without being committed again. Typical case: the user taps a prediction or search
 * suggestion drawn by the host app, which replaces the composing text; the IME's buffer would
 * otherwise be re-inserted on the next key press and the text would appear twice.
 */
object CompositionSync {
    /** Updates arriving this soon after our own edit may be stale callbacks of the previous state. */
    const val OWN_EDIT_GRACE_MS = 150L

    fun shouldDiscardOnSelectionUpdate(
        hasComposition: Boolean,
        candidatesStart: Int,
        candidatesEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        expectedLength: Int,
        msSinceOwnEdit: Long
    ): Boolean {
        if (!hasComposition) return false
        if (msSinceOwnEdit in 0 until OWN_EDIT_GRACE_MS) return false
        // The composing region was cleared by the app.
        if (candidatesStart < 0 || candidatesEnd < 0) return true
        // The composing region exists but no longer matches what we put there.
        if (candidatesEnd - candidatesStart != expectedLength) return true
        // The caret moved outside the composing region (tap elsewhere in the text).
        if (newSelStart < candidatesStart || newSelEnd > candidatesEnd) return true
        return false
    }
}
