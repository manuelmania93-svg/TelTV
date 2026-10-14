package com.velastudio.teltv.ui.player

import com.velastudio.teltv.util.OnlineSubtitle
import com.velastudio.teltv.util.EpisodeEvidence
import org.junit.Assert.*
import org.junit.Test

class SubtitleSelectionPolicyTest {
    private fun sub(id: String, language: String, evidence: EpisodeEvidence? = null) =
        OnlineSubtitle(id, language, language, "https://example.invalid/$id", "$id.srt", episodeEvidence = evidence)

    @Test fun usesBestAvailableInSavedLanguageWithoutRequiringConfidence() {
        val english = sub("en", "eng")
        val german = sub("first", "ger")
        assertEquals(german, SubtitleSelectionPolicy.best(listOf(english, german, sub("second", "deu")), "de-AT"))
    }
    @Test fun noSilentLanguageFallback() {
        assertNull(SubtitleSelectionPolicy.best(listOf(sub("en", "eng")), "de"))
        assertNull(SubtitleSelectionPolicy.best(emptyList(), "de"))
    }
    @Test fun explicitWrongEpisodeIsSkippedUnlessItsTitleMatches() {
        val wrong = sub("wrong", "de", EpisodeEvidence(false, true, -100, "conflict"))
        val titleMatch = sub("title", "de", EpisodeEvidence(true, true, 100, "title matches"))
        assertNull(SubtitleSelectionPolicy.best(listOf(wrong), "de"))
        assertEquals(titleMatch, SubtitleSelectionPolicy.best(listOf(wrong, titleMatch), "de"))
    }
    @Test fun timingIsIsolatedByVideoAndExactSubtitleContent() {
        val a = SubtitleSelectionPolicy.fingerprint("first subtitle".toByteArray())
        val b = SubtitleSelectionPolicy.fingerprint("different release".toByteArray())
        assertEquals(a, SubtitleSelectionPolicy.fingerprint("first subtitle".toByteArray()))
        assertNotEquals(a, b)
        assertNotEquals(SubtitleSelectionPolicy.syncStorageKey("video", a), SubtitleSelectionPolicy.syncStorageKey("video", b))
        assertNotEquals(SubtitleSelectionPolicy.syncStorageKey("one", a), SubtitleSelectionPolicy.syncStorageKey("two", a))
        assertNotEquals(SubtitleSelectionPolicy.syncStorageKey("a:b", "c"), SubtitleSelectionPolicy.syncStorageKey("a", "b:c"))
        assertNotEquals("subtitle_sync_video", SubtitleSelectionPolicy.syncStorageKey("video", a))
    }
}
