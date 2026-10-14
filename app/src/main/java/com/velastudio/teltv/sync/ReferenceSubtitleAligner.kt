package com.velastudio.teltv.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Result of lining a subtitle up with a reference subtitle that is already in sync with the video.
 * On screen, a target cue at time t shows at t * [scale] + [offsetMs].
 */
data class ReferenceAlignment(
    val offsetMs: Long,
    val scale: Double,
    /** Share of the speaking time the two subtitles agree on, 0..1 (cosine of the two on/off signals). */
    val score: Double,
    /** Best score of any clearly different offset/scale. */
    val runnerUp: Double,
    val confident: Boolean
)

/**
 * Language-agnostic subtitle-to-subtitle alignment (own implementation, no third-party code).
 *
 * Both subtitles are reduced to "someone is talking" on/off signals, whatever the language. For
 * each common frame-rate ratio the target is stretched, the best shift is found by FFT
 * cross-correlation at 100 ms resolution, then refined to 10 ms with exact interval overlap.
 * Constant offsets and fixed frame-rate changes are handled; cuts/ad breaks (different offsets in
 * different parts) are not, and are reported as not confident.
 */
object ReferenceSubtitleAligner {
    const val COARSE_MS = 100
    const val MAX_SHIFT_MS = 180_000L
    const val MIN_SCORE = 0.45
    const val MIN_RATIO = 1.15
    const val MIN_CUES = 20

    /** Scales a target written for one frame rate needs to play at another, plus no change. */
    val CANDIDATE_SCALES: List<Double> = listOf(
        1.0,
        23.976 / 25.0, 25.0 / 23.976,
        24.0 / 25.0, 25.0 / 24.0,
        23.976 / 24.0, 24.0 / 23.976,
        29.97 / 30.0, 30.0 / 29.97,
        29.97 / 25.0, 25.0 / 29.97
    )

    /** An identity result must lose by this factor before a stretched one replaces it. */
    private const val STRETCH_MARGIN = 1.05

    fun align(reference: List<SubtitleCue>, target: List<SubtitleCue>, maxShiftMs: Long = MAX_SHIFT_MS): ReferenceAlignment? {
        val ref = reference.sortedBy { it.startMs }
        val tgt = target.sortedBy { it.startMs }
        if (ref.size < MIN_CUES || tgt.size < MIN_CUES) return null
        val span = max(ref.last().endMs, (tgt.last().endMs * 1.1).toLong()) + maxShiftMs
        val n = (span / COARSE_MS).toInt() + 2
        val maxLag = (maxShiftMs / COARSE_MS).toInt()

        val refSig = raster(ref, 1.0, n)
        val refSum = refSig.sum()
        if (refSum <= 0.0) return null
        val size = Fft.nextPow2(2 * n)
        val refRe = DoubleArray(size); val refIm = DoubleArray(size)
        refSig.copyInto(refRe)
        Fft.transform(refRe, refIm, false)

        data class Peak(val scale: Double, val lagMs: Long, val score: Double)
        val peaks = ArrayList<Peak>()
        val allBest = ArrayList<Pair<Peak, Double>>() // peak with the best competing score at the same scale
        for (scale in CANDIDATE_SCALES) {
            val sig = raster(tgt, scale, n)
            val sum = sig.sum()
            if (sum <= 0.0) continue
            val re = DoubleArray(size); val im = DoubleArray(size)
            sig.copyInto(re)
            Fft.transform(re, im, false)
            // r[d] = sum_i sig[i] * ref[i + d]  ->  IFFT(conj(S) * R)
            for (i in 0 until size) {
                val a = re[i]; val b = im[i]
                re[i] = a * refRe[i] + b * refIm[i]
                im[i] = a * refIm[i] - b * refRe[i]
            }
            Fft.transform(re, im, true)
            val norm = sqrt(sum * refSum)
            // Lag d in 0..n-1 means target shifted by +d; wrapped indices are negative shifts.
            var best = Double.NEGATIVE_INFINITY
            var bestLag = 0
            for (lag in -maxLag..maxLag) {
                val idx = if (lag >= 0) lag else size + lag
                val v = re[idx]
                if (v > best) { best = v; bestLag = lag }
            }
            var second = 0.0
            val guard = 2_000 / COARSE_MS
            for (lag in -maxLag..maxLag) {
                if (abs(lag - bestLag) <= guard) continue
                val idx = if (lag >= 0) lag else size + lag
                if (re[idx] > second) second = re[idx]
            }
            val p = Peak(scale, bestLag.toLong() * COARSE_MS, best / norm)
            peaks.add(p)
            allBest.add(p to second / norm)
        }
        if (peaks.isEmpty()) return null

        val identity = peaks.firstOrNull { it.scale == 1.0 }
        var chosen = peaks.maxBy { it.score }
        if (identity != null && chosen.scale != 1.0 && chosen.score < identity.score * STRETCH_MARGIN) chosen = identity

        // Refine at 10 ms with exact overlap around the coarse peak.
        var bestOffset = chosen.lagMs
        var bestScore = -1.0
        var o = chosen.lagMs - 200
        while (o <= chosen.lagMs + 200) {
            val s = exactScore(ref, tgt, chosen.scale, o)
            if (s > bestScore) { bestScore = s; bestOffset = o }
            o += 10
        }

        val sameScaleSecond = allBest.first { it.first === chosen }.second
        // A competing candidate only counts when its scale is a real alternative (not 1.0 vs 1.001).
        val otherScale = peaks.filter { it !== chosen && abs(it.scale - chosen.scale) > 0.002 }
            .maxOfOrNull { it.score } ?: 0.0
        val runnerUp = max(sameScaleSecond, otherScale)
        val confident = bestScore >= MIN_SCORE && bestScore >= runnerUp * MIN_RATIO
        return ReferenceAlignment(bestOffset, chosen.scale, bestScore, runnerUp, confident)
    }

    private fun raster(cues: List<SubtitleCue>, scale: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        for (c in cues) {
            val a = ((c.startMs * scale) / COARSE_MS).roundToInt().coerceAtLeast(0)
            val b = ((c.endMs * scale) / COARSE_MS).roundToInt().coerceAtMost(n)
            for (i in a until b) out[i] = 1.0
        }
        return out
    }

    /** Cosine of the on/off signals computed from exact intervals; cues must not overlap much. */
    internal fun exactScore(ref: List<SubtitleCue>, tgt: List<SubtitleCue>, scale: Double, offsetMs: Long): Double {
        var overlap = 0L
        var j = 0
        var tgtTotal = 0L
        val moved = tgt.map { SubtitleCue((it.startMs * scale).roundToLong() + offsetMs, (it.endMs * scale).roundToLong() + offsetMs) }
        for (m in moved) tgtTotal += m.endMs - m.startMs
        val refTotal = ref.sumOf { it.endMs - it.startMs }
        for (m in moved) {
            while (j < ref.size && ref[j].endMs <= m.startMs) j++
            var k = j
            while (k < ref.size && ref[k].startMs < m.endMs) {
                overlap += max(0L, min(ref[k].endMs, m.endMs) - max(ref[k].startMs, m.startMs))
                k++
            }
        }
        if (tgtTotal <= 0 || refTotal <= 0) return 0.0
        return overlap / sqrt(tgtTotal.toDouble() * refTotal.toDouble())
    }
}
