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
