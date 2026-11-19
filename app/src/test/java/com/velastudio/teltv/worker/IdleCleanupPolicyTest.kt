package com.velastudio.teltv.worker

import org.junit.Assert.*
import org.junit.Test

class IdleCleanupPolicyTest {
    @Test fun fourHourDefaultAndValidatedChoices() {
        assertEquals(4L, IdleCleanupPolicy.interval(-1))
        assertEquals(14_400L, IdleCleanupPolicy.retention(-1))
        IdleCleanupPolicy.intervals.forEach { assertEquals(it, IdleCleanupPolicy.interval(it)) }
        IdleCleanupPolicy.retentions.forEach { assertEquals(it, IdleCleanupPolicy.retention(it)) }
    }
    @Test fun dueRespectsIntervalAndDoesNotLoopOnClockGoingBackwards() {
        assertTrue(IdleCleanupPolicy.due(1, null, 4))
        assertFalse(IdleCleanupPolicy.due(14_399_999, 0, 4))
        assertTrue(IdleCleanupPolicy.due(14_400_000, 0, 4))
        assertFalse(IdleCleanupPolicy.due(0, 5_000, 4))
    }
}
