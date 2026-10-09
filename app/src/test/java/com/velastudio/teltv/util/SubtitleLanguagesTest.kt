package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleLanguagesTest {
    private fun sub(id: String, lang: String, forced: Boolean = false, file: String = "$id.srt") =
        OnlineSubtitle(id = id, lang = lang, langDisplay = lang, url = "http://x/$id", fileName = file, isForced = forced)

    @Test fun germanIsDefault() {
        assertEquals("de", SubtitleLanguages.DEFAULT_CODE)
        assertEquals("de", SubtitleLanguages.search("").first().code)
    }

    @Test fun normalizeHandlesThreeLetterAndRegionCodes() {
        assertEquals("de", SubtitleLanguages.normalize("ger"))
        assertEquals("de", SubtitleLanguages.normalize("deu"))
        assertEquals("de", SubtitleLanguages.normalize("de-AT"))
        assertEquals("fr", SubtitleLanguages.normalize("fre"))
        assertEquals("pt", SubtitleLanguages.normalize("pob"))
        assertEquals("pt", SubtitleLanguages.normalize("pt-BR"))
        assertEquals("zh", SubtitleLanguages.normalize("chi"))
        assertEquals("zh", SubtitleLanguages.normalize("zht"))
        assertEquals("", SubtitleLanguages.normalize(null))
    }

    @Test fun unknownCodeStaysGroupable() {
        assertEquals("xx", SubtitleLanguages.normalize("XX-yy"))
        assertEquals("XX", SubtitleLanguages.displayName("xx"))
    }

    @Test fun searchMatchesEnglishNativeAndCodeAndAlias() {
        assertEquals("fr", SubtitleLanguages.search("french").first().code)
        assertEquals("fr", SubtitleLanguages.search("franc").first().code)
        assertEquals("es", SubtitleLanguages.search("espanol").first().code)
        assertEquals("ja", SubtitleLanguages.search("jpn").first().code)
        assertEquals("de", SubtitleLanguages.search("Deutsch").first().code)
    }

    @Test fun searchIsAccentAndUmlautInsensitive() {
        assertEquals("da", SubtitleLanguages.search("dänisch").first().code)
        assertEquals("tr", SubtitleLanguages.search("TÜRKISCH").first().code)
        assertEquals("fr", SubtitleLanguages.search("Français").first().code)
    }

    @Test fun prefixRanksBeforeContains() {
        val codes = SubtitleLanguages.search("ar").map { it.code }
        assertEquals("ar", codes.first())
        assertTrue(codes.size > 1)
    }

    @Test fun searchWithNoHitIsEmpty() {
        assertTrue(SubtitleLanguages.search("klingonzzz").isEmpty())
    }

    @Test fun blankSearchPinsSelectedThenGermanThenEnglish() {
        val codes = SubtitleLanguages.search("", selected = "ja").map { it.code }
        assertEquals(listOf("ja", "de", "en"), codes.take(3))
        assertEquals(SubtitleLanguages.catalog.size, codes.size)
    }

    @Test fun catalogHasNoDuplicateCodes() {
        val codes = SubtitleLanguages.catalog.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun orderForSelectionPutsChosenFirstForcedFirstThenEnglish() {
        val subs = listOf(sub("1", "eng"), sub("2", "fre"), sub("3", "ger"), sub("4", "ger", forced = true), sub("5", "spa"))
        val ordered = SubtitleLanguages.orderForSelection(subs, "de").map { it.id }
        assertEquals(listOf("4", "3", "1", "2", "5"), ordered)
    }

    @Test fun orderForSelectionWithEnglishChosenKeepsProviderOrderOtherwise() {
        val subs = listOf(sub("1", "ger"), sub("2", "eng"), sub("3", "fre"))
        assertEquals(listOf("2", "1", "3"), SubtitleLanguages.orderForSelection(subs, "en").map { it.id })
    }

    @Test fun countByLanguageGroupsCodeVariants() {
        val counts = SubtitleLanguages.countByLanguage(listOf(sub("1", "ger"), sub("2", "deu"), sub("3", "de"), sub("4", "eng")))
        assertEquals(3, counts["de"])
        assertEquals(1, counts["en"])
        assertFalse(counts.containsKey("fr"))
    }

    @Test fun rankResultsUsesChosenLanguageThenKeywords() {
        val subs = listOf(
            sub("1", "eng"),
            sub("2", "fre", file = "show.WEB-DL.srt"),
            sub("3", "fre", file = "show.bluray.srt"),
            sub("4", "ger")
        )
        val ranked = SubtitleLanguages.rankResults(subs, "fr", listOf("web-dl")).map { it.id }
        assertEquals(listOf("2", "3", "1", "4"), ranked)
        val german = SubtitleLanguages.rankResults(subs, "de", emptyList()).map { it.id }
        assertEquals(listOf("4", "1"), german.take(2))
    }

    @Test fun displayNameShowsNativeWhenDifferent() {
        assertEquals("German (Deutsch)", SubtitleLanguages.displayName("ger"))
        assertEquals("English", SubtitleLanguages.displayName("en"))
        assertEquals("Hindi", SubtitleLanguages.displayName("hin"))
    }
}
