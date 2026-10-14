package com.velastudio.teltv.sync

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RequiresApi

/**
 * Reads the cue start times of the video's own text subtitle track in a short window, through
 * the platform [MediaExtractor]. Nothing outside the window is read (the extractor seeks).
 *
 * DEVICE BEHAVIOUR IS UNVERIFIED: whether the platform exposes text tracks, and for which
 * containers, differs by Android version and file. When there is no usable track this throws
 * [NoEmbeddedSubtitle] and the caller falls back to audio.
 */
@RequiresApi(23)
object EmbeddedSubtitleReader {
    class NoEmbeddedSubtitle(message: String) : Exception(message)

    private val textMimes = setOf("application/x-subrip", "text/x-ssa", "text/vtt", "text/plain", "application/x-ass")

    /** True when the file has at least one text subtitle track the extractor can read. */
    fun hasTextTrack(source: MediaDataSource): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source)
            findTrack(extractor) >= 0
        } catch (e: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun readWindow(source: MediaDataSource, startMs: Long, lengthMs: Long, isCancelled: () -> Boolean): List<SubtitleCue> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source)
            val track = findTrack(extractor)
            if (track < 0) throw NoEmbeddedSubtitle("no text subtitle track")
            val ass = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) in setOf("text/x-ssa", "application/x-ass")
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val endUs = (startMs + lengthMs) * 1000
            val buf = java.nio.ByteBuffer.allocate(64 * 1024)
            val samples = ArrayList<Pair<Long, String>>()
            while (true) {
                if (isCancelled()) throw InterruptedException("cancelled")
                buf.clear()
                val n = extractor.readSampleData(buf, 0)
                val t = extractor.sampleTime
                if (n < 0 || t < 0 || t > endUs) break
                val bytes = ByteArray(n)
                buf.position(0)
                buf.get(bytes, 0, n)
                val text = EmbeddedCueUtil.cueText(String(bytes, Charsets.UTF_8), ass)
                samples.add(t / 1000 to text)
                extractor.advance()
            }
            return EmbeddedCueUtil.toCues(samples)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.lowercase()
            if (mime != null && mime in textMimes) return i
        }
        return -1
    }
}
