package com.velastudio.teltv.sync

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Keeps roughly the 300-3400 Hz telephone/speech band before energy is measured, so bass, drums,
 * sub rumble and high-frequency hiss or effects no longer count as "someone is talking".
 * Two second-order Butterworth sections (high-pass, then low-pass). Pure Kotlin, JVM tested.
 */
class SpeechBandFilter(private val sampleRate: Int, lowHz: Double = LOW_HZ, highHz: Double = HIGH_HZ) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0
        var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }

    private val highPass = design(lowHz, sampleRate, highPass = true)
    private val lowPass = design(minOf(highHz, sampleRate / 2.0 * 0.9), sampleRate, highPass = false)

    fun process(x: Float): Float = lowPass.process(highPass.process(x.toDouble())).toFloat()

    private fun design(freq: Double, rate: Int, highPass: Boolean): Biquad {
        val w0 = 2.0 * PI * freq / rate
        val q = 1.0 / Math.sqrt(2.0)
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        val b0: Double; val b1: Double; val b2: Double
        if (highPass) {
            b0 = (1.0 + c) / 2.0; b1 = -(1.0 + c); b2 = (1.0 + c) / 2.0
        } else {
            b0 = (1.0 - c) / 2.0; b1 = 1.0 - c; b2 = (1.0 - c) / 2.0
        }
        return Biquad(b0 / a0, b1 / a0, b2 / a0, (-2.0 * c) / a0, (1.0 - alpha) / a0)
    }

    companion object {
        const val LOW_HZ = 300.0
        const val HIGH_HZ = 3400.0
    }
}
