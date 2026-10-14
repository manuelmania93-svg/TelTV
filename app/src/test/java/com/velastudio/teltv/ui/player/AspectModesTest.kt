package com.velastudio.teltv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class AspectModesTest {
    private val wide = 16f / 9f
    private val fourThree = 4f / 3f

    @Test fun fourThreePictureHasThreeModes() {
        assertEquals(3, AspectModes.modeCount(fourThree))
        assertEquals(0, AspectModes.next(2, fourThree))
    }

    @Test fun widePictureCyclesThroughBakedBarModes() {
        assertEquals(5, AspectModes.modeCount(wide))
        var m = 0
        val seen = mutableListOf<Int>()
        repeat(5) { m = AspectModes.next(m, wide); seen.add(m) }
        assertEquals(listOf(1, 2, 3, 4, 0), seen)
    }

    @Test fun barsScaleTurns43IntoFullWidth() {
        assertEquals(4f / 3f, AspectModes.barsScale(wide) * 1f, 0.01f) // (16/9)/(4/3) = 1.333
        assertEquals(1f, AspectModes.barsScale(fourThree), 0.0001f)
        assertEquals(1f, AspectModes.scaleY(AspectModes.BARS_STRETCH, wide), 0f)
        assertEquals(AspectModes.barsScale(wide), AspectModes.scaleY(AspectModes.BARS_ZOOM, wide), 0f)
        assertEquals(1f, AspectModes.scaleX(AspectModes.FIT, wide), 0f)
    }

    @Test fun wideOnlyModeOn43BecomesFit() {
        assertEquals(0, AspectModes.sanitize(3, fourThree))
        assertEquals(3, AspectModes.sanitize(3, wide))
    }

    @Test fun repeatedSizeEventKeepsUserChoice() {
        assertEquals(3, AspectModes.modeAfterSizeChange(wide, wide, 3, 0))
        assertEquals(2, AspectModes.modeAfterSizeChange(fourThree, fourThree, 2, 0))
    }

    @Test fun newVideoUsesSavedOnlyFor43() {
        assertEquals(2, AspectModes.modeAfterSizeChange(wide, fourThree, 3, 2))
        assertEquals(0, AspectModes.modeAfterSizeChange(fourThree, wide, 2, 2))
        assertEquals(0, AspectModes.modeAfterSizeChange(0f, wide, 4, 2))
    }

    @Test fun classification() {
        assertTrue(AspectModes.is4x3(fourThree))
        assertFalse(AspectModes.is4x3(wide))
        assertTrue(AspectModes.isWide(wide))
        assertFalse(AspectModes.isWide(fourThree))
    }
}
