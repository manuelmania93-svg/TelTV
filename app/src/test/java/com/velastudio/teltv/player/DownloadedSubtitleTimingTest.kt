package com.velastudio.teltv.player

import org.junit.Assert.*
import org.junit.Test

class DownloadedSubtitleTimingTest {
    @Test fun absentSettingUsesRequestedPreset() {
        val sync = SubtitleTiming.forDownloadedSubtitle(null)
        assertEquals(0L, sync.offsetMs)
        assertEquals(23.976 / 25, sync.scale, 1e-12)
    }
    @Test fun storedResetWinsOverDefault() {
        assertEquals(SubtitleSync(), SubtitleTiming.forDownloadedSubtitle(SubtitleTiming.encode(SubtitleSync())))
    }
    @Test fun savedOffsetAndReversePresetWin() {
        val saved = SubtitleSync(-1700, 1.04271)
        assertEquals(saved, SubtitleTiming.forDownloadedSubtitle(SubtitleTiming.encode(saved)))
    }
    @Test fun brokenSavedEntryFailsSafe() {
        for (raw in listOf("", "broken", "1;NaN", "1;Infinity")) {
            assertEquals(1.0, SubtitleTiming.forDownloadedSubtitle(raw).scale, 0.0)
        }
    }
    @Test fun defaultIsNotMultipliedOnReload() {
        val initial = SubtitleTiming.forDownloadedSubtitle(null)
        val reload = SubtitleTiming.forDownloadedSubtitle(SubtitleTiming.encode(initial))
        assertEquals(initial.scale, reload.scale, 0.00001)
    }
    @Test fun embeddedSubtitleIdentityRemainsUnchanged() {
        assertEquals(1.0, SubtitleSync().scale, 0.0)
        assertEquals(1.0, SubtitleTiming.decode(null).scale, 0.0)
    }
}
