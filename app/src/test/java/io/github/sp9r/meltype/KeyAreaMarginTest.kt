package io.github.sp9r.meltype

import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyAreaMarginTest {
    @Test
    fun marginShiftsEdgeKeyCentersInward() {
        val width = 1000f
        val (l0, w0) = KeyLayoutGeometry.keyAreaSpan(width, 0f)
        val (l1, w1) = KeyLayoutGeometry.keyAreaSpan(width, 33f) // 12dp 相当
        val q0 = KeyLayoutGeometry.keyCenterXPx('q', l0, w0)!!
        val q1 = KeyLayoutGeometry.keyCenterXPx('q', l1, w1)!!
        val p0 = KeyLayoutGeometry.keyCenterXPx('p', l0, w0)!!
        val p1 = KeyLayoutGeometry.keyCenterXPx('p', l1, w1)!!
        assertTrue(q1 > q0)
        assertTrue(p1 < p0)
        assertEquals(33f + (1000f - 66f) * 0.05f, q1, 0.01f)
        assertEquals(1000f - 33f - (1000f - 66f) * 0.05f, p1, 0.01f)
    }

    @Test
    fun touchInsideMarginSnapsToEdgeKeyArea() {
        val (left, w) = KeyLayoutGeometry.keyAreaSpan(1000f, 33f)
        assertEquals(left, KeyLayoutGeometry.snapToKeyArea(10f, left, w, 33f), 0.001f)
        assertEquals(left + w, KeyLayoutGeometry.snapToKeyArea(990f, left, w, 33f), 0.001f)
        // 領域内はそのまま
        assertEquals(500f, KeyLayoutGeometry.snapToKeyArea(500f, left, w, 33f), 0.001f)
        // 端に割り当たったXは q / p のキー(左端・右端の列)に最も近い
        val snappedL = KeyLayoutGeometry.snapToKeyArea(5f, left, w, 33f)
        val nx = (snappedL - left) / w
        assertTrue(nx < 0.05f)
    }

    @Test
    fun settingsSnapMarginTo2dpSteps() {
        assertEquals(12, MeltypeSettings.snapSideMarginDp(12))
        assertEquals(14, MeltypeSettings.snapSideMarginDp(13))
        assertEquals(0, MeltypeSettings.snapSideMarginDp(-5))
        assertEquals(40, MeltypeSettings.snapSideMarginDp(99))
    }
}
