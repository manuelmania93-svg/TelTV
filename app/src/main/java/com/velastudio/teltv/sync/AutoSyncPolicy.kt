package com.velastudio.teltv.sync

/** What the player should do with an auto-sync outcome. */
data class AutoSyncDecision(val newOffsetMs: Long?, val message: String)

/** What the analysis was run against; compared again before anything is applied. */
data class AutoSyncTarget(val mediaUri: String?, val subtitlePath: String?, val audio: AudioTrackRef?)

object AutoSyncPolicy {
    /**
     * The analysis uses the subtitle's original cue times, so its offset is the absolute offset to
     * play with. "Already in sync" therefore means offset 0, which also clears an earlier manual
     * offset. Results for a different media, subtitle or audio track are dropped.
     */
    fun decide(
        outcome: AutoSyncOutcome,
        currentOffsetMs: Long,
        started: AutoSyncTarget,
        now: AutoSyncTarget
    ): AutoSyncDecision {
        if (started != now) {
            return AutoSyncDecision(null, "Auto-sync discarded: the video, subtitle or audio track changed")
        }
        return when (outcome) {
            is AutoSyncOutcome.Synced -> AutoSyncDecision(
                outcome.offsetMs,
                "Synced: ${"%+.2f".format(java.util.Locale.ROOT, outcome.offsetMs / 1000.0)}s " +
                    "(${outcome.result.agreeingWindows} of ${outcome.result.totalWindows} checks agree)"
            )
            is AutoSyncOutcome.AlreadyInSync ->
                if (currentOffsetMs != 0L) AutoSyncDecision(0L, "Subtitle file matches the audio. Manual offset reset to 0.")
                else AutoSyncDecision(null, "Already in sync with the audio")
            is AutoSyncOutcome.Drifting ->
                AutoSyncDecision(null, "Timing drifts through the film (different frame rate or cut). Try another subtitle.")
            is AutoSyncOutcome.NotConfident ->
                AutoSyncDecision(null, "Could not match this subtitle to the audio confidently. Nothing changed.")
            is AutoSyncOutcome.Failed -> AutoSyncDecision(null, "Auto-sync unavailable: ${outcome.reason}")
        }
    }
}
