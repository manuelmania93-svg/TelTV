package com.velastudio.teltv.sync

import android.net.Uri
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Aligns a downloaded subtitle to the video's own embedded text subtitle (assumed to match the
 * video), whatever language either is in. Same window idea as the audio sync: a few windows
 * spread through the film are read, compared, and the result is only used when they agree.
 * Returns null when the video has no readable text subtitle track, so the caller can use audio.
 */
@UnstableApi
object EmbeddedReferenceSync {
    suspend fun run(
        factory: DataSource.Factory,
        mediaUri: Uri,
        subtitleFile: File,
        durationMs: Long
    ): AutoSyncOutcome? = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < 23) return@withContext null
        val cues = try {
            SubtitleCueParser.parse(subtitleFile.readText())
        } catch (e: Exception) {
            return@withContext AutoSyncOutcome.Failed("Could not read subtitle file")
        }
        if (cues.size < 20) return@withContext AutoSyncOutcome.Failed("Subtitle has too few lines")
        val starts = SubtitleAutoSync.windowStarts(durationMs)
        if (starts.isEmpty()) return@withContext AutoSyncOutcome.Failed("Unknown video length")

        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        val ds = Media3MediaDataSource(factory, mediaUri)
        val estimates = ArrayList<WindowEstimate>()
        try {
            for (start in starts) {
                ensureActive()
                try {
                    val reference = runInterruptible(Dispatchers.IO) {
                        EmbeddedSubtitleReader.readWindow(ds, start, SubtitleAutoSync.WINDOW_MS, cancelled)
                    }
                    if (reference.size < 5) continue
                    val signal = EmbeddedCueUtil.rasterizeStarts(reference, start, SubtitleAutoSync.WINDOW_MS)
                    SubtitleAligner.alignWindow(signal, start, cues, SubtitleAutoSync.MAX_SHIFT_MS, startsOnly = true)?.let { estimates.add(it) }
                } catch (e: EmbeddedSubtitleReader.NoEmbeddedSubtitle) {
                    return@withContext null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    ensureActive()
                    return@withContext null
                } catch (e: java.io.IOException) {
                    ensureActive()
                } catch (e: RuntimeException) {
                    // Platform extractor problems: treat as "no usable embedded track".
                    ensureActive()
                    return@withContext null
                }
            }
        } finally {
            ds.close()
        }
        if (estimates.isEmpty()) return@withContext null
        val result = SubtitleAligner.combine(estimates)
        when {
            result.driftSuspected -> AutoSyncOutcome.Drifting(result)
            !result.confident -> AutoSyncOutcome.NotConfident(result)
            kotlin.math.abs(result.offsetMs) <= SubtitleAutoSync.ALREADY_OK_MS -> AutoSyncOutcome.AlreadyInSync(result)
            else -> AutoSyncOutcome.Synced(result.offsetMs, result)
        }
    }
}
