package com.velastudio.teltv.sync

/**
 * Identity of the audio track the player is playing: its position among the audio tracks and its
 * language tag (null or "und" when the file does not say).
 */
data class AudioTrackRef(val ordinal: Int, val language: String?)

/** One audio track as MediaExtractor sees it. [ordinal] counts audio tracks only. */
data class AudioTrackCandidate(val extractorIndex: Int, val ordinal: Int, val language: String?)

data class AudioTrackChoice(val extractorIndex: Int, val reason: Reason) {
    enum class Reason { EXACT, LANGUAGE_FALLBACK, FIRST_FALLBACK }
}

/** Pure track resolution so the choice can be tested without a device. */
object AudioTrackSelection {
    fun normalize(lang: String?): String? {
        val l = lang?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        return if (l.isNullOrEmpty() || l == "und") null else l
    }

    /**
     * Prefers the same position when its language agrees (or either side has no language), then
     * the first track with the same language, then the first audio track. Null when there is none.
     */
    fun resolve(candidates: List<AudioTrackCandidate>, ref: AudioTrackRef?): AudioTrackChoice? {
        if (candidates.isEmpty()) return null
        if (ref == null) return AudioTrackChoice(candidates.first().extractorIndex, AudioTrackChoice.Reason.FIRST_FALLBACK)
        val wanted = normalize(ref.language)
        candidates.firstOrNull { it.ordinal == ref.ordinal }?.let {
            val have = normalize(it.language)
            if (wanted == null || have == null || wanted == have) {
                return AudioTrackChoice(it.extractorIndex, AudioTrackChoice.Reason.EXACT)
            }
        }
        if (wanted != null) {
            candidates.firstOrNull { normalize(it.language) == wanted }?.let {
                return AudioTrackChoice(it.extractorIndex, AudioTrackChoice.Reason.LANGUAGE_FALLBACK)
            }
        }
        return AudioTrackChoice(candidates.first().extractorIndex, AudioTrackChoice.Reason.FIRST_FALLBACK)
    }
}
