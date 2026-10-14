package com.velastudio.teltv.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubDlSourceTest {
    private fun response(files: String) = """{"status":true,"subtitles":[$files]}"""
    private val german = """{"language":"German","name":"Show.S02E03.srt","url":"/subtitle/1-2.zip","season":2,"episode":3,"release_name":"Show.S02E03.WEB-DL"}"""

    @Test fun disabledKeyDoesNotMakeRequests() = runBlocking {
        val result = SubDlSource("").fetch("movie", "tt123") { error("must not request") }
        assertTrue(result.isEmpty())
    }
    @Test fun requestUsesGermanAndExactIdentity() {
        val url = SubDlSource.requestUrl("fake&key", "series", "tt123:2:3")
        assertEquals("fake&key", url.queryParameter("api_key"))
        assertEquals("tt123", url.queryParameter("imdb_id"))
        assertEquals("DE", url.queryParameter("languages"))
        assertEquals("tv", url.queryParameter("type"))
        assertEquals("2", url.queryParameter("season_number"))
        assertEquals("3", url.queryParameter("episode_number"))
        assertEquals("1", url.queryParameter("unpack"))
        assertNull(SubDlSource.requestUrl("fake", "movie", "tt123").queryParameter("season_number"))
    }
    @Test fun preservesReleaseAndStableIdentity() {
        val sub = SubDlSource.parse(response(german), "series", "tt123:2:3").single()
        assertEquals("de", sub.lang)
        assertTrue(sub.id.startsWith("subdl-"))
        assertEquals("Show.S02E03.WEB-DL", sub.releaseName)
        assertEquals("https://dl.subdl.com/subtitle/1-2.zip", sub.url)
        assertEquals(sub.id, SubDlSource.parse(response(german), "series", "tt123:2:3").single().id)
    }
    @Test fun excludesWrongEpisodeAndWrongLanguage() {
        assertTrue(SubDlSource.parse(response(german), "series", "tt123:2:4").isEmpty())
        assertTrue(SubDlSource.parse(response(german.replace("German", "English")), "series", "tt123:2:3").isEmpty())
    }
    @Test fun packOnlyProvidesRequestedGermanSrt() {
        val pack = """{"language":"German","name":"pack.zip","url":"/subtitle/pack.zip","full_season":true,"season":2,"episode":3,"unpack_files":[
            {"name":"E03.srt","format":"srt","url":"/subtitle/pack/file3","season":2,"episode":3,"language":"DE"},
            {"name":"E04.srt","url":"/subtitle/pack/file4","season":2,"episode":4,"language":"DE"},
            {"name":"E03-en.srt","url":"/subtitle/pack/file3en","season":2,"episode":3,"language":"EN"}]}"""
        val sub = SubDlSource.parse(response(pack), "series", "tt123:2:3").single()
        assertEquals("E03.srt", sub.fileName)
        assertTrue(sub.url.endsWith("/file3"))
        assertTrue(SubDlSource.parse(response(german.replace("\"season\":2", "\"full_season\":true,\"season\":2")), "series", "tt123:2:3").isEmpty())
    }
    @Test fun rejectsMaliciousUrlsAndUnsupportedFormats() {
        for (url in listOf("http://dl.subdl.com/subtitle/x", "https://evil.example/subtitle/x", "//evil.example/subtitle/x", "/subtitle/x?api_key=secret", "/other/path"))
            assertNull(SubDlSource.downloadUrl(url))
        assertTrue(SubDlSource.parse("bad", "movie", "tt123").isEmpty())
        assertTrue(SubDlSource.parse(response(german.replace(".srt", ".idx").replace("1-2.zip", "1-2.idx")), "series", "tt123:2:3").isEmpty())
    }
    @Test fun quotaFailureStopsRepeatedAttemptsAndRetainsKeylessResults() = runBlocking {
        var calls = 0
        val source = SubDlSource("fake")
        val first = source.fetch("series", "tt123:2:3") { calls++; 429 to """{"error":"daily_limit"}""" }
        val second = source.fetch("series", "tt123:2:4") { error("no retry after quota") }
        assertEquals(1, calls); assertTrue(first.isEmpty()); assertTrue(second.isEmpty())
        assertTrue(source.notice!!.contains("limit")); assertFalse(source.notice!!.contains("fake"))
        val other = OnlineSubtitle("keyless", "de", "German", "https://cdn.example/a.srt", "a.srt")
        assertEquals(listOf(other), SubtitleAddons.merge(listOf(other) + first))
    }
    @Test fun invalidKeyAndNetworkErrorNeverLeakDetails() = runBlocking {
        assertTrue(SubDlSource.errorNotice(403, """{"error":"fake-private-key"}""")!!.contains("rejected"))
        val source = SubDlSource("secret")
        source.fetch("movie", "tt123") { throw java.io.IOException("https://api.example?api_key=secret") }
        assertFalse(source.notice!!.contains("secret"))
    }
    @Test fun duplicateSearchUsesCache() = runBlocking {
        val source = SubDlSource("fake")
        var calls = 0
        repeat(2) { assertEquals(1, source.fetch("series", "tt123:2:3") { calls++; 200 to response(german) }.size) }
        assertEquals(1, calls)
    }
    @Test fun cancellationIsNotSwallowed() = runBlocking {
        try { SubDlSource("fake").fetch("movie", "tt123") { throw CancellationException("cancel") }; fail() }
        catch (_: CancellationException) { }
    }
}
