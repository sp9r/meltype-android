package io.github.sp9r.meltype.core

import io.github.sp9r.meltype.glide.GlideDebugResult
import io.github.sp9r.meltype.glide.GlideScoreBreakdown
import io.github.sp9r.meltype.glide.TouchPoint
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

data class GlideEvalSummary(
    val totalEvents: Int,
    val glideCount: Int,
    val altPickCount: Int,
    val backspaceCount: Int,
    val glideUndoCount: Int,
    val tapCount: Int,
    val promptDoneCount: Int,
    val fileBytes: Long,
    val recentGlides: List<String>
)

/**
 * Opt-in local JSONL telemetry writer for diagnosing real-finger glide trajectories and
 * downstream Mozc/typing corrections inside app-private storage.
 */
class GlideEvalLogger(private val filesDir: File) {

    val logFile: File = File(filesDir, LOG_FILE_NAME)
    private val lock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun logGlide(
        rawPoints: List<TouchPoint>,
        normalizedPoints: List<TouchPoint>,
        boundsLeft: Float,
        boundsTop: Float,
        boundsWidth: Float,
        boundsHeight: Float,
        oneHandedMode: String,
        heightScale: Float,
        preferJapanese: Boolean,
        debugResult: GlideDebugResult,
        composingBefore: String,
        snapshotAfter: CompositionSnapshot,
        promptId: String,
        expectedText: String
    ) {
        val now = System.currentTimeMillis()
        val sampledNorm = downsampleNormalized(normalizedPoints, MAX_STORED_POINTS)
        val t0 = rawPoints.firstOrNull()?.tMs ?: 0L
        val rawDurationMs = ((rawPoints.lastOrNull()?.tMs ?: t0) - t0).coerceAtLeast(0L)
        val normT0 = sampledNorm.firstOrNull()?.tMs ?: t0
        val topList = debugResult.candidates
        val chosen = topList.firstOrNull()

        val line = buildString(1024) {
            append('{')
            appendKV("ts", now); append(',')
            appendKVStr("type", "glide"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("expected", expectedText); append(',')
            appendKV("preferJp", preferJapanese); append(',')
            appendKVStr("oneHanded", oneHandedMode); append(',')
            appendKVNum("heightScale", round3(heightScale)); append(',')
            append("\"bounds\":[")
            append(round1(boundsLeft)).append(',')
            append(round1(boundsTop)).append(',')
            append(round1(boundsWidth)).append(',')
            append(round1(boundsHeight)).append("],")
            appendKV("rawPtCount", rawPoints.size); append(',')
            appendKV("normPtCount", sampledNorm.size); append(',')
            appendKV("durationMs", rawDurationMs); append(',')
            appendKVNum("pathLen", round4(debugResult.pathLen)); append(',')
            appendKVStr("anchorSeq", debugResult.anchorSeq); append(',')
            appendKeyPairArray("startKeys", debugResult.startKeys); append(',')
            appendKeyPairArray("endKeys", debugResult.endKeys); append(',')
            append("\"pts\":[")
            for (i in sampledNorm.indices) {
                if (i > 0) append(',')
                val p = sampledNorm[i]
                val dt = (p.tMs - normT0).coerceAtLeast(0L)
                append('[').append(round4(p.x)).append(',').append(round4(p.y)).append(',').append(dt).append(']')
            }
            append("],")
            appendKVStr("chosen", chosen?.word.orEmpty()); append(',')
            appendKVStr("chosenKana", chosen?.kanaHint.orEmpty()); append(',')
            appendKVStr("composingBefore", composingBefore); append(',')
            appendKVStr("composingAfter", snapshotAfter.previewText); append(',')
            append("\"clausesAfter\":[")
            for (i in snapshotAfter.clauses.indices) {
                if (i > 0) append(',')
                val c = snapshotAfter.clauses[i]
                append('{')
                appendKVStr("raw", c.raw); append(',')
                appendKVStr("reading", c.reading); append(',')
                appendKVStr("text", c.text); append(',')
                appendKV("isEn", c.isEnglish); append(',')
                append("\"cands\":[")
                val topC = c.candidates.take(4)
                for (j in topC.indices) {
                    if (j > 0) append(',')
                    appendJsonStr(this, topC[j])
                }
                append("]}")
            }
            append("],")
            append("\"top\":[")
            for (i in topList.indices) {
                if (i > 0) append(',')
                appendBreakdownJson(this, topList[i])
            }
            append("]}")
        }
        appendLineAsync(line)
    }

    fun logGlideAltPicked(
        promptId: String,
        fromRaw: String,
        pickedWord: String,
        isEnglish: Boolean,
        previewAfter: String
    ) {
        val line = buildString(256) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "glide_alt"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("fromRaw", fromRaw); append(',')
            appendKVStr("pickedWord", pickedWord); append(',')
            appendKV("isEn", isEnglish); append(',')
            appendKVStr("previewAfter", previewAfter)
            append('}')
        }
        appendLineAsync(line)
    }

