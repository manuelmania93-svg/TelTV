package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test

class EpisodeTitleResolverTest {
    private val topic = "Breaking Bad / Better Call Saul"
    private fun fixture(name: String) = javaClass.getResource("/episode-titles/$name.json")!!.readText()
    private fun catalog() = EpisodeTitleResolver.merge(
        EpisodeTitleResolver.parse("tt0903747", fixture("bb-en")) +
        EpisodeTitleResolver.parse("tt0903747", fixture("bb-de")) +
        EpisodeTitleResolver.parse("tt3032476", fixture("bcs-de"))
    )
    private fun resolve(caption: String) = EpisodeTitleResolver.resolve(
        EpisodeTitleResolver.titleHint(caption, topic), catalog()
    )
    @Test fun realGermanCaptionOverridesWrongSeasonAcrossMixedTopic() {
        val caption = "3 3 ... und der Sack ist im Fluss"
        assertEquals(3, CaptionEpisodeParser.parse(caption, topic)!!.season)
        val match = resolve(caption).unique!!
        assertEquals("tt0903747", match.showId)
        assertEquals(1, match.season)
        assertEquals(3, match.episode)
        assertTrue(match.titles.contains("...And the Bag's in the River"))
    }
    @Test fun englishNameAlsoOverridesExplicitNumbers() {
        assertEquals(1, resolve("S03E03 ...And the Bag's in the River.mkv").unique!!.season)
    }
    @Test fun titleOnlyCaptionWorks() {
        assertEquals(3, resolve("… und der Sack ist im Fluss").unique!!.episode)
    }
    @Test fun punctuationAndCaseAreNotIdentity() {
        assertEquals(3, resolve("3 3 UND DER SACK IST IM FLUSS.mp4").unique!!.episode)
    }
    @Test fun unknownTitleKeepsNoTitleResolution() {
        assertTrue(resolve("3 3 Completely Unknown Name").matches.isEmpty())
    }
    @Test fun malformedAndMissingMetadataFailIndependently() {
        assertTrue(EpisodeTitleResolver.parse("bad", "not json").isEmpty())
        assertTrue(EpisodeTitleResolver.parse("bad", null).isEmpty())
        assertEquals(3, resolve("3 3 und der Sack ist im Fluss").unique!!.episode)
    }
    @Test fun duplicateAliasesDoNotCreateAmbiguity() {
        val eps = catalog()
        assertNotNull(EpisodeTitleResolver.resolve("und der Sack ist im Fluss", eps + eps).unique)
    }
    @Test fun duplicateTitleAcrossShowsMustNotAutoPick() {
        val a = EpisodeTitleResolver.Episode("a", 1, 3, listOf("The Long Night"))
        val b = a.copy(showId = "b")
        val result = EpisodeTitleResolver.resolve("The Long Night", listOf(a, b))
        assertTrue(result.ambiguous)
        assertNull(result.unique)
    }
    @Test fun duplicateTitleAcrossSeasonsMustNotAutoPick() {
        val a = EpisodeTitleResolver.Episode("a", 1, 3, listOf("The Long Night"))
        assertTrue(EpisodeTitleResolver.resolve("The Long Night", listOf(a, a.copy(season = 3))).ambiguous)
    }
    @Test fun shortAndGenericTitlesCannotOverrideNumbers() {
        for (title in listOf("Pilot", "Nacho", "Episode 12", "Folge 5", "It")) {
            val a = EpisodeTitleResolver.Episode("a", 1, 1, listOf(title))
            assertTrue(EpisodeTitleResolver.resolve(title, listOf(a)).matches.isEmpty())
        }
    }
    @Test fun metadataSupportsDocumentedTitleAndNumberFields() {
        val body = """{"meta":{"videos":[{"season":2,"number":4,"title":"The Long Night"}]}}"""
        assertEquals(4, EpisodeTitleResolver.parse("a", body).single().episode)
    }
    @Test fun umlautsAndSharpSNormalize() {
        val a = EpisodeTitleResolver.Episode("a", 1, 1, listOf("Grüße aus Köln"))
        assertNotNull(EpisodeTitleResolver.resolve("Grusse aus Koln", listOf(a)).unique)
    }
}
