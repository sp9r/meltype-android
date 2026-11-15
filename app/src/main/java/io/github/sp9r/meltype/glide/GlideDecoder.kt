package io.github.sp9r.meltype.glide

import io.github.sp9r.meltype.core.MeltypeDictionaries
import io.github.sp9r.meltype.core.RomajiDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GlideCandidate(
    val word: String,
    val isEnglish: Boolean,
    val scoreCost: Float,
    val kanaHint: String? = null
)

data class GlideScoreBreakdown(
    val word: String,
    val traceWord: String,
    val isEnglish: Boolean,
    val scoreCost: Float,
    val baseCost: Float,
    val endpointCost: Float,
    val locDist: Float,
    val shapeDist: Float,
    val dirDist: Float,
    val wpPenalty: Float,
    val cornerAdj: Float,
    val freqBonus: Float,
    val langPrior: Float,
    val source: String = "dict",
    val kanaHint: String? = null
)

data class GlideDebugResult(
    val candidates: List<GlideScoreBreakdown>,
    val pathLen: Float,
    val startKeys: List<Pair<Char, Float>>,
    val endKeys: List<Pair<Char, Float>>,
    val anchorSeq: String
)

/**
 * Dual-language (English + Japanese Romaji) QWERTY gesture/glide decoder.
 * Indexed by (firstChar, lastChar) buckets and scored in two stages:
 *  1) Endpoint distance + M=32 polyline shape + collapsed double-letter fit + arc length + priors
 *  2) Two-way inflection corner verification + monotonic waypoint alignment on top shortlist
 *
 * Japanese lexicon entries expand Hepburn + Kunrei ("si/ti/tu/hu/zi/sya/tya/zya") + shortened long
 * vowels ("ou->o") + JMdict conjugated verb/adjective stems, all resolving to canonical Hepburn output.
 */
class GlideDecoder(private val dictionaries: MeltypeDictionaries) {

    private data class LexiconEntry(
        val traceWord: String,
        val outputWord: String,
        val isEnglish: Boolean,
        val freqBonus: Float,
        val letterMask: Int,
        val kanaHint: String?,
        val template: FloatArray,
        val arcLen: Float,
        val smoothedTemplate: FloatArray,
        val smoothedArcLen: Float,
        val collapsedTrace: String,
        val collapsedTemplate: FloatArray?,
        val collapsedArcLen: Float,
        val arcFractions: FloatArray,
        val collapsedArcFractions: FloatArray,
        val collinearMask: Int,
        val collapsedCollinearMask: Int,
        val tplCx: Float,
        val tplCy: Float,
        val tplScale: Float,
        val tplInvScale: Float,
        val smoothCx: Float,
        val smoothCy: Float,
        val smoothInvScale: Float
    )

    private data class SuffixEntry(
        val romaji: String,
        val kana: String,
        val template: FloatArray,
        val arcLen: Float
    )

    // 26 x 26 bucket index keyed by (firstChar - 'a') * 26 + (lastChar - 'a')
    private val buckets = Array<MutableList<LexiconEntry>>(26 * 26) { ArrayList() }
    private val compoundSuffixes = ArrayList<SuffixEntry>(16)

