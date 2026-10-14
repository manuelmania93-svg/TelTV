package com.velastudio.teltv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ReferenceSubtitleAlignerTest {
    /** A 24 minute episode of dialogue. Same timeline for reference; the target is a different language. */
    private fun reference(seed: Long = 1L, spanMs: Long = 24 * 60_000L): List<SubtitleCue> {
        val r = Random(seed)
        val cues = ArrayList<SubtitleCue>()
        var t = 5_000L
        while (t < spanMs) {
            val len = 800L + r.nextInt(3500)
            cues.add(SubtitleCue(t, t + len))
            t += len + 200L + r.nextInt(6000)
        }
        return cues
    }

    /** Other-language file: same speech but cues merged/split/trimmed differently, then moved. */
    private fun translate(ref: List<SubtitleCue>, seed: Long, scale: Double, offsetMs: Long): List<SubtitleCue> {
        val r = Random(seed)
        val out = ArrayList<SubtitleCue>()
        for (c in ref) {
            if (r.nextInt(12) == 0) continue // line not translated
            val a = c.startMs + r.nextInt(200) - 100
            val b = c.endMs + r.nextInt(300) - 150
            if (b > a) out.add(SubtitleCue((a / scale).toLong() - (offsetMs / scale).toLong(), (b / scale).toLong() - (offsetMs / scale).toLong()))
        }
        return out.sortedBy { it.startMs }
    }

    @Test fun findsConstantOffset() {
        val ref = reference()
        val tgt = translate(ref, 2, 1.0, 7_340L)   // target is 7.34 s early
        val res = ReferenceSubtitleAligner.align(ref, tgt)!!
        assertTrue(res.confident)
        assertEquals(1.0, res.scale, 1e-9)
        assertEquals(7_340.0, res.offsetMs.toDouble(), 150.0)
    }

    @Test fun findsNegativeOffset() {
        val ref = reference(3)
        val tgt = translate(ref, 4, 1.0, -12_500L)
        val res = ReferenceSubtitleAligner.align(ref, tgt)!!
        assertTrue(res.confident)
        assertEquals(-12_500.0, res.offsetMs.toDouble(), 150.0)
    }

    @Test fun findsFrameRateChange() {
        val ref = reference(5)
        // Target was timed for 25 fps, video plays at 23.976: target times must be stretched by 25/23.976.
        val tgt = translate(ref, 6, 25.0 / 23.976, 0L)
        val res = ReferenceSubtitleAligner.align(ref, tgt)!!
        assertTrue(res.confident)
        assertEquals(25.0 / 23.976, res.scale, 1e-9)
        assertEquals(0.0, res.offsetMs.toDouble(), 250.0)
    }

    @Test fun offsetPlusFrameRateChange() {
        val ref = reference(7)
        val tgt = translate(ref, 8, 23.976 / 25.0, 3_000L)
        val res = ReferenceSubtitleAligner.align(ref, tgt)!!
        assertTrue(res.confident)
        assertEquals(23.976 / 25.0, res.scale, 1e-9)
        assertEquals(3_000.0, res.offsetMs.toDouble(), 250.0)
    }

    @Test fun unrelatedSubtitlesAreNotConfident() {
        val res = ReferenceSubtitleAligner.align(reference(9), reference(10))!!
        assertFalse(res.confident)
    }

    @Test fun tooFewCuesIsRefused() {
        val ref = reference()
        assertNull(ReferenceSubtitleAligner.align(ref, ref.take(5)))
        assertNull(ReferenceSubtitleAligner.align(emptyList(), ref))
    }

    @Test fun alreadySyncedGivesZeroOffsetAndIdentityScale() {
        val ref = reference(11)
        val res = ReferenceSubtitleAligner.align(ref, translate(ref, 12, 1.0, 0L))!!
        assertNotNull(res)
        assertTrue(res.confident)
        assertEquals(1.0, res.scale, 1e-9)
        assertEquals(0.0, res.offsetMs.toDouble(), 100.0)
    }
}
