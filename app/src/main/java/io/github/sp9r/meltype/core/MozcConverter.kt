package io.github.sp9r.meltype.core

import android.content.Context
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJni
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class ConversionClause(
    val reading: String,
    val text: String
)

interface KanjiConverter {
    val engineName: String
    fun convertClauses(hiragana: String, context: String? = null): List<ConversionClause>?
    fun candidates(reading: String): List<String>
    fun learn(context: String?, clauses: List<ConversionClause>): Boolean
}

object MozcRuntime {
    private val lock = Any()

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var dataVersion: String = ""
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        synchronized(lock) {
            if (initialized) return true
            return try {
                val appCtx = context.applicationContext
                val profileDir = File(appCtx.filesDir, "mozc")
                if (!profileDir.exists()) profileDir.mkdirs()
                val dictFile = File(profileDir, "mozc.data")
                val expectedSize = appCtx.assets.openFd("mozc.data").use { it.length }
                if (!dictFile.exists() || (expectedSize > 0 && dictFile.length() != expectedSize)) {
                    appCtx.assets.open("mozc.data").use { input ->
                        FileOutputStream(dictFile).use { output ->
                            val buf = ByteArray(1024 * 256)
                            while (true) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                            }
                        }
                    }
                }
                val ok = MozcJni.onPostLoad(profileDir.absolutePath, dictFile.absolutePath)
                if (!ok) {
                    lastError = "MozcJni.onPostLoad returned false"
                    false
                } else {
                    val ver = MozcJni.getDataVersion().orEmpty()
                    if (ver.isEmpty()) {
                        lastError = "MozcJni.getDataVersion is empty"
                        false
                    } else {
                        dataVersion = ver
                        lastError = null
                        initialized = true
                        true
                    }
                }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                false
            }
        }
    }

    fun evaluate(inputBytes: ByteArray): ByteArray {
        synchronized(lock) {
            val wrapped = Proto.blob(1, inputBytes)
            val commandOut = MozcJni.evalCommand(wrapped)
            return Proto.parse(commandOut).bytes(2)
        }
    }
}

/**
 * Embedded OSS Mozc converter using MozcJni + proto2 wire protocol,
 * with dictionary-assisted fallback when running outside Android (e.g. JVM unit tests).
 */
