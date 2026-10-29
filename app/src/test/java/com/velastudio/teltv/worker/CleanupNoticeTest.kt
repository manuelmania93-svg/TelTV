package com.velastudio.teltv.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupNoticeTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun formatsGigabytesWithOneDecimal() {
        assertEquals(
            "Last cleanup freed 1.5 GB (2 h ago)",
            CleanupNotice.format(gb + gb / 2, 0L, 2 * 3_600_000L)
        )
    }

    @Test
    fun formatsMegabytesBelowOneGigabyte() {
        assertEquals(
            "Last cleanup freed 250 MB (30 min ago)",
            CleanupNotice.format(250L * 1024 * 1024, 0L, 30 * 60_000L)
        )
    }

    @Test
    fun freshCleanupReadsJustNow() {
        assertEquals(
            "Last cleanup freed 2.0 GB (just now)",
            CleanupNotice.format(2 * gb, 0L, 30_000L)
        )
    }

    @Test
    fun switchesToDaysAfterTwentyFourHours() {
        assertEquals(
            "Last cleanup freed 1.0 GB (3 d ago)",
            CleanupNotice.format(gb, 0L, 3 * 24 * 3_600_000L)
        )
    }
}
