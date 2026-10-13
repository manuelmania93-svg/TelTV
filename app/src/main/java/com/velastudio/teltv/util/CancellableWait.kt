package com.velastudio.teltv.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Blocking waits that notice cancellation within one slice instead of after the full timeout. */
object CancellableWait {
    /**
     * @return true when the latch opened, false on timeout or cancellation. An interrupt is
     * restored on the thread and counts as cancellation.
     */
    fun await(latch: CountDownLatch, timeoutMs: Long, sliceMs: Long = 200L, isCancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                if (latch.count == 0L) return true
                if (isCancelled()) return false
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return latch.count == 0L
                if (latch.await(minOf(sliceMs, left), TimeUnit.MILLISECONDS)) return true
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
    }

    /** Sleeps up to [ms]; returns false if cancelled or interrupted before the time was up. */
    fun sleep(ms: Long, sliceMs: Long = 200L, isCancelled: () -> Boolean = { false }): Boolean {
        await(CountDownLatch(1), ms, sliceMs, isCancelled)
        return !isCancelled() && !Thread.currentThread().isInterrupted
    }

    /**
     * Polls [condition] every [sliceMs] until it is true, [timeoutMs] passes, or [isCancelled].
     * @return true only when the condition became true.
     */
    fun waitUntil(
        timeoutMs: Long,
        sliceMs: Long = 100L,
        isCancelled: () -> Boolean = { false },
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            if (condition()) return true
            if (isCancelled() || Thread.currentThread().isInterrupted) return false
            val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (left <= 0) return false
            if (!sleep(minOf(sliceMs, left), sliceMs, isCancelled)) return false
        }
    }
}
