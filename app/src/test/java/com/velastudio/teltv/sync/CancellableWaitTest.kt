package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class CancellableWaitTest {
    @Test fun returnsTrueWhenLatchOpens() {
        val l = CountDownLatch(1)
        Thread { Thread.sleep(50); l.countDown() }.start()
        assertTrue(CancellableWait.await(l, 5_000, 20))
    }

    @Test fun cancellationEndsLongWaitQuickly() {
        val l = CountDownLatch(1)
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        Thread { Thread.sleep(100); cancel.set(true) }.start()
        val t0 = System.nanoTime()
        assertFalse(CancellableWait.await(l, 10_000, 20) { cancel.get() })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }

    @Test fun timeoutReturnsFalse() {
        assertFalse(CancellableWait.await(CountDownLatch(1), 80, 20))
    }

    @Test fun interruptCountsAsCancellationAndIsRestored() {
        val l = CountDownLatch(1)
        var result = true
        var flag = false
        val th = Thread { result = CancellableWait.await(l, 10_000, 20); flag = Thread.currentThread().isInterrupted }
        th.start(); Thread.sleep(80); th.interrupt(); th.join(2_000)
        assertFalse(result)
        assertTrue(flag)
    }

    @Test fun sleepStopsOnCancel() {
        assertFalse(CancellableWait.sleep(10_000, 20) { true })
        assertTrue(CancellableWait.sleep(40, 20))
    }
}
