package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSearchLogicTest {

    @Test fun mixedTopicSeparatorsSurviveShowCleanup() {
        for (separator in listOf(" / ", " & ", " + ", " | ")) {
            val clean = OnlineSubtitleProvider.cleanShowName("Breaking Bad" + separator + "Better Call Saul")
            assertEquals(listOf("Breaking Bad", "Better Call Saul"), ShowNameMatcher.splitMixed(clean))
        }
    }
    @Test fun titleOnlySeriesTopicCannotFallBackToMovie() {
        assertTrue(OnlineSubtitleProvider.identifyMedia("... und der Sack ist im Fluss", "Breaking Bad / Better Call Saul").isSeries)
    }

    // Season lengths of Naruto Shippuden S1..S10 as listed by Cinemeta (tt0988824).
    private val shippuden = mapOf(1 to 32, 2 to 21, 3 to 18, 4 to 17, 5 to 24, 6 to 31, 7 to 8, 8 to 24, 9 to 21, 10 to 26)

    @Test
    fun shippudenS10E20IsAbsolute216() {
        assertEquals(216, OnlineSubtitleProvider.absoluteEpisodeNumber(shippuden, 10, 20))
        assertEquals(215, OnlineSubtitleProvider.absoluteEpisodeNumber(shippuden, 10, 19))
        assertEquals(217, OnlineSubtitleProvider.absoluteEpisodeNumber(shippuden, 10, 21))
    }

    @Test
    fun absoluteNumberMapsBackToSeasonEpisode() {
        assertEquals(10 to 20, OnlineSubtitleProvider.seasonEpisodeFromAbsolute(shippuden, 216))
        assertEquals(1 to 1, OnlineSubtitleProvider.seasonEpisodeFromAbsolute(shippuden, 1))
        assertNull(OnlineSubtitleProvider.seasonEpisodeFromAbsolute(shippuden, 5000))
    }

    @Test
    fun seasonCountsIgnoreSpecials() {
        val json = """{"meta":{"videos":[
            {"season":0,"episode":1},{"season":1,"episode":1},{"season":1,"episode":2},{"season":2,"episode":1}]}}"""
        assertEquals(mapOf(1 to 2, 2 to 1), OnlineSubtitleProvider.parseSeasonCounts(json))
    }

    @Test
    fun shippudenCaptionWithChannelName() {
        val id = OnlineSubtitleProvider.identifyMedia("Naruto Shippuden - S10E20", "Naruto Shippuden")
        assertEquals("Naruto Shippuden", id.title)
        assertEquals(true, id.isSeries)
        assertEquals(10, id.season)
        assertEquals(20, id.episode)
    }

    @Test
    fun simpsonsEpisodeTitleIsIgnoredAndChannelNameWins() {
        val id = OnlineSubtitleProvider.identifyMedia(
            "Die Simpsons S36E18 Was vom Tage übrig blieb",
            "Die Simpsons"
        )
        assertEquals("Die Simpsons", id.title)
        assertEquals(36, id.season)
        assertEquals(18, id.episode)

        val p = OnlineSubtitleProvider.identifyMedia("S36E14 P S Ich hasse dich", "Die Simpsons")
        assertEquals("Die Simpsons", p.title)
        assertEquals(36, p.season)
        assertEquals(14, p.episode)
    }

    @Test
    fun xFactorMultiStoryCaptionUsesChannelName() {
        val id = OnlineSubtitleProvider.identifyMedia(
            "S06E01: Der Kontrolleur / Das Hotel des Grauens / Der Anruf",
            "X-Factor: Das Unfassbare"
        )
        assertEquals("X-Factor: Das Unfassbare", id.title)
        assertEquals(6, id.season)
        assertEquals(1, id.episode)
    }

    @Test
    fun withoutChannelNameLanguageTagsAreStripped() {
        val id = OnlineSubtitleProvider.identifyMedia("Naruto Shippuden GerSub S10E20")
        assertEquals("Naruto Shippuden", id.title)
        assertEquals(10, id.season)
        assertEquals(20, id.episode)
    }

    @Test
    fun showNameCleanupDropsTagsAndPlaylistSuffix() {
        assertEquals("Naruto Shippuden", OnlineSubtitleProvider.cleanShowName("Naruto Shippuden GER SUB"))
        assertEquals("Die Simpsons", OnlineSubtitleProvider.cleanShowName("Die Simpsons Marathon"))
        assertEquals("Die Simpsons", OnlineSubtitleProvider.cleanShowName("📺 Die Simpsons"))
    }

    @Test
    fun titleCandidatesRetryWithShorterNames() {
        assertEquals(
            listOf("X-Factor: Das Unfassbare", "X-Factor"),
            OnlineSubtitleProvider.titleCandidates("X-Factor: Das Unfassbare")
        )
        val c = OnlineSubtitleProvider.titleCandidates("Naruto Shippuden Ultimate Ninja")
        assertEquals("Naruto Shippuden Ultimate Ninja", c.first())
        assertTrue(c.contains("Naruto Shippuden"))
    }

    @Test
    fun exactCatalogNameBeatsFirstHit() {
        val names = listOf("Beyond Belief: Fact or Fiction", "X-Factor: Das Unfassbare kehrt zurück", "X-Factor: Das Unfassbare")
        assertEquals(2, OnlineSubtitleProvider.bestMetaIndex(names, "X-Factor: Das Unfassbare"))
        assertEquals(0, OnlineSubtitleProvider.bestMetaIndex(names, "something else"))
    }
}

