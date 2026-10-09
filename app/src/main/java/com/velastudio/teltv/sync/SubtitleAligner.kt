package com.velastudio.teltv.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Result of correlating one stretch of audio against the subtitle file. */
data class WindowEstimate(
    val windowStartMs: Long,
    /** Add this to every subtitle time to line it up with the audio. */
    val offsetMs: Long,
    /** How many standard deviations the best offset stands above the average candidate. */
    val zScore: Double,
    /** (best - mean) / (best competing peak outside +-1 s - mean). */
    val peakRatio: Double
) {
    val accepted: Boolean get() = zScore >= MIN_Z && peakRatio >= MIN_RATIO

    companion object {
        const val MIN_Z = 5.0
        const val MIN_RATIO = 1.25
    }
}

data class AlignmentResult(
    val offsetMs: Long,
    val confident: Boolean,
    val agreeingWindows: Int,
    val totalWindows: Int,
    /** True when good windows disagree with each other: offset is not constant (FPS drift or cuts). */
    val driftSuspected: Boolean,
    val estimates: List<WindowEstimate>
)

object SubtitleAligner {
    private const val FRAME = SpeechDetector.FRAME_MS

    /**
     * Finds the subtitle shift that best lines up with [speech] (a 10 ms-resolution signal whose
     * first sample is at [windowStartMs] on the audio clock), searching +-[maxShiftMs].
     */
    fun alignWindow(
        speech: FloatArray,
        windowStartMs: Long,
        cues: List<SubtitleCue>,
        maxShiftMs: Long
    ): WindowEstimate? {
        if (speech.size < 500 || cues.isEmpty()) return null
        val m = (maxShiftMs / FRAME).toInt()
        val subLen = speech.size + 2 * m
        // sub[j] covers original subtitle time subStart + j*FRAME.
        val subStart = windowStartMs - m.toLong() * FRAME
        val sub = DoubleArray(subLen)
        for (c in cues) {
            val a = ((c.startMs - subStart) / FRAME).toInt().coerceAtLeast(0)
            val b = ((c.endMs - subStart) / FRAME).toInt().coerceAtMost(subLen)
            for (i in a until b) sub[i] = 1.0
        }
        if (sub.all { it == 0.0 }) return null
        val a = DoubleArray(speech.size) { speech[it].toDouble() }
        if (a.all { it == a[0] }) return null
        center(a); center(sub)

        val size = Fft.nextPow2(subLen + speech.size)
        val ar = DoubleArray(size); val ai = DoubleArray(size)
        val br = DoubleArray(size); val bi = DoubleArray(size)
        a.copyInto(ar); sub.copyInto(br)
        Fft.transform(ar, ai, false); Fft.transform(br, bi, false)
        // r[d] = sum_i a[i] * sub[i + d]  ->  IFFT(conj(A) * B)
        for (i in 0 until size) {
            val re = ar[i] * br[i] + ai[i] * bi[i]
            val im = ar[i] * bi[i] - ai[i] * br[i]
            ar[i] = re; ai[i] = im
        }
        Fft.transform(ar, ai, true)

        val lags = 2 * m + 1
        var best = Int.MIN_VALUE
        var bestV = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var sumSq = 0.0
        for (d in 0 until lags) {
            val v = ar[d]
            sum += v; sumSq += v * v
            if (v > bestV) { bestV = v; best = d }
        }
        val mean = sum / lags
        val std = sqrt(max(sumSq / lags - mean * mean, 1e-18))
        val z = (bestV - mean) / std
        var second = Double.NEGATIVE_INFINITY
        val guard = 1000 / FRAME
        for (d in 0 until lags) {
            if (abs(d - best) > guard && ar[d] > second) second = ar[d]
        }
        val ratio = if (second.isInfinite()) 10.0 else (bestV - mean) / max(second - mean, 1e-12)
        // d = M - k, offset = k * FRAME.
        val offset = (m - best).toLong() * FRAME
        return WindowEstimate(windowStartMs, offset, z, ratio)
    }

    /** Combines window estimates: needs agreement, not just one lucky peak. */
    fun combine(estimates: List<WindowEstimate>, toleranceMs: Long = 300): AlignmentResult {
        val good = estimates.filter { it.accepted }
        if (good.isEmpty()) return AlignmentResult(0, false, 0, estimates.size, false, estimates)
        // Largest cluster of estimates within tolerance of each other.
        val cluster = good.map { anchor -> good.filter { abs(it.offsetMs - anchor.offsetMs) <= toleranceMs } }
            .maxWith(compareBy({ it.size }, { c -> c.sumOf { it.zScore } }))
        val sorted = cluster.sortedBy { it.offsetMs }
        val median = sorted[sorted.size / 2].offsetMs
        val strong = cluster.size == 1 && cluster[0].zScore >= 9.0 && estimates.size == 1
        val confident = cluster.size >= 2 || strong
        val outliers = good.size - cluster.size
        val drift = outliers >= 2 && outliers >= cluster.size
        return AlignmentResult(median, confident && !drift, cluster.size, estimates.size, drift, estimates)
    }

    private fun center(x: DoubleArray) {
        var s = 0.0
        for (v in x) s += v
        val mean = s / x.size
        for (i in x.indices) x[i] -= mean
    }
}
