package com.velastudio.teltv.ui.player

/**
 * Aspect modes of the player button. 0 = fit, 1 = zoom (crop), 2 = fill (stretch to the view).
 * For wide (about 16:9) pictures two extra modes handle a 4:3 picture with black bars baked
 * into the video frame: fit/zoom/fill cannot remove those bars because they are part of the image.
 *  3 = stretch the picture sideways so the 4:3 part fills the width (slightly wide, nothing cropped)
 *  4 = zoom the picture evenly so the 4:3 part fills the width (top and bottom are cropped)
 */
object AspectModes {
    const val FIT = 0
    const val ZOOM = 1
    const val FILL = 2
    const val BARS_STRETCH = 3
    const val BARS_ZOOM = 4

    private const val FOUR_THREE = 4f / 3f
    /** A picture this wide (or wider) can have a 4:3 picture inside it. */
    const val WIDE_THRESHOLD = 1.7f

    fun isWide(displayAspect: Float): Boolean = displayAspect >= WIDE_THRESHOLD

    fun modeCount(displayAspect: Float): Int = if (isWide(displayAspect)) 5 else 3

    fun next(current: Int, displayAspect: Float): Int = (current + 1) % modeCount(displayAspect)

    /** Mode index that is valid for this picture (a wide-only mode on a 4:3 picture becomes fit). */
    fun sanitize(mode: Int, displayAspect: Float): Int = if (mode in 0 until modeCount(displayAspect)) mode else FIT

    /** Factor that turns the 4:3 part of a baked-bars frame into the full width. */
    fun barsScale(displayAspect: Float): Float = (displayAspect / FOUR_THREE).coerceAtLeast(1f)

    fun scaleX(mode: Int, displayAspect: Float): Float = when (mode) {
        BARS_STRETCH, BARS_ZOOM -> barsScale(displayAspect)
        else -> 1f
    }

    fun scaleY(mode: Int, displayAspect: Float): Float = when (mode) {
        BARS_ZOOM -> barsScale(displayAspect)
        else -> 1f
    }

    fun label(mode: Int): String = when (mode) {
        ZOOM -> "Aspect: Zoom to Fill (Crop)"
        FILL -> "Aspect: Stretch"
        BARS_STRETCH -> "Aspect: Stretch 4:3 picture (baked-in bars)"
        BARS_ZOOM -> "Aspect: Zoom 4:3 picture (baked-in bars, crops top/bottom)"
        else -> "Aspect: Fit (Original)"
    }

    fun is4x3(aspect: Float): Boolean = aspect in 1.1f..1.55f

    /**
     * Mode to use after a video size event. A repeated event with the same picture shape keeps
     * the user's choice (HYPOTHESIS: the old code reset it on every size event); a new shape
     * uses the saved 4:3 choice for 4:3 pictures and fit otherwise.
     */
    fun modeAfterSizeChange(previousAspect: Float, newAspect: Float, current: Int, saved4x3: Int): Int {
        val same = previousAspect > 0f && kotlin.math.abs(previousAspect - newAspect) < 0.02f
        return if (same) sanitize(current, newAspect)
        else if (is4x3(newAspect)) sanitize(saved4x3, newAspect)
        else FIT
    }
}
