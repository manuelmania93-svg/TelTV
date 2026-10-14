package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caption formats seen in the user's topics (screenshots 2026-10-14). */
class CaptionEpisodeParserTest {
    private val mixed = "Breaking Bad & Better Call Saul"

    @Test
    fun seasonSpaceEpisodeSpaceTitle() {
        val p = CaptionEpisodeParser.parse("Breaking Bad & Better Call Saul \uD83D\uDCC1 4 4 Die Diagnose mp41", mixed)!!
        assertEquals(4, p.season)
        assertEquals(4, p.episode)
        assertEquals("Die Diagnose", p.titleHint)
    }

    @Test
    fun standardFormats() {
        assertEquals(4 to 4, CaptionEpisodeParser.parse("Show S04E04 Title", "Show")!!.let { it.season to it.episode })
        assertEquals(4 to 4, CaptionEpisodeParser.parse("Show 4x04", "Show")!!.let { it.season to it.episode })
        assertEquals(4 to 4, CaptionEpisodeParser.parse("Show Staffel 4 Folge 4", "Show")!!.let { it.season to it.episode })
        assertEquals(10 to 20, CaptionEpisodeParser.parse("Naruto Shippuden - S10E20", "Naruto Shippuden")!!.let { it.season to it.episode })
        val ep = CaptionEpisodeParser.parse("Naruto Shippuden Episode 50", "Naruto Shippuden")!!
        assertNull(ep.season)
        assertEquals(50, ep.episode)
    }

    @Test
    fun threeDigitCaptionIsBareDigits() {
        val p = CaptionEpisodeParser.parse("Malcolm Mittendrin 101", "Malcom Mittendrin")!!
        assertNull(p.season)
        assertEquals(101, p.episode)
        assertEquals(101, p.bareDigits)
        assertEquals(1 to 1, CaptionEpisodeParser.splitBareDigits(101))
        assertEquals(10 to 1, CaptionEpisodeParser.splitBareDigits(1001))
        assertNull(CaptionEpisodeParser.splitBareDigits(100))
    }

    @Test
    fun animeDashNumberIsNotBareDigits() {
        assertNull(CaptionEpisodeParser.parse("Naruto - 120", "Naruto")?.bareDigits)
        assertNull(CaptionEpisodeParser.parse("One Piece #1050", "One Piece")?.bareDigits)
    }

    @Test
    fun yearIsNotAnEpisode() {
        assertNull(CaptionEpisodeParser.parse("Some Event 1999", "Some Event"))
    }

    @Test
    fun datedWrestlingCaptionIsNotSeasonEpisode() {
        val caption = "480p smackdown \uD83D\uDCC1 WWF SMACKDOWN 03/08/2000 \uD83C\uDFA5 SHD"
        assertTrue(CaptionEpisodeParser.hasDate(caption))
        assertNull(CaptionEpisodeParser.parse(caption, "480p smackdown"))
        assertNull(CaptionEpisodeParser.parse("ECW 12.03.01", "ECW"))
        assertNull(CaptionEpisodeParser.parse("WCW Nitro 2000-03-08", "WCW Nitro"))
    }

    @Test
    fun pairRuleNeedsTopicName() {
        assertNull(CaptionEpisodeParser.parse("4 4 Die Diagnose"))
    }

    @Test
    fun extensionsAreStripped() {
        assertEquals("Die Diagnose", CaptionEpisodeParser.stripExtension("Die Diagnose mp41"))
        assertEquals("Die Diagnose", CaptionEpisodeParser.stripExtension("Die Diagnose.mp4"))
        assertEquals("Die Diagnose", CaptionEpisodeParser.stripExtension("Die Diagnose.mkv"))
        assertEquals("Mp4 Guide", CaptionEpisodeParser.stripExtension("Mp4 Guide"))
        assertFalse(MediaTitleCleaner.clean("Die Diagnose mp41").contains("mp4"))
    }

    @Test
    fun episodeDashGermanTitle() {
        val p = CaptionEpisodeParser.parse("One Piece \uD83D\uDCC1 08 - Die Teufelsfrucht", "One Piece")!!
        assertNull(p.season)
        assertEquals(8, p.episode)
        assertNull(p.bareDigits)
        assertEquals("Die Teufelsfrucht", p.titleHint)
    }
}