class AndroidMozcConverter(
    private val dictionaries: MeltypeDictionaries? = null,
    private val userDictProvider: () -> Map<String, String> = { emptyMap() },
    private val canLearn: () -> Boolean = { true },
    private val transport: ((ByteArray) -> ByteArray)? = null
) : KanjiConverter {

    private val gate = Any()
    private val cachedCandidates = LinkedHashMap<String, List<String>>()
    private val cachedResults = LinkedHashMap<Triple<String, String?, Boolean>, SavedConversion>()
    private var lastLearningMode: Boolean? = null

    private data class SavedConversion(
        val clauses: List<ConversionClause>,
        val candidates: Map<String, List<String>>
    )

    override val engineName: String
        get() = if (transport != null || MozcRuntime.initialized) {
            "Mozc JNI (${MozcRuntime.dataVersion.ifEmpty { "custom" }})"
        } else {
            "Dictionary Fallback"
        }

    private fun evalTransport(request: ByteArray): ByteArray {
        return transport?.invoke(request) ?: MozcRuntime.evaluate(request)
    }

    override fun convertClauses(hiragana: String, context: String?): List<ConversionClause>? {
        if (hiragana.isEmpty()) return emptyList()
        if (hiragana.length > 100 || hiragana.any { it.isISOControl() }) return null

        // If user dictionary has an exact full match, prioritize it cleanly
        val userDict = userDictProvider()
        val exactUser = userDict[hiragana]
        if (exactUser != null) {
            synchronized(gate) {
                cachedCandidates[hiragana] = listOf(exactUser, hiragana, RomajiDetector.toKatakana(hiragana)).distinct()
            }
            return listOf(ConversionClause(hiragana, exactUser))
        }

        if (transport == null && !MozcRuntime.initialized) {
            return fallbackConvert(hiragana)
        }

        synchronized(gate) {
            return convertCore(hiragana, context, System.currentTimeMillis(), singleClause = false)
                ?: fallbackConvert(hiragana)
        }
    }

    override fun candidates(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val extras = collectExtraCandidates(reading)

        if (transport == null && !MozcRuntime.initialized) {
            return (extras + listOf(reading, RomajiDetector.toKatakana(reading))).distinct()
        }

        synchronized(gate) {
            checkLearningMode()
            val cached = cachedCandidates[reading]
            if (cached != null) {
                return mergeCandidates(reading, cached, extras)
            }
            val startMs = System.currentTimeMillis()
            val clauses = convertCore(reading, null, startMs, singleClause = false)
            if (clauses != null && clauses.size > 1) {
                convertCore(reading, null, startMs, singleClause = true)
            }
            val fetched = cachedCandidates[reading].orEmpty()
            return mergeCandidates(reading, fetched, extras)
        }
    }

    private fun mergeCandidates(reading: String, mozc: List<String>, extras: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(collectPriorityExtras(reading))
        out.addAll(mozc)
        out.addAll(extras)
        out.add(reading)
        out.add(RomajiDetector.toKatakana(reading))
        dictionaries?.emojiCandidates?.get(reading)?.let { out.addAll(it) }
        return out.toList()
    }

    private fun collectPriorityExtras(reading: String): List<String> {
        val out = LinkedHashSet<String>()
        userDictProvider()[reading]?.let { out.add(it) }
        dictionaries?.readingToPhrases?.get(reading)?.let { out.add(it) }
        return out.toList()
    }

    private fun collectExtraCandidates(reading: String): List<String> {
        val out = LinkedHashSet<String>()
        dictionaries?.kanaToBrandsAndLoanwords?.get(reading)?.let { out.addAll(it) }
        dictionaries?.extraCandidates?.get(reading)?.let { out.addAll(it) }
        return out.toList()
    }

    private fun checkLearningMode() {
        val mode = canLearn()
        if (lastLearningMode != mode) {
            lastLearningMode = mode
            cachedResults.clear()
            cachedCandidates.clear()
        }
    }

    private fun convertCore(
        hiragana: String,
        context: String?,
        startMs: Long,
        singleClause: Boolean
    ): List<ConversionClause>? {
        checkLearningMode()
        val key = Triple(hiragana, context, singleClause)
        cachedResults[key]?.let { saved ->
            cachedCandidates.clear()
            cachedCandidates.putAll(saved.candidates)
            return saved.clauses
        }

        var session = 0L
        cachedCandidates.clear()

        fun call(kind: Int, payload: ByteArray = ByteArray(0)): Proto {
            if (kind != 2 && System.currentTimeMillis() - startMs > 2000L) {
                throw IllegalStateException("Mozc conversion timeout")
            }
            val req = Proto.concat(
                Proto.number(1, kind.toLong()),
                if (session == 0L) ByteArray(0) else Proto.number(2, session),
                payload
            )
            val output = Proto.parse(evalTransport(req))
            if (output.int(11) != 0L || output.has(4)) {
                throw IllegalStateException("Mozc returned error=${output.int(11)}")
            }
            return output
        }

        fun command(kind: Int, extra: ByteArray = ByteArray(0)): Proto {
            return call(5, Proto.blob(4, Proto.concat(Proto.number(1, kind.toLong()), extra)))
        }

        return try {
            session = call(1, Proto.blob(7, ByteArray(0))).int(1)
            if (session == 0L) return null

            val incognitoVal = if (canLearn()) 0L else 1L
            call(17, Proto.blob(9, Proto.number(23, incognitoVal)))
            command(22, Proto.number(3, 1L)) // TURN_ON_IME + HIRAGANA

            val surrounding = if (context.isNullOrEmpty()) {
                ByteArray(0)
            } else {
                Proto.blob(6, Proto.string(1, context))
            }
            val update = Proto.concat(
                Proto.number(1, 26L),
                Proto.blob(11, Proto.string(1, hiragana))
            )
            call(5, Proto.concat(Proto.blob(4, update), surrounding))
            var output = call(3, Proto.concat(Proto.blob(3, Proto.number(3, 4L)), surrounding)) // SPACE

            var clauses = readClauses(output)
            if (!matchesReading(clauses, hiragana)) return null

            if (singleClause && clauses.size > 1) {
                val merged = extendCandidateClause(output, hiragana) {
                    call(3, Proto.blob(3, Proto.concat(Proto.number(3, 7L), Proto.number(4, 4L))))
                } ?: return null
                output = merged
                clauses = readClauses(output)
            }

            var offset = 0
            val finalClauses = ArrayList<ConversionClause>(clauses.size)
            for (c in clauses) {
                val orig = hiragana.substring(offset, (offset + c.reading.length).coerceAtMost(hiragana.length))
                val restored = restoreSymbolWidth(c.text, orig)
                finalClauses.add(ConversionClause(orig, restored))
                offset += orig.length
            }

            for (index in finalClauses.indices) {
                if (index > 0) {
                    output = call(3, Proto.blob(3, Proto.number(3, 7L))) // RIGHT: focus next clause
                }
                var words = output.message(14).messages(2)
                    .map { it.text(4) }
                    .filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    words = output.message(6).groups(3)
                        .map { it.text(5) }
                        .filter { it.isNotEmpty() }
                }
                val r = finalClauses[index].reading
                val cands = (listOf(finalClauses[index].text) + words.map { restoreSymbolWidth(it, r) }).distinct()
                cachedCandidates[r] = cands
            }

            if (cachedResults.size >= 32) cachedResults.clear()
            cachedResults[key] = SavedConversion(finalClauses.toList(), LinkedHashMap(cachedCandidates))
            finalClauses
        } catch (_: Throwable) {
            cachedCandidates.clear()
            null
        } finally {
            if (session != 0L) {
                runCatching { call(2) }
            }
        }
    }

    override fun learn(context: String?, clauses: List<ConversionClause>): Boolean {
        if (!canLearn() || clauses.isEmpty()) return false
        if (transport == null && !MozcRuntime.initialized) return false
        val reading = clauses.joinToString("") { it.reading }
        if (reading.isEmpty() || reading.length > 100 || reading.any { it.isISOControl() }) return false
        if (clauses.any { it.text.isEmpty() || it.text.any { ch -> ch.isISOControl() } }) return false

        synchronized(gate) {
            cachedResults.clear()
            cachedCandidates.clear()
            var session = 0L
            val startMs = System.currentTimeMillis()

            fun call(kind: Int, payload: ByteArray = ByteArray(0), allowResult: Boolean = false): Proto {
                if (kind != 2 && System.currentTimeMillis() - startMs > 2000L) {
                    throw IllegalStateException("Mozc learning timeout")
                }
                val req = Proto.concat(
                    Proto.number(1, kind.toLong()),
                    if (session == 0L) ByteArray(0) else Proto.number(2, session),
                    payload
                )
                val output = Proto.parse(evalTransport(req))
                if (output.int(11) != 0L || (!allowResult && output.has(4))) {
                    throw IllegalStateException("Unexpected Mozc response during learn")
                }
                return output
            }

            return try {
                val config = call(6).message(9)
                if (config.int(20) != 0L || config.int(50) != 0L) return false
                session = call(1, Proto.blob(7, ByteArray(0))).int(1)
                if (session == 0L) return false
                call(17, Proto.blob(9, Proto.number(23, 0L)))
                call(5, Proto.blob(4, Proto.concat(Proto.number(1, 22L), Proto.number(3, 1L))))
                val surrounding = if (context.isNullOrEmpty()) ByteArray(0) else Proto.blob(6, Proto.string(1, context))
                val update = Proto.concat(Proto.number(1, 26L), Proto.blob(11, Proto.string(1, reading)))
                call(5, Proto.concat(Proto.blob(4, update), surrounding))
                var output = call(3, Proto.concat(Proto.blob(3, Proto.number(3, 4L)), surrounding))

                val actualReadings = readClauses(output).map { it.reading }
                if (actualReadings != clauses.map { it.reading }) return false

                for (index in clauses.indices) {
                    if (index > 0) {
                        output = call(3, Proto.blob(3, Proto.number(3, 7L)))
                    }
                    val match = output.message(14).messages(2)
                        .firstOrNull { it.has(1) && it.text(4) == clauses[index].text }
                        ?: return false
                    output = call(
                        5,
                        Proto.blob(4, Proto.concat(Proto.number(1, 4L), Proto.number(2, match.int(1))))
                    )
                }

                val selected = readClauses(output).joinToString("") { it.text }
                val expected = clauses.joinToString("") { it.text }
                if (selected != expected) return false

                val result = call(5, Proto.blob(4, Proto.number(1, 2L)), allowResult = true)
                if (result.message(4).text(2) != expected) return false
                call(8)
                true
            } catch (_: Throwable) {
                false
            } finally {
                if (session != 0L) runCatching { call(2) }
            }
        }
    }

    private fun fallbackConvert(hiragana: String): List<ConversionClause> {
        dictionaries?.readingToPhrases?.get(hiragana)?.let {
            return listOf(ConversionClause(hiragana, it))
        }
        val builtIn = BUILTIN_FALLBACK[hiragana]
        if (builtIn != null) return listOf(ConversionClause(hiragana, builtIn))
        return listOf(ConversionClause(hiragana, hiragana))
    }

    private fun readClauses(output: Proto): List<ConversionClause> {
        return output.message(5).groups(2).map { p ->
            ConversionClause(reading = p.text(6), text = p.text(4))
        }
    }

    private fun matchesReading(clauses: List<ConversionClause>, reading: String): Boolean {
        if (clauses.isEmpty()) return false
        val joined = clauses.joinToString("") { it.reading }
        return sameReading(joined, reading)
    }

    private fun extendCandidateClause(outputInit: Proto, reading: String, extend: () -> Proto): Proto? {
        var output = outputInit
        var clauses = readClauses(output)
        var step = 0
        while (clauses.size > 1 && step < reading.length) {
            val prevLen = clauses[0].reading.length
            output = extend()
            clauses = readClauses(output)
            if (!matchesReading(clauses, reading) || clauses[0].reading.length <= prevLen) return null
            step++
        }
        return if (clauses.size == 1) output else null
    }

    private fun foldSymbol(c: Char): Char {
        return if (c in '\uFF01'..'\uFF5E' && !c.isLetterOrDigit()) {
            (c.code - 0xFEE0).toChar()
        } else {
            c
        }
    }

    private fun sameReading(returned: String, requested: String): Boolean {
        if (returned.length != requested.length) return false
        for (i in returned.indices) {
            if (foldSymbol(returned[i]) != foldSymbol(requested[i])) return false
        }
        return true
    }

    private fun restoreSymbolWidth(text: String, reading: String): String {
        val allAsciiSymbol = reading.isNotEmpty() && reading.all {
            val f = foldSymbol(it)
            f in '!'..'~' && !f.isLetterOrDigit()
        }
        return if (allAsciiSymbol && sameReading(text, reading)) reading else text
    }

    companion object {
        private val BUILTIN_FALLBACK = mapOf(
            "きょうは" to "今日は",
            "きょう" to "今日",
            "あしたは" to "明日は",
            "あした" to "明日",
            "あしたの" to "明日の",
            "でけんさく" to "で検索",
            "けんさく" to "検索",
            "をついか" to "を追加",
            "ついか" to "追加",
            "わたし" to "私",
            "わたしは" to "私は",
            "にほんご" to "日本語",
            "ありがとう" to "ありがとう",
            "よろしく" to "よろしく",
            "おねがいします" to "お願いします"
        )
    }
}

