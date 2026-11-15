package io.github.sp9r.meltype.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Utility conversions: date/time shortcuts (kyou/ashita/ima/nau),
 * inline calculator expressions (e.g. 128*16=), and bracket pairs.
 */
object UtilityConverter {

    fun utilityCandidates(readingHiragana: String, rawAscii: String): List<String> {
        val out = linkedSetOf<String>()
        val now = Date()
        val cal = Calendar.getInstance()

        when (readingHiragana) {
            "きょう" -> {
                out.add(SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(now))
                out.add(SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(now))
                out.add(SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(now))
            }
            "あした", "あす" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val d = cal.time
                out.add(SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(d))
                out.add(SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(d))
            }
            "きのう" -> {
                cal.time = now
                cal.add(Calendar.DAY_OF_MONTH, -1)
                val d = cal.time
                out.add(SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(d))
                out.add(SimpleDateFormat("M月d日(E)", Locale.JAPAN).format(d))
            }
            "いま", "なう" -> {
                out.add(SimpleDateFormat("HH:mm", Locale.JAPAN).format(now))
                out.add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(now))
            }
        }

        val calc = evaluateCalculator(rawAscii)
        if (calc != null) {
            out.add(calc)
        }
        return out.toList()
    }

    fun evaluateCalculator(input: String): String? {
        val trimmed = input.trim()
        if (!trimmed.endsWith("=") || trimmed.length < 4) return null
        val expr = trimmed.dropLast(1).trim()
        if (!expr.any { it in charArrayOf('+', '-', '*', '/') }) return null
        if (!expr.all { it in '0'..'9' || it in charArrayOf('.', '+', '-', '*', '/', '(', ')', ' ') }) return null
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
