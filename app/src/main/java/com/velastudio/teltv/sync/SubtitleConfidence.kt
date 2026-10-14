package com.velastudio.teltv.sync

import com.velastudio.teltv.util.EpisodeEvidence
import kotlin.math.abs

/** Offline timing checks support identity evidence; none proves that translated words are correct. */
data class SubtitleConfidence(val confident: Boolean, val rejected: Boolean, val score: Int, val message: String)
data class SpeechWindow(val startMs: Long, val signal: FloatArray)

object SubtitleConfidencePolicy {
    const val MAX_SPAN_MS = 4 * 60 * 60 * 1000L
    fun usable(cues: List<SubtitleCue>): Boolean = cues.size in 20..20_000 &&
        cues.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= MAX_SPAN_MS }

    fun assess(evidence: EpisodeEvidence?, cues: List<SubtitleCue>, durationMs: Long,
               reference: ReferenceAlignment?, speech: AlignmentResult?): SubtitleConfidence {
        if (!usable(cues)) return SubtitleConfidence(false, true, 0, "Subtitle has too few or invalid cue times")
        val last = cues.maxOf { it.endMs }
        // Late/absent dialogue and credits make a short subtitle inconclusive, not incorrect.
        val runtimeKnown = durationMs in 1..MAX_SPAN_MS
        if (runtimeKnown && last > durationMs * 1.20 + 60_000) {
            return SubtitleConfidence(false, true, 0, "Subtitle runs far past this video's length (wrong episode or cut)")
        }
        if (evidence?.numberConflict == true && !evidence.titleMatch) {
            return SubtitleConfidence(false, true, 0, "Release episode number conflicts and no title match was found")
        }
        val cross = reference?.let { it.confident && it.score >= 0.60 && abs(it.offsetMs) <= 15_000 && it.scale in 0.94..1.06 } == true
        val audio = speech?.let { it.confident && !it.driftSuspected && it.agreeingWindows >= 2 && abs(it.offsetMs) <= 15_000 } == true
        val title = evidence?.titleMatch == true
        val score = (if (title) 45 else 0) + (if (runtimeKnown) 10 else 0) +
            (if (cross) 20 else 0) + (if (audio) 25 else 0)
        val good = title && runtimeKnown && (audio || cross)
        val checks = listOf(
            if (title) "title match" else "episode identity unverified",
            if (runtimeKnown) "runtime plausible" else "runtime unknown",
            if (cross) "cross-language timing agrees" else "cross-language check inconclusive/unavailable",
            if (audio) "speech timing agrees" else "speech check inconclusive/unavailable"
        ).joinToString("; ")
        return SubtitleConfidence(good, false, score,
            (if (good) "Timing checks passed" else "Not sure, pick one") + " ($score/100): " + checks +
                ". Timing agreement is not a translation/content guarantee.")
    }

    fun speechAgreement(windows: List<SpeechWindow>, cues: List<SubtitleCue>): AlignmentResult? {
        if (!usable(cues)) return null
        val estimates = windows.mapNotNull { SubtitleAligner.alignWindow(it.signal, it.startMs, cues, 15_000) }
        return estimates.takeIf { it.isNotEmpty() }?.let(SubtitleAligner::combine)
    }
}
