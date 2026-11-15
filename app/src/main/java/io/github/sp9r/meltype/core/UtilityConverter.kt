package io.github.sp9r.meltype.core

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Utility conversions: date/era/time shortcuts (kyou/ashita/asatte/ima/kotoshi),
 * numeric formatting (comma-separated, full-width, Kanji numbers),
 * and inline calculator expressions (with or without trailing '=').
 */
object UtilityConverter {

    fun utilityCandidates(readingHiragana: String, rawAscii: String): List<String> {
        val out = linkedSetOf<String>()
        val now = Date()
        val cal = Calendar.getInstance()

        fun addDateFormats(d: Date, c: Calendar) {
            out.add(SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(d))
            out.add(SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(d))
            out.add(SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(d))
            val reiwaYear = c.get(Calendar.YEAR) - 2018
            if (reiwaYear >= 1) {
                val month = c.get(Calendar.MONTH) + 1
                val day = c.get(Calendar.DAY_OF_MONTH)
                val yearLabel = if (reiwaYear == 1) "令和元年" else "令和${reiwaYear}年"
                out.add("${yearLabel}${month}月${day}日")
            }
        }

        when (readingHiragana) {
            "きょう" -> addDateFormats(now, cal)
            "あした", "あす" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, 1)
                addDateFormats(cal.time, cal)
            }
            "あさって" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, 2)
                addDateFormats(cal.time, cal)
            }
            "きのう" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, -1)
                addDateFormats(cal.time, cal)
            }
            "おととい" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, -2)
                addDateFormats(cal.time, cal)
            }
            "ことし" -> {
                val y = cal.get(Calendar.YEAR)
                out.add("${y}年")
                if (y >= 2019) {
                    val rw = y - 2018
                    out.add(if (rw == 1) "令和元年" else "令和${rw}年")
                }
            }
            "いま", "なう" -> {
                out.add(SimpleDateFormat("HH:mm", Locale.JAPAN).format(now))
                out.add(SimpleDateFormat("H時mm分", Locale.JAPAN).format(now))
                out.add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(now))
            }
        }

        val calc = evaluateCalculator(rawAscii)
        if (calc != null) {
            out.add(calc)
        }
        out.addAll(numericCandidates(rawAscii))
        return out.toList()
    }

    fun numericCandidates(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 15 || !trimmed.all { it in '0'..'9' }) return emptyList()
        val out = linkedSetOf<String>()
        val num = trimmed.toLongOrNull()
        if (num != null) {
            if (trimmed.length >= 4) {
                out.add(DecimalFormat("#,###").format(num))
            }
            toKanjiFinancialOrReading(num)?.let { out.add(it) }
        }
        out.add(toFullWidthDigits(trimmed))
        if (trimmed.length in 1..4) {
            val positional = trimmed.map { KANJI_DIGITS[it - '0'] }.joinToString("")
            out.add(positional)
        }
        return out.toList()
    }

    fun evaluateCalculator(input: String): String? {
        val trimmed = input.trim()
        val hasEquals = trimmed.endsWith("=")
        val expr = if (hasEquals) trimmed.dropLast(1).trim() else trimmed
        if (hasEquals && trimmed.length < 4) return null
        if (!hasEquals && expr.length < 3) return null
        if (!expr.any { it in charArrayOf('+', '-', '*', '/') }) return null
        // Avoid treating negative numbers or dates without operator between operands as math without '='
        if (!hasEquals && !expr.any { it.isDigit() }) return null
        if (!expr.all { it in '0'..'9' || it in charArrayOf('.', '+', '-', '*', '/', '(', ')', ' ') }) return null
        if (!hasEquals) {
            val opIdx = expr.indexOfAny(charArrayOf('+', '-', '*', '/'), startIndex = 1)
            if (opIdx < 1 || opIdx >= expr.length - 1) return null
            if (!expr.substring(opIdx + 1).any { it.isDigit() }) return null
        }
        return runCatching {
            val value = ExprParser(expr).parse()
            if (value.isNaN() || value.isInfinite()) return null
            if (abs(value - value.toLong()) < 1e-9) {
                value.toLong().toString()
            } else {
                String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
            }
        }.getOrNull()
    }

    private fun toFullWidthDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(if (c in '0'..'9') (c.code - '0'.code + '０'.code).toChar() else c)
        }
        return sb.toString()
    }

    private fun toKanjiFinancialOrReading(n: Long): String? {
        if (n < 0 || n >= 1_0000_0000_0000L) return null
        if (n == 0L) return "〇"
        val bigUnits = arrayOf("", "万", "億", "兆")
        val sb = StringBuilder()
        var rem = n
        var unitIdx = 0
        while (rem > 0) {
            val chunk = (rem % 10000).toInt()
            if (chunk > 0) {
                val chunkStr = fourDigitsToKanji(chunk)
                sb.insert(0, chunkStr + bigUnits[unitIdx])
            }
            rem /= 10000
            unitIdx++
        }
        return sb.toString()
    }

    private fun fourDigitsToKanji(chunk: Int): String {
        val smallUnits = arrayOf("", "十", "百", "千")
        val digits = intArrayOf(chunk / 1000, (chunk / 100) % 10, (chunk / 10) % 10, chunk % 10)
        val sb = StringBuilder()
        for (i in 0 until 4) {
            val d = digits[i]
            if (d == 0) continue
            val unitPos = 3 - i
            if (d > 1 || unitPos == 0) {
                sb.append(KANJI_DIGITS[d])
            }
            sb.append(smallUnits[unitPos])
        }
        return sb.toString()
    }

    private val KANJI_DIGITS = charArrayOf('〇', '一', '二', '三', '四', '五', '六', '七', '八', '九')

    private class ExprParser(private val expr: String) {
        private var pos = 0

        fun parse(): Double {
            val res = parseExpression()
            skipWs()
            if (pos != expr.length) throw IllegalArgumentException("Trailing chars")
            return res
        }

        private fun skipWs() {
            while (pos < expr.length && expr[pos] == ' ') pos++
        }

        private fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                skipWs()
                if (pos >= expr.length) return x
                when (expr[pos]) {
                    '+' -> { pos++; x += parseTerm() }
                    '-' -> { pos++; x -= parseTerm() }
                    else -> return x
                }
            }
        }

        private fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                skipWs()
                if (pos >= expr.length) return x
                when (expr[pos]) {
                    '*' -> { pos++; x *= parseFactor() }
                    '/' -> { pos++; x /= parseFactor() }
                    else -> return x
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            if (pos < expr.length && expr[pos] == '+') {
                pos++
                return parseFactor()
            }
            if (pos < expr.length && expr[pos] == '-') {
                pos++
                return -parseFactor()
            }
            if (pos < expr.length && expr[pos] == '(') {
                pos++
                val v = parseExpression()
                skipWs()
                if (pos < expr.length && expr[pos] == ')') pos++
                return v
            }
            val start = pos
            while (pos < expr.length && (expr[pos] in '0'..'9' || expr[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("Unexpected char")
            return expr.substring(start, pos).toDouble()
        }
    }
}