/**
 * Minimal bounded proto2 encoder/decoder matching Mozc IPC schema.
 */
class Proto private constructor(
    private val fields: Map<Int, List<Any>>
) {
    fun has(field: Int): Boolean = fields.containsKey(field)

    fun int(field: Int): Long {
        val list = fields[field] ?: return 0L
        return (list.firstOrNull() as? Long) ?: 0L
    }

    fun bytes(field: Int): ByteArray {
        val list = fields[field] ?: return ByteArray(0)
        return (list.firstOrNull() as? ByteArray) ?: ByteArray(0)
    }

    fun text(field: Int): String = String(bytes(field), Charsets.UTF_8)

    fun message(field: Int): Proto = parse(bytes(field))

    fun messages(field: Int): List<Proto> {
        val list = fields[field] ?: return emptyList()
        return list.filterIsInstance<ByteArray>().map { parse(it) }
    }

    fun groups(field: Int): List<Proto> {
        val list = fields[field] ?: return emptyList()
        return list.filterIsInstance<Proto>()
    }

    companion object {
        fun number(field: Int, value: Long): ByteArray {
            val out = ByteArrayOutputStream(16)
            writeVarint(out, (field shl 3).toLong())
            writeVarint(out, value)
            return out.toByteArray()
        }

        fun blob(field: Int, value: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(value.size + 16)
            writeVarint(out, ((field shl 3) or 2).toLong())
            writeVarint(out, value.size.toLong())
            out.write(value)
            return out.toByteArray()
        }

        fun string(field: Int, value: String): ByteArray {
            return blob(field, value.toByteArray(Charsets.UTF_8))
        }

        fun concat(vararg arrays: ByteArray): ByteArray {
            var total = 0
            for (a in arrays) total += a.size
            val out = ByteArray(total)
            var pos = 0
            for (a in arrays) {
                System.arraycopy(a, 0, out, pos, a.size)
                pos += a.size
            }
            return out
        }

        private fun writeVarint(out: ByteArrayOutputStream, valueInit: Long) {
            var v = valueInit.toULong()
            while (v > 127uL) {
                out.write(((v and 127uL) or 128uL).toInt())
                v = v shr 7
            }
            out.write(v.toInt())
        }

        fun parse(data: ByteArray): Proto {
            var offset = 0

            fun readVarint(): Long {
                var value = 0uL
                var shift = 0
                while (shift < 70) {
                    if (offset >= data.size) throw IllegalArgumentException("Truncated varint")
                    val b = data[offset++].toInt() and 0xFF
                    if (shift == 63 && b > 1) throw IllegalArgumentException("Varint overflow")
                    value = value or ((b and 127).toULong() shl shift)
                    if (b < 128) return value.toLong()
                    shift += 7
                }
                throw IllegalArgumentException("Varint overflow")
            }

            fun readGroup(endGroup: Int, depth: Int): Proto {
                if (depth > 32) throw IllegalArgumentException("Excessive nesting")
                val map = HashMap<Int, MutableList<Any>>()
                while (offset < data.size) {
                    val tag = readVarint()
                    val field = (tag ushr 3).toInt()
                    val wire = (tag and 7L).toInt()
                    if (field == 0) throw IllegalArgumentException("Invalid field 0")
                    if (wire == 4) {
                        if (field != endGroup) throw IllegalArgumentException("Unexpected end group")
                        return Proto(map)
                    }
                    val value: Any = when (wire) {
                        0 -> readVarint()
                        3 -> readGroup(field, depth + 1)
                        1, 2, 5 -> {
                            val size = when (wire) {
                                2 -> readVarint().toInt()
                                1 -> 8
                                else -> 4
                            }
                            if (size < 0 || size > data.size - offset) {
                                throw IllegalArgumentException("Truncated field")
                            }
                            val slice = data.copyOfRange(offset, offset + size)
                            offset += size
                            slice
                        }
                        else -> throw IllegalArgumentException("Invalid wire type: $wire")
                    }
                    map.getOrPut(field) { ArrayList(2) }.add(value)
                }
                if (endGroup != 0) throw IllegalArgumentException("Unterminated group")
                return Proto(map)
            }

            return readGroup(0, 0)
        }
    }
}
