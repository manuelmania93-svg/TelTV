package com.velastudio.teltv.player

import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.extractor.text.Subtitle
import androidx.media3.extractor.text.SubtitleDecoder
import androidx.media3.extractor.text.SubtitleInputBuffer
import androidx.media3.extractor.text.SubtitleOutputBuffer

object SubtitleSyncOffset {
    @Volatile
    var offsetUs: Long = 0L
        private set

    fun setOffsetMs(offsetMs: Long) {
        offsetUs = offsetMs * 1_000L
    }
}

@UnstableApi
object SubtitleOffsetDecoderFactory : SubtitleDecoderFactory {
    override fun supportsFormat(format: Format): Boolean =
        SubtitleDecoderFactory.DEFAULT.supportsFormat(format)

    override fun createDecoder(format: Format): SubtitleDecoder =
        OffsetSubtitleDecoder(SubtitleDecoderFactory.DEFAULT.createDecoder(format))
}

@UnstableApi
internal class OffsetSubtitleDecoder(
    private val decoder: SubtitleDecoder
) : SubtitleDecoder {

    override fun getName(): String = "Offset${decoder.name}"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        decoder.setOutputStartTimeUs(outputStartTimeUs - SubtitleSyncOffset.offsetUs)
    }

    override fun dequeueInputBuffer(): SubtitleInputBuffer? = decoder.dequeueInputBuffer()

    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) {
        decoder.queueInputBuffer(inputBuffer)
    }

    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        val output = decoder.dequeueOutputBuffer() ?: return null
        val offset = SubtitleSyncOffset.offsetUs
        return OffsetSubtitleOutputBuffer(output, offset)
    }

    override fun setPositionUs(positionUs: Long) {
        decoder.setPositionUs(positionUs)
    }

    override fun flush() = decoder.flush()

    override fun release() = decoder.release()
}

@UnstableApi
private class OffsetSubtitleOutputBuffer(
    private var source: SubtitleOutputBuffer?,
    private val offsetUs: Long
) : SubtitleOutputBuffer() {

    init {
        val output = requireNotNull(source)
        timeUs = output.timeUs + offsetUs
        skippedOutputBufferCount = output.skippedOutputBufferCount
        shouldBeSkipped = output.shouldBeSkipped
        if (output.isEndOfStream) {
            addFlag(androidx.media3.common.C.BUFFER_FLAG_END_OF_STREAM)
        } else {
            setContent(
                timeUs,
                OffsetSubtitle(output, offsetUs),
                0L
            )
        }
    }

    override fun release() {
        source?.release()
        source = null
        clear()
    }
}

@UnstableApi
internal class OffsetSubtitle(
    private val subtitle: Subtitle,
    private val offsetUs: Long
) : Subtitle {
    override fun getNextEventTimeIndex(timeUs: Long): Int =
        subtitle.getNextEventTimeIndex(timeUs - offsetUs)

    override fun getEventTimeCount(): Int = subtitle.eventTimeCount

    override fun getEventTime(index: Int): Long =
        subtitle.getEventTime(index) + offsetUs

    override fun getCues(timeUs: Long): List<Cue> =
        subtitle.getCues(timeUs - offsetUs)
}
