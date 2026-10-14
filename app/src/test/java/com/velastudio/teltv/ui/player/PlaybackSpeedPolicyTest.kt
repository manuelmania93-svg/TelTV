package com.velastudio.teltv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlaybackSpeedPolicyTest {
    @Test fun exactRequestedOptionsAndNormalDefault() {
        assertEquals(listOf(1f, 1.25f, 1.5f, 2f, 3f, 4f), PlaybackSpeedPolicy.options)
        assertEquals(1f, PlaybackSpeedPolicy.DEFAULT, 0f)
    }
    @Test fun everyRatePreservesPitch() {
        PlaybackSpeedPolicy.options.forEach { speed ->
            val params = PlaybackSpeedPolicy.parameters(speed)
            assertEquals(speed, params.speed, 0f)
            assertEquals(1f, params.pitch, 0f)
        }
    }
    @Test fun invalidRatesFallBackToNormal() {
        listOf(0f, -1f, 9f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(1f, PlaybackSpeedPolicy.parameters(it).speed, 0f)
        }
    }
    @Test fun labelsDoNotConfusePlaybackWithSubtitleScale() {
        assertEquals(listOf("1x", "1.25x", "1.5x", "2x", "3x", "4x"),
            PlaybackSpeedPolicy.options.map(PlaybackSpeedPolicy::label))
    }
}
