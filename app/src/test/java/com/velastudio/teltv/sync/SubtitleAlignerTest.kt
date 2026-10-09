package com.velastudio.teltv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sin

@androidx.media3.common.util.UnstableApi
class SubtitleAlignerTest {

    private fun randomCues(seed: Long, spanMs: Long): List<SubtitleCue> {
        val r = Random(seed)
        val cues = ArrayList<SubtitleCue>()
        var t = 3_000L
        while (t < spanMs) {
            val len = 800L + r.nextInt(3500)
            cues.add(SubtitleCue(t, t + len))
            t += len + 300L + r.nextInt(7000)
        }
        return cues
    }

    /** Speech signal for [startMs, startMs+lengthMs): the cues moved by [trueShiftMs], with noise. */
    private fun speechFor(cues: List<SubtitleCue>, trueShiftMs: Long, startMs: Long, lengthMs: Long, noise: Double, seed: Long): FloatArray {
        val r = Random(seed)
        val n = (lengthMs / 10).toInt()
        val out = FloatArray(n)
        for (c in cues) {
            val a = ((c.startMs + trueShiftMs - startMs) / 10).toInt().coerceAtLeast(0)
            val b = ((c.endMs + trueShiftMs - startMs) / 10).toInt().coerceAtMost(n)
            for (i in a until b) out[i] = 1f
        }
        for (i in 0 until n) if (r.nextDouble() < noise) out[i] = 1f - out[i]
        return out
    }

    @Test
    fun recoversPositiveShift() {
        val cues = randomCues(1, 3_000_000)
        val speech = speechFor(cues, 4_370, 600_000, 150_000, 0.08, 7)
        val e = SubtitleAligner.alignWindow(speech, 600_000, cues, 60_000)
        assertNotNull(e)
        assertTrue("offset ${e!!.offsetMs}", abs(e.offsetMs - 4_370) <= 30)
        assertTrue(e.accepted)
    }

    @Test
    fun recoversNegativeShift() {
        val cues = randomCues(2, 3_000_000)
        val speech = speechFor(cues, -12_480, 1_200_000, 150_000, 0.08, 9)
        val e = SubtitleAligner.alignWindow(speech, 1_200_000, cues, 60_000)!!
        assertTrue("offset ${e.offsetMs}", abs(e.offsetMs + 12_480) <= 30)
        assertTrue(e.accepted)
    }

    @Test
    fun alreadyAlignedGivesNearZero() {
        val cues = randomCues(3, 3_000_000)
        val speech = speechFor(cues, 0, 300_000, 150_000, 0.1, 11)
        val e = SubtitleAligner.alignWindow(speech, 300_000, cues, 60_000)!!
        assertTrue(abs(e.offsetMs) <= 30)
    }

    @Test
    fun unrelatedAudioIsRejected() {
        val cues = randomCues(4, 3_000_000)
        val other = randomCues(99, 3_000_000)
        val speech = speechFor(other, 0, 600_000, 150_000, 0.05, 5)
        val e = SubtitleAligner.alignWindow(speech, 600_000, cues, 60_000)!!
        assertFalse("z=${e.zScore} ratio=${e.peakRatio}", e.accepted)
    }

    @Test
    fun silenceGivesNoEstimate() {
        val cues = randomCues(5, 3_000_000)
        assertEquals(null, SubtitleAligner.alignWindow(FloatArray(15_000), 0, cues, 60_000))
    }

    @Test
    fun combineNeedsAgreement() {
        fun est(off: Long, z: Double = 12.0) = WindowEstimate(0, off, z, 3.0)
        val ok = SubtitleAligner.combine(listOf(est(2_000), est(2_040), est(1_980), est(9_000, 2.0)))
        assertTrue(ok.confident); assertEquals(2_000, ok.offsetMs); assertFalse(ok.driftSuspected)

        val single = SubtitleAligner.combine(listOf(est(2_000), est(0, 1.0), est(0, 1.0), est(0, 1.0)))
        assertFalse(single.confident)

        val drift = SubtitleAligner.combine(listOf(est(1_000), est(1_020), est(5_000), est(5_030)))
        assertTrue(drift.driftSuspected); assertFalse(drift.confident)

        assertFalse(SubtitleAligner.combine(emptyList()).confident)
    }

    @Test
    fun endToEndThroughEnergyAndDetector() {
        // Build fake audio energy: loud during shifted cues, quiet otherwise, with level wobble.
        val cues = randomCues(6, 3_000_000)
        val shift = -2_650L
        val start = 900_000L
        val n = 15_000
        val r = Random(3)
        val rms = FloatArray(n) { (0.003 + 0.002 * r.nextDouble()).toFloat() }
        for (c in cues) {
            val a = ((c.startMs + shift - start) / 10).toInt().coerceAtLeast(0)
            val b = ((c.endMs + shift - start) / 10).toInt().coerceAtMost(n)
            for (i in a until b) rms[i] = (0.05 + 0.04 * abs(sin(i * 0.3)) + 0.01 * r.nextDouble()).toFloat()
        }
        val speech = SpeechDetector.detect(rms)
        val e = SubtitleAligner.alignWindow(speech, start, cues, 60_000)!!
        assertTrue("offset ${e.offsetMs}", abs(e.offsetMs - shift) <= 40)
        assertTrue(e.accepted)
    }

    @Test
    fun detectorRejectsFlatNoise() {
        val rms = FloatArray(5_000) { 0.01f }
        assertTrue(SpeechDetector.detect(rms).all { it == 0f })
    }

    @Test
    fun parserHandlesSrtAndVtt() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHi\n\n2\n01:02:03,5 --> 01:02:04,25\nBye"
        val cues = SubtitleCueParser.parse(srt)
        assertEquals(listOf(SubtitleCue(1_500, 3_000), SubtitleCue(3_723_500, 3_724_250)), cues)
        val vtt = "WEBVTT\n\n00:05.000 --> 00:07.250\nHello"
        assertEquals(listOf(SubtitleCue(5_000, 7_250)), SubtitleCueParser.parse(vtt))
    }

    @Test
    fun accumulatorBucketsByPresentationTime() {
        val acc = FrameEnergyAccumulator(startMs = 1_000, lengthMs = 100)
        val rate = 8_000
        // 100 ms of audio starting 20 ms before the window: first 20 ms is ignored.
        val pcm = FloatArray(800) { 0.5f }
        acc.add(pcm, pcm.size, rate, 980_000)
        val frames = acc.frames()
        assertEquals(10, frames.size)
        assertEquals(0.5f, frames[0], 1e-4f)
        assertTrue(acc.coverage() > 0.7)
        acc.add(FloatArray(800), 800, rate, 1_100_000)
        assertTrue(acc.finished)
    }

    @Test
    fun windowStartsStayInsideFilm() {
        val starts = SubtitleAutoSync.windowStarts(2_700_000)
        assertEquals(4, starts.size)
        assertTrue(starts.all { it >= 0 && it + SubtitleAutoSync.WINDOW_MS <= 2_700_000 })
    }
}
