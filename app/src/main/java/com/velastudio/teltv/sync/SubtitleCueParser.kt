package com.velastudio.teltv.sync

/** One subtitle cue's display interval, in milliseconds on the subtitle file's own clock. */
data class SubtitleCue(val startMs: Long, val endMs: Long)

/**
 * Pulls cue timings out of SRT and WebVTT text. Only timing matters for audio alignment, so cue
 * text is ignored. Lines that do not look like a timing line are skipped, which keeps this
 * tolerant of the messy files that online providers return.
 */
object SubtitleCueParser {
    private val timing = Regex(
        """(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})"""
    )

    fun parse(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (line in text.lineSequence()) {
            val m = timing.find(line) ?: continue
            val g = m.groupValues
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])
            if (end > start) cues.add(SubtitleCue(start, end))
        }
        return cues
    }

    private fun toMs(h: String, m: String, s: String, frac: String): Long {
        val hours = if (h.isEmpty()) 0L else h.toLong()
        // "5" after the separator means 500 ms, "05" means 50 ms: pad on the right.
        val millis = frac.padEnd(3, '0').toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }
}
