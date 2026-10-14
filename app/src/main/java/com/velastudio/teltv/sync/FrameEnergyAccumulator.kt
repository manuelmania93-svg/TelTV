package com.velastudio.teltv.sync

import kotlin.math.sqrt

/**
 * Collects decoded PCM into [SpeechDetector.FRAME_MS] RMS frames on the media clock.
 * Pure Kotlin so it can be tested on the JVM; the Android decoder feeds it.
 */
class FrameEnergyAccumulator(private val startMs: Long, private val lengthMs: Long) {
    private val frameCount = (lengthMs / SpeechDetector.FRAME_MS).toInt()
    private val sumSq = DoubleArray(frameCount)
    private val counts = IntArray(frameCount)

    private var bandFilter: SpeechBandFilter? = null
    private var filterRate = 0

    /** True once audio past the end of the window has been seen. */
    var finished = false
        private set

    /**
     * @param mono samples in -1..1, already averaged across channels
     * @param ptsUs presentation time of mono[0]
     */
    fun add(mono: FloatArray, count: Int, sampleRate: Int, ptsUs: Long) {
        // Measure energy in the speech band only (bass and hiss are not dialogue).
        val filter = bandFilter?.takeIf { filterRate == sampleRate }
            ?: SpeechBandFilter(sampleRate).also { bandFilter = it; filterRate = sampleRate }
        for (i in 0 until count) {
            val v = filter.process(mono[i])
            val tMs = (ptsUs + i * 1_000_000L / sampleRate) / 1000 - startMs
            if (tMs < 0) continue
            val f = (tMs / SpeechDetector.FRAME_MS).toInt()
            if (f >= frameCount) { finished = true; return }
            sumSq[f] += (v * v).toDouble()
            counts[f]++
        }
    }

    fun frames(): FloatArray = FloatArray(frameCount) {
        if (counts[it] == 0) 0f else sqrt(sumSq[it] / counts[it]).toFloat()
    }

    /** Fraction of frames that received any audio. Low coverage means a failed or partial decode. */
    fun coverage(): Double = if (frameCount == 0) 0.0 else counts.count { it > 0 }.toDouble() / frameCount
}
