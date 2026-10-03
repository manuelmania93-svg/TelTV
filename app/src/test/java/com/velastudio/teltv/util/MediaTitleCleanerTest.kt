package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun parseSignatureDoesNotTreatMovieOrEventYearAsEpisode() {
        assertNull(HybridEpisodeMatcher.parseSignature("The.Matrix.1999.1080p.mkv"))
        assertNull(HybridEpisodeMatcher.parseSignature("WWE 2000"))
    }

    @Test
    fun parseSignatureKeepsExplicitAnimeEpisodeNumber() {
        val sig = HybridEpisodeMatcher.parseSignature("One Piece - 1050")
        assertNotNull(sig)
        assertEquals(1050, sig!!.episode)
    }

    @Test
    fun subtitleIdentityDetectsSeasonEpisodeFromSceneFilename() {
        val identity = OnlineSubtitleProvider.identifyMedia("The.Office.US.S02E03.1080p.WEB-DL.mkv")
        assertEquals("The Office US", identity.title)
        assertEquals(true, identity.isSeries)
        assertEquals(2, identity.season)
        assertEquals(3, identity.episode)
    }

    @Test
    fun subtitleIdentityDetectsAnimeEpisodeNumber() {
        val identity = OnlineSubtitleProvider.identifyMedia("One Piece - 1050 [1080p].mkv")
        assertEquals("One Piece", identity.title)
        assertEquals(true, identity.isSeries)
        assertNull(identity.season)
        assertEquals(1050, identity.episode)
    }

    @Test
    fun subtitleIdentityRecognizesMovieYear() {
        val identity = OnlineSubtitleProvider.identifyMedia("The.Matrix.1999.1080p.mkv")
        assertEquals("The Matrix", identity.title)
        assertEquals(false, identity.isSeries)
        assertEquals(1999, identity.year)
    }

    @Test
    fun subtitleResultsPrioritizeAndroidPreferredLanguage() {
        val english = OnlineSubtitle("1", "eng", "English", "https://example.test/en.srt", "en.srt")
        val spanish = OnlineSubtitle("2", "spa", "Spanish", "https://example.test/es.srt", "es.srt")

        val ordered = OnlineSubtitleProvider.prioritizePreferredLanguages(
            listOf(english, spanish),
            listOf("es-MX", "en-US")
        )

        assertEquals(listOf(spanish, english), ordered)
        assertEquals(spanish, OnlineSubtitleProvider.firstPreferredLanguageMatch(ordered, listOf("es-MX")))
    }
}
