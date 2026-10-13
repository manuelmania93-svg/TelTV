package com.velastudio.teltv.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.Subtitle
import org.junit.Assert.*
import org.junit.Test

class SubtitleTimingTest {
    @Test fun offsetMovesInHundredMillisecondSteps() {
        assertEquals(100L, SubtitleTiming.stepOffset(0L, 1))
        assertEquals(-100L, SubtitleTiming.stepOffset(0L, -1))
        assertEquals(1_200L, SubtitleTiming.stepOffset(1_100L, 1))
        assertEquals(0L, SubtitleTiming.stepOffset(100L, -1))
    }

    @Test fun offsetIsClamped() {
        assertEquals(SubtitleTiming.MAX_OFFSET_MS, SubtitleTiming.stepOffset(SubtitleTiming.MAX_OFFSET_MS, 1))
        assertEquals(-SubtitleTiming.MAX_OFFSET_MS, SubtitleTiming.clampOffset(Long.MIN_VALUE / 2))
    }

    @Test fun scaleStepsDoNotAccumulateNoise() {
        var s = 1.0
        repeat(10) { s = SubtitleTiming.stepScale(s, 1) }
        assertEquals(1.01, s, 1e-12)
        repeat(10) { s = SubtitleTiming.stepScale(s, -1) }
        assertEquals(1.0, s, 1e-12)
    }

    @Test fun scaleIsClampedAndBadValuesFallBack() {
        assertEquals(SubtitleTiming.MAX_SCALE, SubtitleTiming.clampScale(50.0), 0.0)
        assertEquals(SubtitleTiming.MIN_SCALE, SubtitleTiming.clampScale(0.0), 0.0)
        assertEquals(1.0, SubtitleTiming.clampScale(Double.NaN), 0.0)
        assertEquals(1.0, SubtitleTiming.clampScale(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test fun frameRatePresetsAreTheExpectedFactors() {
        assertEquals(0.959, SubtitleTiming.SCALE_23976_TO_25, 0.0001)
        assertEquals(1.0427, SubtitleTiming.SCALE_25_TO_23976, 0.0001)
        assertEquals(1.0, SubtitleTiming.SCALE_23976_TO_25 * SubtitleTiming.SCALE_25_TO_23976, 1e-12)
    }

    @Test fun mappingAppliesScaleThenOffset() {
        // Subtitle line at 1h00m00s in a 23.976 fps file, shown on 25 fps video, 2 s later.
        val oneHourUs = 3_600_000_000L
        val mapped = SubtitleTiming.mapUs(oneHourUs, SubtitleTiming.SCALE_23976_TO_25, 2_000_000L)
        assertEquals((3_452_544_000L + 2_000_000L).toDouble(), mapped.toDouble(), 5.0)
        assertEquals(1_234_567L, SubtitleTiming.mapUs(1_234_567L, 1.0, 0L))
    }

    @Test fun unmapIsTheInverseOfMap() {
        for (scale in listOf(1.0, 0.95904, 1.04271, 0.999)) {
            for (t in listOf(0L, 1L, 999_999L, 5_400_000_000L)) {
                val back = SubtitleTiming.unmapUs(SubtitleTiming.mapUs(t, scale, -750_000L), scale, -750_000L)
                assertEquals(t.toDouble(), back.toDouble(), 2.0)
            }
        }
    }

    @Test fun savedStateRoundTrips() {
        val sync = SubtitleSync(-1_300L, 0.95904)
        assertEquals(sync, SubtitleTiming.decode(SubtitleTiming.encode(sync)))
    }

    @Test fun unreadableSavedStateMeansNoCorrection() {
        val none = SubtitleSync()
        assertEquals(none, SubtitleTiming.decode(null))
        assertEquals(none, SubtitleTiming.decode(""))
        assertEquals(none, SubtitleTiming.decode("garbage"))
        assertEquals(none, SubtitleTiming.decode("1;2;3"))
        assertEquals(none, SubtitleTiming.decode("abc;1.0"))
        assertEquals(none, SubtitleTiming.decode("100;xyz"))
    }

    @Test fun defaultStateIsNotWorthSaving() {
        assertTrue(SubtitleSync().isDefault)
        assertFalse(SubtitleSync(100L, 1.0).isDefault)
        assertFalse(SubtitleSync(0L, 1.001).isDefault)
    }

    @Test fun formatting() {
        assertEquals("+0.0s", SubtitleTiming.formatOffset(0L))
        assertEquals("+1.2s", SubtitleTiming.formatOffset(1_200L))
        assertEquals("-0.1s", SubtitleTiming.formatOffset(-100L))
        assertEquals("x0.9590", SubtitleTiming.formatScale(0.959))
    }

    private class FakeSubtitle(private val times: LongArray) : Subtitle {
        override fun getNextEventTimeIndex(timeUs: Long): Int = times.indexOfFirst { it > timeUs }.let { if (it < 0) -1 else it }
        override fun getEventTimeCount(): Int = times.size
        override fun getEventTime(index: Int): Long = times[index]
        override fun getCues(timeUs: Long): List<Cue> = emptyList()
    }

    @Test fun offsetSubtitleShiftsAndScalesEventTimes() {
        val src = FakeSubtitle(longArrayOf(1_000_000L, 2_000_000L, 4_000_000L))
        val sub = OffsetSubtitle(src, 500_000L, 2.0)
        assertEquals(3, sub.eventTimeCount)
        assertEquals(2_500_000L, sub.getEventTime(0))
        assertEquals(4_500_000L, sub.getEventTime(1))
        assertEquals(8_500_000L, sub.getEventTime(2))
        // On-screen 3.0 s is 1.25 s in the file, so the next source event is the one at 2 s (index 1).
        assertEquals(1, sub.getNextEventTimeIndex(3_000_000L))
    }

    @Test fun offsetSubtitleWithoutScaleMatchesOldBehaviour() {
        val src = FakeSubtitle(longArrayOf(1_000_000L, 3_000_000L))
        val sub = OffsetSubtitle(src, -250_000L)
        assertEquals(750_000L, sub.getEventTime(0))
        assertEquals(2_750_000L, sub.getEventTime(1))
        assertEquals(0, sub.getNextEventTimeIndex(0L))
    }
}
