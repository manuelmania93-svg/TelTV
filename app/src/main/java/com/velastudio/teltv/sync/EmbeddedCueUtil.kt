package com.velastudio.teltv.sync

/** Pure helpers for turning embedded subtitle samples into cue intervals. JVM tested. */
object EmbeddedCueUtil {
    private const val MIN_MS = 1_000L
    private const val MAX_MS = 7_000L
    private const val MS_PER_CHAR = 65L

    /**
     * Text of a Matroska sample. SRT samples are plain text; ASS samples are
     * "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text" (text may contain commas).
     */
    fun cueText(raw: String, ass: Boolean): String {
        var t = raw
        if (ass) {
            var commas = 0
            var i = 0
            while (i < t.length && commas < 8) { if (t[i] == ',') commas++; i++ }
            t = if (commas == 8) t.substring(i) else t
        }
        return t.replace(Regex("""\{[^}]*\}"""), "")
            .replace(Regex("<[^>]*>"), "")
            .replace("\\N", " ").replace("\\n", " ").replace('\n', ' ')
            .trim()
    }

    /**
     * MediaExtractor gives the start of a subtitle sample but not how long it is shown, so the end
     * is estimated from the text length. Only the start times are exact; this is why the result is
     * only used when several windows agree.
     */
    fun estimatedDurationMs(text: String): Long = (text.length * MS_PER_CHAR).coerceIn(MIN_MS, MAX_MS)

    /** 10 ms on/off signal for [startMs, startMs + lengthMs) from cues on the media clock. */
    fun rasterize(cues: List<SubtitleCue>, startMs: Long, lengthMs: Long): FloatArray {
        val n = (lengthMs / SpeechDetector.FRAME_MS).toInt()
        val out = FloatArray(n)
        for (c in cues) {
            val a = ((c.startMs - startMs) / SpeechDetector.FRAME_MS).toInt().coerceAtLeast(0)
            val b = ((c.endMs - startMs) / SpeechDetector.FRAME_MS).toInt().coerceAtMost(n)
            for (i in a until b) out[i] = 1f
        }
        return out
    }

    /** Samples with the same start and text (karaoke/multi-layer ASS) collapse to one cue. */
    fun toCues(samples: List<Pair<Long, String>>): List<SubtitleCue> {
        val seen = HashSet<Pair<Long, String>>()
        val out = ArrayList<SubtitleCue>()
        for ((start, text) in samples.sortedBy { it.first }) {
            if (text.isBlank() || !seen.add(start to text)) continue
            out.add(SubtitleCue(start, start + estimatedDurationMs(text)))
        }
        return out
    }
}
