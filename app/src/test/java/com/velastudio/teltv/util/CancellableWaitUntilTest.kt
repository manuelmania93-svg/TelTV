package com.velastudio.teltv.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class CancellableWaitUntilTest {
    @Test fun returnsTrueWhenConditionBecomesTrue() {
        val ready = AtomicBoolean(false)
        Thread { Thread.sleep(80); ready.set(true) }.start()
        assertTrue(CancellableWait.waitUntil(5_000, 20) { ready.get() })
    }

    @Test fun returnsTrueImmediatelyWhenAlreadyTrue() {
        assertTrue(CancellableWait.waitUntil(0, 20) { true })
    }

    @Test fun timesOutWhenConditionNeverTrue() {
        val t0 = System.nanoTime()
        assertFalse(CancellableWait.waitUntil(150, 20) { false })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun cancellationEndsWaitQuickly() {
        val cancel = AtomicBoolean(false)
        Thread { Thread.sleep(100); cancel.set(true) }.start()
        val t0 = System.nanoTime()
        assertFalse(CancellableWait.waitUntil(10_000, 20, { cancel.get() }) { false })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
}
