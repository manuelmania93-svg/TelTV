package com.velastudio.teltv.sync

import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.*

/** Decode three short windows once; reuse their speech/silence patterns for candidate checks. */
@UnstableApi
object SubtitleSpeechProbe {
    const val WINDOW_MS = 25_000L
    fun starts(durationMs: Long): List<Long> = if (durationMs < WINDOW_MS * 3) emptyList() else
        listOf(0.20, 0.50, 0.78).map { (durationMs * it).toLong().coerceAtMost(durationMs - WINDOW_MS) }.distinct()

    suspend fun sample(factory: DataSource.Factory, uri: Uri, durationMs: Long, audio: AudioTrackRef?): List<SpeechWindow> {
        if (Build.VERSION.SDK_INT < 23) return emptyList()
        val samples = mutableListOf<SpeechWindow>()
        val job = currentCoroutineContext()[Job]
        val ds = Media3MediaDataSource(factory, uri)
        try {
            for (start in starts(durationMs)) {
                currentCoroutineContext().ensureActive()
                val rms = runInterruptible(Dispatchers.IO) {
                    AudioEnergyExtractor.extract(ds, start, WINDOW_MS, audio) { job?.isActive == false }
                }
                samples.add(SpeechWindow(start, SpeechDetector.detect(rms)))
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            // Unsupported codec/IO means unknown, never a confident match.
        } finally { ds.close() }
        return samples
    }
}
