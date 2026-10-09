package com.velastudio.teltv.sync

import org.junit.Assert.*
import org.junit.Test

class AutoSyncPolicyTest {
    private val t = AutoSyncTarget("tdlib://file/1", "/sub/a.srt", AudioTrackRef(0, "ger"))
    private fun result(offset: Long) = AlignmentResult(offset, true, 4, 4, false, emptyList())

    @Test fun alreadyInSyncClearsEarlierManualOffset() {
        val d = AutoSyncPolicy.decide(AutoSyncOutcome.AlreadyInSync(result(40)), 1500L, t, t)
        assertEquals(0L, d.newOffsetMs)
        assertTrue(d.message.contains("reset"))
    }

    @Test fun alreadyInSyncWithNoOffsetChangesNothing() {
        val d = AutoSyncPolicy.decide(AutoSyncOutcome.AlreadyInSync(result(40)), 0L, t, t)
        assertNull(d.newOffsetMs)
    }

    @Test fun syncedAppliesAbsoluteOffset() {
        val d = AutoSyncPolicy.decide(AutoSyncOutcome.Synced(-2300L, result(-2300)), 500L, t, t)
        assertEquals(-2300L, d.newOffsetMs)
        assertTrue(d.message.startsWith("Synced: -2.30s"))
    }

    @Test fun staleResultIsDroppedWhenAnythingChanged() {
        val synced = AutoSyncOutcome.Synced(1000L, result(1000))
        listOf(
            t.copy(mediaUri = "tdlib://file/2"),
            t.copy(subtitlePath = "/sub/b.srt"),
            t.copy(audio = AudioTrackRef(1, "eng"))
        ).forEach { now ->
            val d = AutoSyncPolicy.decide(synced, 0L, t, now)
            assertNull(d.newOffsetMs)
            assertTrue(d.message.contains("discarded"))
        }
    }

    @Test fun failuresNeverChangeOffset() {
        listOf(
            AutoSyncOutcome.Failed("x"),
            AutoSyncOutcome.NotConfident(result(1)),
            AutoSyncOutcome.Drifting(result(1))
        ).forEach { assertNull(AutoSyncPolicy.decide(it, 900L, t, t).newOffsetMs) }
    }
}
