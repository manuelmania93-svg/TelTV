package com.velastudio.teltv.sync

import android.content.Context
import com.velastudio.teltv.util.OnlineSubtitle
import com.velastudio.teltv.util.OnlineSubtitleProvider
import com.velastudio.teltv.util.SubtitleLanguages
import kotlinx.coroutines.*

sealed interface SubtitlePickResult {
    data class Applied(val message: String) : SubtitlePickResult
    data class Uncertain(val candidate: OnlineSubtitle, val message: String) : SubtitlePickResult
    data class Failed(val message: String) : SubtitlePickResult
}

object SubtitleCandidateVerifier {
    suspend fun check(context: Context, sub: OnlineSubtitle, all: List<OnlineSubtitle>, durationMs: Long,
                      speech: List<SpeechWindow>): SubtitleConfidence = withContext(Dispatchers.Default) {
        val file = OnlineSubtitleProvider.downloadSubtitle(context, sub)
            ?: return@withContext SubtitleConfidence(false, true, 0, "Could not download subtitle")
        val cues = withContext(Dispatchers.IO) { SubtitleCueParser.parse(file.readText()) }
        // Reject unreasonable timing spans before the FFT aligner can allocate large arrays.
        val early = SubtitleConfidencePolicy.assess(sub.episodeEvidence, cues, durationMs, null, null)
        if (early.rejected) return@withContext early
        val reference = all.firstOrNull {
            it.id != sub.id && SubtitleLanguages.normalize(it.lang) != SubtitleLanguages.normalize(sub.lang) &&
                it.episodeEvidence?.titleMatch == true && sub.episodeEvidence?.titleMatch == true
        }
        val referenceCues = reference?.let { OnlineSubtitleProvider.downloadSubtitle(context, it) }?.let {
            withContext(Dispatchers.IO) { SubtitleCueParser.parse(it.readText()) }
        }
        val alignment = referenceCues?.takeIf(SubtitleConfidencePolicy::usable)?.let {
            ReferenceSubtitleAligner.align(it, cues, maxShiftMs = 15_000)
        }
        SubtitleConfidencePolicy.assess(sub.episodeEvidence, cues, durationMs, alignment,
            SubtitleConfidencePolicy.speechAgreement(speech, cues))
    }
}
