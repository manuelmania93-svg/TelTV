package com.velastudio.teltv.sync

import kotlin.math.log10

/**
 * Turns per-frame audio energy into a speech / no-speech signal.
 *
 * This is a deliberately simple energy VAD (the same idea as the "auditok" option in ffsubsync):
 * level the clip between its own noise floor and its own loud end, then call the loud part
 * speech. It cannot tell speech from music or effects, so alignment later requires the
 * correlation peak to be clearly better than chance before anything is applied.
 */
object SpeechDetector {
    const val FRAME_MS = 10

    /** @param frameRms linear RMS of each [FRAME_MS] frame. Returns 1f for speech, 0f otherwise. */
    fun detect(frameRms: FloatArray): FloatArray {
        val n = frameRms.size
        if (n < 100) return FloatArray(n)
        val db = FloatArray(n) { (20.0 * log10(frameRms[it].toDouble() + 1e-6)).toFloat() }
        val sorted = db.copyOf().also { it.sort() }
        val floor = sorted[(n * 0.20).toInt()]
        val ceil = sorted[(n * 0.95).toInt().coerceAtMost(n - 1)]
        // Under ~8 dB of dynamic range the clip is silence or constant noise: no usable signal.
        if (ceil - floor < 8f) return FloatArray(n)
        val threshold = floor + 0.40f * (ceil - floor)
        val raw = BooleanArray(n) { db[it] > threshold }
        // Close pauses shorter than 250 ms, then drop blips shorter than 120 ms.
        fillRuns(raw, value = false, maxLen = 25, replaceWith = true)
        fillRuns(raw, value = true, maxLen = 12, replaceWith = false)
        return FloatArray(n) { if (raw[it]) 1f else 0f }
    }

    private fun fillRuns(a: BooleanArray, value: Boolean, maxLen: Int, replaceWith: Boolean) {
        var i = 0
        while (i < a.size) {
            if (a[i] != value) { i++; continue }
            var j = i
            while (j < a.size && a[j] == value) j++
            val interior = i > 0 && j < a.size
            if (interior && j - i <= maxLen) for (k in i until j) a[k] = replaceWith
            i = j
        }
    }
}
