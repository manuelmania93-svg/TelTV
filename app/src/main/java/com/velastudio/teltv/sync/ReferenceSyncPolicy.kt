package com.velastudio.teltv.sync

import com.velastudio.teltv.player.SubtitleSync
import com.velastudio.teltv.player.SubtitleTiming

/** What the player should do with a reference-subtitle alignment. Nothing is applied unless [sync] is set. */
data class ReferenceSyncDecision(val sync: SubtitleSync?, val message: String)

object ReferenceSyncPolicy {
    /** Turns parsed cues of the reference and target files into a decision. */
    fun decide(referenceText: String, targetText: String): ReferenceSyncDecision {
        val ref = SubtitleCueParser.parse(referenceText)
        val tgt = SubtitleCueParser.parse(targetText)
        if (ref.size < ReferenceSubtitleAligner.MIN_CUES) return ReferenceSyncDecision(null, "Reference subtitle has too few lines")
        if (tgt.size < ReferenceSubtitleAligner.MIN_CUES) return ReferenceSyncDecision(null, "Subtitle has too few lines")
        val result = ReferenceSubtitleAligner.align(ref, tgt)
            ?: return ReferenceSyncDecision(null, "Could not compare the subtitles")
        if (!result.confident) {
            return ReferenceSyncDecision(null, "The two subtitles do not line up clearly (different cut?). Nothing changed.")
        }
        val sync = SubtitleSync(SubtitleTiming.clampOffset(result.offsetMs), SubtitleTiming.clampScale(result.scale))
        return ReferenceSyncDecision(
            sync,
            "Synced to reference: ${SubtitleTiming.formatOffset(sync.offsetMs)}, ${SubtitleTiming.formatScale(sync.scale)}"
        )
    }
}
