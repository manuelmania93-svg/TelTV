package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MediaTitleCleanerTest {

    @Test
    fun cleanKeepsEpisodeAfterQualityTag() {
        val cleaned = MediaTitleCleaner.clean("Show.Name.1080p.S01E02.WEBRip.x264.mkv")
        assertEquals("Show Name - S01E02", cleaned)
    }

    @Test
    fun parseSignatureSurvivesSceneStyleQualityPrefix() {
        val sig = HybridEpisodeMatcher.parseSignature("Naruto.1080p.S02E05.WEB-DL.mkv")
        assertNotNull(sig)
        assertEquals("naruto", sig!!.stem)
        assertEquals(2, sig.season)
        assertEquals(5, sig.episode)
    }

    @Test
    fun parseSignatureKeepsWordEpisodeAfterTag() {
        val sig = HybridEpisodeMatcher.parseSignature("Naruto Shippuden 1080p Episode 50")
        assertNotNull(sig)
        assertEquals("narutoshippuden", sig!!.stem)
        assertEquals(50, sig.episode)
    }
}
