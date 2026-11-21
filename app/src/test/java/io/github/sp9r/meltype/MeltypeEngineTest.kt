package io.github.sp9r.meltype

import io.github.sp9r.meltype.core.AndroidMozcConverter
import io.github.sp9r.meltype.core.CandidateKind
import io.github.sp9r.meltype.core.CompositionSession
import io.github.sp9r.meltype.core.EmojiCatalog
import io.github.sp9r.meltype.core.EmojiCategory
import io.github.sp9r.meltype.core.FileDictionarySource
import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import io.github.sp9r.meltype.core.RomajiTypoEngine
import io.github.sp9r.meltype.core.ScoreEngine
import io.github.sp9r.meltype.core.SegmentKind
import io.github.sp9r.meltype.core.UtilityConverter
import io.github.sp9r.meltype.glide.GlideDecoder
import io.github.sp9r.meltype.glide.KeyLayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        assertEquals("今日はGoogleで検索", session.snapshot.previewText)

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

        // 3. Accidentally readable neighbor slips offer correction chips non-destructively (and auto-fix with spatial hint)
        val readableSlips = listOf(
            "arigatoi" to listOf("ありがとう", "有り難う"),
            "arigayou" to listOf("ありがとう", "有り難う"),
            "ohayoi" to listOf("おはよう"),
            "watashu" to listOf("わたし", "私")
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
        val spatialArigatoi = CompositionSession(dictionaries, scoreEngine, converter)
        for ((idx, ch) in "arigatoi".withIndex()) {
            spatialArigatoi.appendChar(ch, spatialHints = if (idx == 6) mapOf('u' to 0.72f) else emptyMap())
        }
        assertTrue(
            "Expected spatial auto-fix or correction chip for arigatoi near u: prev=${spatialArigatoi.snapshot.previewText} cands=${spatialArigatoi.snapshot.candidates.map { it.text }}",
            spatialArigatoi.snapshot.previewText.contains("ありがとう") ||
                spatialArigatoi.snapshot.candidates.any { it.text.contains("ありがとう") }
        )

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

    @Test
    fun noFalseEnglishSplitsPartialCommitAndFastGlideLatency() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Japanese stems containing valid English substrings must stay JAPANESE
        val pureJpWords = listOf("makenai", "homete", "tameninaru", "samenai", "hareta")
        for (raw in pureJpWords) {
            val segs = scoreEngine.segment(raw)
            assertTrue(
                "Expected '$raw' to remain pure JAPANESE, got ${segs.map { "${it.kind}:${it.raw}" }}",
                segs.all { it.kind == SegmentKind.JAPANESE }
            )
        }

        // True proper nouns / non-romaji English still split properly
        val ytSegs = scoreEngine.segment("youtubewomiru")
        assertTrue(ytSegs.any { it.kind == SegmentKind.ENGLISH && it.raw.equals("youtube", ignoreCase = true) })

        // 2. Partial clause commit commits focused prefix while preserving remaining tail
        val partialSession = CompositionSession(dictionaries, scoreEngine, converter)
        partialSession.appendText("kyouhagoogledekensaku")
        partialSession.focusClause(0)
        val firstClauseText = partialSession.snapshot.clauses[0].text
        val committed = partialSession.commitFocusedPrefix()
        assertEquals(firstClauseText, committed)
        assertTrue(partialSession.snapshot.isComposing)
        assertTrue(partialSession.snapshot.previewText.contains("google", ignoreCase = true))

        // 3. Conversational Japanese phrases & fast decode latency
        val convGlideTargets = listOf(
            "maji" to "maji",
            "yokatta" to "yokatta",
            "ganbatte" to "ganbatte",
            "toriaezu" to "toriaezu",
            "wakaranai" to "wakaranai",
            "arigatougozaimasu" to "arigatougozaimasu"
        )
        // Warmup JIT before timing
        glideDecoder.decode(KeyLayoutGeometry.syntheticPathForWord("kyouha", jitter = 0.02f), maxResults = 6)

        var totalNs = 0L
        for ((trace, want) in convGlideTargets) {
            val path = KeyLayoutGeometry.syntheticPathForWord(trace, jitter = 0.02f)
            val t0 = System.nanoTime()
            val decoded = glideDecoder.decode(path, maxResults = 6, fastPreview = false).map { it.word }
            totalNs += System.nanoTime() - t0
            assertTrue("Expected '$want' in top-3 for '$trace', got $decoded", decoded.take(3).contains(want))
        }
        val avgMs = (totalNs / convGlideTargets.size) / 1_000_000.0
        assertTrue("Expected avg full glide decode <= 35ms on JVM, got ${avgMs}ms", avgMs <= 35.0)
    }

    @Test
    fun denjikiNeverMisconvertsToDanjiki() {
        val converter = AndroidMozcConverter(dictionaries)
        val typoEngine = RomajiTypoEngine(dictionaries)

        val fixes = typoEngine.suggestReadableTypos("denjiki", "でんじき", allowFinalTailTrim = false)
        assertFalse("denjiki must never suggest だんじき", fixes.any { it.kana == "だんじき" })

        val session = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "denjiki") session.appendChar(ch)
        val snap = session.snapshot
        assertEquals("でんじき", snap.clauses.firstOrNull()?.reading)
        assertFalse("Preview must not become だんじき: ${snap.previewText}", snap.previewText.contains("だんじき"))
        assertFalse("Preview must not become 断食: ${snap.previewText}", snap.previewText.contains("断食"))
    }

    @Test
    fun lowercaseCollidingProperNounsStayJapanese() {
        val pureJapaneseInputs = listOf(
            "tsukaretanodekaeru",
            "kokodenohanashi",
            "mitemiro",
            "kamen",
            "siri",
            "karen"
        )
        for (input in pureJapaneseInputs) {
            val segs = scoreEngine.segment(input)
            assertTrue(
                "Expected '$input' to segment cleanly as JAPANESE, got $segs",
                segs.all { it.kind == SegmentKind.JAPANESE }
            )
        }

        // Capitalized proper nouns and non-colliding English must still segment as ENGLISH
        assertTrue(scoreEngine.segment("Node").all { it.kind == SegmentKind.ENGLISH })
        assertTrue(scoreEngine.segment("Siri").all { it.kind == SegmentKind.ENGLISH })
        val ytSegs = scoreEngine.segment("youtubewomiru")
        assertTrue(ytSegs.any { it.kind == SegmentKind.ENGLISH && it.raw.equals("youtube", ignoreCase = true) })
    }

    @Test
    fun mobileQwertyQAndJSlipsRecoverWithoutFalsePositives() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Index-0 'q' -> 'a' recovery ("qrigatou" -> "ありがとう")
        val qStart = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "qrigatou") qStart.appendChar(ch)
        assertTrue(
            "Expected qrigatou -> ありがとう, got ${qStart.snapshot.previewText} / ${qStart.snapshot.candidates.map { it.text }}",
            qStart.snapshot.previewText.contains("ありがとう") ||
                qStart.snapshot.candidates.any { it.text.contains("ありがとう") || it.text.contains("有り難") }
        )

        // 2. Readable 'q' + vowel cluster recovery ("qite" -> "あいて", "kqeru" -> "かえる")
        val qVowel = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "qite") qVowel.appendChar(ch)
        assertTrue(
            "Expected qite -> あいて, got ${qVowel.snapshot.previewText} / ${qVowel.snapshot.candidates.map { it.text }}",
            qVowel.snapshot.clauses.any { it.reading == "あいて" } ||
                qVowel.snapshot.candidates.any { it.text == "あいて" || it.text == "相手" }
        )

        // 3. Valid word "suji" must NOT auto-overwrite to "suki" even with near-edge touch,
        //    yet high spatial weight toward 'k' offers "すき" in candidates!
        val sujiCenter = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "suji") sujiCenter.appendChar(ch)
        assertEquals("すじ", sujiCenter.snapshot.clauses.firstOrNull()?.reading)

        val sujiNearK = CompositionSession(dictionaries, scoreEngine, converter)
        sujiNearK.appendChar('s')
        sujiNearK.appendChar('u')
        sujiNearK.appendChar('j', spatialHints = mapOf('k' to 0.72f))
        sujiNearK.appendChar('i')
        assertEquals("すじ", sujiNearK.snapshot.clauses.firstOrNull()?.reading)
        assertTrue(
            "Expected spatial 'k' suggestion 'すき' in candidates for border tap: ${sujiNearK.snapshot.candidates.map { it.text }}",
            sujiNearK.snapshot.candidates.any { it.text == "すき" }
        )

        // 4. Non-word 'j' -> 'k' slip ("jaikei" -> "かいけい")
        val jaikei = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "jaikei") jaikei.appendChar(ch)
        assertTrue(
            "Expected jaikei -> かいけい, got ${jaikei.snapshot.previewText} / ${jaikei.snapshot.candidates.map { it.text }}",
            jaikei.snapshot.clauses.any { it.reading == "かいけい" } ||
                jaikei.snapshot.candidates.any { it.text == "かいけい" || it.text == "会計" }
        )
    }

    @Test
    fun primaryCandidatesExcludeEmojiAndDatesAtIndexZero() {
        val converter = AndroidMozcConverter(dictionaries)
        val shinbunCands = converter.candidates("しんぶん")
        assertFalse(
            "First candidate for しんぶん must not be emoji: $shinbunCands",
            shinbunCands.firstOrNull() == "📰"
        )

        val kyouSess = CompositionSession(dictionaries, scoreEngine, converter)
        kyouSess.appendText("kyou")
        val firstCand = kyouSess.snapshot.candidates.firstOrNull()?.text.orEmpty()
        assertFalse(
            "First candidate for 'kyou' should not be slash/ISO date ahead of 今日: $firstCand",
            firstCand.contains('/') || firstCand.contains('-')
        )
    }

    @Test
    fun emojiCatalogLoadsFullUnicodeAndSupportsJapaneseSearch() {
        val dictDir = File("app/src/main/assets/dictionaries").let {
            if (it.exists()) it else File("src/main/assets/dictionaries")
        }
        val catalog = EmojiCatalog(FileDictionarySource(dictDir))
        assertTrue("Expected >= 1800 base RGI emojis, got ${catalog.allEntries.size}", catalog.allEntries.size >= 1800)
        for (cat in EmojiCategory.CATALOG_CATEGORIES) {
            assertTrue("Expected non-empty category ${cat.name}", catalog.entriesByCategory[cat].orEmpty().isNotEmpty())
        }
        val smileMatches = catalog.search("えがお").map { it.emoji }
        assertTrue("Expected smile matches for えがお, got $smileMatches", smileMatches.isNotEmpty())
        val romajiMatches = catalog.search("egao").map { it.emoji }
        assertTrue("Expected smile matches for egao, got $romajiMatches", romajiMatches.isNotEmpty())
        val englishMatches = catalog.search("heart").map { it.emoji }
        assertTrue("Expected matches for 'heart', got $englishMatches", englishMatches.any { it.contains("❤") || it == "🩷" })
    }

    @Test
    fun glideEvalLoggerAndDebugBreakdownProduceConsistentDiagnostics() {
        val converter = AndroidMozcConverter(dictionaries)
        val sess = CompositionSession(dictionaries, scoreEngine, converter)
        val path = KeyLayoutGeometry.syntheticPathForWord("arigatou", jitter = 0.02f)

        val normal = glideDecoder.decode(path, maxResults = 5, preferJapanese = true)
        val debug = glideDecoder.decodeDebug(path, maxResults = 5, preferJapanese = true)
        assertTrue(normal.isNotEmpty())
        assertEquals(normal.map { it.word }, debug.candidates.map { it.word })
        assertTrue(debug.pathLen > 0.5f)
        assertTrue(debug.startKeys.isNotEmpty() && debug.endKeys.isNotEmpty())
        assertTrue(debug.anchorSeq.isNotEmpty())

        val top = debug.candidates.first()
        val snap = sess.appendGlideResult(top.word, top.isEnglish, debug.candidates.map { it.word to it.isEnglish })

        val tempDir = File.createTempFile("meltype_eval_test", "").apply { delete(); mkdirs() }
        try {
            val logger = io.github.sp9r.meltype.core.GlideEvalLogger(tempDir)
            logger.logGlide(
                rawPoints = path,
                normalizedPoints = path,
                boundsLeft = 0f,
                boundsTop = 0f,
                boundsWidth = 1080f,
                boundsHeight = 600f,
                oneHandedMode = "FULL",
                heightScale = 1.0f,
                preferJapanese = true,
                debugResult = debug,
                composingBefore = "",
                snapshotAfter = snap,
                promptId = "t01",
                expectedText = "ありがとう\"テスト\""
            )
            logger.logGlideAltPicked("t01", top.word, "arigatou", false, "ありがとう")
            logger.logBackspace("t01", "glide_undo", "ありがとう", "")
            logger.logPromptDone("t01", "テスト", "ありがとう", "ありがとう", "next")

            val summary = logger.readSummary()
            assertEquals(4, summary.totalEvents)
            assertEquals(1, summary.glideCount)
            assertEquals(1, summary.altPickCount)
            assertEquals(1, summary.backspaceCount)
            assertEquals(1, summary.glideUndoCount)
            assertEquals(1, summary.promptDoneCount)
            assertTrue(summary.recentGlides.isNotEmpty())

            val rawLines = logger.logFile.readLines(Charsets.UTF_8)
            assertEquals(4, rawLines.size)
            assertTrue(rawLines[0].contains("\"type\":\"glide\"") && rawLines[0].contains("\"pts\":["))
            assertTrue(rawLines[0].contains("\\\"テスト\\\""))

            logger.clear()
            assertFalse(logger.logFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun directClauseSplitAdjustsToExactKanaLengthAndMergesAdjacentSegments() {
        val converter = AndroidMozcConverter(dictionaries)
        val sess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhaiitenki") {
            sess.appendChar(ch)
        }
        val init = sess.focusClause(0)
        assertTrue(init.focusedAdjustableKana.startsWith("きょう"))
        assertEquals(init.clauses.first().reading.length, init.focusedAdjustableLen)

        val snapped4 = sess.setFocusedClauseLength(4)
        assertEquals("きょうは", snapped4.clauses[0].reading)
        assertEquals(4, snapped4.focusedAdjustableLen)

        val shrunk2 = sess.setFocusedClauseLength(2)
        assertEquals("きょ", shrunk2.clauses[0].reading)
        assertEquals(2, shrunk2.focusedAdjustableLen)

        val restored3 = sess.resizeFocusedClause(+1)
        assertEquals("きょう", restored3.clauses[0].reading)
        assertEquals(3, restored3.focusedAdjustableLen)

        // Adjacent Japanese tap + glide segments can also extend directly across segment boundaries
        val mixedSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouha") mixedSess.appendChar(ch)
        mixedSess.appendGlideResult("tenki", false, listOf("tenki" to false))
        val mixedInit = mixedSess.focusClause(0)
        assertTrue(mixedInit.focusedAdjustableKana.length >= 5)
        val extendedAcross = mixedSess.setFocusedClauseLength(4)
        assertEquals(4, extendedAcross.clauses[0].reading.length)
    }

    @Test
    fun zSequencesFollowGoogleIme() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        s.appendChar('z')
        assertNotNull(s.tryZSymbol('.'))
        assertEquals("…", s.snapshot.rawInput)

        val arrow = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kaz") arrow.appendChar(ch)
        assertNotNull(arrow.tryZSymbol('l'))
        assertEquals("ka→", arrow.snapshot.rawInput)

        // English words ending in z are not touched.
        val quiz = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "quiz") quiz.appendChar(ch)
        assertNull(quiz.tryZSymbol('.'))
        // Keys outside the z table are not touched.
        assertNull(arrow.tryZSymbol('a'))
    }

    @Test
    fun englishModeKeepsTypedWordsEnglish() {
        val converter = AndroidMozcConverter(dictionaries)
        // Romaji-readable English words (make = まけ, home = ほめ) stay English in English mode.
        for (word in listOf("make", "home", "name", "hello")) {
            val s = CompositionSession(dictionaries, scoreEngine, converter)
            s.englishMode = true
            for (ch in word) s.appendChar(ch)
            val snap = s.snapshot
            assertEquals(word, snap.previewText)
            assertTrue("$word should be English", snap.clauses.all { it.isEnglish })
        }
        // A glided word followed by typed letters merges into one English word.
        val merged = CompositionSession(dictionaries, scoreEngine, converter)
        merged.englishMode = true
        merged.appendGlideResult("game", true, listOf("game" to true))
        merged.appendChar('s')
        assertEquals("games", merged.snapshot.previewText)
        // z sequences are off in English mode.
        val z = CompositionSession(dictionaries, scoreEngine, converter)
        z.englishMode = true
        z.appendChar('z')
        assertNull(z.tryZSymbol('.'))
    }

    @Test
    fun englishOnlyGlideFindsWordsJapaneseBiasHid() {
        // Words that lose to Japanese readings under the Japanese bias are found once only English is kept.
        for (word in listOf("game", "name", "breakfast", "really", "sorry", "hello", "today")) {
            val path = KeyLayoutGeometry.syntheticPathForWord(word, jitter = 0.02f)
            val english = glideDecoder.decode(path, maxResults = 20, preferJapanese = false).filter { it.isEnglish }
            assertTrue("$word not in English top 3: ${english.take(3).map { it.word }}", english.take(3).any { it.word == word })
        }
    }

    @Test
    fun conversionCommitsClauseByClauseAndBackspaceRevertsToKana() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") s.appendChar(ch)
        val clauses = s.snapshot.clauses
        assertTrue("needs several clauses: ${clauses.map { it.text }}", clauses.size > 1)
        assertFalse(s.snapshot.isConverting)
        val started = s.startConversion()
        assertTrue(started.isConverting)
        assertEquals(0, started.focusedClauseIndex)
        val first = clauses.first().text
        val committed = s.commitFocusedPrefix()
        assertEquals(first, committed)
        assertTrue(s.snapshot.isConverting)
        assertEquals(0, s.snapshot.focusedClauseIndex)
        // Backspace in conversion returns the rest to unconverted kana instead of deleting.
        val rawBefore = s.snapshot.rawInput
        val reverted = s.backspace()
        assertEquals(rawBefore, reverted.rawInput)
        assertFalse(reverted.isConverting)
        assertTrue(reverted.isEditing)
        assertTrue(reverted.isKanaView)
        assertEquals("googleでけんさく", reverted.previewText)
        // The next Backspace deletes one kana.
        val shorter = s.backspace()
        assertEquals(reverted.previewText.dropLast(1), shorter.previewText)
    }

    @Test
    fun pickingCandidateCommitsUpToItsClause() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") s.appendChar(ch)
        s.startConversion()
        val first = s.snapshot.clauses.first()
        val pick = s.snapshot.candidates.first {
            it.kind == CandidateKind.CONVERSION && (it.targetClauseIndex == 0 || it.targetClauseIndex < 0)
        }
        assertTrue(s.prepareCandidateCommit(pick))
        val committed = s.commitFocusedPrefix()
        assertEquals(pick.text, committed)
        assertFalse(s.isEmpty)
        assertTrue(s.snapshot.rawInput.isNotEmpty())
        assertFalse("first clause ${first.reading} left in composition", s.snapshot.previewText.startsWith(first.text))
        // A single-clause composition is committed whole.
        val one = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "ame") one.appendChar(ch)
        val c = one.snapshot.candidates.first { it.kind == CandidateKind.CONVERSION }
        assertTrue(one.prepareCandidateCommit(c))
        assertEquals(c.text, one.commitFocusedPrefix())
        assertTrue(one.isEmpty)
    }

    @Test
    fun upDownArrowsMoveCaretToCompositionEdgesWithoutCommit() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhaame") s.appendChar(ch)
        val top = s.moveCaretToEdge(start = true)
        assertTrue(top.isComposing)
        assertTrue(top.isEditing)
        assertEquals(0, top.editKanaCaret)
        assertEquals("kyouhaame", top.rawInput)
        s.appendChar('a')
        assertEquals("あきょうはあめ", s.snapshot.editKana)
        val bottom = s.moveCaretToEdge(start = false)
        assertFalse(bottom.isEditing)
        assertEquals("akyouhaame", bottom.rawInput)
    }

    @Test
    fun caretEditsInsideCompositionBeforeConversion() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhaame") s.appendChar(ch)
        // Two kana left: きょうは|あめ
        val moved = s.moveCaret(-2)
        assertTrue(moved.isEditing)
        assertFalse(moved.isKanaView)
        // The conversion stays on screen; the badge has the kana with the caret.
        assertEquals(moved.clauses.joinToString("") { it.text }, moved.previewText)
        assertEquals("きょうはあめ", moved.editKana)
        assertEquals(4, moved.editKanaCaret)
        val before = moved.previewText.substring(0, moved.caretOffset)
        assertTrue("caret after は: ${moved.previewText} @${moved.caretOffset}", before.endsWith("は"))
        // Delete は and type が at the caret.
        val deleted = s.backspace()
        assertEquals("きょうあめ", deleted.editKana)
        assertEquals(3, deleted.editKanaCaret)
        s.appendChar('g')
        val inserted = s.appendChar('a')
        assertEquals("kyougaame", inserted.rawInput)
        assertEquals("きょうがあめ", inserted.editKana)
        assertEquals(4, inserted.editKanaCaret)
        assertFalse(inserted.isKanaView)
        // Moving back to the end returns to live conversion.
        val end = s.moveCaret(+5)
        assertFalse(end.isEditing)
        assertEquals("kyougaame", end.rawInput)
        // Space-style conversion from the kana view.
        s.moveCaret(-1)
        val conv = s.startConversion()
        assertTrue(conv.isConverting)
        assertFalse(conv.isEditing)
        // Typing English letters mid-word in English mode stays English.
        val en = CompositionSession(dictionaries, scoreEngine, converter)
        en.englishMode = true
        for (ch in "helo") en.appendChar(ch)
        en.moveCaret(-1)
        en.appendChar('l')
        assertEquals("hello", en.snapshot.rawInput)
        assertTrue(en.snapshot.clauses.all { it.isEnglish })
    }

    @Test
    fun englishClausesResizeLetterByLetter() {
        val converter = AndroidMozcConverter(dictionaries)
        val s = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") s.appendChar(ch)
        val conv = s.startConversion()
        val enIdx = conv.clauses.indexOfFirst { it.isEnglish }
        assertTrue("english clause in ${conv.clauses.map { it.raw }}", enIdx >= 0)
        assertEquals("google", conv.clauses[enIdx].raw)
        val focused = s.focusClause(enIdx)
        assertTrue(focused.canShrinkClause)
        assertTrue(focused.canExtendClause)
        // Shorter: the released letter joins the Japanese after it.
        val shrunk = s.resizeFocusedClause(-1)
        assertEquals("googl", shrunk.clauses[shrunk.focusedClauseIndex].raw)
        assertTrue(shrunk.clauses[shrunk.focusedClauseIndex].isEnglish)
        val after = shrunk.clauses.drop(shrunk.focusedClauseIndex + 1).joinToString("") { it.reading }
        assertTrue(after, after.startsWith("え"))
        // Longer again.
        val back = s.resizeFocusedClause(+1)
        assertEquals("google", back.clauses[back.focusedClauseIndex].raw)
        assertEquals("kyouhagoogledekensaku", back.rawInput)

        // A Japanese clause right before an English word takes its first kana.
        val j = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogledekensaku") j.appendChar(ch)
        val jc = j.startConversion()
        val en2 = jc.clauses.indexOfFirst { it.isEnglish }
        val jf = j.focusClause(en2 - 1)
        assertTrue(jf.canExtendClause)
        val ext = j.resizeFocusedClause(+1)
        assertTrue(ext.clauses[en2 - 1].reading, ext.clauses[en2 - 1].reading.endsWith("ご"))
        assertEquals("ogle", ext.clauses.first { it.isEnglish }.raw)
    }

    @Test
    fun numericAndCounterSegmentationPreservesModelNumbers() {
        val converter = AndroidMozcConverter(dictionaries)

        fun assertSegs(input: String, expected: List<Pair<SegmentKind, String>>) {
            val segs = scoreEngine.segment(input)
            val actual = segs.map { it.kind to it.raw }
            assertEquals("Segmentation mismatch for '$input': $segs", expected, actual)
        }

        assertSegs("1.6mann", listOf(SegmentKind.LITERAL to "1.6", SegmentKind.JAPANESE to "mann"))
        assertSegs("20mann", listOf(SegmentKind.LITERAL to "20", SegmentKind.JAPANESE to "mann"))
        assertSegs("1nenn", listOf(SegmentKind.LITERAL to "1", SegmentKind.JAPANESE to "nenn"))
        assertSegs("tuki6mann", listOf(SegmentKind.JAPANESE to "tuki", SegmentKind.LITERAL to "6", SegmentKind.JAPANESE to "mann"))
        assertSegs("12gatuga", listOf(SegmentKind.LITERAL to "12", SegmentKind.JAPANESE to "gatuga"))
        assertSegs("40", listOf(SegmentKind.LITERAL to "40"))
        assertSegs("3.6", listOf(SegmentKind.LITERAL to "3.6"))
        assertSegs("1/3wo", listOf(SegmentKind.LITERAL to "1/3", SegmentKind.JAPANESE to "wo"))

        val fracSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "1/3") fracSess.appendChar(ch)
        assertEquals("1/3", fracSess.snapshot.previewText)

        val eqSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "128*16=") eqSess.appendChar(ch)
        assertEquals("2048", eqSess.snapshot.previewText)

        val bareSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "128*16") bareSess.appendChar(ch)
        assertEquals("128*16", bareSess.snapshot.previewText)
        assertTrue(
            "Expected 2048 in candidates for 128*16: ${bareSess.snapshot.candidates.map { it.text }}",
            bareSess.snapshot.candidates.any { it.text == "2048" }
        )

        assertSegs("RTX4090wokau", listOf(SegmentKind.ENGLISH to "RTX4090", SegmentKind.JAPANESE to "wokau"))
        assertSegs("iPhone16", listOf(SegmentKind.ENGLISH to "iPhone16"))
        assertSegs("16GB", listOf(SegmentKind.ENGLISH to "16GB"))
        assertSegs("4K", listOf(SegmentKind.ENGLISH to "4K"))
        assertSegs("M4", listOf(SegmentKind.ENGLISH to "M4"))
    }

    @Test
    fun localJapaneseGuardBlocksEnglishSplitsNextToTypos() {
        val converter = AndroidMozcConverter(dictionaries)

        val onajiSegs = scoreEngine.segment("onqjikuraino")
        assertTrue(
            "onqjikuraino must not carve English 'rain': $onajiSegs",
            onajiSegs.none { it.kind == SegmentKind.ENGLISH && it.raw == "rain" }
        )
        val onajiSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "onqjikuraino") onajiSess.appendChar(ch)
        assertFalse(onajiSess.snapshot.previewText.contains("rain"))
        assertTrue(
            "Expected おなじくらいの reading/preview, got ${onajiSess.snapshot.clauses.map { it.reading }} / ${onajiSess.snapshot.previewText}",
            onajiSess.snapshot.clauses.joinToString("") { it.reading } == "おなじくらいの" ||
                onajiSess.snapshot.previewText.contains("同じくらいの")
        )

        val sentSegs = scoreEngine.segment("heyanonakanisentakumonowohoserutokoroxzq")
        assertTrue(
            "Long Japanese sentence ending in typo must not split 'sent' or 'hose': $sentSegs",
            sentSegs.none { it.kind == SegmentKind.ENGLISH && (it.raw == "sent" || it.raw == "hose") }
        )

        // Real embedded English words remain intact
        assertTrue(scoreEngine.segment("kyouhagoogledekensaku").any { it.kind == SegmentKind.ENGLISH && it.raw == "google" })
        assertTrue(scoreEngine.segment("ashitanomeetingwotsuika").any { it.kind == SegmentKind.ENGLISH && it.raw == "meeting" })
        assertTrue(scoreEngine.segment("youtubewomiru").any { it.kind == SegmentKind.ENGLISH && it.raw == "youtube" })
        assertTrue(scoreEngine.segment("claudenikiitemiru").any { it.kind == SegmentKind.ENGLISH && it.raw == "claude" })
    }

    @Test
    fun alphanumericAndLiteralClausesWidenShrinkAndShowBoundaryBar() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Decimal literal "3.6" shrinks to "3." + "6" and widens back to "3.6"
        val decSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "3.6") decSess.appendChar(ch)
        val decShrunk = decSess.resizeFocusedClause(-1)
        assertEquals(listOf("3.", "6"), decShrunk.clauses.map { it.raw })
        assertTrue(decShrunk.clauses.all { it.isEnglish })
        assertTrue(decShrunk.canExtendClause)
        assertTrue(decShrunk.isConverting)
        val decWidened = decSess.resizeFocusedClause(+1)
        assertEquals(listOf("3.6"), decWidened.clauses.map { it.raw })

        // 2. "1.6mann" focused on "1.6": boundary bar chips, widen +1 -> "1.6m" + "あん", snap -> "1.6ma", shrink back -> "1.6" + "まん"
        val mannSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "1.6mann") mannSess.appendChar(ch)
        val focused16 = mannSess.focusClause(0)
        assertTrue(focused16.canExtendClause)
        assertEquals("1.6mann", focused16.focusedAdjustableKana)
        assertEquals(3, focused16.focusedAdjustableLen)

        val widened1 = mannSess.resizeFocusedClause(+1)
        assertEquals("1.6m", widened1.clauses[0].raw)
        assertEquals("あん", widened1.clauses.drop(1).joinToString("") { it.reading })

        val snapped5 = mannSess.setFocusedClauseLength(5)
        assertEquals("1.6ma", snapped5.clauses[0].raw)
        assertEquals("ん", snapped5.clauses.drop(1).joinToString("") { it.reading })

        mannSess.resizeFocusedClause(-1)
        val restored16 = mannSess.resizeFocusedClause(-1)
        assertEquals("1.6", restored16.clauses[0].raw)
        assertEquals("まん", restored16.clauses.drop(1).joinToString("") { it.reading })

        // 3. "google123" widens from "google" into LITERAL digits -> "google1"
        val gSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "google123") gSess.appendChar(ch)
        gSess.focusClause(0)
        assertTrue(gSess.snapshot.canExtendClause)
        val gWide = gSess.resizeFocusedClause(+1)
        assertEquals("google1", gWide.clauses[0].raw)
        assertEquals("23", gWide.clauses[1].raw)

        // 4. "tuki6" Japanese clause widens into adjacent LITERAL "6"
        val tSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "tuki6") tSess.appendChar(ch)
        tSess.focusClause(0)
        assertTrue(tSess.snapshot.canExtendClause)
        val tWide = tSess.resizeFocusedClause(+1)
        assertEquals("つき6", tWide.clauses[0].reading)

        // 5. Language toggle and split updates enter conversion mode
        val togSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouha") togSess.appendChar(ch)
        assertFalse(togSess.snapshot.isConverting)
        assertTrue(togSess.toggleFocusedSegmentLanguage().isConverting)

        val splitSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouha") splitSess.appendChar(ch)
        assertFalse(splitSess.snapshot.isConverting)
        assertTrue(splitSess.setFocusedClauseLength(2).isConverting)
    }

    @Test
    fun englishGlideModeDecodesEnglishOnlyWithoutJapaneseHijack() {
        val words = listOf("google", "vscode", "claude", "wordle", "twitter", "salt", "dirty")
        for (w in words) {
            val path = KeyLayoutGeometry.syntheticPathForWord(w, jitter = 0.02f)
            val res = glideDecoder.decode(path, maxResults = 8, preferJapanese = false)
            assertTrue("Expected non-empty English results for '$w'", res.isNotEmpty())
            assertTrue("Expected all results to be English when preferJapanese=false for '$w', got $res", res.all { it.isEnglish })
            val top3 = res.take(3).map { it.word }
            assertTrue("Expected '$w' in top-3 English glide results, got $top3", top3.contains(w))
        }
    }

    @Test
    fun sammuAndSaltumuConvertToSammuLongAndLtuStaysSokuon() {
        val converter = AndroidMozcConverter(dictionaries)

        val sammu = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "sammu-") sammu.appendChar(ch)
        assertEquals("さっむー", sammu.snapshot.previewText)
        assertTrue(sammu.snapshot.clauses.none { it.isEnglish })

        val saltumu = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "saltumu-") saltumu.appendChar(ch)
        assertEquals("さっむー", saltumu.snapshot.previewText)
        assertTrue(saltumu.snapshot.clauses.none { it.isEnglish })

        val ltuSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "ltu") ltuSess.appendChar(ch)
        assertEquals("っ", ltuSess.snapshot.previewText)
        assertTrue(ltuSess.snapshot.clauses.none { it.isEnglish })
    }

    @Test
    fun domainAmpersandProperNounsAndQwertySegmentCleanly() {
        val converter = AndroidMozcConverter(dictionaries)

        fun previewFor(raw: String): String {
            val s = CompositionSession(dictionaries, scoreEngine, converter)
            for (ch in raw) s.appendChar(ch)
            return s.snapshot.previewText
        }

        val xSegs = scoreEngine.segment("x.com/")
        assertEquals(listOf(SegmentKind.LITERAL to "x.com/"), xSegs.map { it.kind to it.raw })
        assertEquals("x.com/", previewFor("x.com/"))

        val hmSegs = scoreEngine.segment("H&M")
        assertEquals(listOf(SegmentKind.ENGLISH to "H&M"), hmSegs.map { it.kind to it.raw })
        assertEquals("H&M", previewFor("H&M"))

        val yahooSegs = scoreEngine.segment("yahoo")
        assertEquals(listOf(SegmentKind.ENGLISH to "yahoo"), yahooSegs.map { it.kind to it.raw })
        assertEquals("Yahoo", previewFor("yahoo"))

        val wordleSegs = scoreEngine.segment("wordle")
        assertEquals(listOf(SegmentKind.ENGLISH to "wordle"), wordleSegs.map { it.kind to it.raw })
        assertEquals("Wordle", previewFor("wordle"))

        val qwertySegs = scoreEngine.segment("qwerty")
        assertEquals(listOf(SegmentKind.ENGLISH to "qwerty"), qwertySegs.map { it.kind to it.raw })
        assertEquals("qwerty", previewFor("qwerty"))
    }

    @Test
    fun readableJapaneseIsNeverCorruptedAndEmbeddedTyposOfferCorrections() {
        val converter = AndroidMozcConverter(dictionaries)

        val benkyou = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "benkyousinakyamazui") benkyou.appendChar(ch)
        assertFalse("benkyousinakyamazui should not rewrite to sinai", benkyou.snapshot.previewText.contains("しない"))
        assertTrue(
            "Expected べんきょうしなきゃまずい in readings, got ${benkyou.snapshot.clauses.map { it.reading }}",
            benkyou.snapshot.clauses.joinToString("") { it.reading } == "べんきょうしなきゃまずい"
        )

        val ranai = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "ranai") ranai.appendChar(ch)
        assertEquals("らない", ranai.snapshot.clauses.joinToString("") { it.reading })

        // Neighbor slips inside/adjacent to words offer corrections or repair cleanly without bogus English chunks
        val imaSlip = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "imaladeinakatta") imaSlip.appendChar(ch)
        assertTrue(
            "Expected いままでいなかった in preview or correction chips for imaladeinakatta, got prev=${imaSlip.snapshot.previewText} cands=${imaSlip.snapshot.candidates.map { "${it.subtitle}:${it.text}" }}",
            imaSlip.snapshot.previewText.contains("いままでいなかった") ||
                imaSlip.snapshot.clauses.joinToString("") { it.reading }.contains("いままでいなかった") ||
                imaSlip.snapshot.candidates.any { it.text.contains("いままでいなかった") }
        )

        val kannkek = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kannkekdato") kannkek.appendChar(ch)
        assertTrue(
            "Expected かんけいだと in preview or candidates for kannkekdato, got prev=${kannkek.snapshot.previewText} cands=${kannkek.snapshot.candidates.map { "${it.subtitle}:${it.text}" }}",
            kannkek.snapshot.clauses.joinToString("") { it.reading } == "かんけいだと" ||
                kannkek.snapshot.candidates.any { it.text.contains("かんけい") }
        )
    }

    @Test
    fun japaneseBackspaceDeletesOneKanaAndPreservesPendingRomajiAndEnglish() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Standard Japanese kana chunks delete one hiragana per backspace
        val s1 = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouha") s1.appendChar(ch)
        assertEquals("kyou", s1.backspace().rawInput)
        assertEquals("kyo", s1.backspace().rawInput)
        val afterKyo = s1.backspace()
        assertEquals("ki", afterKyo.rawInput)
        assertEquals("き", afterKyo.clauses.joinToString("") { it.reading })
        assertEquals("", s1.backspace().rawInput)

        val watashi = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "watashi") watashi.appendChar(ch)
        assertEquals("wata", watashi.backspace().rawInput)

        // 2. Moraic nasal nn/n deletes in 1 press
        val kann = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kann") kann.appendChar(ch)
        assertEquals("ka", kann.backspace().rawInput)

        val kan = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kan") kan.appendChar(ch)
        assertEquals("ka", kan.backspace().rawInput)

        // 3. Sokuon double consonant stays "っ" instead of reverting to raw consonant
        val katta = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "katta") katta.appendChar(ch)
        val kattaBack = katta.backspace()
        assertEquals("kaltu", kattaBack.rawInput)
        assertEquals("かっ", kattaBack.clauses.joinToString("") { it.reading })
        assertFalse(kattaBack.previewText.contains("t"))
        katta.appendChar('t')
        val kattaRestored = katta.appendChar('a')
        assertEquals("かった", kattaRestored.clauses.joinToString("") { it.reading })
        assertEquals("ka", katta.apply {
            backspace()
            backspace()
        }.snapshot.rawInput)

        // 4. Context-dependent nasals (m before b/p, single n before j) preserve "ん"
        val shimbun = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "shimbun") shimbun.appendChar(ch)
        assertEquals("shimbu", shimbun.backspace().rawInput)
        val shimbunBack2 = shimbun.backspace()
        assertEquals("shinn", shimbunBack2.rawInput)
        assertEquals("しん", shimbunBack2.clauses.joinToString("") { it.reading })
        assertEquals("shi", shimbunBack2.let { shimbun.backspace().rawInput })

        val kanji = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kanji") kanji.appendChar(ch)
        val kanjiBack = kanji.backspace()
        assertEquals("kann", kanjiBack.rawInput)
        assertEquals("かん", kanjiBack.clauses.joinToString("") { it.reading })
        val kanjiA = kanji.appendChar('a')
        assertEquals("かんあ", kanjiA.clauses.joinToString("") { it.reading })

        // 5. Multi-kana sokuon chunk (ccha -> っちゃ -> っち -> っ -> "")
        val ccha = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "ccha") ccha.appendChar(ch)
        val ccha1 = ccha.backspace()
        assertEquals("tchi", ccha1.rawInput)
        assertEquals("っち", ccha1.clauses.joinToString("") { it.reading })
        val ccha2 = ccha.backspace()
        assertEquals("ltu", ccha2.rawInput)
        assertEquals("っ", ccha2.clauses.joinToString("") { it.reading })
        assertEquals("", ccha.backspace().rawInput)

        // 6. Pending romaji consonant tail deletes 1 romaji letter, then switches to kana
        val pending = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhash") pending.appendChar(ch)
        assertEquals("kyouhas", pending.backspace().rawInput)
        assertEquals("kyouha", pending.backspace().rawInput)
        assertEquals("kyou", pending.backspace().rawInput)

        // 7. English tail and English mode delete 1 ASCII letter
        val mixedEn = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "kyouhagoogle") mixedEn.appendChar(ch)
        assertEquals("kyouhagoogl", mixedEn.backspace().rawInput)

        val enMode = CompositionSession(dictionaries, scoreEngine, converter)
        enMode.englishMode = true
        for (ch in "make") enMode.appendChar(ch)
        val enBack = enMode.backspace()
        assertEquals("mak", enBack.rawInput)
        assertTrue(enBack.clauses.all { it.isEnglish })

        // 8. Repaired typo chunk keeps kana deletion
        val typo = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "onegaishimsu") typo.appendChar(ch)
        assertEquals("onegaishim", typo.backspace().rawInput)
    }

    @Test
    fun idlePunctuationComposesWithCandidatesAndAllUpperAcronymsStayEnglish() {
        val converter = AndroidMozcConverter(dictionaries)

        // 1. Idle punctuation stays composing with alternative candidates and no boundary strip
        val commaSess = CompositionSession(dictionaries, scoreEngine, converter)
        val commaSnap = commaSess.appendText("、")
        assertTrue(commaSnap.isComposing)
        assertEquals("、", commaSnap.previewText)
        val commaCands = commaSnap.candidates.map { it.text }
        assertTrue("Expected punctuation candidates for '、', got $commaCands",
            commaCands.containsAll(listOf("、", "，", ",", "・")))
        assertFalse(commaSnap.canExtendClause)
        assertFalse(commaSnap.canShrinkClause)
        assertTrue(commaSnap.scriptVariants.isEmpty())

        val contSnap = commaSess.appendText("sorede")
        assertTrue(contSnap.previewText.startsWith("、"))
        assertTrue(contSnap.clauses.joinToString("") { it.reading }.contains("それで"))

        val periodSess = CompositionSession(dictionaries, scoreEngine, converter)
        val periodSnap = periodSess.appendText("。")
        assertTrue(periodSnap.candidates.map { it.text }.containsAll(listOf("。", "．", ".")))

        // 2. Gliding English after Japanese punctuation must not insert a leading space
        val glideAfterPunct = CompositionSession(dictionaries, scoreEngine, converter)
        glideAfterPunct.appendText("、")
        glideAfterPunct.appendGlideResult("google", true, listOf("google" to true))
        assertEquals("、google", glideAfterPunct.snapshot.rawInput)

        // 3. 2+ char all-uppercase acronyms stay ENGLISH instead of turning into sokuon ("っf")
        for (acronym in listOf("FF", "TL", "DM", "SNS")) {
            val segs = scoreEngine.segment(acronym)
            assertEquals("Expected ENGLISH for '$acronym': $segs", listOf(SegmentKind.ENGLISH), segs.map { it.kind })
        }
        val ffSess = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "FF") ffSess.appendChar(ch)
        assertEquals("FF", ffSess.snapshot.previewText)
    }

    @Test
    fun illitTypingAndEnglishGlideAccuracyAndInitSpeed() {
        val tInit0 = System.nanoTime()
        val freshDecoder = GlideDecoder(dictionaries)
        val initMs = (System.nanoTime() - tInit0) / 1_000_000.0
        assertTrue("Expected GlideDecoder init < 2500ms on JVM, got ${initMs}ms", initMs < 2500.0)

        val converter = AndroidMozcConverter(dictionaries)

        // 1. Direct typing of ILLIT and mixed phrases never falls back to broken 'いt'
        val illitSingle = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "illit") illitSingle.appendChar(ch)
        assertEquals("ILLIT", illitSingle.snapshot.previewText)

        val illitParticle = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "illitwo") illitParticle.appendChar(ch)
        assertEquals("ILLITを", illitParticle.snapshot.previewText)

        val superIllit = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "super illit") superIllit.appendChar(ch)
        assertFalse("Must not break illit into 'いt': ${superIllit.snapshot.previewText}", superIllit.snapshot.previewText.contains("い"))
        assertEquals("super ILLIT", superIllit.snapshot.previewText)

        val superUnknown = CompositionSession(dictionaries, scoreEngine, converter)
        for (ch in "super xyllit") superUnknown.appendChar(ch)
        assertFalse("Unknown non-romaji word must not produce broken kana: ${superUnknown.snapshot.previewText}", superUnknown.snapshot.previewText.contains("い"))

        // 2. English-only glide (preferJapanese = false) recovers common English words and ILLIT accurately
        fun swipeFromKeys(trace: String, scale: Float = 1.0f): List<io.github.sp9r.meltype.glide.TouchPoint> {
            val pts = KeyLayoutGeometry.syntheticPathForWord(trace, jitter = 0.02f)
            if (scale == 1.0f || pts.isEmpty()) return pts
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            return pts.map {
                io.github.sp9r.meltype.glide.TouchPoint(
                    cx + (it.x - cx) * scale,
                    cy + (it.y - cy) * scale,
                    it.tMs
                )
            }
        }

        val mustRankFirst = listOf(
            "perfect", "green", "got", "sorry", "sweet",
            "thumbnail", "different", "important", "actually",
            "nonfiction", "esim", "illit"
        )
        for (word in mustRankFirst) {
            val decoded = freshDecoder.decode(swipeFromKeys(word), maxResults = 5, preferJapanese = false).map { it.word }
            assertEquals("Expected '$word' #1 in English glide, got $decoded", word, decoded.firstOrNull())
        }

        // Perturbed traces observed in real usage: perfebt -> perfect, scaled ilut-like illit
        val perfebtDecoded = freshDecoder.decode(swipeFromKeys("perfebt"), maxResults = 5, preferJapanese = false).map { it.word }
        assertTrue(
            "Expected 'perfect' in top-2 for perturbed 'perfebt', got $perfebtDecoded",
            perfebtDecoded.take(2).contains("perfect")
        )

        val illitScaled = freshDecoder.decode(swipeFromKeys("illit", scale = 0.85f), maxResults = 5, preferJapanese = false).map { it.word }
        assertTrue(
            "Expected 'illit' in top-2 for scaled swipe, got $illitScaled",
            illitScaled.take(2).contains("illit")
        )
    }
}
