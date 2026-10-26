package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the wrong-artwork bug: an X-Factor episode ("S06E03: Der Unbekannte /
 * Das böse ...") showed "Die Sendung mit der Maus" artwork and a "1971–" year line because the
 * provider blindly took the first Cinemeta hit for the full caption. Selection is now scored,
 * and an untrustworthy match must fall back to the Telegram thumbnail.
 */
class TmdbMetadataSelectionTest {

    private fun candidate(name: String, year: String? = null, isSeries: Boolean = true) =
        MetaCandidate(name, year, posterUrl = "https://example.test/poster.jpg", backdropUrl = null, rating = null, overview = null, isSeries = isSeries)

    @Test
    fun extractQueryStripsLeadingEpisodeMarker() {
        assertEquals(
            "Der Unbekannte / Das böse Spiegelbild / Die Zeitverschiebung / Stalker / Dreht um!",
            TmdbMetadataProvider.extractQuery("S06E03: Der Unbekannte / Das böse Spiegelbild / Die Zeitverschiebung / Stalker / Dreht um!")
        )
        assertEquals("Breaking Bad", TmdbMetadataProvider.extractQuery("Breaking Bad - S01E03"))
        assertEquals("The Simpsons", TmdbMetadataProvider.extractQuery("The Simpsons S16E07 Irgendein Titel"))
    }

    @Test
    fun unrelatedFirstHitIsRejected() {
        val query = "Der Unbekannte / Das böse Spiegelbild / Die Zeitverschiebung / Stalker / Dreht um!"
        val candidates = listOf(
            candidate("Die Sendung mit der Maus", "1971–"),
            candidate("Der Staatsanwalt hat das Wort", "1965–1991")
        )
        assertNull(TmdbMetadataProvider.pickBestCandidate(query, candidates))
    }

    @Test
    fun movieWithMatchingYearBeatsSameNameSeries() {
        val query = "Guardians of the Galaxy Vol 3 - 2023"
        val series = candidate("Guardians of the Galaxy", "2015–2019", isSeries = true)
        val movie = candidate("Guardians of the Galaxy Vol. 3", "2023", isSeries = false)
        assertEquals(movie, TmdbMetadataProvider.pickBestCandidate(query, listOf(series, movie)))
        assertEquals(movie, TmdbMetadataProvider.pickBestCandidate(query, listOf(movie, series)))
    }

    @Test
    fun episodeAirdateInsideSeriesYearRangeStillMatches() {
        val query = "WWE SmackDown 03/08/2000"
        val show = candidate("WWE SmackDown!", "1999–")
        assertEquals(show, TmdbMetadataProvider.pickBestCandidate(query, listOf(show)))
    }

    @Test
    fun conflictingYearLowersScoreBelowThreshold() {
        val query = "Guardians of the Galaxy Vol 3 - 2023"
        val seriesOnly = candidate("Guardians of the Galaxy", "2015–2019", isSeries = true)
        // Only the year-conflicting series is available: no trustworthy match -> thumbnail fallback.
        assertNull(TmdbMetadataProvider.pickBestCandidate(query, listOf(seriesOnly)))
    }

    @Test
    fun exactAndContainedShowNamesMatch() {
        assertEquals(
            candidate("Breaking Bad", "2008–2013"),
            TmdbMetadataProvider.pickBestCandidate("Breaking Bad", listOf(candidate("Breaking Bad", "2008–2013")))
        )
        val kehrtZurueck = candidate("X-Factor: Das Unfassbare kehrt zurück")
        assertEquals(
            kehrtZurueck,
            TmdbMetadataProvider.pickBestCandidate("X-Factor: Das Unfassbare", listOf(candidate("Beyond Belief: Fact or Fiction"), kehrtZurueck))
        )
    }

    @Test
    fun yearlessMovieBeatsSeriesWhoseRangeContainsQueryYear() {
        // Live Cinemeta data for "Guardians of the Galaxy Vol 2 - 2017": the movie hit has no
        // year and the animated series' 2015-2019 range contains 2017. The range bonus must not
        // rescue the series when the query's "vol 2" tokens are absent from its name.
        val query = "Guardians of the Galaxy Vol 2 - 2017"
        val series = candidate("Guardians of the Galaxy", "2015–2019", isSeries = true)
        val movie = candidate("Guardians of the Galaxy: Vol. 2", year = null, isSeries = false)
        assertEquals(movie, TmdbMetadataProvider.pickBestCandidate(query, listOf(series, movie)))
        assertEquals(movie, TmdbMetadataProvider.pickBestCandidate(query, listOf(movie, series)))
    }

    @Test
    fun emptyCandidateListYieldsNull() {
        assertNull(TmdbMetadataProvider.pickBestCandidate("Anything", emptyList()))
    }

    @Test
    fun yearAgreementParsing() {
        assertEquals(true, TmdbMetadataProvider.yearAgreement("Movie 2023", "2023"))
        assertEquals(false, TmdbMetadataProvider.yearAgreement("Movie 2023", "2015–2019"))
        assertEquals(true, TmdbMetadataProvider.yearAgreement("WWE SmackDown 03/08/2000", "1999–"))
        assertEquals(false, TmdbMetadataProvider.yearAgreement("Show 1990", "1999–"))
        assertNull(TmdbMetadataProvider.yearAgreement("No year here", "2023"))
        assertNull(TmdbMetadataProvider.yearAgreement("Movie 2023", null))
    }
}
