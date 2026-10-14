package com.velastudio.teltv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechBandFilterTest {
    private val rate = 16_000

    private fun rms(freq: Double): Double {
        val f = SpeechBandFilter(rate)
        var sum = 0.0
        var n = 0
        for (i in 0 until rate * 2) {
            val y = f.process(sin(2 * PI * freq * i / rate).toFloat())
            if (i > rate / 2) { sum += y * y; n++ }
        }
        return sqrt(sum / n)
    }

    @Test fun speechBandPassesAndBassAndHissAreCut() {
        val speech = rms(1_000.0)
        assertTrue("1 kHz kept", speech > 0.6)
        assertTrue("60 Hz bass cut", rms(60.0) < 0.05)
        assertTrue("100 Hz cut", rms(100.0) < 0.12)
        assertTrue("7 kHz hiss cut", rms(7_000.0) < 0.12)
    }

    private fun correlation(a: FloatArray, b: FloatArray): Double {
        val n = minOf(a.size, b.size)
        val ma = a.take(n).average(); val mb = b.take(n).average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in 0 until n) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return sab / sqrt(saa * sbb)
    }

    @Test fun loudBassMusicNoLongerHidesDialogueBursts() {
        val lengthMs = 60_000L
        val acc = FrameEnergyAccumulator(0L, lengthMs)
        val samples = FloatArray((lengthMs * rate / 1000).toInt())
        val truth = FloatArray((lengthMs / 10).toInt())
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            val talking = (t % 6.0) < 2.5
            val bass = 0.6 * sin(2 * PI * 55.0 * t) * (0.7 + 0.3 * sin(2 * PI * 0.5 * t)) // loud, pulsing music bass
            val voice = if (talking) 0.08 * sin(2 * PI * 900.0 * t) * (0.6 + 0.4 * sin(2 * PI * 4.0 * t)) else 0.0
            samples[i] = (bass + voice).toFloat()
        }
        for (f in truth.indices) truth[f] = if ((f * 0.01) % 6.0 < 2.5) 1f else 0f
        acc.add(samples, samples.size, rate, 0L)
        val detected = SpeechDetector.detect(acc.frames())
        assertTrue("dialogue pattern recovered, got ${correlation(detected, truth)}", correlation(detected, truth) > 0.8)
        assertEquals(1.0, acc.coverage(), 0.01)
    }
}
