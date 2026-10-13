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

    @Volatile
    var scale: Double = 1.0
        private set

    fun setOffsetMs(offsetMs: Long) {
        offsetUs = offsetMs * 1_000L
    }

    fun setScale(newScale: Double) {
        scale = SubtitleTiming.clampScale(newScale)
    }

    fun set(sync: SubtitleSync) {
        setOffsetMs(sync.offsetMs)
        setScale(sync.scale)
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
        decoder.setOutputStartTimeUs(outputStartTimeUs)
    }

    override fun dequeueInputBuffer(): SubtitleInputBuffer? = decoder.dequeueInputBuffer()

    override fun queueInputBuffer(inputBuffer: SubtitleInputBuffer) {
        decoder.queueInputBuffer(inputBuffer)
    }

    override fun dequeueOutputBuffer(): SubtitleOutputBuffer? {
        val output = decoder.dequeueOutputBuffer() ?: return null
        return OffsetSubtitleOutputBuffer(output, SubtitleSyncOffset.offsetUs, SubtitleSyncOffset.scale)
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
    private val offsetUs: Long,
    private val scale: Double
) : SubtitleOutputBuffer() {

    init {
        val output = requireNotNull(source)
        timeUs = SubtitleTiming.mapUs(output.timeUs, scale, offsetUs)
        skippedOutputBufferCount = output.skippedOutputBufferCount
        shouldBeSkipped = output.shouldBeSkipped
        if (output.isEndOfStream) {
            addFlag(androidx.media3.common.C.BUFFER_FLAG_END_OF_STREAM)
        } else {
            setContent(
                timeUs,
                OffsetSubtitle(output, offsetUs, scale),
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
    private val offsetUs: Long,
    private val scale: Double = 1.0
) : Subtitle {
    override fun getNextEventTimeIndex(timeUs: Long): Int =
        subtitle.getNextEventTimeIndex(SubtitleTiming.unmapUs(timeUs, scale, offsetUs))

    override fun getEventTimeCount(): Int = subtitle.eventTimeCount

    override fun getEventTime(index: Int): Long =
        SubtitleTiming.mapUs(subtitle.getEventTime(index), scale, offsetUs)

    override fun getCues(timeUs: Long): List<Cue> =
        subtitle.getCues(SubtitleTiming.unmapUs(timeUs, scale, offsetUs))
}
