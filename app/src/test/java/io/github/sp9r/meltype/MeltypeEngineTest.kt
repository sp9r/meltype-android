package io.github.sp9r.meltype

import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.FileDictionarySource
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.core.SegmentKind
import io.github.sp9r.meltype.core.UtilityConverter
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class MeltypeEngineTest {

    companion object {
        private lateinit var dictionaries: MeltypeDictionaries
        private lateinit var scoreEngine: ScoreEngine
        private lateinit var glideDecoder: GlideDecoder

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val dictDir = locateDictionariesDir()
            dictionaries = MeltypeDictionaries(FileDictionarySource(dictDir))
            scoreEngine = ScoreEngine(dictionaries)
            glideDecoder = GlideDecoder(dictionaries)
        }

        private fun locateDictionariesDir(): File {
            val candidates = listOf(
                File("src/main/assets/dictionaries"),
                File("app/src/main/assets/dictionaries")
            )
            return candidates.firstOrNull { it.isDirectory && File(it, "english.txt").isFile }
                ?: error("Could not locate dictionaries directory from ${File(".").absolutePath}")
        }
    }

    @Test
    fun romajiConversionHandlesStandardSokuonAndHatsuon() {
        val kyouha = RomajiDetector.analyze("kyouha", strictForDetection = true)
        assertTrue(kyouha.isStrictValid)
        assertEquals("きょうは", kyouha.hiragana)
        assertTrue(kyouha.strongYouon >= 1)

        val gakkou = RomajiDetector.analyze("gakkou", strictForDetection = true)
        assertTrue(gakkou.isStrictValid)
        assertEquals("がっこう", gakkou.hiragana)
        assertEquals(1, gakkou.sokuonCount)

        val shinbun = RomajiDetector.analyze("shinbun", strictForDetection = true)
        assertTrue(shinbun.isStrictValid)
        assertEquals("しんぶん", shinbun.finalizedHiragana())

        val partial = RomajiDetector.analyze("kensakuk", strictForDetection = true)
        assertEquals("けんさく", partial.hiragana)
        assertEquals("k", partial.pendingTail)
    }

    @Test
    fun scoreEngineSegmentsMixedJapaneseAndEnglishCorrectly() {
        val seg1 = scoreEngine.segment("kyouhagoogledekensaku")
        val seg1Desc = seg1.joinToString { "${it.kind}:${it.raw}->${it.hiragana}" }
        assertEquals(seg1Desc, 3, seg1.size)
        assertEquals(seg1Desc, SegmentKind.JAPANESE, seg1[0].kind)
        assertEquals(seg1Desc, "きょうは", seg1[0].hiragana)
        assertEquals(SegmentKind.ENGLISH, seg1[1].kind)
        assertEquals("google", seg1[1].raw)
        assertEquals(SegmentKind.JAPANESE, seg1[2].kind)
        assertEquals("でけんさく", seg1[2].hiragana)

        val seg2 = scoreEngine.segment("ashitanomeetingwotsuika")
        assertEquals(3, seg2.size)
        assertEquals(SegmentKind.JAPANESE, seg2[0].kind)
        assertEquals("あしたの", seg2[0].hiragana)
        assertEquals(SegmentKind.ENGLISH, seg2[1].kind)
        assertEquals("meeting", seg2[1].raw)
        assertEquals(SegmentKind.JAPANESE, seg2[2].kind)
        assertEquals("をついか", seg2[2].hiragana)

        val engSentence = scoreEngine.segment("I want to go to the park")
        assertEquals(1, engSentence.size)
        assertEquals("I want to go to the park", engSentence[0].raw)

        val cmd = scoreEngine.segment("/commit")
        assertEquals(SegmentKind.LITERAL, cmd[0].kind)
        assertEquals("/commit", cmd[0].raw)

        val model = scoreEngine.segment("RTX4090wokau")
        assertEquals(SegmentKind.ENGLISH, model[0].kind)
        assertEquals("RTX4090", model[0].raw)
        assertEquals(SegmentKind.JAPANESE, model[1].kind)
        assertEquals("をかう", model[1].hiragana)

        // Lowercase "ai" in Japanese phrase must stay Japanese
        val aiJp = scoreEngine.segment("aiwotsukau")
        assertTrue(aiJp.all { it.kind == SegmentKind.JAPANESE })

        // Prolonged hyphen '-' inside a Japanese word stays in JAPANESE segment as 'ー'
        val ramen = scoreEngine.segment("ra-menwotaberu")
        assertEquals(1, ramen.size)
        assertEquals(SegmentKind.JAPANESE, ramen[0].kind)
        assertEquals("らーめんをたべる", RomajiDetector.analyze(ramen[0].raw).finalizedHiragana())
    }

    @Test
    fun glideDecoderRecognizesEnglishAndJapaneseWords() {
        val englishTargets = listOf("google", "meeting", "android", "keyboard", "tomorrow")
        for (word in englishTargets) {
            val path = KeyLayoutGeometry.syntheticPathForWord(word, jitter = 0.025f)
            val decoded = glideDecoder.decode(path, maxResults = 5).map { it.word }
            assertTrue("Expected '$word' in top-2 for English glide, got $decoded", decoded.take(2).contains(word))
        }

        val japaneseTargets = listOf(
            "kyouha" to "kyouha",
            "kensaku" to "kensaku",
            "arigatou" to "arigatou",
            "ohayou" to "ohayou",
            "ashitano" to "ashitano",
            "watashi" to "watashi",
            "ashita" to "ashita",
            "shashin" to "shashin",
            "chotto" to "chotto",
            "toukyou" to "toukyou",
            // Kunrei-shiki swipe inputs must resolve to canonical Hepburn output
            "watasi" to "watashi",
            "asita" to "ashita",
            "syasin" to "shashin",
            "tyotto" to "chotto",
            "tugi" to "tsugi",
            // Inflected verbs & adjectives
            "tabete" to "tabete",
            "ikimasu" to "ikimasu",
            "takakute" to "takakute",
            // Japanese words that collide with obscure SCOWL English words
            "kore" to "kore",
            "sore" to "sore"
        )
        for ((traceWord, expectedOut) in japaneseTargets) {
            val path = KeyLayoutGeometry.syntheticPathForWord(traceWord, jitter = 0.02f)
            val res = glideDecoder.decode(path, maxResults = 5)
            val decoded = res.map { it.word }
            assertTrue(
                "Expected '$expectedOut' in top-3 for trace '$traceWord', got $decoded",
                decoded.take(3).contains(expectedOut)
            )
            if (traceWord == "kore" || traceWord == "sore") {
                val matched = res.firstOrNull { it.word == expectedOut }
                assertTrue("Expected '$expectedOut' to be treated as Japanese", matched != null && !matched.isEnglish)
            }
        }
    }

    @Test
    fun compositionSessionSupportsTapGlideHybridAndUtilities() {
        val converter = AndroidMozcConverter(dictionaries)
        val session = CompositionSession(dictionaries, scoreEngine, converter)

        session.appendGlideResult("kyouha", false, listOf("kyouha" to false))
        session.appendGlideResult("google", true, listOf("google" to true, "goggle" to true))
        for (ch in "dekensaku") session.appendChar(ch)

        assertEquals("今日はgoogleで検索", session.snapshot.previewText)

        // Language toggle flips last clause
        val toggled = session.toggleFocusedSegmentLanguage()
        assertTrue(toggled.clauses.last().isEnglish)
        session.toggleFocusedSegmentLanguage()

        // 1-tap glide undo deletes entire glided word in one Backspace press
        val undoSession = CompositionSession(dictionaries, scoreEngine, converter)
        undoSession.appendText("kyouha")
        undoSession.appendGlideResult("ashitano", false, listOf("ashitano" to false))
        assertEquals("kyouhaashitano", undoSession.snapshot.rawInput)
        assertTrue(undoSession.snapshot.hasGlideUndo)
        undoSession.backspace()
        assertEquals("kyouha", undoSession.snapshot.rawInput)

        // Clause boundary resize shrinks & extends focused Japanese clause reading
        val resizeSession = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") resizeSession.appendChar(ch)
        val kyouhaIdx = resizeSession.snapshot.clauses.indexOfFirst { it.reading.length >= 3 }
        if (kyouhaIdx >= 0) {
            val focusedBefore = resizeSession.focusClause(kyouhaIdx)
            val lenBefore = focusedBefore.clauses[focusedBefore.focusedClauseIndex].reading.length
            val shrunk = resizeSession.resizeFocusedClause(-1)
            val lenAfter = shrunk.clauses[shrunk.focusedClauseIndex].reading.length
            assertEquals(lenBefore - 1, lenAfter)
            val extended = resizeSession.resizeFocusedClause(+1)
            val lenRestored = extended.clauses[extended.focusedClauseIndex].reading.length
            assertEquals(lenBefore, lenRestored)
        }

        // Script variants should include hiragana, katakana, half-width katakana
        val svLabels = resizeSession.snapshot.scriptVariants.map { it.first }
        assertTrue(svLabels.contains("あ") && svLabels.contains("ア"))

        // Auto-advance focus when selecting a conversion candidate with advanceFocus = true
        val advSession = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") advSession.appendChar(ch)
        advSession.focusClause(0)
        val cand0 = advSession.snapshot.candidates.first()
        val afterSelect = advSession.selectCandidate(cand0, advanceFocus = true)
        if (afterSelect.clauses.size > 1) {
            assertEquals(1, afterSelect.focusedClauseIndex)
        }

        // Utility checks
        assertEquals("2048", UtilityConverter.evaluateCalculator("128*16="))
        assertEquals("2048", UtilityConverter.evaluateCalculator("128*16"))
        assertTrue(UtilityConverter.utilityCandidates("きょう", "kyou").isNotEmpty())
        assertTrue(UtilityConverter.numericCandidates("1234").contains("1,234"))
    }

    @Test
    fun romajiTypoToleranceAndBilabialAndMisspellings() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Bilabial m -> ん before b/p/m
        assertEquals("しんぶん", RomajiDetector.analyze("shimbun").finalizedHiragana())
        assertEquals("さんぽ", RomajiDetector.analyze("sampo").finalizedHiragana())
        assertEquals("てんぷら", RomajiDetector.analyze("tempura").finalizedHiragana())

        // 2. Unreadable cluster auto-repair (skipped vowel / adjacent hit / transposition)
        val unrepaired = listOf(
            "onegaishimsu" to listOf("お願いします", "おねがいします"),
            "arigatpu" to listOf("ありがとう", "有り難う"),
            "sumimasne" to listOf("すみません")
        )
        for ((raw, expects) in unrepaired) {
            val s = CompositionSession(dictionaries, scoreEngine, converter)
            for (ch in raw) s.appendChar(ch)
            val prev = s.snapshot.previewText
            val cands = s.snapshot.candidates.map { it.text }
            assertTrue(
                "Expected one of $expects for mistyped '$raw', got preview='$prev' cands=$cands",
                expects.any { prev.contains(it) || cands.any { c -> c.contains(it) } }
            )
        }

        // 3. Accidentally readable neighbor slips with word boundary
        val readableSlips = listOf(
            "arigatoi " to listOf("ありがとう", "有り難う"),
            "arigayou " to listOf("ありがとう", "有り難う"),
            "ohayoi " to listOf("おはよう"),
            "watashu " to listOf("わたし", "私")
        )
        for ((raw, expects) in readableSlips) {
            val s = CompositionSession(dictionaries, scoreEngine, converter)
            for (ch in raw) s.appendChar(ch)
            val prev = s.snapshot.previewText
            val cands = s.snapshot.candidates.map { it.text }
            assertTrue(
                "Expected correction for readable slip '$raw' -> $expects, got preview='$prev' cands=$cands",
                expects.any { prev.contains(it) || cands.any { c -> c.contains(it) } }
            )
        }

        // 4. Katakana misspellings resolution (misspellings.txt)
        val misspell = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "shumire-shon") misspell.appendChar(ch)
        assertTrue(
            "Expected シュミレーション -> シミュレーション candidate, got preview='${misspell.snapshot.previewText}' cands=${misspell.snapshot.candidates.map { it.text }}",
            misspell.snapshot.previewText.contains("シミュレーション") ||
                misspell.snapshot.candidates.any { it.text == "シミュレーション" }
        )

        // 5. Commit undo restore
        val undoSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "arigatou") undoSess.appendChar(ch)
        val committed = undoSess.commitAndLearn()
        assertTrue(committed.isNotEmpty() && undoSess.isEmpty)
        val restored = undoSess.restoreFromUndo("arigatou")
        assertTrue(restored.isComposing && restored.rawInput == "arigatou")
    }

    @Test
    fun humanCurvedScaledAndCompoundJapaneseGlideAccuracy() {
        fun humanSwipePath(
            word: String,
            cornerRoundAlpha: Float,
            scale: Float,
            shiftX: Float,
            shiftY: Float
        ): List<io.github.sp9r.meltype.glide.TouchPoint> {
            val centers = word.mapNotNull { KeyLayoutGeometry.centerOf(it) }
            val rounded = ArrayList<io.github.sp9r.meltype.glide.TouchPoint>(centers.size)
            rounded.add(io.github.sp9r.meltype.glide.TouchPoint(centers.first().x, centers.first().y))
            for (i in 1 until centers.lastIndex) {
                val p = centers[i - 1]
                val c = centers[i]
                val n = centers[i + 1]
                val mx = (p.x + n.x) * 0.5f
                val my = (p.y + n.y) * 0.5f
                rounded.add(
                    io.github.sp9r.meltype.glide.TouchPoint(
                        c.x + (mx - c.x) * cornerRoundAlpha,
                        c.y + (my - c.y) * cornerRoundAlpha
                    )
                )
            }
            rounded.add(io.github.sp9r.meltype.glide.TouchPoint(centers.last().x, centers.last().y))

            val cx = rounded.map { it.x }.average().toFloat()
            val cy = rounded.map { it.y }.average().toFloat()
            val transformed = rounded.map {
                io.github.sp9r.meltype.glide.TouchPoint(
                    cx + (it.x - cx) * scale + shiftX,
                    cy + (it.y - cy) * scale + shiftY
                )
            }
            val dense = ArrayList<io.github.sp9r.meltype.glide.TouchPoint>(transformed.size * 5)
            for (i in 0 until transformed.lastIndex) {
                val a = transformed[i]
                val b = transformed[i + 1]
                for (step in 0 until 5) {
                    val t = step / 5f
                    dense.add(
                        io.github.sp9r.meltype.glide.TouchPoint(
                            a.x + (b.x - a.x) * t,
                            a.y + (b.y - a.y) * t
                        )
                    )
                }
            }
            dense.add(transformed.last())
            return dense
        }

        val humanCases = listOf(
            "watashi" to "watashi",
            "watasiwa" to "watashiha",
            "arigatou" to "arigatou",
            "arigato" to "arigatou",
            "konichiwa" to "konnichiwa",
            "ohayou" to "ohayou",
            "onegaishimasu" to "onegaishimasu",
            "kakuninshimasu" to "kakuninshimasu",
            "arigatougozaimasu" to "arigatougozaimasu"
        )
        for ((traceWord, expectedOut) in humanCases) {
            val path = humanSwipePath(traceWord, cornerRoundAlpha = 0.25f, scale = 0.72f, shiftX = 0.11f, shiftY = -0.05f)
            val decoded = glideDecoder.decode(path, maxResults = 6).map { it.word }
            assertTrue(
                "Expected '$expectedOut' in top-3 for human curved+scaled trace '$traceWord', got $decoded",
                decoded.take(3).contains(expectedOut)
            )
        }
    }
}