    fun logCandidatePicked(
        promptId: String,
        clauseReading: String,
        pickedText: String,
        kind: String,
        previewAfter: String
    ) {
        val line = buildString(256) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "cand_pick"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("clauseReading", clauseReading); append(',')
            appendKVStr("pickedText", pickedText); append(',')
            appendKVStr("kind", kind); append(',')
            appendKVStr("previewAfter", previewAfter)
            append('}')
        }
        appendLineAsync(line)
    }

    fun logTap(
        promptId: String,
        ch: Char,
        directCommit: Boolean,
        composingAfter: String
    ) {
        val line = buildString(192) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "tap"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("ch", ch.toString()); append(',')
            appendKV("direct", directCommit); append(',')
            appendKVStr("composingAfter", composingAfter)
            append('}')
        }
        appendLineAsync(line)
    }

    fun logBackspace(
        promptId: String,
        action: String,
        detail: String,
        composingAfter: String
    ) {
        val line = buildString(192) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "backspace"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("action", action); append(',')
            appendKVStr("detail", detail); append(',')
            appendKVStr("composingAfter", composingAfter)
            append('}')
        }
        appendLineAsync(line)
    }

    fun logCommit(
        promptId: String,
        rawInput: String,
        committedText: String,
        via: String,
        autoText: String? = null,
        actions: List<String> = emptyList(),
        clauses: String = "",
        englishMode: Boolean = false
    ) {
        val line = buildString(256) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "commit"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("rawInput", rawInput); append(',')
            appendKVStr("committed", committedText); append(',')
            appendKVStr("via", via)
            if (autoText != null) {
                append(','); appendKVStr("auto", autoText)
                append(','); appendKVStr("actions", actions.joinToString(" "))
                append(','); appendKVStr("clauses", clauses)
                append(','); appendKV("en", englishMode)
            }
            append('}')
        }
        appendLineAsync(line)
    }

    /** Generic usage event (language mode switch etc.). */
    fun logEvent(kind: String, detail: String, preview: String) {
        val line = buildString(128) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", kind); append(',')
            appendKVStr("detail", detail); append(',')
            appendKVStr("preview", preview)
            append('}')
        }
        appendLineAsync(line)
    }

    fun logPromptDone(
        promptId: String,
        category: String,
        expected: String,
        actual: String,
        action: String
    ) {
        val line = buildString(320) {
            append('{')
            appendKV("ts", System.currentTimeMillis()); append(',')
            appendKVStr("type", "prompt_done"); append(',')
            appendKVStr("promptId", promptId); append(',')
            appendKVStr("category", category); append(',')
            appendKVStr("expected", expected); append(',')
            appendKVStr("actual", actual); append(',')
            appendKV("exactMatch", actual.trim() == expected.trim()); append(',')
            appendKVStr("action", action)
            append('}')
        }
        appendLineAsync(line)
    }

    fun flush() {
        runCatching { ioExecutor.submit {}.get() }
    }

    fun clear() {
        runCatching {
            ioExecutor.submit {
                synchronized(lock) {
                    if (logFile.exists()) {
                        logFile.delete()
                    }
                }
            }.get()
        }
    }

    fun readSummary(): GlideEvalSummary {
        flush()
        synchronized(lock) {
            if (!logFile.isFile) {
                return GlideEvalSummary(0, 0, 0, 0, 0, 0, 0, 0L, emptyList())
            }
            var total = 0
            var glides = 0
            var alts = 0
            var backspaces = 0
            var glideUndos = 0
            var taps = 0
            var promptDone = 0
            val recent = ArrayDeque<String>(4)
            logFile.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                total++
                when (extractStrField(line, "type")) {
                    "glide" -> {
                        glides++
                        val chosen = extractStrField(line, "chosen")
                        val kana = extractStrField(line, "chosenKana")
                        val anchors = extractStrField(line, "anchorSeq")
                        val item = if (kana.isNotEmpty() && kana != chosen) {
                            "$chosen($kana) [$anchors]"
                        } else {
                            "$chosen [$anchors]"
                        }
                        if (recent.size >= 3) recent.removeFirst()
                        recent.addLast(item)
                    }
                    "glide_alt" -> alts++
                    "backspace" -> {
                        backspaces++
                        if (extractStrField(line, "action") == "glide_undo") {
                            glideUndos++
                        }
                    }
                    "tap" -> taps++
                    "prompt_done" -> promptDone++
                }
            }
            return GlideEvalSummary(
                totalEvents = total,
                glideCount = glides,
                altPickCount = alts,
                backspaceCount = backspaces,
                glideUndoCount = glideUndos,
                tapCount = taps,
                promptDoneCount = promptDone,
                fileBytes = logFile.length(),
                recentGlides = recent.toList()
            )
        }
    }

    private fun appendLineAsync(line: String) {
        ioExecutor.execute {
            synchronized(lock) {
                runCatching {
                    filesDir.mkdirs()
                    if (logFile.length() > MAX_LOG_BYTES) {
                        // Keep one previous generation so the log never grows without bound.
                        val old = File(filesDir, "$LOG_FILE_NAME.1")
                        old.delete()
                        logFile.renameTo(old)
                    }
                    logFile.appendText(line + "\n", Charsets.UTF_8)
                }
            }
        }
    }

    private fun downsampleNormalized(points: List<TouchPoint>, maxPoints: Int): List<TouchPoint> {
        if (points.size <= maxPoints) return points
        val out = ArrayList<TouchPoint>(maxPoints)
        val lastIdx = points.lastIndex
        for (i in 0 until maxPoints) {
            val idx = ((i.toLong() * lastIdx) / (maxPoints - 1)).toInt().coerceIn(0, lastIdx)
            out.add(points[idx])
        }
        return out
    }

    private fun appendBreakdownJson(sb: StringBuilder, b: GlideScoreBreakdown) {
        sb.append('{')
        sb.appendKVStr("w", b.word); sb.append(',')
        sb.appendKVStr("tr", b.traceWord); sb.append(',')
        sb.appendKV("en", b.isEnglish); sb.append(',')
        sb.appendKVStr("kana", b.kanaHint.orEmpty()); sb.append(',')
        sb.appendKVNum("cost", round4(b.scoreCost)); sb.append(',')
        sb.appendKVNum("base", round4(b.baseCost)); sb.append(',')
        sb.appendKVNum("endp", round4(b.endpointCost)); sb.append(',')
        sb.appendKVNum("loc", round4(b.locDist)); sb.append(',')
        sb.appendKVNum("shp", round4(b.shapeDist)); sb.append(',')
        sb.appendKVNum("dir", round4(b.dirDist)); sb.append(',')
        sb.appendKVNum("wp", round4(b.wpPenalty)); sb.append(',')
        sb.appendKVNum("crn", round4(b.cornerAdj)); sb.append(',')
        sb.appendKVNum("freq", round4(b.freqBonus)); sb.append(',')
        sb.appendKVNum("lp", round4(b.langPrior)); sb.append(',')
        sb.appendKVStr("src", b.source)
        sb.append('}')
    }

    private fun StringBuilder.appendKeyPairArray(key: String, pairs: List<Pair<Char, Float>>) {
        appendJsonStr(this, key)
        append(":[")
        for (i in pairs.indices) {
            if (i > 0) append(',')
            append('[').append('"').append(pairs[i].first).append("\",").append(round3(pairs[i].second)).append(']')
        }
        append(']')
    }

    private fun StringBuilder.appendKV(key: String, v: Long) {
        appendJsonStr(this, key); append(':').append(v)
    }

    private fun StringBuilder.appendKV(key: String, v: Int) {
        appendJsonStr(this, key); append(':').append(v)
    }

    private fun StringBuilder.appendKV(key: String, v: Boolean) {
        appendJsonStr(this, key); append(':').append(v)
    }

    private fun StringBuilder.appendKVNum(key: String, numStr: String) {
        appendJsonStr(this, key); append(':').append(numStr)
    }

    private fun StringBuilder.appendKVStr(key: String, value: String) {
        appendJsonStr(this, key); append(':'); appendJsonStr(this, value)
    }

    private fun appendJsonStr(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.US, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }

    private fun round1(v: Float): String = String.format(Locale.US, "%.1f", if (v.isFinite()) v else 0f)
    private fun round3(v: Float): String = String.format(Locale.US, "%.3f", if (v.isFinite()) v else 0f)
    private fun round4(v: Float): String = String.format(Locale.US, "%.4f", if (v.isFinite()) v else 0f)

    private fun extractStrField(jsonLine: String, field: String): String {
        val needle = "\"$field\":\""
        val start = jsonLine.indexOf(needle)
        if (start < 0) return ""
        val sb = StringBuilder()
        var i = start + needle.length
        while (i < jsonLine.length) {
            val ch = jsonLine[i]
            if (ch == '"') break
            if (ch == '\\' && i + 1 < jsonLine.length) {
                i++
                when (val esc = jsonLine[i]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(esc)
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    companion object {
        const val LOG_FILE_NAME = "glide_eval_log.jsonl"
        private const val MAX_STORED_POINTS = 72
        private const val MAX_LOG_BYTES = 8L * 1024 * 1024
    }
}