    init {
        val seen = HashMap<String, LexiconEntry>(56000)
        val japaneseTraceSet = HashSet<String>(40000)

        fun flagsToMask(flags: BooleanArray): Int {
            var mask = 0
            val limit = min(flags.size, 30)
            for (i in 0 until limit) {
                if (flags[i]) mask = mask or (1 shl i)
            }
            return mask
        }

        fun registerTemplate(
            rawTrace: String,
            rawOutput: String,
            isEnglish: Boolean,
            freqBonus: Float,
            kanaHint: String? = null
        ) {
            val trace = rawTrace.lowercase().trim()
            val output = rawOutput.lowercase().trim()
            if (trace.length < 2 || trace.length > 18) return
            if (output.length < 2 || output.length > 20) return
            if (!trace.all { it in 'a'..'z' } || !output.all { it in 'a'..'z' }) return

            if (!isEnglish) {
                japaneseTraceSet.add(trace)
            }

            val dedupKey = "$trace|$output|${if (isEnglish) "E" else "J"}"
            val existing = seen[dedupKey]
            if (existing != null) {
                if (freqBonus > existing.freqBonus || (existing.kanaHint == null && kanaHint != null)) {
                    val idx = (trace.first() - 'a') * 26 + (trace.last() - 'a')
                    val list = buckets[idx]
                    val pos = list.indexOfFirst { it === existing }
                    val updated = existing.copy(
                        freqBonus = max(freqBonus, existing.freqBonus),
                        kanaHint = existing.kanaHint ?: kanaHint
                    )
                    if (pos >= 0) list[pos] = updated
                    seen[dedupKey] = updated
                }
                return
            }

            val (tpl, arc) = KeyLayoutGeometry.templateForWord(trace)
            val collapsed = KeyLayoutGeometry.collapseConsecutiveLetters(trace)
            val (cTpl, cArc) = if (collapsed != trace && collapsed.length >= 2) {
                KeyLayoutGeometry.templateForWord(collapsed)
            } else {
                null to 0f
            }
            val smoothBase = if (collapsed != trace && collapsed.length >= 2) collapsed else trace
            val (sTpl, sArc) = KeyLayoutGeometry.smoothedTemplateForWord(smoothBase)
            val arcFracs = KeyLayoutGeometry.keyArcFractionsForWord(trace)
            val colMask = flagsToMask(KeyLayoutGeometry.collinearFlagsForWord(trace))
            val (cArcFracs, cColMask) = if (collapsed != trace && collapsed.length >= 2) {
                KeyLayoutGeometry.keyArcFractionsForWord(collapsed) to
                    flagsToMask(KeyLayoutGeometry.collinearFlagsForWord(collapsed))
            } else {
                arcFracs to colMask
            }

            val (tCx, tCy, tScale) = templateCentroidAndScale(tpl, KeyLayoutGeometry.RESAMPLE_N)
            val (sCx, sCy, sScale) = templateCentroidAndScale(sTpl, KeyLayoutGeometry.RESAMPLE_N)
            val entry = LexiconEntry(
                traceWord = trace,
                outputWord = output,
                isEnglish = isEnglish,
                freqBonus = freqBonus,
                letterMask = KeyLayoutGeometry.letterMask(trace),
                kanaHint = kanaHint,
                template = tpl,
                arcLen = arc,
                smoothedTemplate = sTpl,
                smoothedArcLen = sArc,
                collapsedTrace = collapsed,
                collapsedTemplate = cTpl,
                collapsedArcLen = cArc,
                arcFractions = arcFracs,
                collapsedArcFractions = cArcFracs,
                collinearMask = colMask,
                collapsedCollinearMask = cColMask,
                tplCx = tCx,
                tplCy = tCy,
                tplScale = tScale,
                tplInvScale = 1f / tScale,
                smoothCx = sCx,
                smoothCy = sCy,
                smoothInvScale = 1f / sScale
            )
            seen[dedupKey] = entry
            val idx = (trace.first() - 'a') * 26 + (trace.last() - 'a')
            buckets[idx].add(entry)
        }

        fun registerJapaneseHira(hira: String, freqBonus: Float, explicitRomaji: String? = null) {
            val canonical = explicitRomaji ?: kanaToRomaji(hira, kunrei = false) ?: return
            if (canonical.length !in 2..18) return
            registerTemplate(canonical, canonical, isEnglish = false, freqBonus = freqBonus, kanaHint = hira)

            // Human single-n trace alias when canonical uses double-n before vowel/y/n ("konnichiwa" -> "konichiwa")
            if (canonical.contains("nn")) {
                val singleN = canonical.replace("nn", "n")
                if (singleN.length >= 2) {
                    registerTemplate(singleN, canonical, isEnglish = false, freqBonus = freqBonus - 0.01f, kanaHint = hira)
                }
            }

            // Kunrei-shiki swipe trace (si, ti, tu, hu, zi, sya, tya, zya) mapped to canonical Hepburn output
            val kunrei = kanaToRomaji(hira, kunrei = true)
            if (kunrei != null && kunrei != canonical && kunrei.length in 2..18) {
                registerTemplate(kunrei, canonical, isEnglish = false, freqBonus = freqBonus - 0.01f, kanaHint = hira)
            }

            // Long vowel shorthand (arigato -> arigatou, tokyo -> toukyou, futsu -> futsuu, toru -> tooru)
            if (canonical.contains("ou") || canonical.contains("uu") || canonical.contains("oo")) {
                val shortVowel = canonical.replace("ou", "o").replace("uu", "u").replace("oo", "o")
                if (shortVowel != canonical && shortVowel.length >= 2) {
                    registerTemplate(shortVowel, canonical, isEnglish = false, freqBonus = freqBonus - 0.025f, kanaHint = hira)
                }
            }
            if (kunrei != null && kunrei != canonical && (kunrei.contains("ou") || kunrei.contains("uu") || kunrei.contains("oo"))) {
                val shortKunrei = kunrei.replace("ou", "o").replace("uu", "u").replace("oo", "o")
                if (shortKunrei != kunrei && shortKunrei.length >= 2) {
                    registerTemplate(shortKunrei, canonical, isEnglish = false, freqBonus = freqBonus - 0.03f, kanaHint = hira)
                }
            }

            // Spoken particle & phonetic aliases on high-value entries (wa->ha, o->wo, ei->e, m->n)
            if (freqBonus >= 0.36f) {
                if (hira.length >= 3 && hira.endsWith("は") && canonical.endsWith("ha") && canonical.length >= 4) {
                    val waAlias = canonical.dropLast(2) + "wa"
                    registerTemplate(waAlias, canonical, isEnglish = false, freqBonus = freqBonus - 0.015f, kanaHint = hira)
                }
                if (canonical.startsWith("wo") && hira.startsWith("を") && canonical.length >= 4) {
                    registerTemplate("o" + canonical.drop(2), canonical, isEnglish = false, freqBonus = freqBonus - 0.02f, kanaHint = hira)
                }
                if (canonical.endsWith("wo") && hira.endsWith("を") && canonical.length >= 4) {
                    registerTemplate(canonical.dropLast(2) + "o", canonical, isEnglish = false, freqBonus = freqBonus - 0.02f, kanaHint = hira)
                }
                if (canonical.length >= 5 && canonical.contains("ei")) {
                    val eAlias = canonical.replace("ei", "e")
                    if (eAlias.length >= 4) {
                        registerTemplate(eAlias, canonical, isEnglish = false, freqBonus = freqBonus - 0.045f, kanaHint = hira)
                    }
                }
                if (canonical.contains("nb") || canonical.contains("np") || canonical.contains("nm")) {
                    val mAlias = canonical.replace("nb", "mb").replace("np", "mp").replace("nm", "mm")
                    registerTemplate(mAlias, canonical, isEnglish = false, freqBonus = freqBonus - 0.025f, kanaHint = hira)
                }
            }
        }

        // Tier 1: Core everyday Japanese romaji & particles
        for ((rom, hiraHint) in CORE_JAPANESE_PAIRS) {
            registerJapaneseHira(hiraHint, freqBonus = 0.56f, explicitRomaji = rom)
            if (!rom.endsWith("ha") && !rom.endsWith("wo")) {
                registerJapaneseHira(hiraHint, freqBonus = 0.54f)
            }
        }
        for (jp in dictionaries.japaneseCanonicalWords) {
            val hira = RomajiDetector.analyze(jp).finalizedHiragana()
            registerJapaneseHira(hira, freqBonus = 0.52f, explicitRomaji = jp)
            for (p in TOP_PARTICLES) {
                if (jp.length + p.length <= 15) {
                    val pKana = RomajiDetector.analyze(p).finalizedHiragana()
                    registerJapaneseHira(hira + pKana, freqBonus = 0.38f, explicitRomaji = jp + p)
                }
            }
        }
        for (stem in CORE_PARTICLE_STEMS) {
            val stemHira = RomajiDetector.analyze(stem).finalizedHiragana()
            for (p in TOP_PARTICLES) {
                val pHira = RomajiDetector.analyze(p).finalizedHiragana()
                registerJapaneseHira(stemHira + pHira, freqBonus = 0.48f, explicitRomaji = stem + p)
            }
        }

        // Tier 2: Phrases, curated candidates, JMdict inflections (medium positive priors)
        for (hira in dictionaries.readingToPhrases.keys) {
            if (hira.length in 2..12) {
                registerJapaneseHira(hira, freqBonus = 0.40f)
            }
        }
        for (hira in dictionaries.extraCandidates.keys) {
            if (hira.length in 2..10) {
                registerJapaneseHira(hira, freqBonus = 0.24f)
            }
        }
        for ((baseHira, tags) in dictionaries.readingWithTags) {
            if (baseHira.length !in 2..8) continue
            for (tag in tags) {
                for (inflected in expandInflections(baseHira, tag)) {
                    if (inflected.length in 2..11) {
                        registerJapaneseHira(inflected, freqBonus = 0.20f)
                    }
                }
            }
        }
        // Tier 4: Untagged JMdict readings (negative prior so obscure readings do not hijack curved traces)
        for (hira in dictionaries.readingsSet) {
            if (hira.length in 2..9) {
                val penalty = if (hira.length <= 2) -0.22f else -0.14f
                registerJapaneseHira(hira, freqBonus = penalty)
            }
        }

        // Tier 1-3 English
        for (w in CORE_ENGLISH_WORDS) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.34f)
        }
        for (w in dictionaries.properNounCanonical.keys) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.30f)
        }
        for (w in dictionaries.commonLatinWordsForGlide) {
            val isJpOrigin = w in dictionaries.japaneseOriginEnglishWords
            registerTemplate(w, w, isEnglish = !isJpOrigin, freqBonus = if (isJpOrigin) 0.14f else 0.24f)
        }
        for (w in dictionaries.englishReadableWords.allWords()) {
            registerTemplate(w, w, isEnglish = true, freqBonus = 0.12f)
        }

        // Tier 5 English (39k SCOWL): only allow medium-long words (>= 5 chars) that do NOT look like
        // valid Japanese romaji, with negative prior so rare English words never hijack Japanese glides.
        for (w in dictionaries.englishAllWords.allWords()) {
            if (w.length in 5..14 && w !in japaneseTraceSet && !dictionaries.japaneseWords.contains(w)) {
                val rom = RomajiDetector.analyze(w, strictForDetection = true)
                val looksLikeJp = rom.isStrictValid && rom.pendingTail.isEmpty() && rom.chunks.size >= 2
                if (!looksLikeJp) {
                    registerTemplate(w, w, isEnglish = true, freqBonus = -0.42f)
                }
            }
        }

        for ((sRom, sKana) in COMPOUND_SUFFIX_PAIRS) {
            val (tpl, arc) = KeyLayoutGeometry.smoothedTemplateForWord(sRom)
            compoundSuffixes.add(SuffixEntry(sRom, sKana, tpl, arc))
        }
    }

    private data class Stage1Hit(
        val entry: LexiconEntry,
        val baseCost: Float,
        val endpointCost: Float = 0f,
        val locDist: Float = 0f,
        val shapeDist: Float = 0f,
        val langPrior: Float = 0f
    )

    private class DebugMetaCollector {
        var pathLen: Float = 0f
        var startKeys: List<Pair<Char, Float>> = emptyList()
        var endKeys: List<Pair<Char, Float>> = emptyList()
        var anchorSeq: String = ""
    }

    /**
     * Decodes normalized isotropic touch points into ranked dual-language glide candidates.
     */
    fun decode(
        normalizedPoints: List<TouchPoint>,
        maxResults: Int = 8,
        preferJapanese: Boolean = true,
        fastPreview: Boolean = false
    ): List<GlideCandidate> {
        return decodeInternal(
            normalizedPoints = normalizedPoints,
            maxResults = maxResults,
            preferJapanese = preferJapanese,
            allowCompoundSplit = !fastPreview,
            fastPreview = fastPreview
        )
    }

    /**
     * Runs the exact production decode path while returning per-candidate cost breakdowns
     * and trajectory anchor metadata for offline evaluation.
     */
    fun decodeDebug(
        normalizedPoints: List<TouchPoint>,
        maxResults: Int = 10,
        preferJapanese: Boolean = true
    ): GlideDebugResult {
        val breakdowns = ArrayList<GlideScoreBreakdown>(64)
        val meta = DebugMetaCollector()
        decodeInternal(
            normalizedPoints = normalizedPoints,
            maxResults = maxResults,
            preferJapanese = preferJapanese,
            allowCompoundSplit = true,
            fastPreview = false,
            debugBreakdownsOut = breakdowns,
            debugMetaOut = meta
        )
        breakdowns.sortBy { it.scoreCost }
        return GlideDebugResult(
            candidates = breakdowns.distinctBy { it.word }.take(maxResults),
            pathLen = meta.pathLen,
            startKeys = meta.startKeys,
            endKeys = meta.endKeys,
            anchorSeq = meta.anchorSeq
        )
    }

    private fun decodeInternal(
        normalizedPoints: List<TouchPoint>,
        maxResults: Int,
        preferJapanese: Boolean,
        allowCompoundSplit: Boolean,
        fastPreview: Boolean = false,
        debugBreakdownsOut: MutableList<GlideScoreBreakdown>? = null,
        debugMetaOut: DebugMetaCollector? = null
    ): List<GlideCandidate> {
        if (normalizedPoints.size < 2) return emptyList()

        val (path, pathLen) = KeyLayoutGeometry.resamplePolyline(normalizedPoints)
        if (pathLen < 0.08f) return emptyList()

        val n = KeyLayoutGeometry.RESAMPLE_N
        val nFloat = n.toFloat()
        val pathShape = KeyLayoutGeometry.normalizeShape(path, n)
        val pathDirs = KeyLayoutGeometry.directionVectors(path, n)

        val startX = normalizedPoints.first().x
        val startY = normalizedPoints.first().y
        val endX = normalizedPoints.last().x
        val endY = normalizedPoints.last().y

        val startLimit = if (fastPreview) 5 else 8
        val endLimit = if (fastPreview) 5 else 8
        val maxEndpointDist = if (fastPreview) 0.58f else 0.68f
        val startKeys = KeyLayoutGeometry.nearestKeys(startX, startY, limit = startLimit)
        val endKeys = KeyLayoutGeometry.nearestKeys(endX, endY, limit = endLimit)
        debugMetaOut?.let {
            it.pathLen = pathLen
            it.startKeys = startKeys.take(4)
            it.endKeys = endKeys.take(4)
        }

        val stage1Limit = if (fastPreview) 40 else 72
        val stage1 = ArrayList<Stage1Hit>(stage1Limit)
        var stage1WorstCost = 2.75f

        val maxPairs = startKeys.size * endKeys.size
        val pairBuckets = IntArray(maxPairs)
        val pairCosts = FloatArray(maxPairs)
        var pairCount = 0
        for ((sChar, sDist) in startKeys) {
            if (sDist > maxEndpointDist && startKeys.first().first != sChar) continue
            val effSDist = max(0f, sDist - 0.13f)
            val sIdx = (sChar - 'a') * 26
            for ((eChar, eDist) in endKeys) {
                if (eDist > maxEndpointDist && endKeys.first().first != eChar) continue
                val effEDist = max(0f, eDist - 0.13f)
                val endpointCost = (effSDist + effEDist) * 0.76f
                var ins = pairCount
                while (ins > 0 && pairCosts[ins - 1] > endpointCost) {
                    pairCosts[ins] = pairCosts[ins - 1]
                    pairBuckets[ins] = pairBuckets[ins - 1]
                    ins--
                }
                pairCosts[ins] = endpointCost
                pairBuckets[ins] = sIdx + (eChar - 'a')
                pairCount++
            }
        }

        for (pIdx in 0 until pairCount) {
            val endpointCost = pairCosts[pIdx]
            val bucket = buckets[pairBuckets[pIdx]]
            for (entry in bucket) {
                val lenDiff = abs(pathLen - entry.arcLen)
                val maxLenBound = maxOf(pathLen, entry.arcLen) * 0.85f + 0.65f
                if (lenDiff > maxLenBound) continue

                val langPrior = when {
                    preferJapanese && !entry.isEnglish -> 0.24f
                    !preferJapanese && entry.isEnglish -> 0.20f
                    else -> 0f
                }
                val minLower = endpointCost - entry.freqBonus - langPrior
                if (minLower - 0.01f >= stage1WorstCost) continue

                val cTpl = entry.collapsedTemplate
                if (cTpl == null) {
                    val cLoc8 = coarseDist8(path, entry.template)
                    if (cLoc8 > 0.96f && coarseDist8(path, entry.smoothedTemplate) > 0.96f) continue
                    val cShape8 = coarseShapeDist8Cached(pathShape, entry.template, entry.tplCx, entry.tplCy, entry.tplInvScale)
                    if (minLower + cLoc8 * 0.78f + cShape8 * 2.15f > stage1WorstCost + 0.55f) continue
                }

                var rawLocDist = meanPointDist(path, entry.template, n, nFloat)
                var effArcDiff = lenDiff

                val smoothLocDist = meanPointDist(path, entry.smoothedTemplate, n, nFloat) - 0.005f
                if (smoothLocDist < rawLocDist) {
                    rawLocDist = smoothLocDist
                    effArcDiff = min(effArcDiff, abs(pathLen - entry.smoothedArcLen) + 0.02f)
                }

                if (cTpl != null) {
                    val cLoc = meanPointDist(path, cTpl, n, nFloat) + 0.01f
                    if (cLoc < rawLocDist) {
                        rawLocDist = cLoc
                        effArcDiff = min(effArcDiff, abs(pathLen - entry.collapsedArcLen) + 0.04f)
                    }
                }

                val shapeDist = min(
                    templateShapeDistCached(pathShape, entry.template, n, nFloat, entry.tplCx, entry.tplCy, entry.tplInvScale),
                    templateShapeDistCached(pathShape, entry.smoothedTemplate, n, nFloat, entry.smoothCx, entry.smoothCy, entry.smoothInvScale)
                )
                val baseCost = minLower +
                    rawLocDist * 0.78f +
                    shapeDist * 2.15f +
                    effArcDiff * 0.10f

                if (baseCost < stage1WorstCost) {
                    val hit = if (debugBreakdownsOut != null) {
                        Stage1Hit(entry, baseCost, endpointCost, rawLocDist, shapeDist, langPrior)
                    } else {
                        Stage1Hit(entry, baseCost)
                    }
                    stage1WorstCost = insertTopStage1(stage1, hit, limit = stage1Limit)
                }
            }
        }

        // Stage 2 reranking: corner anchors + direction channel + arc-fraction waypoint penalty
        val anchors = KeyLayoutGeometry.extractAnchorPoints(normalizedPoints)
        debugMetaOut?.let { meta ->
            val dedup = StringBuilder(anchors.size)
            for (a in anchors) {
                if (dedup.isEmpty() || dedup[dedup.length - 1] != a.nearestChar) {
                    dedup.append(a.nearestChar)
                }
            }
            meta.anchorSeq = dedup.toString()
        }
        val interiorAnchors = anchors.filter { !it.isEndpoint }.map { a ->
            val near2 = KeyLayoutGeometry.nearestKeys(a.x, a.y, limit = 2)
            var nearMask = 0
            for ((kch, d) in near2) {
                if (d < 0.36f) {
                    nearMask = nearMask or (1 shl (kch - 'a'))
                }
            }
            a to nearMask
        }

        val candidates = ArrayList<GlideCandidate>(stage1.size + 8)
        for (hit in stage1) {
            val e = hit.entry
            var cornerAdj = 0f
            for ((anchor, nearMask) in interiorAnchors) {
                if (nearMask != 0) {
                    if ((e.letterMask and nearMask) == 0) {
                        cornerAdj += 0.10f * anchor.sharpness
                    } else {
                        cornerAdj -= 0.03f * anchor.sharpness
                    }
                }
            }
            val d1 = templateDirDist(pathDirs, e.template, n)
            val d2 = templateDirDist(pathDirs, e.smoothedTemplate, n)
            val dirDist = min(d1, d2)

            var wpPenalty = computeWaypointPenalty(
                trace = e.traceWord,
                arcFractions = e.arcFractions,
                collinearMask = e.collinearMask,
                resampledPath = path,
                pathShape = pathShape,
                n = n,
                tCx = e.tplCx,
                tCy = e.tplCy,
                tScale = e.tplScale
            )
            if (e.collapsedTrace != e.traceWord && e.collapsedTrace.length >= 2 && e.collapsedTemplate != null) {
                val (cCx, cCy, cScale) = templateCentroidAndScale(e.collapsedTemplate, n)
                val cWp = computeWaypointPenalty(
                    trace = e.collapsedTrace,
                    arcFractions = e.collapsedArcFractions,
                    collinearMask = e.collapsedCollinearMask,
                    resampledPath = path,
                    pathShape = pathShape,
                    n = n,
                    tCx = cCx,
                    tCy = cCy,
                    tScale = cScale
                ) + 0.01f
                if (cWp < wpPenalty) wpPenalty = cWp
            }

            val finalCost = hit.baseCost + cornerAdj + dirDist * 1.40f + wpPenalty * 0.60f
            candidates.add(GlideCandidate(e.outputWord, e.isEnglish, finalCost, e.kanaHint))
            debugBreakdownsOut?.add(
                GlideScoreBreakdown(
                    word = e.outputWord,
                    traceWord = e.traceWord,
                    isEnglish = e.isEnglish,
                    scoreCost = finalCost,
                    baseCost = hit.baseCost,
                    endpointCost = hit.endpointCost,
                    locDist = hit.locDist,
                    shapeDist = hit.shapeDist,
                    dirDist = dirDist,
                    wpPenalty = wpPenalty,
                    cornerAdj = cornerAdj,
                    freqBonus = e.freqBonus,
                    langPrior = hit.langPrior,
                    source = "dict",
                    kanaHint = e.kanaHint
                )
            )
        }

        // Compound suffix split rescue for single-stroke Stem + masu/desu/shimasu/gozaimasu/etc.
        if (!fastPreview && allowCompoundSplit && preferJapanese && pathLen >= 1.35f && normalizedPoints.size >= 6) {
            decodeCompoundSplits(normalizedPoints, pathLen, endKeys, n, candidates, debugBreakdownsOut)
        }

        // Syllable lattice fallback if trajectory anchors themselves form strict valid romaji
        if (anchors.size >= 2) {
            val dedupChars = ArrayList<Char>(anchors.size)
            for (a in anchors) {
                if (dedupChars.lastOrNull() != a.nearestChar) dedupChars.add(a.nearestChar)
            }
            if (dedupChars.size >= 2) {
                val anchorStr = dedupChars.joinToString("")
                val analysis = RomajiDetector.analyze(anchorStr, strictForDetection = true)
                if (analysis.isStrictValid && analysis.pendingTail.isEmpty() && candidates.none { it.word == anchorStr }) {
                    val kana = analysis.finalizedHiragana()
                    candidates.add(
                        GlideCandidate(
                            word = anchorStr,
                            isEnglish = false,
                            scoreCost = 1.42f,
                            kanaHint = kana
                        )
                    )
                    debugBreakdownsOut?.add(
                        GlideScoreBreakdown(
                            word = anchorStr,
                            traceWord = anchorStr,
                            isEnglish = false,
                            scoreCost = 1.42f,
                            baseCost = 1.42f,
                            endpointCost = 0f,
                            locDist = 0f,
                            shapeDist = 0f,
                            dirDist = 0f,
                            wpPenalty = 0f,
                            cornerAdj = 0f,
                            freqBonus = 0f,
                            langPrior = 0f,
                            source = "anchor_fallback",
                            kanaHint = kana
                        )
                    )
                }
            }
        }

        candidates.sortBy { it.scoreCost }
        return candidates.distinctBy { it.word }.take(maxResults)
    }

    private fun decodeCompoundSplits(
        normalizedPoints: List<TouchPoint>,
        totalLen: Float,
        endKeys: List<Pair<Char, Float>>,
        n: Int,
        out: MutableList<GlideCandidate>,
        debugBreakdownsOut: MutableList<GlideScoreBreakdown>? = null
    ) {
        val m = normalizedPoints.size
        val cum = FloatArray(m)
        var acc = 0f
        for (i in 1 until m) {
            val dx = normalizedPoints[i].x - normalizedPoints[i - 1].x
            val dy = normalizedPoints[i].y - normalizedPoints[i - 1].y
            acc += sqrt(dx * dx + dy * dy)
            cum[i] = acc
        }
        val endChars = endKeys.take(3).map { it.first }.toSet()

        var bestSuf: SuffixEntry? = null
        var bestSplitIdx = -1
        var bestTailCost = Float.MAX_VALUE

        for (suf in compoundSuffixes) {
            if (suf.romaji.last() !in endChars) continue
            val sufStartKey = KeyLayoutGeometry.centerOf(suf.romaji.first()) ?: continue
            var splitIdx = -1
            var bestStartD = 0.45f
            for (i in 2 until m - 2) {
                val frac = cum[i] / totalLen
                if (frac !in 0.30f..0.85f) continue
                val dx = normalizedPoints[i].x - sufStartKey.x
                val dy = normalizedPoints[i].y - sufStartKey.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < bestStartD) {
                    bestStartD = d
                    splitIdx = i
                }
            }
            if (splitIdx < 2) continue

            val tailPts = normalizedPoints.subList(splitIdx, m)
            val (tailPath, tailLen) = KeyLayoutGeometry.resamplePolyline(tailPts, n)
            if (tailLen < 0.25f) continue
            val lenRatioDiff = abs(tailLen - suf.arcLen) / max(suf.arcLen, 0.3f)
            if (lenRatioDiff > 0.65f) continue

            val tailShape = KeyLayoutGeometry.normalizeShape(tailPath, n)
            val tailDirs = KeyLayoutGeometry.directionVectors(tailPath, n)
            val loc = meanPointDist(tailPath, suf.template, n, n.toFloat())
            val shp = templateShapeDist(tailShape, suf.template, n)
            val dir = templateDirDist(tailDirs, suf.template, n)
            val cost = loc * 0.55f + shp * 0.65f + dir * 0.45f + bestStartD * 0.25f
            if (cost < bestTailCost) {
                bestTailCost = cost
                bestSuf = suf
                bestSplitIdx = splitIdx
            }
        }

        val suf = bestSuf ?: return
        if (bestTailCost > 0.30f || bestSplitIdx < 2) return

        val headPts = normalizedPoints.subList(0, bestSplitIdx + 1)
        val stems = decodeInternal(headPts, maxResults = 3, preferJapanese = true, allowCompoundSplit = false)
        for (stem in stems) {
            if (stem.isEnglish || stem.word.length < 2 || stem.scoreCost > 1.15f) continue
            val joined = stem.word + suf.romaji
            val joinedKana = (stem.kanaHint ?: RomajiDetector.analyze(stem.word).finalizedHiragana()) + suf.kana
            val combinedCost = stem.scoreCost + bestTailCost * 0.95f + 0.25f
            if (out.none { it.word == joined }) {
                out.add(GlideCandidate(joined, isEnglish = false, scoreCost = combinedCost, kanaHint = joinedKana))
                debugBreakdownsOut?.add(
                    GlideScoreBreakdown(
                        word = joined,
                        traceWord = joined,
                        isEnglish = false,
                        scoreCost = combinedCost,
                        baseCost = stem.scoreCost,
                        endpointCost = 0f,
                        locDist = 0f,
                        shapeDist = 0f,
                        dirDist = 0f,
                        wpPenalty = bestTailCost,
                        cornerAdj = 0f,
                        freqBonus = 0f,
                        langPrior = 0f,
                        source = "compound+${suf.romaji}",
                        kanaHint = joinedKana
                    )
                )
            }
        }
    }

    private fun coarseDist8(a: FloatArray, b: FloatArray): Float {
        // Indices 0, 4, 8, 12, 16, 20, 24, 28 -> Float offsets 0, 8, 16, ..., 56
        var sum = 0f
        var idx = 0
        while (idx <= 56) {
            val dx = a[idx] - b[idx]
            val dy = a[idx + 1] - b[idx + 1]
            sum += sqrt(dx * dx + dy * dy)
            idx += 8
        }
        return sum * 0.125f
    }

    private fun coarseShapeDist8Cached(
        pathShape: FloatArray,
        tpl: FloatArray,
        cx: Float,
        cy: Float,
        invScale: Float
    ): Float {
        var sum = 0f
        var idx = 0
        while (idx <= 56) {
            val sx = (tpl[idx] - cx) * invScale
            val sy = (tpl[idx + 1] - cy) * invScale
            val dx = pathShape[idx] - sx
            val dy = pathShape[idx + 1] - sy
            sum += sqrt(dx * dx + dy * dy)
            idx += 8
        }
        return sum * 0.125f
    }

    private fun meanPointDist(a: FloatArray, b: FloatArray, n: Int, nFloat: Float): Float {
        var sum = 0f
        var j = 0
        val limit = n * 2
        while (j < limit) {
            val dx = a[j] - b[j]
            val dy = a[j + 1] - b[j + 1]
            sum += sqrt(dx * dx + dy * dy)
            j += 2
        }
        return sum / nFloat
    }

    private fun templateCentroidAndScale(tpl: FloatArray, n: Int): Triple<Float, Float, Float> {
        var cx = 0f
        var cy = 0f
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var i = 0
        val limit = n * 2
        while (i < limit) {
            val x = tpl[i]
            val y = tpl[i + 1]
            cx += x
            cy += y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            i += 2
        }
        cx /= n.toFloat()
        cy /= n.toFloat()
        val scale = max(max(maxX - minX, maxY - minY), 0.22f)
        return Triple(cx, cy, scale)
    }

    private fun templateShapeDistCached(
        pathShape: FloatArray,
        tpl: FloatArray,
        n: Int,
        nFloat: Float,
        cx: Float,
        cy: Float,
        invScale: Float
    ): Float {
        var sum = 0f
        var i = 0
        val limit = n * 2
        while (i < limit) {
            val sx = (tpl[i] - cx) * invScale
            val sy = (tpl[i + 1] - cy) * invScale
            val dx = pathShape[i] - sx
            val dy = pathShape[i + 1] - sy
            sum += sqrt(dx * dx + dy * dy)
            i += 2
        }
        return sum / nFloat
    }

    private fun templateShapeDist(pathShape: FloatArray, tpl: FloatArray, n: Int): Float {
        val (cx, cy, scale) = templateCentroidAndScale(tpl, n)
        return templateShapeDistCached(pathShape, tpl, n, n.toFloat(), cx, cy, 1f / scale)
    }

    private fun templateDirDist(pathDirs: FloatArray, tpl: FloatArray, n: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until n - 1) {
            val px = pathDirs[i * 2]
            val py = pathDirs[i * 2 + 1]
            if (px == 0f && py == 0f) continue
            val tx = tpl[(i + 1) * 2] - tpl[i * 2]
            val ty = tpl[(i + 1) * 2 + 1] - tpl[i * 2 + 1]
            val tLen = sqrt(tx * tx + ty * ty)
            if (tLen > 1e-5f) {
                val dot = ((px * tx + py * ty) / tLen).coerceIn(-1f, 1f)
                sum += 0.5f * (1f - dot)
                count++
            }
        }
        return if (count > 0) sum / count.toFloat() else 0.5f
    }

    private fun computeWaypointPenalty(
        trace: String,
        arcFractions: FloatArray,
        collinearMask: Int,
        resampledPath: FloatArray,
        pathShape: FloatArray,
        n: Int,
        tCx: Float,
        tCy: Float,
        tScale: Float
    ): Float {
        val len = trace.length
        if (len <= 2) return 0f
        var rawWeightedSum = 0f
        var normWeightedSum = 0f
        var totalWeight = 0f
        var rawMaxCornerDist = 0f
        var normMaxCornerDist = 0f
        var minSearchIdx = 0
        var fracIdx = 0
        for (k in 0 until len) {
            val kc = KeyLayoutGeometry.centerOf(trace[k]) ?: continue
            val frac = if (fracIdx < arcFractions.size) arcFractions[fracIdx] else (k.toFloat() / max(1, len - 1))
            val expectedIdx = (frac * (n - 1).toFloat()).toInt().coerceIn(0, n - 1)
            val lo = max(minSearchIdx, expectedIdx - 6).coerceIn(0, n - 1)
            val hi = (expectedIdx + 6).coerceIn(lo, n - 1)
            var bestRawD = Float.MAX_VALUE
            var bestNormD = Float.MAX_VALUE
            var bestIdx = lo
            for (pIdx in lo..hi) {
                val rdx = resampledPath[pIdx * 2] - kc.x
                val rdy = resampledPath[pIdx * 2 + 1] - kc.y
                val rd = sqrt(rdx * rdx + rdy * rdy)
                if (rd < bestRawD) {
                    bestRawD = rd
                    bestIdx = pIdx
                }
                val ax = pathShape[pIdx * 2] * tScale + tCx
                val ay = pathShape[pIdx * 2 + 1] * tScale + tCy
                val ndx = ax - kc.x
                val ndy = ay - kc.y
                val nd = sqrt(ndx * ndx + ndy * ndy)
                if (nd < bestNormD) {
                    bestNormD = nd
                }
            }
            minSearchIdx = max(minSearchIdx, bestIdx - 2)
            val isCollinear = fracIdx < 30 && ((collinearMask and (1 shl fracIdx)) != 0)
            val weight = if (isCollinear) 0.35f else 1.0f
            rawWeightedSum += bestRawD * weight
            normWeightedSum += bestNormD * weight
            totalWeight += weight
            if (!isCollinear) {
                if (bestRawD > rawMaxCornerDist) rawMaxCornerDist = bestRawD
                if (bestNormD > normMaxCornerDist) normMaxCornerDist = bestNormD
            }
            fracIdx++
        }
        val rawAvg = if (totalWeight > 0f) rawWeightedSum / totalWeight else 0f
        val normAvg = if (totalWeight > 0f) normWeightedSum / totalWeight else 0f
        val rawWp = rawAvg * 0.65f + rawMaxCornerDist * 0.35f
        val normWp = normAvg * 0.65f + normMaxCornerDist * 0.35f
        return min(rawWp, normWp + 0.04f)
    }

    private fun insertTopStage1(list: MutableList<Stage1Hit>, item: Stage1Hit, limit: Int): Float {
        if (list.size < limit) {
            list.add(item)
            if (list.size < limit) return 2.75f
            var mx = list[0].baseCost
            for (i in 1 until list.size) {
                if (list[i].baseCost > mx) mx = list[i].baseCost
            }
            return mx
        }
        var maxIdx = 0
        var maxCost = list[0].baseCost
        for (i in 1 until list.size) {
            if (list[i].baseCost > maxCost) {
                maxCost = list[i].baseCost
                maxIdx = i
            }
        }
        if (item.baseCost < maxCost) {
            list[maxIdx] = item
            var nextMax = list[0].baseCost
            for (i in 1 until list.size) {
                if (list[i].baseCost > nextMax) nextMax = list[i].baseCost
            }
            return nextMax
        }
        return maxCost
    }

    companion object {
        private val HEPBURN_MAP = mapOf(
            "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
            "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
            "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
            "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
            "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
            "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
            "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
            "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
            "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
            "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
            "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
            "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
            "てぃ" to "thi", "でぃ" to "dhi", "うぃ" to "wi", "うぇ" to "we",
            "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
            "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
            "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
            "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
            "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
            "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
            "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
            "や" to "ya", "ゆ" to "yu", "よ" to "yo",
            "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
            "わ" to "wa", "を" to "wo",
            "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
            "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
            "だ" to "da", "ぢ" to "di", "づ" to "du", "で" to "de", "ど" to "do",
            "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
            "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po"
        )

        private val KUNREI_OVERRIDES = mapOf(
            "し" to "si",
            "ち" to "ti",
            "つ" to "tu",
            "ふ" to "hu",
            "じ" to "zi",
            "しゃ" to "sya", "しゅ" to "syu", "しょ" to "syo",
            "ちゃ" to "tya", "ちゅ" to "tyu", "ちょ" to "tyo",
            "じゃ" to "zya", "じゅ" to "zyu", "じょ" to "zyo"
        )

        fun kanaToRomaji(hira: String, kunrei: Boolean = false): String? {
            if (hira.isEmpty()) return null
            val sb = StringBuilder(hira.length * 2)
            var i = 0
            while (i < hira.length) {
                val ch = hira[i]
                if (ch == 'っ') {
                    val nextPair = if (i + 2 < hira.length) hira.substring(i + 1, i + 3) else null
                    val nextRom = when {
                        nextPair != null && (if (kunrei) KUNREI_OVERRIDES[nextPair] else null) != null -> KUNREI_OVERRIDES[nextPair]
                        nextPair != null && HEPBURN_MAP[nextPair] != null -> HEPBURN_MAP[nextPair]
                        i + 1 < hira.length -> {
                            val s = hira[i + 1].toString()
                            (if (kunrei) KUNREI_OVERRIDES[s] else null) ?: HEPBURN_MAP[s]
                        }
                        else -> null
                    }
                    val c = nextRom?.firstOrNull() ?: return null
                    if (c !in charArrayOf('a', 'i', 'u', 'e', 'o', 'n')) {
                        sb.append(c)
                    }
                    i++
                    continue
                }
                if (ch == 'ん') {
                    val nextChar = if (i + 1 < hira.length) hira[i + 1] else null
                    val needsDoubleN = nextChar != null && nextChar in "あいうえおやゆよなにぬねの"
                    sb.append(if (needsDoubleN) "nn" else "n")
                    i++
                    continue
                }
                if (i + 1 < hira.length) {
                    val pair = hira.substring(i, i + 2)
                    val rom = (if (kunrei) KUNREI_OVERRIDES[pair] else null) ?: HEPBURN_MAP[pair]
                    if (rom != null) {
                        sb.append(rom)
                        i += 2
                        continue
                    }
                }
                val singleKey = ch.toString()
                val single = (if (kunrei) KUNREI_OVERRIDES[singleKey] else null) ?: HEPBURN_MAP[singleKey] ?: return null
                sb.append(single)
                i++
            }
            return sb.toString()
        }

        private fun expandInflections(hira: String, tag: String): List<String> {
            val out = ArrayList<String>(8)
            when (tag) {
                "v1" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ます", "${stem}ました", "${stem}ません", "${stem}て", "${stem}た", "${stem}ない", "${stem}よう", "${stem}たい"))
                }
                "v5r", "v5r-i" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ります", "${stem}りました", "${stem}りません", "${stem}って", "${stem}った", "${stem}らない", "${stem}りたい"))
                }
                "v5u" -> if (hira.endsWith("う")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}います", "${stem}いました", "${stem}いません", "${stem}って", "${stem}った", "${stem}わない", "${stem}いたい"))
                }
                "v5t" -> if (hira.endsWith("つ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ちます", "${stem}ちました", "${stem}って", "${stem}った", "${stem}たない"))
                }
                "v5k" -> if (hira.endsWith("く")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}いて", "${stem}いた", "${stem}かない", "${stem}きたい"))
                }
                "v5k-s" -> if (hira.endsWith("く")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}って", "${stem}った", "${stem}かない", "${stem}きたい"))
                }
                "v5g" -> if (hira.endsWith("ぐ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}ぎます", "${stem}ぎました", "${stem}いで", "${stem}いだ", "${stem}がない"))
                }
                "v5s" -> if (hira.endsWith("す")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}します", "${stem}しました", "${stem}して", "${stem}した", "${stem}さない", "${stem}したい"))
                }
                "v5m" -> if (hira.endsWith("む")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}みます", "${stem}みました", "${stem}んで", "${stem}んだ", "${stem}まない", "${stem}みたい"))
                }
                "v5b" -> if (hira.endsWith("ぶ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}びます", "${stem}びました", "${stem}んで", "${stem}んだ", "${stem}ばない"))
                }
                "v5n" -> if (hira.endsWith("ぬ")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}にます", "${stem}んで", "${stem}んだ", "${stem}なない"))
                }
                "v5aru" -> if (hira.endsWith("る")) {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}います", "${stem}いました", "${stem}いません"))
                }
                "vk" -> if (hira.endsWith("くる")) {
                    val stem = hira.dropLast(2)
                    out.addAll(listOf("${stem}きます", "${stem}きました", "${stem}きて", "${stem}きた", "${stem}こない", "${stem}きたい"))
                }
                "vs" -> if (hira.endsWith("する")) {
                    val stem = hira.dropLast(2)
                    out.addAll(listOf("${stem}します", "${stem}しました", "${stem}して", "${stem}した", "${stem}しない", "${stem}したい"))
                }
                "i" -> if (hira.endsWith("い") && hira != "いい") {
                    val stem = hira.dropLast(1)
                    out.addAll(listOf("${stem}く", "${stem}くて", "${stem}かった", "${stem}くない", "${stem}いです"))
                }
            }
            return out
        }

        private val TOP_PARTICLES = listOf("ha", "ga", "wo", "ni", "de", "to", "no", "mo", "kara", "made")

        private val CORE_PARTICLE_STEMS = listOf(
            "kyou", "ashita", "kinou", "watashi", "kore", "sore", "are", "koko", "soko", "asoko",
            "dare", "nani", "itsu", "doko", "kaigi", "kadai", "jugyou", "kensaku", "tsuika",
            "kakunin", "henkou", "henshin", "soushin", "hozon", "sakujo", "nihongo", "eigo",
            "denwa", "densha", "jikan", "basho", "shigoto", "kenkyuu", "kaihatsu", "jissou",
            "pasokon", "sumaho", "apuri", "meiru", "meeru", "minna", "sensei", "tomodachi",
            "ie", "eki", "daigaku", "heya", "machi", "tenki", "yoru", "asa", "hiru"
        )

        private val CORE_JAPANESE_PAIRS = listOf(
            "kyou" to "きょう", "kyouha" to "きょうは", "kyoumo" to "きょうも", "kyouno" to "きょうの",
            "ashita" to "あした", "ashitaha" to "あしたは", "ashitano" to "あしたの",
            "kinou" to "きのう", "konshuu" to "こんしゅう", "raishuu" to "らいしゅう", "senshuu" to "せんしゅう",
            "ima" to "いま", "ato" to "あと", "mae" to "まえ", "tsugi" to "つぎ", "saisho" to "さいしょ",
            "saigo" to "さいご", "itsumo" to "いつも", "tokidoki" to "ときどき", "tabun" to "たぶん",
            "kitto" to "きっと", "mousugu" to "もうすぐ", "yappari" to "やっぱり", "yahari" to "やはり",
            "watashi" to "わたし", "watashiha" to "わたしは", "boku" to "ぼく", "jibun" to "じぶん",
            "anata" to "あなた", "kare" to "かれ", "kanojo" to "かのじょ", "minna" to "みんな", "hito" to "ひと",
            "arigatou" to "ありがとう", "ohayou" to "おはよう",
            "konnichiwa" to "こんにちは", "konnichiha" to "こんにちは",
            "konbanwa" to "こんばんは", "konbanha" to "こんばんは",
            "oyasumi" to "おやすみ", "sumimasen" to "すみません", "gomennasai" to "ごめんなさい",
            "onegai" to "おねがい", "onegaishimasu" to "おねがいします",
            "yoroshiku" to "よろしく", "otsukaresama" to "おつかれさま", "daijoubu" to "だいじょうぶ",
            "shitsurei" to "しつれい", "mochiron" to "もちろん", "tashikani" to "たしかに", "naruhodo" to "なるほど",
            "desu" to "です", "masu" to "ます", "mashita" to "ました", "masen" to "ません", "deshita" to "でした",
            "suru" to "する", "shita" to "した", "shimasu" to "します", "shite" to "して", "shimasen" to "しません",
            "dekimasu" to "できます", "dekiru" to "できる", "dekita" to "できた",
            "arimasu" to "あります", "arimashita" to "ありました", "imasu" to "います", "imashita" to "いました",
            "narimasu" to "なります", "narimashita" to "なりました", "naru" to "なる", "natta" to "なった",
            "wakarimashita" to "わかりました", "wakarimasu" to "わかります", "wakatta" to "わかった", "wakaru" to "わかる",
            "omoimasu" to "おもいます", "omou" to "おもう", "omotta" to "おもった",
            "kensaku" to "けんさく", "dekensaku" to "でけんさく", "tsuika" to "ついか", "wotsuika" to "をついか",
            "kakunin" to "かくにん", "henkou" to "へんこう", "shuusei" to "しゅうせい",
            "sakujo" to "さくじょ", "hozon" to "ほぞん", "koushin" to "こうしん",
            "soushin" to "そうしん", "henshin" to "へんしん", "renraku" to "れんらく",
            "shousai" to "しょうさい", "yotei" to "よてい", "kaigi" to "かいぎ", "shiryou" to "しりょう",
            "kadai" to "かだい", "teishutsu" to "ていしゅつ", "shimekiri" to "しめきり",
            "jugyou" to "じゅぎょう", "daigaku" to "だいがく", "kenkyuu" to "けんきゅう",
            "kaihatsu" to "かいはつ", "jissou" to "じっそう", "settei" to "せってい",
            "jikkou" to "じっこう", "saikidou" to "さいきどう", "kanryou" to "かんりょう",
            "nihongo" to "にほんご", "eigo" to "えいご", "henkan" to "へんかん", "nyuuryoku" to "にゅうりょく",
            "kiiboodo" to "きーぼーど", "sumaho" to "すまほ", "pasokon" to "ぱそこん", "apuri" to "あぷり",
            "gamen" to "がめん", "botan" to "ぼたん", "rireki" to "りれき", "takasa" to "たかさ",
            "suuji" to "すうじ", "kigou" to "きごう", "seido" to "せいど",
            "kono" to "この", "sono" to "その", "ano" to "あの", "dono" to "どの",
            "kore" to "これ", "sore" to "それ", "are" to "あれ", "dore" to "どれ",
            "koko" to "ここ", "soko" to "そこ", "asoko" to "あそこ", "doko" to "どこ",
            "nan" to "なん", "nani" to "なに", "dare" to "だれ", "itsu" to "いつ", "naze" to "なぜ", "dou" to "どう",
            "doushite" to "どうして", "dokohe" to "どこへ", "nandesu" to "なんです",
            "hontou" to "ほんとう", "chotto" to "ちょっと", "sukoshi" to "すこし", "takusan" to "たくさん",
            "zenbu" to "ぜんぶ", "issyo" to "いっしょ", "issho" to "いっしょ", "futsuu" to "ふつう",
            "tokuni" to "とくに", "kanari" to "かなり", "chanto" to "ちゃんと", "shikkari" to "しっかり",
            "sugoi" to "すごい", "yabai" to "やばい", "kawaii" to "かわいい", "tanoshii" to "たのしい",
            "ureshii" to "うれしい", "kanashii" to "かなしい", "omoshiroi" to "おもしろい",
            "muzukashii" to "むずかしい", "kantan" to "かんたん", "benri" to "べんり",
            "tsukareta" to "つかれた", "nemui" to "ねむい", "atatakai" to "あたたかい", "samui" to "さむい", "atsui" to "あつい",
            "onaka" to "おなか", "gohan" to "ごはん", "ryouri" to "りょうり", "kaimono" to "かいもの",
            "taberu" to "たべる", "tabete" to "たべて", "tabeta" to "たべた", "tabemasu" to "たべます",
            "nomu" to "のむ", "nonde" to "のんで", "kaeru" to "かえる", "kaette" to "かえって",
            "iku" to "いく", "itte" to "いって", "ikimasu" to "いきます", "itta" to "いった",
            "kuru" to "くる", "kite" to "きて", "kimasu" to "きます", "kita" to "きた",
            "miru" to "みる", "mite" to "みて", "mita" to "みた", "mimasu" to "みます",
            "kiku" to "きく", "kiite" to "きいて", "kaku" to "かく", "kaite" to "かいて",
            "yomu" to "よむ", "yonde" to "よんで", "hanasu" to "はなす", "hanashite" to "はなして",
            "oshieru" to "おしえる", "oshiete" to "おしえて", "shiraberu" to "しらべる", "shirabete" to "しらべて",
            "tsukuru" to "つくる", "tsukutte" to "つくって", "tsukau" to "つかう", "tsukatte" to "つかって",
            "motsu" to "もつ", "motte" to "もって", "kau" to "かう", "katte" to "かって",
            "omou" to "おもう", "kangaeru" to "かんがえる", "kangaete" to "かんがえて",
            "matsu" to "まつ", "matte" to "まって", "tetsudau" to "てつだう", "naosu" to "なおす", "naoshite" to "なおして",
            "ha" to "は", "ga" to "が", "wo" to "を", "ni" to "に", "de" to "で", "to" to "と", "no" to "の", "mo" to "も",
            "he" to "へ", "kara" to "から", "made" to "まで", "yori" to "より", "dake" to "だけ",
            "nado" to "など", "toka" to "とか", "demo" to "でも", "kedo" to "けど", "node" to "ので",
            "noni" to "のに", "tame" to "ため", "youni" to "ように", "desuka" to "ですか", "desune" to "ですね", "desuyo" to "ですよ",
            "maji" to "まじ", "yokatta" to "よかった", "ganbaru" to "がんばる", "ganbatte" to "がんばって",
            "shiranai" to "しらない", "wakaranai" to "わからない", "dekinai" to "できない",
            "taihen" to "たいへん", "daiji" to "だいじ", "hitsuyou" to "ひつよう", "mondai" to "もんだい",
            "riyu" to "りゆう", "kekka" to "けっか", "keikaku" to "けいかく", "jissai" to "じっさい",
            "toriaezu" to "とりあえず", "sonomama" to "そのまま", "seikou" to "せいこう", "shippai" to "しっぱい",
            "shinpai" to "しんぱい", "zannen" to "ざんねん", "mendou" to "めんどう", "tadaima" to "ただいま",
            "okaeri" to "おかえり", "douzo" to "どうぞ", "kondo" to "こんど", "kongetsu" to "こんげつ",
            "raigetsu" to "らいげつ", "kotoshi" to "ことし", "rainen" to "らいねん", "kyonen" to "きょねん",
            "yarimasu" to "やります", "yatte" to "やって", "yatta" to "やった", "neru" to "ねる", "okiru" to "おきる",
            "tasukete" to "たすけて", "isogashii" to "いそがしい", "tanoshimi" to "たのしみ", "ohayougozaimasu" to "おはようございます"
        )

        private val CORE_ENGLISH_WORDS = listOf(
            "the", "be", "to", "of", "and", "in", "that", "have", "it", "for", "not", "on", "with",
            "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say",
            "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can",
            "like", "time", "no", "just", "him", "know", "take", "people", "into", "year", "your",
            "good", "some", "could", "them", "see", "other", "than", "then", "now", "look", "only",
            "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our",
            "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give",
            "day", "most", "us", "need", "try", "ask", "feel", "become", "leave", "put", "mean",
            "keep", "let", "begin", "seem", "help", "talk", "turn", "start", "might", "show", "hear",
            "play", "run", "move", "live", "believe", "hold", "bring", "happen", "write", "provide",
            "sit", "stand", "lose", "pay", "meet", "meeting", "include", "continue", "set", "learn",
            "change", "lead", "understand", "watch", "follow", "stop", "create", "speak", "read",
            "allow", "add", "spend", "grow", "open", "walk", "win", "offer", "remember", "love",
            "consider", "appear", "buy", "wait", "serve", "die", "send", "expect", "build", "stay",
            "fall", "cut", "reach", "kill", "remain", "suggest", "raise", "pass", "sell", "require",
            "report", "decide", "pull", "today", "tomorrow", "yesterday", "tonight", "morning",
            "afternoon", "evening", "week", "month", "world", "hello", "thanks", "thank", "please",
            "sorry", "welcome", "friend", "home", "house", "room", "school", "office", "park",
            "google", "android", "github", "meltype", "keyboard", "glide", "swipe", "search",
            "project", "code", "test", "commit", "push", "pull", "branch", "merge", "build",
            "app", "application", "system", "data", "file", "folder", "window", "screen", "button",
            "check", "update", "install", "version", "release", "error", "warning", "debug", "log",
            "message", "chat", "email", "mail", "calendar", "schedule", "task", "todo", "note",
            "notes", "music", "video", "photo", "camera", "phone", "mobile", "laptop", "desktop",
            "server", "client", "network", "wifi", "bluetooth", "password", "account", "user",
            "settings", "config", "theme", "light", "dark", "layout", "design", "style", "input",
            "output", "text", "word", "words", "japanese", "english", "romaji", "hiragana", "katakana",
            "kanji", "mozc", "linux", "windows", "macos", "python", "kotlin", "java", "gradle"
        )

        private val COMPOUND_SUFFIX_PAIRS = listOf(
            "gozaimasu" to "ございます",
            "shimasen" to "しません",
            "shimashita" to "しました",
            "shimasu" to "します",
            "deshita" to "でした",
            "mashita" to "ました",
            "masen" to "ません",
            "kudasai" to "ください",
            "shite" to "して",
            "desu" to "です",
            "masu" to "ます",
            "nai" to "ない",
            "tai" to "たい"
        )
    }
}
