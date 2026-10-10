package com.velastudio.teltv.sync

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.nio.ByteOrder

/**
 * Decodes a stretch of a media file's first audio track with the platform decoders and reduces
 * it to 10 ms RMS frames. Nothing is kept except the frame energies; no resampling is needed.
 */
@RequiresApi(23)
object AudioEnergyExtractor {
    class Unsupported(message: String) : Exception(message)

    /**
     * @return frame energies for [startMs, startMs + lengthMs), or throws [Unsupported] when the
     * track cannot be decoded on this device (for example some AC-3/DTS tracks) or too little
     * audio came out.
     */
    fun extract(
        source: MediaDataSource,
        startMs: Long,
        lengthMs: Long,
        audio: AudioTrackRef?,
        isCancelled: () -> Boolean
    ): FloatArray {
        val extractor = MediaExtractor()
        var opened: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            var ordinal = 0
            val candidates = ArrayList<AudioTrackCandidate>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
                    candidates.add(AudioTrackCandidate(i, ordinal++, lang))
                }
            }
            val choice = AudioTrackSelection.resolve(candidates, audio) ?: throw Unsupported("no audio track")
            if (choice.reason != AudioTrackChoice.Reason.EXACT) {
                Timber.w("Audio track %s not found exactly (%s of %d audio tracks); using %s, extractor track %d",
                    audio, choice.reason, candidates.size, choice.reason, choice.extractorIndex)
            }
            val track = choice.extractorIndex
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val codec: MediaCodec = try {
                MediaCodec.createDecoderByType(mime).also { opened = it; it.configure(format, null, null, 0); it.start() }
            } catch (e: Exception) {
                throw Unsupported("no decoder for $mime")
            }

            val acc = FrameEnergyAccumulator(startMs, lengthMs)
            val info = MediaCodec.BufferInfo()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false
            var inputDone = false
            var outputDone = false
            var mono = FloatArray(8192)
            val endUs = (startMs + lengthMs) * 1000

            while (!outputDone && !acc.finished) {
                if (isCancelled()) throw InterruptedException("cancelled")
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t > endUs + 1_000_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val bytes = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                            bytes.position(info.offset).limit(info.offset + info.size)
                            val bytesPerSample = if (pcmFloat) 4 else 2
                            val frames = info.size / (bytesPerSample * channels)
                            if (mono.size < frames) mono = FloatArray(frames)
                            for (f in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) {
                                    s += if (pcmFloat) bytes.float else bytes.short / 32768f
                                }
                                mono[f] = s / channels
                            }
                            acc.add(mono, frames, sampleRate, info.presentationTimeUs)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (acc.coverage() < 0.6) throw Unsupported("decoded only ${(acc.coverage() * 100).toInt()}% of the window")
            return acc.frames()
        } finally {
            runCatching { opened?.stop() }
            runCatching { opened?.release() }
            runCatching { extractor.release() }
        }
    }
}
