package com.velastudio.teltv.player

import kotlin.math.abs
import kotlin.math.roundToLong

/** Subtitle timing for one video: a shift in milliseconds and a speed scale (1.0 = untouched). */
data class SubtitleSync(val offsetMs: Long = 0L, val scale: Double = 1.0) {
    val isDefault: Boolean get() = offsetMs == 0L && abs(scale - 1.0) < 1e-9
}

/** Pure timing math for the subtitle sync controls. No Android types, so it is unit tested on the JVM. */
object SubtitleTiming {
    const val OFFSET_STEP_MS = 100L
    const val MAX_OFFSET_MS = 300_000L
    const val SCALE_STEP = 0.001
    const val MIN_SCALE = 0.5
    const val MAX_SCALE = 2.0

    /** Subtitle written for a 23.976 fps video, shown on a 25 fps video (video runs faster, so times shrink). */
    const val SCALE_23976_TO_25 = 23.976 / 25.0
    /** Subtitle written for a 25 fps video, shown on a 23.976 fps video. */
    const val SCALE_25_TO_23976 = 25.0 / 23.976

    fun clampOffset(offsetMs: Long): Long = offsetMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)

    fun clampScale(scale: Double): Double {
        if (scale.isNaN() || scale.isInfinite()) return 1.0
        // Round to 5 decimals so repeated +/- 0.001 steps do not accumulate floating point noise.
        val rounded = (scale * 100_000.0).roundToLong() / 100_000.0
        return rounded.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun stepOffset(currentMs: Long, steps: Int): Long =
        clampOffset(currentMs + steps * OFFSET_STEP_MS)

    fun stepScale(current: Double, steps: Int): Double =
        clampScale(current + steps * SCALE_STEP)

    /**
     * Time in the subtitle file -> time on screen.
     *
     * [originUs] is where the subtitle's own zero sits on the player clock. Media3's renderer clock
     * starts at 1,000,000,000,000 us, so scaling the raw player time would move every cue by
     * (scale - 1) * 1e12 us. Only the time since the origin may be scaled.
     */
    fun mapUs(sourceUs: Long, scale: Double, offsetUs: Long, originUs: Long = 0L): Long =
        originUs + ((sourceUs - originUs) * scale).roundToLong() + offsetUs

    /** Time on screen -> time in the subtitle file. */
    fun unmapUs(playerUs: Long, scale: Double, offsetUs: Long, originUs: Long = 0L): Long =
        originUs + ((playerUs - offsetUs - originUs) / scale).roundToLong()

    /**
     * Position to seek to so the player re-decodes subtitles after a sync change. Media3 ignores a
     * seek that lands in the same millisecond as the current position, so nudge by 1 ms.
     */
    fun refreshSeekPositionMs(currentMs: Long, durationMs: Long): Long {
        val next = currentMs + 1
        return if (durationMs > 0 && next > durationMs) (currentMs - 1).coerceAtLeast(0L) else next
    }

    /** User-selected default for newly downloaded subtitles, not a detected frame-rate mismatch.
     * A stored identity correction is an explicit Reset exception and must win too.
     * Corrupt existing entries fail safe to identity rather than changing the video's timing.
     */
    fun forDownloadedSubtitle(saved: String?): SubtitleSync =
        if (saved == null) SubtitleSync(scale = SCALE_23976_TO_25) else decode(saved)

    fun encode(sync: SubtitleSync): String = "${sync.offsetMs};${sync.scale}"

    /** Anything unreadable falls back to "no correction" rather than breaking playback. */
    fun decode(raw: String?): SubtitleSync {
        if (raw.isNullOrBlank()) return SubtitleSync()
        val parts = raw.split(';')
        if (parts.size != 2) return SubtitleSync()
        val offset = parts[0].toLongOrNull() ?: return SubtitleSync()
        val scale = parts[1].toDoubleOrNull() ?: return SubtitleSync()
        return SubtitleSync(clampOffset(offset), clampScale(scale))
    }

    fun formatOffset(offsetMs: Long): String =
        "${if (offsetMs >= 0) "+" else "-"}${"%.1f".format(java.util.Locale.US, abs(offsetMs) / 1000.0)}s"

    fun formatScale(scale: Double): String = "x" + "%.4f".format(java.util.Locale.US, scale)
}
