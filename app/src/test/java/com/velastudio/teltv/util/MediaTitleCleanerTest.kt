package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.Subtitle
import com.velastudio.teltv.player.OffsetSubtitle

@OptIn(UnstableApi::class)
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

    @Test
    fun forcedSubtitleInPreferredLanguageRanksBeforeFullSubtitle() {
        val fullGerman = OnlineSubtitle("1", "de", "German", "https://example.test/de.srt", "German.srt")
        val forcedGerman = OnlineSubtitle(
            "2",
            "de",
            "German",
            "https://example.test/de-forced.srt",
            "German.Forced.srt",
            isForced = true
        )

        val ordered = OnlineSubtitleProvider.prioritizePreferredLanguages(
            listOf(fullGerman, forcedGerman),
            listOf("de-DE")
        )

        assertEquals(listOf(forcedGerman, fullGerman), ordered)
        assertEquals(forcedGerman, OnlineSubtitleProvider.firstPreferredLanguageMatch(ordered, listOf("de-DE")))
        assertEquals(true, OnlineSubtitleProvider.isPreferredLanguage("deu", listOf("de-DE")))
    }

    @Test
    fun shiftSubtitleMovesCueTimesWithoutChangingOriginal() {
        val source = File.createTempFile("subtitle", ".srt")
        val shifted = File(source.parentFile, "${source.nameWithoutExtension}_shift_1250.srt")
        try {
            source.writeText(
                "1\n00:00:01,000 --> 00:00:02,500\nHello\n"
            )

            val result = OnlineSubtitleProvider.shiftSubtitle(source, 1_250L)

            assertEquals(shifted, result)
            assertEquals("1\n00:00:02,250 --> 00:00:03,750\nHello\n", result.readText())
            assertEquals("1\n00:00:01,000 --> 00:00:02,500\nHello\n", source.readText())
        } finally {
            shifted.delete()
            source.delete()
        }
    }

    @Test
    fun embeddedSubtitleOffsetMovesCueEventsInBothDirections() {
        val original = object : Subtitle {
            private val eventTimes = longArrayOf(1_000L, 2_000L)

            override fun getNextEventTimeIndex(timeUs: Long): Int =
                eventTimes.indexOfFirst { it > timeUs }.takeIf { it >= 0 } ?: -1

            override fun getEventTimeCount(): Int = eventTimes.size

            override fun getEventTime(index: Int): Long = eventTimes[index]

            override fun getCues(timeUs: Long): List<Cue> = emptyList()
        }

        val delayed = OffsetSubtitle(original, 500L)
        assertEquals(1_500L, delayed.getEventTime(0))
        assertEquals(0, delayed.getNextEventTimeIndex(1_499L))

        val advanced = OffsetSubtitle(original, -500L)
        assertEquals(500L, advanced.getEventTime(0))
        assertEquals(0, advanced.getNextEventTimeIndex(499L))
    }
}
