package com.velastudio.teltv.player

/** Shared skip choices and boundary arithmetic for the remote and on-screen controls. */
object SeekSteps {
    const val DEFAULT_MS = 10_000L
    val choicesMs: List<Long> = listOf(5_000L, 10_000L, 30_000L, 60_000L, 120_000L, 300_000L)

    fun normalize(ms: Long): Long = if (ms in choicesMs) ms else DEFAULT_MS

    fun label(ms: Long): String = when (normalize(ms)) {
        5_000L -> "5 seconds"
        10_000L -> "10 seconds"
        30_000L -> "30 seconds"
        60_000L -> "1 minute"
        120_000L -> "2 minutes"
        else -> "5 minutes"
    }

    fun badge(ms: Long): String = when (ms) {
        60_000L -> "1 min"
        120_000L -> "2 min"
        300_000L -> "5 min"
        else -> "${ms / 1_000}s"
    }

    /** Preserve short-step acceleration, but never shrink the selected minute step. */
    fun effectiveStepMs(selectedMs: Long, rapid: Boolean): Long =
        if (rapid) maxOf(30_000L, normalize(selectedMs)) else normalize(selectedMs)

    /** Unknown duration must not turn a forward jump into a jump back to zero. */
    fun targetMs(positionMs: Long, durationMs: Long, forward: Boolean, stepMs: Long): Long {
        val start = positionMs.coerceAtLeast(0L)
        val step = stepMs.coerceAtLeast(0L)
        val target = if (forward) {
            if (start > Long.MAX_VALUE - step) Long.MAX_VALUE else start + step
        } else {
            (start - step).coerceAtLeast(0L)
        }
        return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
    }
}
