package io.github.sp9r.meltype.glide

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

data class KeyCenter(
    val ch: Char,
    val row: Int,
    val colIndex: Int,
    /** Isotropic coordinate: X scaled by ASPECT_RATIO (2.35), Y in [0, 1]. */
    val x: Float,
    val y: Float
)

data class TouchPoint(
    val x: Float,
    val y: Float,
    val tMs: Long = 0L
)

/**
 * Geometric model of the 3-row QWERTY letter layout used for both rendering and glide decoding.
 */
object KeyLayoutGeometry {
    /** Width / Height ratio of the 3-row QWERTY letter area (10 keys wide x 3 rows high). */
    const val ASPECT_RATIO = 2.35f
    const val RESAMPLE_N = 32

    private val ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    val keyCenters: Map<Char, KeyCenter>
    val allLetters: CharArray = "abcdefghijklmnopqrstuvwxyz".toCharArray()
    private val keyByIndex = Array<KeyCenter?>(26) { null }

    init {
        val map = LinkedHashMap<Char, KeyCenter>(26)
        // Row 0: 10 keys, each width 1/10 -> centers at (i + 0.5) / 10
        for (i in ROWS[0].indices) {
            val ch = ROWS[0][i]
            val nx = (i + 0.5f) / 10f * ASPECT_RATIO
            val ny = 1f / 6f
            val kc = KeyCenter(ch, 0, i, nx, ny)
            map[ch] = kc
            keyByIndex[ch - 'a'] = kc
        }
        // Row 1: 9 keys indented by 0.5 key width -> centers at (i + 1.0) / 10
        for (i in ROWS[1].indices) {
            val ch = ROWS[1][i]
            val nx = (i + 1.0f) / 10f * ASPECT_RATIO
            val ny = 3f / 6f
            val kc = KeyCenter(ch, 1, i, nx, ny)
            map[ch] = kc
            keyByIndex[ch - 'a'] = kc
        }
        // Row 2: Shift (1.5w) + 7 keys (z..m) + Backspace (1.5w) -> centers at (i + 2.0) / 10
        for (i in ROWS[2].indices) {
            val ch = ROWS[2][i]
            val nx = (i + 2.0f) / 10f * ASPECT_RATIO
            val ny = 5f / 6f
            val kc = KeyCenter(ch, 2, i, nx, ny)
            map[ch] = kc
            keyByIndex[ch - 'a'] = kc
        }
        keyCenters = map
    }

    fun centerOf(ch: Char): KeyCenter? {
        val idx = ch.lowercaseChar() - 'a'
        return if (idx in 0 until 26) keyByIndex[idx] else null
    }

    /**
     * Converts View-pixel points inside the 3-row letter bounds into isotropic geometry coordinates.
     */
    fun normalizeFromView(points: List<TouchPoint>, letterBounds: RectF): List<TouchPoint> {
        val w = letterBounds.width().coerceAtLeast(1f)
        val h = letterBounds.height().coerceAtLeast(1f)
        return points.map { pt ->
            val nx = ((pt.x - letterBounds.left) / w).coerceIn(-0.1f, 1.1f) * ASPECT_RATIO
            val ny = ((pt.y - letterBounds.top) / h).coerceIn(-0.1f, 1.1f)
            TouchPoint(nx, ny, pt.tMs)
        }
    }

    /**
     * Generates synthetic glide points in isotropic geometry space connecting each key center of [word].
     * Used in unit tests and self-tests.
     */
    fun syntheticPathForWord(word: String, jitter: Float = 0f): List<TouchPoint> {
        val pts = ArrayList<TouchPoint>()
        var t = 0L
        var prev: KeyCenter? = null
        for ((idx, rawCh) in word.lowercase().withIndex()) {
            val kc = centerOf(rawCh) ?: continue
            if (prev != null && prev.ch != kc.ch) {
                for (step in 1..4) {
                    val alpha = step / 5f
                    pts.add(
                        TouchPoint(
                            prev.x + (kc.x - prev.x) * alpha,
                            prev.y + (kc.y - prev.y) * alpha,
                            t
                        )
                    )
                    t += 12L
                }
            }
            val jx = if (idx % 2 == 0) jitter else -jitter
            pts.add(TouchPoint(kc.x + jx, kc.y - jx * 0.5f, t))
            t += 16L
            prev = kc
        }
        return pts
    }

