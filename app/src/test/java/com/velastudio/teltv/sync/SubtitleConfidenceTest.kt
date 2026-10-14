package com.velastudio.teltv.sync

import com.velastudio.teltv.util.EpisodeEvidence
import org.junit.Assert.*
import org.junit.Test

class SubtitleConfidenceTest {
    private val title = EpisodeEvidence(true, false, 100, "title")
    private val cues = (0..39).map { SubtitleCue(it * 25_000L, it * 25_000L + 2000) }
    private val cross = ReferenceAlignment(0, 1.0, 0.85, 0.4, true)
    private val speech = AlignmentResult(100, true, 3, 3, false, emptyList())
    @Test fun titleRuntimeAndSpeechCanPass() {
        assertTrue(SubtitleConfidencePolicy.assess(title, cues, 1_200_000, null, speech).confident)
    }
    @Test fun twoWrongTracksAgreeingDoesNotProveEpisode() {
        assertFalse(SubtitleConfidencePolicy.assess(null, cues, 1_200_000, cross, speech).confident)
    }
    @Test fun titleOnlyStillNeedsTimingEvidence() {
        assertFalse(SubtitleConfidencePolicy.assess(title, cues, 1_200_000, null, null).confident)
    }
    @Test fun christmasSpecialLengthOnNormalEpisodeRejected() {
        val longCues = cues + SubtitleCue(2_500_000, 2_510_000)
        assertTrue(SubtitleConfidencePolicy.assess(title, longCues, 1_320_000, cross, speech).rejected)
    }
    @Test fun unknownRuntimeAndSilentScenesRemainUncertain() {
        assertFalse(SubtitleConfidencePolicy.assess(title, cues, 0, cross, speech).confident)
        assertNull(SubtitleConfidencePolicy.speechAgreement(emptyList(), cues))
        assertFalse(SubtitleConfidencePolicy.assess(title, cues.take(20), 1_200_000, null, null).rejected)
    }
    @Test fun hugeAndMalformedTimesRejectedBeforeFft() {
        assertFalse(SubtitleConfidencePolicy.usable(cues + SubtitleCue(0, Long.MAX_VALUE)))
        assertFalse(SubtitleConfidencePolicy.usable(cues + SubtitleCue(-1, 10)))
    }
    @Test fun conflictingNumberNeedsTitleAndTiming() {
        val conflicting = EpisodeEvidence(false, true, -100, "number conflict")
        assertTrue(SubtitleConfidencePolicy.assess(conflicting, cues, 1_200_000, cross, speech).rejected)
        assertTrue(SubtitleConfidencePolicy.assess(title.copy(numberConflict = true), cues, 1_200_000, cross, null).confident)
    }
    @Test fun largeShiftsOrDriftDoNotBecomeConfident() {
        assertFalse(SubtitleConfidencePolicy.assess(title, cues, 1_200_000, cross.copy(offsetMs = 30_000), speech.copy(driftSuspected = true)).confident)
    }
}
