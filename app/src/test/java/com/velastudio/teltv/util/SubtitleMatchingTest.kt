package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test

class SubtitleMatchingTest {
    private fun sub(id: String, release: String, lang: String = "eng") =
        OnlineSubtitle(id, lang, lang, "https://example.com/$id.srt", release, releaseName = release)

    @Test fun ignoresPunctuationAndAccentsButNotDifferentShows() {
        assertEquals(100, OnlineSubtitleProvider.titleScore("Amélie", "Amelie"))
        assertEquals(100, OnlineSubtitleProvider.titleScore("The.Office.US", "The Office US"))
        assertTrue(OnlineSubtitleProvider.titleScore("The Office US", "The Office UK") < 70)
    }

    @Test fun rejectsWrongEpisodeSeasonAndRemakeYear() {
        assertNull(OnlineSubtitleProvider.releaseScore("Office.S02E03.mkv", sub("1", "Office.S02E04.srt")))
        assertNull(OnlineSubtitleProvider.releaseScore("Office.S02E03.mkv", sub("1", "Office.S01E03.srt")))
        assertNull(OnlineSubtitleProvider.releaseScore("Dune.2021.mkv", sub("1", "Dune.1984.srt")))
    }

    @Test fun ranksMatchingReleaseAheadOfGenericFallback() {
        val generic = sub("1", "eng.srt")
        val wrong = sub("2", "Office.S02E04.WEB-DL.srt")
        val exact = sub("3", "Office.S02E03.WEB-DL-GROUP.srt")
        val other = sub("4", "Office.S02E03.HDTV-OTHER.srt")
        assertEquals(listOf("3", "4", "1"), OnlineSubtitleProvider.rankSubtitles(
            "Office.S02E03.WEB-DL-GROUP.mkv", listOf(generic, wrong, other, exact)).map { it.id })
    }

    @Test fun acceptsSeasonPackUsingEpisodeFilenameAndRejectsProviderConflict() {
        val pack = sub("pack", "Office Season 2 Complete").copy(
            fileName = "Office.S02E03.srt", season = 2, episode = 3)
        assertNotNull(OnlineSubtitleProvider.releaseScore("Office.S02E03.mkv", pack))
        assertNull(OnlineSubtitleProvider.releaseScore("Office.S02E03.mkv", pack.copy(episode = 4)))
    }

    @Test fun keepsLanguagePreferenceAndParenthesizedYear() {
        val identity = OnlineSubtitleProvider.identifyMedia("Dune (2021).mkv")
        assertEquals(2021, identity.year)
        assertEquals("Dune", identity.title)
        assertEquals(listOf("de", "en"), OnlineSubtitleProvider.rankSubtitles("Dune.2021.mkv",
            listOf(sub("en", "Dune.2021.srt"), sub("de", "Dune.2021.srt", "ger"))).map { it.id })
    }
}
