package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test

class EpisodeMatchPolicyTest {
    private fun sub(release: String) = OnlineSubtitle("id", "ger", "German", "https://example.invalid/sub", release, releaseName = release)
    private val titles = listOf("Der Berg ruft", "The Man Who Flew Too Much")
    @Test fun simpsonsProviderTenIsNotIdentityProof() {
        val result = EpisodeMatchPolicy.assess(sub("The.Simpsons.O.Cmon.All.Ye.Faithful.2024.1080p.WEB.h264-EDITH.ger.srt"), titles, 36, 10)
        assertFalse(result.titleMatch)
    }
    @Test fun simpsonsCorrectReleaseInProviderElevenMatchesTitle() {
        val result = EpisodeMatchPolicy.assess(sub("The.Simpsons.S36E10.The.Man.Who.Flew.Too.Much.1080p.DSNP.WEB-DL.DDP5.1.H.264-NTb.srt"), titles, 36, 10)
        assertTrue(result.titleMatch)
        assertFalse(result.numberConflict)
    }
    @Test fun simpsonsTwelveBottleEpisodeMustNotWin() {
        val result = EpisodeMatchPolicy.assess(sub("The.Simpsons.S36E11.Bottle.Episode.1080p.srt"), titles, 36, 10)
        assertFalse(result.titleMatch)
        assertTrue(result.numberConflict)
    }
    @Test fun mechanicsAreNotSpecificToSimpsons() {
        val result = EpisodeMatchPolicy.assess(sub("Some.Show.S02E09.The.Last.Train.720p.srt"), listOf("The Last Train"), 2, 8)
        assertTrue(result.titleMatch)
        assertTrue(result.numberConflict)
        assertEquals(listOf(7, 9, 6, 10), EpisodeMatchPolicy.neighbours(8))
        assertEquals(listOf(2, 3), EpisodeMatchPolicy.neighbours(1))
    }
    @Test fun commonWordsAndNumberAloneNeverProveIdentity() {
        assertFalse(EpisodeMatchPolicy.assess(sub("Show.S01E02.1080p.srt"), listOf("The Long Night"), 1, 2).titleMatch)
        assertFalse(EpisodeMatchPolicy.assess(sub("Show.S01E02.Night.1080p.srt"), listOf("The Long Night"), 1, 2).titleMatch)
    }
    @Test fun preservesFilenameTitleAndMetadataAlias() {
        val meta = """{"meta":{"videos":[{"season":36,"episode":10,"name":"The Man Who Flew Too Much"}]}}"""
        assertEquals(titles, EpisodeMatchPolicy.expectedTitles("Simpsons - S36E10 Der Berg ruft", meta, 36, 10))
    }
}
