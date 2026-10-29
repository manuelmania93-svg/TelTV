package com.velastudio.teltv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageGuardPolicyTest {

    private val soft = StorageGuardPolicy.SOFT_FREE_BYTES
    private val critical = StorageGuardPolicy.CRITICAL_FREE_BYTES
    private val evictGap = StorageGuardPolicy.MIN_EVICT_INTERVAL_MS
    private val emergencyGap = StorageGuardPolicy.MIN_EMERGENCY_INTERVAL_MS

    @Test
    fun plentyOfSpaceMeansNoAction() {
        assertEquals(
            StorageGuardPolicy.Action.NONE,
            StorageGuardPolicy.decideAction(soft, 0L, 0L, 1_000_000L)
        )
        assertEquals(
            StorageGuardPolicy.Action.NONE,
            StorageGuardPolicy.decideAction(soft + 1, 0L, 0L, 1_000_000L)
        )
    }

    @Test
    fun lowSpaceTriggersEviction() {
        assertEquals(
            StorageGuardPolicy.Action.EVICT_OLD_FILES,
            StorageGuardPolicy.decideAction(soft - 1, 0L, 0L, 1_000_000L)
        )
    }

    @Test
    fun criticalSpaceTriggersEmergency() {
        assertEquals(
            StorageGuardPolicy.Action.EMERGENCY_CLEAR_AND_RESUME,
            StorageGuardPolicy.decideAction(critical - 1, 0L, 0L, 1_000_000L)
        )
    }

    @Test
    fun evictionIsRateLimited() {
        val now = 1_000_000L
        // An eviction ran one minute ago: below soft, but too soon to run again.
        assertEquals(
            StorageGuardPolicy.Action.NONE,
            StorageGuardPolicy.decideAction(soft - 1, now - 60_000L, 0L, now)
        )
        // Two minutes later it fires again.
        assertEquals(
            StorageGuardPolicy.Action.EVICT_OLD_FILES,
            StorageGuardPolicy.decideAction(soft - 1, now - evictGap, 0L, now)
        )
    }

    @Test
    fun emergencyIsRateLimited() {
        val now = 10_000_000L
        // Emergency ran four minutes ago: still critical, but too soon to repeat.
        assertEquals(
            StorageGuardPolicy.Action.EVICT_OLD_FILES,
            StorageGuardPolicy.decideAction(critical - 1, 0L, now - emergencyGap + 60_000L, now)
        )
        // Once the cooldown passed, emergency fires again.
        assertEquals(
            StorageGuardPolicy.Action.EMERGENCY_CLEAR_AND_RESUME,
            StorageGuardPolicy.decideAction(critical - 1, 0L, now - emergencyGap, now)
        )
    }

    @Test
    fun emergencyInsideEvictCooldownStillFires() {
        val now = 10_000_000L
        // A fresh eviction just ran but space is still critical: escalate without waiting
        // out the eviction cooldown.
        assertEquals(
            StorageGuardPolicy.Action.EMERGENCY_CLEAR_AND_RESUME,
            StorageGuardPolicy.decideAction(critical - 1, now - 1_000L, 0L, now)
        )
    }

    @Test
    fun thresholdOrderingIsSane() {
        // The critical line must sit strictly below the soft line, otherwise the emergency
        // branch is unreachable.
        assertEquals(true, critical < soft)
    }

    @Test
    fun preflightSkipsWhenFreeSpaceIsHealthy() {
        assertEquals(
            false,
            StorageGuardPolicy.shouldPreflightEvict(
                StorageGuardPolicy.PREFLIGHT_FREE_BYTES, 0L, 1_000_000L
            )
        )
    }

    @Test
    fun preflightEvictsBelowFloorAfterCooldown() {
        assertEquals(
            true,
            StorageGuardPolicy.shouldPreflightEvict(
                StorageGuardPolicy.PREFLIGHT_FREE_BYTES - 1, 0L,
                StorageGuardPolicy.MIN_PREFLIGHT_INTERVAL_MS + 1
            )
        )
    }

    @Test
    fun preflightIsRateLimited() {
        assertEquals(
            false,
            StorageGuardPolicy.shouldPreflightEvict(
                StorageGuardPolicy.PREFLIGHT_FREE_BYTES - 1, 0L,
                StorageGuardPolicy.MIN_PREFLIGHT_INTERVAL_MS - 1
            )
        )
    }

    @Test
    fun playbackErrorBelowFloorTriggersImmediateEmergency() {
        assertEquals(
            true,
            StorageGuardPolicy.shouldEmergencyOnPlaybackError(
                StorageGuardPolicy.ERROR_TRIGGER_FREE_BYTES - 1, 0L,
                StorageGuardPolicy.MIN_EMERGENCY_INTERVAL_MS + 1
            )
        )
    }

    @Test
    fun playbackErrorWithFreeSpaceIsNotTreatedAsStorageFull() {
        // A network stall looks identical to the player; without a nearly-full disk it is not
        // a storage emergency and must not wipe the cache.
        assertEquals(
            false,
            StorageGuardPolicy.shouldEmergencyOnPlaybackError(
                StorageGuardPolicy.ERROR_TRIGGER_FREE_BYTES, 0L, 1_000_000L
            )
        )
    }

    @Test
    fun playbackErrorEmergencySharesTheRateLimit() {
        assertEquals(
            false,
            StorageGuardPolicy.shouldEmergencyOnPlaybackError(
                StorageGuardPolicy.ERROR_TRIGGER_FREE_BYTES - 1, 0L,
                StorageGuardPolicy.MIN_EMERGENCY_INTERVAL_MS - 1
            )
        )
    }
}