class TopicCaptionIdentityTest {
    @Test
    fun mixedTopicWithSeasonEpisodeSpaceCaptionIsSeries() {
        val id = OnlineSubtitleProvider.identifyMedia(
            "Breaking Bad & Better Call Saul \uD83D\uDCC1 4 4 Die Diagnose mp41",
            "Breaking Bad & Better Call Saul"
        )
        assertEquals("Breaking Bad & Better Call Saul", id.title)
        assertEquals(true, id.isSeries)
        assertEquals(4, id.season)
        assertEquals(4, id.episode)
        assertEquals(false, id.dated)
    }

    @Test
    fun threeDigitCaptionKeepsBothReadings() {
        val id = OnlineSubtitleProvider.identifyMedia("Malcolm Mittendrin 101", "Malcom Mittendrin")
        assertEquals(true, id.isSeries)
        assertNull(id.season)
        assertEquals(101, id.episode)
        assertEquals(101, id.bareDigits)
    }

    @Test
    fun animeDashNumberStaysAbsolute() {
        val id = OnlineSubtitleProvider.identifyMedia("Naruto - 120", "Naruto")
        assertEquals(true, id.isSeries)
        assertEquals(120, id.episode)
        assertNull(id.bareDigits)
    }

    @Test
    fun datedWrestlingCaptionIsNotAMovieGuess() {
        val id = OnlineSubtitleProvider.identifyMedia(
            "480p smackdown \uD83D\uDCC1 WWF SMACKDOWN 03/08/2000 \uD83C\uDFA5 SHD",
            "480p smackdown"
        )
        assertEquals(false, id.isSeries)
        assertEquals(true, id.dated)
    }

    @Test
    fun explicitSeasonEpisodeBeatsDate() {
        val id = OnlineSubtitleProvider.identifyMedia("Show S02E03 recorded 03/08/2000", "Show")
        assertEquals(true, id.isSeries)
        assertEquals(false, id.dated)
    }

    @Test
    fun onePieceEpisodeDashTitleIsSeries() {
        val id = OnlineSubtitleProvider.identifyMedia("One Piece \uD83D\uDCC1 08 - Die Teufelsfrucht", "One Piece")
        assertEquals(true, id.isSeries)
        assertNull(id.season)
        assertEquals(8, id.episode)
    }

    @Test
    fun xFactorLongFormStaffelEpisode() {
        val caption = "X-Factor: Das Unfassbare Staffel 02 Episode 03 \uD83D\uDC49 Die blutige Hand"
        val withTopic = OnlineSubtitleProvider.identifyMedia(caption, "X-Factor: Das Unfassbare")
        assertEquals("X-Factor: Das Unfassbare", withTopic.title)
        assertEquals(2, withTopic.season)
        assertEquals(3, withTopic.episode)
        val noTopic = OnlineSubtitleProvider.identifyMedia(caption)
        assertEquals("X-Factor: Das Unfassbare", noTopic.title)
        assertEquals(2, noTopic.season)
        assertEquals(3, noTopic.episode)
    }
}
