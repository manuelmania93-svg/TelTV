package com.velastudio.teltv.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.Random

class ReferenceSyncPolicyTest {
    private fun srt(cues: List<Pair<Long, Long>>): String = buildString {
        cues.forEachIndexed { i, (a, b) ->
            append(i + 1).append('\n').append(ts(a)).append(" --> ").append(ts(b)).append("\nline\n\n")
        }
    }

    private fun ts(ms: Long): String =
        String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    private fun cues(seed: Long): List<Pair<Long, Long>> {
        val r = Random(seed)
        val out = ArrayList<Pair<Long, Long>>()
        var t = 5_000L
        while (t < 20 * 60_000L) { val len = 900L + r.nextInt(3000); out.add(t to t + len); t += len + 300 + r.nextInt(5000) }
        return out
    }

    @Test fun shiftedFileGetsAnOffset() {
        val ref = cues(1)
        val late = ref.map { (it.first + 4_000) to (it.second + 4_000) } // file shows 4 s late
        val d = ReferenceSyncPolicy.decide(srt(ref), srt(late))
        assertNotNull(d.sync)
        assertEquals(-4_000.0, d.sync!!.offsetMs.toDouble(), 100.0)
        assertEquals(1.0, d.sync!!.scale, 1e-9)
    }

    @Test fun unrelatedFilesChangeNothing() {
        assertNull(ReferenceSyncPolicy.decide(srt(cues(1)), srt(cues(2))).sync)
    }

    @Test fun emptyOrTinyFilesChangeNothing() {
        assertNull(ReferenceSyncPolicy.decide("", srt(cues(1))).sync)
        assertNull(ReferenceSyncPolicy.decide(srt(cues(1)), srt(cues(1).take(3))).sync)
    }
}