    /**
     * Returns up to [limit] closest QWERTY keys ordered by distance to isotropic coordinate (x, y).
     */
    fun nearestKeys(x: Float, y: Float, limit: Int = 3): List<Pair<Char, Float>> {
        val dists = ArrayList<Pair<Char, Float>>(26)
        for (i in 0 until 26) {
            val kc = keyByIndex[i] ?: continue
            val d = hypot((x - kc.x).toDouble(), (y - kc.y).toDouble()).toFloat()
            dists.add(kc.ch to d)
        }
        dists.sortBy { it.second }
        return dists.take(limit)
    }

    /**
     * Resamples an open polyline to exactly [n] equally-spaced points in isotropic space,
     * returning interleaved FloatArray [x0, y0, x1, y1, ...] and total arc length.
     */
    fun resamplePolyline(points: List<TouchPoint>, n: Int = RESAMPLE_N): Pair<FloatArray, Float> {
        val out = FloatArray(n * 2)
        if (points.isEmpty()) return out to 0f
        if (points.size == 1) {
            for (i in 0 until n) {
                out[i * 2] = points[0].x
                out[i * 2 + 1] = points[0].y
            }
            return out to 0f
        }

        val cum = FloatArray(points.size)
        var totalLen = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            totalLen += hypot(dx.toDouble(), dy.toDouble()).toFloat()
            cum[i] = totalLen
        }

        if (totalLen < 1e-5f) {
            for (i in 0 until n) {
                out[i * 2] = points[0].x
                out[i * 2 + 1] = points[0].y
            }
            return out to 0f
        }

        var segIdx = 0
        for (k in 0 until n) {
            val targetDist = (k.toFloat() / (n - 1).toFloat()) * totalLen
            while (segIdx < points.size - 2 && cum[segIdx + 1] < targetDist) {
                segIdx++
            }
            val d0 = cum[segIdx]
            val d1 = cum[segIdx + 1]
            val span = (d1 - d0).coerceAtLeast(1e-6f)
            val frac = ((targetDist - d0) / span).coerceIn(0f, 1f)
            out[k * 2] = points[segIdx].x + (points[segIdx + 1].x - points[segIdx].x) * frac
            out[k * 2 + 1] = points[segIdx].y + (points[segIdx + 1].y - points[segIdx].y) * frac
        }
        return out to totalLen
    }

    /**
     * Generates resampled template FloatArray (length 2*N) + arc length for a word's key sequence.
     */
    fun templateForWord(word: String, n: Int = RESAMPLE_N): Pair<FloatArray, Float> {
        val rawPts = ArrayList<TouchPoint>(word.length)
        for (c in word) {
            val kc = centerOf(c) ?: continue
            rawPts.add(TouchPoint(kc.x, kc.y))
        }
        return resamplePolyline(rawPts, n)
    }

    fun collapseConsecutiveLetters(word: String): String {
        if (word.length <= 1) return word
        val sb = StringBuilder(word.length)
        var prev = '\u0000'
        for (c in word) {
            if (c != prev) {
                sb.append(c)
                prev = c
            }
        }
        return sb.toString()
    }

    /**
     * Detects salient inflection/corner points along a normalized path and returns nearest key candidates.
     */
    fun extractAnchorKeys(points: List<TouchPoint>): List<Char> {
        if (points.isEmpty()) return emptyList()
        val anchors = ArrayList<Char>()
        fun addKey(ch: Char) {
            if (anchors.lastOrNull() != ch) anchors.add(ch)
        }
        nearestKeys(points.first().x, points.first().y, 1).firstOrNull()?.let { addKey(it.first) }
        for (i in 1 until points.size - 1) {
            val ax = points[i].x - points[i - 1].x
            val ay = points[i].y - points[i - 1].y
            val bx = points[i + 1].x - points[i].x
            val by = points[i + 1].y - points[i].y
            val la = hypot(ax.toDouble(), ay.toDouble()).toFloat()
            val lb = hypot(bx.toDouble(), by.toDouble()).toFloat()
            if (la > 0.04f && lb > 0.04f) {
                val cosTheta = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
                if (cosTheta < 0.72f || abs(ay) > 0.08f && cosTheta < 0.85f) {
                    nearestKeys(points[i].x, points[i].y, 1).firstOrNull()?.let { addKey(it.first) }
                }
            }
        }
        nearestKeys(points.last().x, points.last().y, 1).firstOrNull()?.let { addKey(it.first) }
        return anchors
    }
}
