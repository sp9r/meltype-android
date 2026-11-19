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

data class AnchorPoint(
    val x: Float,
    val y: Float,
    val nearestChar: Char,
    val sharpness: Float,
    val isEndpoint: Boolean
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
        // Row 1: a..l from the left edge plus "ー" at the right end (Gboard Japanese QWERTY)
        // -> centers at (i + 0.5) / 10
        for (i in ROWS[1].indices) {
            val ch = ROWS[1][i]
            val nx = (i + 0.5f) / 10f * ASPECT_RATIO
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

    /**
     * Generates a biomechanically smoothed template that models human corner-rounding
     * and interior reversal undershoot while keeping endpoints anchored.
     */
    fun smoothedTemplateForWord(word: String, n: Int = RESAMPLE_N): Pair<FloatArray, Float> {
        val rawPts = ArrayList<TouchPoint>(word.length)
        for (c in word) {
            val kc = centerOf(c) ?: continue
            rawPts.add(TouchPoint(kc.x, kc.y))
        }
        if (rawPts.size <= 2) {
            return resamplePolyline(rawPts, n)
        }
        val smoothed = ArrayList<TouchPoint>(rawPts.size)
        smoothed.add(rawPts[0])
        for (i in 1 until rawPts.lastIndex) {
            val prev = rawPts[i - 1]
            val cur = rawPts[i]
            val next = rawPts[i + 1]
            val v1x = cur.x - prev.x
            val v1y = cur.y - prev.y
            val v2x = next.x - cur.x
            val v2y = next.y - cur.y
            val l1 = kotlin.math.hypot(v1x, v1y)
            val l2 = kotlin.math.hypot(v2x, v2y)
            val cosTheta = if (l1 > 1e-5f && l2 > 1e-5f) {
                ((v1x * v2x + v1y * v2y) / (l1 * l2)).coerceIn(-1f, 1f)
            } else {
                1f
            }
            val alpha = 0.21f - 0.11f * cosTheta
            val midX = (prev.x + next.x) * 0.5f
            val midY = (prev.y + next.y) * 0.5f
            smoothed.add(
                TouchPoint(
                    cur.x + (midX - cur.x) * alpha,
                    cur.y + (midY - cur.y) * alpha
                )
            )
        }
        smoothed.add(rawPts[rawPts.lastIndex])
        return resamplePolyline(smoothed, n)
    }

    /**
     * Returns cumulative normalized Euclidean arc fraction [0f..1f] for each key in [word].
     */
    fun keyArcFractionsForWord(word: String): FloatArray {
        val rawPts = ArrayList<TouchPoint>(word.length)
        for (c in word) {
            val kc = centerOf(c) ?: continue
            rawPts.add(TouchPoint(kc.x, kc.y))
        }
        val m = rawPts.size
        if (m == 0) return FloatArray(0)
        if (m == 1) return floatArrayOf(0f)
        val cum = FloatArray(m)
        var total = 0f
        for (i in 1 until m) {
            val d = kotlin.math.hypot(rawPts[i].x - rawPts[i - 1].x, rawPts[i].y - rawPts[i - 1].y)
            total += d
            cum[i] = total
        }
        if (total < 1e-5f) {
            return FloatArray(m) { idx -> idx.toFloat() / (m - 1).toFloat() }
        }
        for (i in 0 until m) {
            cum[i] /= total
        }
        return cum
    }

    /**
     * Flags interior keys that lie nearly straight along the segment between adjacent keys.
     */
    fun collinearFlagsForWord(word: String): BooleanArray {
        val rawPts = ArrayList<TouchPoint>(word.length)
        for (c in word) {
            val kc = centerOf(c) ?: continue
            rawPts.add(TouchPoint(kc.x, kc.y))
        }
        val m = rawPts.size
        val flags = BooleanArray(m)
        if (m < 3) return flags
        for (i in 1 until m - 1) {
            val prev = rawPts[i - 1]
            val cur = rawPts[i]
            val next = rawPts[i + 1]
            val v1x = cur.x - prev.x
            val v1y = cur.y - prev.y
            val v2x = next.x - cur.x
            val v2y = next.y - cur.y
            val l1 = kotlin.math.hypot(v1x, v1y)
            val l2 = kotlin.math.hypot(v2x, v2y)
            if (l1 < 1e-4f || l2 < 1e-4f) continue
            val cosTheta = (v1x * v2x + v1y * v2y) / (l1 * l2)
            if (cosTheta < 0.88f) continue
            val segDx = next.x - prev.x
            val segDy = next.y - prev.y
            val segLen = kotlin.math.hypot(segDx, segDy)
            if (segLen < 1e-4f) continue
            val perp = kotlin.math.abs(segDx * (cur.y - prev.y) - segDy * (cur.x - prev.x)) / segLen
            if (perp <= 0.14f) {
                flags[i] = true
            }
        }
        return flags
    }

    /**
     * Centroid-and-scale normalized polyline (SHARK2 Shape Channel) from resampled [2*n] coords.
     */
    fun normalizeShape(resampledPath: FloatArray, n: Int = RESAMPLE_N): FloatArray {
        if (resampledPath.size < 2 * n || n <= 0) return FloatArray(0)
        var cx = 0f
        var cy = 0f
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until n) {
            val x = resampledPath[i * 2]
            val y = resampledPath[i * 2 + 1]
            cx += x
            cy += y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        cx /= n.toFloat()
        cy /= n.toFloat()
        val scale = kotlin.math.max(kotlin.math.max(maxX - minX, maxY - minY), 0.22f)
        val out = FloatArray(2 * n)
        for (i in 0 until n) {
            out[i * 2] = (resampledPath[i * 2] - cx) / scale
            out[i * 2 + 1] = (resampledPath[i * 2 + 1] - cy) / scale
        }
        return out
    }

    /**
     * Normalized step direction vectors (length 2*(n-1)) for translation/scale-invariant matching.
     */
    fun directionVectors(resampledPath: FloatArray, n: Int = RESAMPLE_N): FloatArray {
        if (resampledPath.size < 2 * n || n <= 1) return FloatArray(0)
        val dirs = FloatArray(2 * (n - 1))
        for (i in 0 until n - 1) {
            val dx = resampledPath[(i + 1) * 2] - resampledPath[i * 2]
            val dy = resampledPath[(i + 1) * 2 + 1] - resampledPath[i * 2 + 1]
            val len = kotlin.math.hypot(dx, dy)
            if (len > 1e-5f) {
                dirs[i * 2] = dx / len
                dirs[i * 2 + 1] = dy / len
            }
        }
        return dirs
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

    fun letterMask(word: String): Int {
        var mask = 0
        for (c in word) {
            val idx = c.lowercaseChar() - 'a'
            if (idx in 0 until 26) {
                mask = mask or (1 shl idx)
            }
        }
        return mask
    }

    /**
     * Simplifies raw touch points slightly by removing jitter steps < [minStep],
     * then extracts start, salient turn/inflection corners, and end anchors.
     */
    fun extractAnchorPoints(points: List<TouchPoint>, minStep: Float = 0.055f): List<AnchorPoint> {
        if (points.isEmpty()) return emptyList()
        val simplified = ArrayList<TouchPoint>(points.size)
        simplified.add(points.first())
        for (i in 1 until points.size - 1) {
            val prev = simplified.last()
            val cur = points[i]
            val d = hypot((cur.x - prev.x).toDouble(), (cur.y - prev.y).toDouble()).toFloat()
            if (d >= minStep) {
                simplified.add(cur)
            }
        }
        if (points.size > 1) {
            simplified.add(points.last())
        }

        val anchors = ArrayList<AnchorPoint>()
        nearestKeys(simplified.first().x, simplified.first().y, 1).firstOrNull()?.let { (ch, _) ->
            anchors.add(AnchorPoint(simplified.first().x, simplified.first().y, ch, 1.0f, isEndpoint = true))
        }
        for (i in 1 until simplified.size - 1) {
            val ax = simplified[i].x - simplified[i - 1].x
            val ay = simplified[i].y - simplified[i - 1].y
            val bx = simplified[i + 1].x - simplified[i].x
            val by = simplified[i + 1].y - simplified[i].y
            val la = hypot(ax.toDouble(), ay.toDouble()).toFloat()
            val lb = hypot(bx.toDouble(), by.toDouble()).toFloat()
            if (la >= minStep && lb >= minStep) {
                val cosTheta = ((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f)
                if (cosTheta < 0.78f || (abs(ay) > 0.07f && cosTheta < 0.88f)) {
                    nearestKeys(simplified[i].x, simplified[i].y, 1).firstOrNull()?.let { (ch, _) ->
                        val sharpness = ((1f - cosTheta) * 0.5f).coerceIn(0f, 1f)
                        anchors.add(AnchorPoint(simplified[i].x, simplified[i].y, ch, sharpness, isEndpoint = false))
                    }
                }
            }
        }
        if (simplified.size > 1) {
            nearestKeys(simplified.last().x, simplified.last().y, 1).firstOrNull()?.let { (ch, _) ->
                anchors.add(AnchorPoint(simplified.last().x, simplified.last().y, ch, 1.0f, isEndpoint = true))
            }
        }
        return anchors
    }

    /**
     * Detects salient inflection/corner points along a normalized path and returns nearest key candidates.
     */
    fun extractAnchorKeys(points: List<TouchPoint>): List<Char> {
        val raw = extractAnchorPoints(points)
        val keys = ArrayList<Char>(raw.size)
        for (a in raw) {
            if (keys.lastOrNull() != a.nearestChar) {
                keys.add(a.nearestChar)
            }
        }
        return keys
    }
}
