package com.velastudio.teltv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun countdownRequiresActualForegroundPlayback() {
        assertTrue(PlaybackPolicy.shouldOfferNext(120000, 112000, true, true, true, true, false))
        assertFalse(PlaybackPolicy.shouldOfferNext(120000, 112000, false, true, true, true, false))
        assertFalse(PlaybackPolicy.shouldOfferNext(120000, 112000, true, false, true, true, false))
        assertFalse(PlaybackPolicy.shouldOfferNext(120000, 112000, true, true, true, true, true))
        assertFalse(PlaybackPolicy.shouldOfferNext(120000, 112000, true, true, false, true, false))
        assertFalse(PlaybackPolicy.shouldOfferNext(120000, 80000, true, true, true, true, false))
    }
    @Test fun privateStreamsNeverLeaveTheApp() {
        assertFalse(PlaybackPolicy.canOpenExternally("tdlib://file/42"))
        assertFalse(PlaybackPolicy.canOpenExternally("file:///data/user/0/secret.mp4"))
        assertFalse(PlaybackPolicy.canOpenExternally(null))
        assertTrue(PlaybackPolicy.canOpenExternally("https://example.com/video.mp4"))
        assertTrue(PlaybackPolicy.canOpenExternally("content://media/external/video/42"))
    }
}
