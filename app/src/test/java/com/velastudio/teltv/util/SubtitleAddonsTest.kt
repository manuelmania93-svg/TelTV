package com.velastudio.teltv.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SubtitleAddonsTest {
    private val a = SubtitleAddon("first", "https://first.example")
    private val b = SubtitleAddon("second", "https://second.example/config")
    private fun json(url: String, id: String = "1", lang: String = "ger") =
        """{"subtitles":[{"id":"$id","lang":"$lang","url":"$url","fileName":"Show.S01E01.srt"}]}"""

    @Test fun endpointsPreserveConfigAndSupportManifestUrls() {
        assertEquals("https://second.example/config/subtitles/series/tt123:2:3.json",
            SubtitleAddons.requestUrl(b.copy(baseUrl = b.baseUrl + "/manifest.json"), "series", "tt123:2:3"))
        assertEquals("https://first.example/subtitles/movie/tt123.json", SubtitleAddons.requestUrl(a, "movie", "tt123"))
    }
    @Test fun defaultsAreKeylessAndFollowSelectedLanguage() {
        val defaults = SubtitleAddons.defaults("fr")
        assertEquals(2, defaults.size)
        val config = java.net.URLDecoder.decode(defaults[1].baseUrl.substringAfter(".com/"), "UTF-8")
        assertTrue(config.contains("fr")); assertFalse(config.contains("ApiKey"))
    }
    @Test fun oneFailedSourceDoesNotBreakOther() = runBlocking {
        val result = SubtitleAddons.fetch(listOf(a,b), "series", "tt123:1:1") { url ->
            if (url.startsWith(a.baseUrl)) throw java.io.IOException("offline")
            json("https://cdn.example/second.srt")
        }
        assertEquals(1, result.size)
    }
    @Test fun malformedSourceAndEntriesAreIgnored() = runBlocking {
        val result = SubtitleAddons.fetch(listOf(a,b), "movie", "tt123") { url ->
            if (url.startsWith(a.baseUrl)) "<html>bad</html>" else
                """{"subtitles":[null,42,{"url":"javascript:bad","lang":"de"},{"url":"https://cdn.example/a.srt","lang":"de"}]}"""
        }
        assertEquals(1, result.size)
    }
    @Test fun collidingAddonLocalIdsRemainDifferent() {
        val first = SubtitleAddons.parse(a, json("https://cdn.example/a.srt"))[0]
        val second = SubtitleAddons.parse(b, json("https://cdn.example/b.srt"))[0]
        assertNotEquals(first.id, second.id)
        assertEquals(2, SubtitleAddons.merge(listOf(first,second)).size)
    }
    @Test fun recycledProviderIndexCannotReuseWrongCacheOrSync() {
        val first = SubtitleAddons.parse(b, json("https://cdn.example/a.srt", "index-0"))[0]
        val second = SubtitleAddons.parse(b, json("https://cdn.example/b.srt", "index-0"))[0]
        assertNotEquals(first.id, second.id)
    }
    @Test fun oldV3IdsStayCompatible() {
        assertEquals("123", SubtitleAddons.parse(SubtitleAddon("opensubtitles-v3",a.baseUrl),
            json("https://cdn.example/a.srt", "123"))[0].id)
    }
    @Test fun dedupeAcrossUrlAliasesAndLanguages() {
        val first = SubtitleAddons.parse(a, json("https://subs5.strem.io/en/download/src-api/file/123", lang="ger"))[0]
        val second = SubtitleAddons.parse(b, json("https://dl.opensubtitles.org/en/download/src-api/file/123.srt", lang="deu"))[0]
        assertEquals(1, SubtitleAddons.merge(listOf(first,second)).size)
        assertEquals(2, SubtitleAddons.merge(listOf(first,second.copy(lang="eng"))).size)
        assertEquals(2, SubtitleAddons.merge(listOf(first,second.copy(isForced=true))).size)
    }
    @Test fun retainReleaseEvidenceAndForcedFlag() {
        val sub = SubtitleAddons.parse(a, """{"subtitles":[{"id":"1","lang":"ger","url":"https://cdn.example/a.srt","fileName":"Show.S02E03.forced.srt","releaseName":"WEB-DL"}]}""")[0]
        assertTrue(sub.isForced); assertEquals("WEB-DL",sub.releaseName)
        assertTrue(sub.fileName.contains("S02E03"))
    }
    @Test fun cancellationIsNotSwallowed() {
        try {
            runBlocking { SubtitleAddons.fetch(listOf(a), "movie", "tt123") { throw CancellationException() } }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
    }
    @Test fun capAddonCountAndResponseSize() = runBlocking {
        var calls = 0
        SubtitleAddons.fetch((1..8).map { SubtitleAddon("addon-$it", "https://addon$it.example") }, "movie", "tt123") {
            calls++; null
        }
        assertEquals(4, calls)
        assertTrue(SubtitleAddons.parse(a, " ".repeat(SubtitleAddons.MAX_RESPONSE_BYTES+1)).isEmpty())
    }
    @Test fun noHttpCredentialsOrQueryInAddonBase() {
        for (base in listOf("http://x.example", "https://u:p@x.example", "https://x.example?api_key=abc")) {
            try { SubtitleAddons.requestUrl(a.copy(baseUrl=base), "movie", "tt123"); fail(base) }
            catch (_: IllegalArgumentException) { }
        }
    }
}
