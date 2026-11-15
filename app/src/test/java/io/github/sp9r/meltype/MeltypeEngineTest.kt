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
        assertEquals(3, seg1.size)
        assertEquals(SegmentKind.JAPANESE, seg1[0].kind)
        assertEquals("きょうは", seg1[0].hiragana)
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
    }

    @Test
    fun glideDecoderRecognizesEnglishAndJapaneseWords() {
        val englishTargets = listOf("google", "meeting", "android", "keyboard", "tomorrow")
        for (word in englishTargets) {
            val path = KeyLayoutGeometry.syntheticPathForWord(word, jitter = 0.025f)
            val decoded = glideDecoder.decode(path, maxResults = 5).map { it.word }
            assertTrue("Expected '$word' in top-2 for English glide, got $decoded", decoded.take(2).contains(word))
        }

        val japaneseTargets = listOf("kyouha", "kensaku", "arigatou", "ohayou", "ashitano")
        for (word in japaneseTargets) {
            val path = KeyLayoutGeometry.syntheticPathForWord(word, jitter = 0.02f)
            val decoded = glideDecoder.decode(path, maxResults = 5).map { it.word }
            assertTrue("Expected '$word' in top-3 for Japanese glide, got $decoded", decoded.take(3).contains(word))
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

        // Utility checks
        assertEquals("2048", UtilityConverter.evaluateCalculator("128*16="))
        assertTrue(UtilityConverter.utilityCandidates("きょう", "kyou").isNotEmpty())
    }
}
