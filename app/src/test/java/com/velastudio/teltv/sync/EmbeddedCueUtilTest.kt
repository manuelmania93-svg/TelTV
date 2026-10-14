package com.velastudio.teltv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedCueUtilTest {
    @Test fun srtSampleTextIsStripped() {
        assertEquals("Hello there", EmbeddedCueUtil.cueText("<i>Hello</i> there", ass = false))
        assertEquals("Two lines", EmbeddedCueUtil.cueText("Two\nlines", ass = false))
    }

    @Test fun assSampleKeepsTextWithCommasAndDropsOverrides() {
        val raw = "12,0,Default,,0,0,0,,{\\i1}Well, you know, it\\Nworks{\\i0}"
        assertEquals("Well, you know, it works", EmbeddedCueUtil.cueText(raw, ass = true))
    }

    @Test fun durationIsEstimatedFromTextLengthWithinBounds() {
        assertEquals(1_000L, EmbeddedCueUtil.estimatedDurationMs("Hi"))
        assertEquals(7_000L, EmbeddedCueUtil.estimatedDurationMs("x".repeat(500)))
        assertEquals(65L * 40, EmbeddedCueUtil.estimatedDurationMs("x".repeat(40)))
    }

    @Test fun duplicatesCollapseAndBlankIsDropped() {
        val cues = EmbeddedCueUtil.toCues(listOf(5_000L to "Line", 5_000L to "Line", 6_000L to " ", 9_000L to "Other"))
        assertEquals(2, cues.size)
        assertEquals(5_000L, cues[0].startMs)
    }

    @Test fun rasterizeMarksCueSpansInsideWindow() {
        val sig = EmbeddedCueUtil.rasterize(listOf(SubtitleCue(1_000, 2_000), SubtitleCue(-500, 200)), 0, 4_000)
        assertEquals(400, sig.size)
        assertEquals(1f, sig[150]); assertEquals(0f, sig[50]); assertEquals(1f, sig[5]); assertEquals(0f, sig[300])
    }

    @Test fun referenceFromEmbeddedCuesAlignsLikeAudioWould() {
        // Reference cues (embedded, estimated ends) vs a subtitle file that is 2.4 s late.
        val r = java.util.Random(3)
        val ref = ArrayList<SubtitleCue>()
        var t = 1_000L
        while (t < 140_000L) { val len = 1_000L + r.nextInt(3000); ref.add(SubtitleCue(t, t + len)); t += len + 400 + r.nextInt(5000) }
        val late = ref.map { SubtitleCue(it.startMs + 2_400, it.endMs + 2_400 + r.nextInt(200) - 100) }
        val signal = EmbeddedCueUtil.rasterize(ref, 0L, 150_000L)
        val est = SubtitleAligner.alignWindow(signal, 0L, late, 60_000L)!!
        assertEquals(-2_400.0, est.offsetMs.toDouble(), 100.0)
        assertTrue(est.accepted)
    }
    @Test fun startsIgnoreDurationAndDoNotClipEarlierCues() {
        val a = listOf(SubtitleCue(-1, 4000), SubtitleCue(1000, 1200), SubtitleCue(3990, 9000), SubtitleCue(4000, 5000))
        val b = a.map { it.copy(endMs = it.startMs + 7000) }
        val signal = EmbeddedCueUtil.rasterizeStarts(a, 0, 4000)
        org.junit.Assert.assertArrayEquals(signal, EmbeddedCueUtil.rasterizeStarts(b, 0, 4000), 0f)
        assertEquals(0f, signal[0]); assertEquals(1f, signal[100])
        assertEquals(1f, signal[109]); assertEquals(0f, signal[110]); assertEquals(1f, signal[399])
    }

    @Test fun startAlignmentHasNoBiasWithVeryDifferentCueEnds() {
        val random = java.util.Random(42)
        val reference = ArrayList<SubtitleCue>()
        var time = 1000L
        while (time < 140000) {
            reference.add(SubtitleCue(time, time + 6500))
            time += 800 + random.nextInt(6000)
        }
        for (shift in listOf(-2400L, 0L, 4370L)) {
            val target = reference.map { SubtitleCue(it.startMs - shift, it.startMs - shift + 500) }
            val signal = EmbeddedCueUtil.rasterizeStarts(reference, 0, 150000)
            val estimate = SubtitleAligner.alignWindow(signal, 0, target, 60000, startsOnly = true)!!
            assertEquals(shift, estimate.offsetMs)
            assertTrue(estimate.accepted)
        }
    }
}
