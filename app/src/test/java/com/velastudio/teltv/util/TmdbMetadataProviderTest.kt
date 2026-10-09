package com.velastudio.teltv.util

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TmdbMetadataProviderTest {
    @Test fun scoresNamesConservatively() {
        assertEquals(100, TmdbMetadataProvider.titleScore("Amélie", "Amelie"))
        assertTrue(TmdbMetadataProvider.titleScore("The Office US", "The Office UK") < 70)
    }

    @Test fun selectsRemakeByYearNotSearchOrder() {
        val results = JSONObject("""{"results":[
            {"id":1,"title":"Dune","release_date":"1984-01-01"},
            {"id":2,"title":"Dune","release_date":"2021-01-01"}]}""")
        assertEquals(2, TmdbMetadataProvider.selectResult(
            SubtitleMediaIdentity("Dune", false, null, null, 2021), results)?.optInt("id"))
        assertNull(TmdbMetadataProvider.selectResult(
            SubtitleMediaIdentity("Dune", false, null, null, null), results))
    }

    @Test fun rejectsUnrelatedTopResultAndFormatsEpisode() {
        assertNull(TmdbMetadataProvider.selectResult(
            SubtitleMediaIdentity("Office", true, 2, 3, null),
            JSONObject("""{"results":[{"id":1,"name":"House"}]}""")))
        assertEquals("S02E03", TmdbMetadata(null, null, null, null, null,
            seasonNumber = 2, episodeNumber = 3).episodeLabel())
        assertEquals("Episode 1050", TmdbMetadata(null, null, null, null, null,
            episodeNumber = 1050).episodeLabel())
    }
}
