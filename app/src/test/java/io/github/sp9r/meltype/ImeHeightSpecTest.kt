package io.github.sp9r.meltype

import io.github.sp9r.meltype.core.ImeHeightSpec
import io.github.sp9r.meltype.core.ImeHeightSpec.BodyState
import io.github.sp9r.meltype.core.ImeHeightSpec.HeaderState
import io.github.sp9r.meltype.core.ImeHeightSpec.LayerSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeHeightSpecTest {

    @Test
    fun headerHeightIsConstantAcrossAllStates() {
        val expected = ImeHeightSpec.TOP_BAR_HEIGHT_DP
        assertEquals(44, expected)
        for (state in HeaderState.entries) {
            assertEquals(
                "Header height must stay ${expected}dp in $state",
                expected,
                ImeHeightSpec.headerHeightDp(state)
            )
        }
    }

    @Test
    fun keyboardBaseAndBodyHeightAreConstantAcrossAllLayers() {
        for (showNumberRow in listOf(true, false)) {
            val expectedBase = if (showNumberRow) 264f else 224f
            for (layer in LayerSpec.entries) {
                assertEquals(
                    "Base height must match across $layer (showNumberRow=$showNumberRow)",
                    expectedBase,
                    ImeHeightSpec.keyboardBaseHeightDp(showNumberRow, layer),
                    0.001f
                )
            }
        }
    }

    @Test
    fun totalAndBodyHeightStayConstantAcrossAllStatesLayersAndTabs() {
        val scales = listOf(0.65f, 0.85f, 1.0f, 1.15f, 1.55f)
        for (showNumberRow in listOf(true, false)) {
            for (scale in scales) {
                val expectedBody = ImeHeightSpec.bodyHeightDp(showNumberRow, scale, LayerSpec.QWERTY, BodyState.KEYBOARD)
                val expectedTotal = 44 + 1 + expectedBody

                for (layer in LayerSpec.entries) {
                    for (headerState in HeaderState.entries) {
                        for (bodyState in BodyState.entries) {
                            val body = ImeHeightSpec.bodyHeightDp(showNumberRow, scale, layer, bodyState)
                            val total = ImeHeightSpec.totalHeightDp(showNumberRow, scale, layer, headerState, bodyState)
                            assertEquals(
                                "Body height jumped for showNumberRow=$showNumberRow scale=$scale layer=$layer body=$bodyState",
                                expectedBody,
                                body
                            )
                            assertEquals(
                                "Total height jumped for showNumberRow=$showNumberRow scale=$scale layer=$layer header=$headerState body=$bodyState",
                                expectedTotal,
                                total
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun heightScaleIsClampedToSupportedBounds() {
        assertEquals(
            ImeHeightSpec.bodyHeightDp(true, 0.65f),
            ImeHeightSpec.bodyHeightDp(true, 0.10f)
        )
        assertEquals(
            ImeHeightSpec.bodyHeightDp(true, 1.55f),
            ImeHeightSpec.bodyHeightDp(true, 3.00f)
        )
        assertEquals(
            ImeHeightSpec.totalHeightDp(false, 0.65f),
            ImeHeightSpec.totalHeightDp(false, 0.20f)
        )
        assertEquals(
            ImeHeightSpec.totalHeightDp(false, 1.55f),
            ImeHeightSpec.totalHeightDp(false, 2.50f)
        )
    }
}
