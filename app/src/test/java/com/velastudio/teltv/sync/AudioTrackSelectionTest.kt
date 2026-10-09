package com.velastudio.teltv.sync

import com.velastudio.teltv.sync.AudioTrackChoice.Reason
import org.junit.Assert.*
import org.junit.Test

class AudioTrackSelectionTest {
    // extractor track 0 is video, audio tracks are 1 (ger), 2 (eng), 3 (no language)
    private val tracks = listOf(
        AudioTrackCandidate(1, 0, "ger"),
        AudioTrackCandidate(2, 1, "eng"),
        AudioTrackCandidate(3, 2, null)
    )

    @Test fun picksSelectedSecondTrackNotTheFirst() {
        val c = AudioTrackSelection.resolve(tracks, AudioTrackRef(1, "eng"))!!
        assertEquals(2, c.extractorIndex)
        assertEquals(Reason.EXACT, c.reason)
    }

    @Test fun missingOrdinalWithUnmatchedLanguageFallsToFirst() {
        // "en" does not equal "ger"/"eng" after normalizing
        assertEquals(Reason.FIRST_FALLBACK, AudioTrackSelection.resolve(tracks, AudioTrackRef(7, "en"))!!.reason)
    }

    @Test fun languageFallbackFindsSameLanguageAtOtherPosition() {
        val c = AudioTrackSelection.resolve(tracks, AudioTrackRef(0, "eng"))!!
        assertEquals(2, c.extractorIndex)
        assertEquals(Reason.LANGUAGE_FALLBACK, c.reason)
    }

    @Test fun unknownLanguageTrustsOrdinal() {
        assertEquals(3, AudioTrackSelection.resolve(tracks, AudioTrackRef(2, "und"))!!.extractorIndex)
        assertEquals(1, AudioTrackSelection.resolve(tracks, AudioTrackRef(0, null))!!.extractorIndex)
    }

    @Test fun regionSuffixIsIgnored() {
        assertEquals("pt", AudioTrackSelection.normalize("pt-BR"))
        assertNull(AudioTrackSelection.normalize("und"))
    }

    @Test fun firstTrackFallbackIsReported() {
        val c = AudioTrackSelection.resolve(tracks, AudioTrackRef(9, "fra"))!!
        assertEquals(1, c.extractorIndex)
        assertEquals(Reason.FIRST_FALLBACK, c.reason)
        assertEquals(Reason.FIRST_FALLBACK, AudioTrackSelection.resolve(tracks, null)!!.reason)
    }

    @Test fun noAudioTracksGivesNull() {
        assertNull(AudioTrackSelection.resolve(emptyList(), AudioTrackRef(0, "eng")))
    }
}
