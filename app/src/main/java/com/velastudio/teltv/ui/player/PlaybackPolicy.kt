package com.velastudio.teltv.ui.player

/** Pure decisions shared by the player UI and regression tests. */
internal object PlaybackPolicy {
    fun shouldOfferNext(duration: Long, position: Long, playing: Boolean, foreground: Boolean,
                        enabled: Boolean, hasNext: Boolean, alreadyHandled: Boolean): Boolean =
        duration > 60_000L && position > 0L && duration - position in 1..10_000L &&
            playing && foreground && enabled && hasNext && !alreadyHandled

    // Never hand another app TDLib IDs, app-private file:// paths or custom stream schemes.
    fun canOpenExternally(uri: String?): Boolean =
        uri?.substringBefore(':')?.lowercase() in setOf("http", "https", "content")
}

/** Media3 advances audio, video and subtitle cues on the same media clock. */
internal object PlaybackSpeedPolicy {
    const val DEFAULT = 1f
    val options = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

    fun parameters(speed: Float) = androidx.media3.common.PlaybackParameters(
        speed.takeIf { it in options } ?: DEFAULT,
        1f // Keep voice pitch unchanged, independent of playback rate.
    )

    fun label(speed: Float): String = java.math.BigDecimal(speed.toString()).stripTrailingZeros().toPlainString() + "x"
}
