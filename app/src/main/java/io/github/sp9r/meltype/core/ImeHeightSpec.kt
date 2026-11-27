package io.github.sp9r.meltype.core

import kotlin.math.roundToInt

/**
 * Single source of truth for fixed IME header/body/total heights across all
 * layers, composing states, expanded tabs, and emoji search.
 */
object ImeHeightSpec {
    const val UPPER_BAR_HEIGHT_DP = 32
    const val CANDIDATE_BAR_HEIGHT_DP = 42
    const val TOP_BAR_HEIGHT_DP = UPPER_BAR_HEIGHT_DP + CANDIDATE_BAR_HEIGHT_DP
    const val DIVIDER_HEIGHT_DP = 1
    const val BASE_HEIGHT_WITH_NUMBER_ROW_DP = 248f
    const val BASE_HEIGHT_WITHOUT_NUMBER_ROW_DP = 210f
    const val MIN_HEIGHT_SCALE = 0.65f
    const val MAX_HEIGHT_SCALE = 1.55f

    enum class HeaderState {
        IDLE,
        COMPOSING,
        CONVERTING,
        EDITING,
        CLAUSE_SUBMODE,
        EMOJI_SEARCH,
        CLIPBOARD_CHIP,
        INLINE_AUTOFILL,
        UNDO_CHIP
    }

    enum class BodyState {
        KEYBOARD,
        EXPANDED_CANDIDATES,
        EXPANDED_EMOJI,
        EXPANDED_KAOMOJI,
        EXPANDED_TEMPLATES,
        EXPANDED_CLIPBOARD,
        EXPANDED_EDIT
    }

    enum class LayerSpec {
        QWERTY,
        SYM1,
        SYM2,
        NUMPAD
    }

    fun keyboardBaseHeightDp(
        showNumberRow: Boolean,
        @Suppress("UNUSED_PARAMETER") layer: LayerSpec = LayerSpec.QWERTY
    ): Float = if (showNumberRow) BASE_HEIGHT_WITH_NUMBER_ROW_DP else BASE_HEIGHT_WITHOUT_NUMBER_ROW_DP

    fun bodyHeightDp(
        showNumberRow: Boolean,
        heightScale: Float,
        layer: LayerSpec = LayerSpec.QWERTY,
        @Suppress("UNUSED_PARAMETER") bodyState: BodyState = BodyState.KEYBOARD
    ): Int {
        val clampedScale = heightScale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE)
        return (keyboardBaseHeightDp(showNumberRow, layer) * clampedScale).roundToInt()
    }

    fun headerHeightDp(@Suppress("UNUSED_PARAMETER") headerState: HeaderState = HeaderState.IDLE): Int =
        TOP_BAR_HEIGHT_DP

    fun totalHeightDp(
        showNumberRow: Boolean,
        heightScale: Float,
        layer: LayerSpec = LayerSpec.QWERTY,
        headerState: HeaderState = HeaderState.IDLE,
        bodyState: BodyState = BodyState.KEYBOARD
    ): Int = headerHeightDp(headerState) + DIVIDER_HEIGHT_DP + bodyHeightDp(showNumberRow, heightScale, layer, bodyState)
}
