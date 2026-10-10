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

/** Outcome shown to the user. Nothing is applied unless this is [Synced]. */
sealed interface AutoSyncOutcome {
    data class Synced(val offsetMs: Long, val result: AlignmentResult) : AutoSyncOutcome
    /** Subtitle already matches the audio to within [SubtitleAutoSync.ALREADY_OK_MS]. */
    data class AlreadyInSync(val result: AlignmentResult) : AutoSyncOutcome
    /** Windows disagree: the offset changes over the film (frame-rate drift or different cut). */
    data class Drifting(val result: AlignmentResult) : AutoSyncOutcome
    data class NotConfident(val result: AlignmentResult) : AutoSyncOutcome
    data class Failed(val reason: String) : AutoSyncOutcome
}

/**
 * Aligns a subtitle file to the audio of the item being played, on device (layer 3 of the
 * subtitle pipeline). Several short windows spread across the film are decoded, each is
 * cross-correlated against the subtitle timeline, and the result is only used when the windows
 * agree.
 */
@UnstableApi
object SubtitleAutoSync {
    const val WINDOW_MS = 150_000L
    const val MAX_SHIFT_MS = 60_000L
    const val ALREADY_OK_MS = 150L

    /** Window start positions: spread through the film, skipping the opening credits. */
    fun windowStarts(durationMs: Long): List<Long> {
        if (durationMs <= 0) return emptyList()
        if (durationMs < WINDOW_MS * 2) return listOf((durationMs / 5).coerceAtLeast(0))
        return listOf(0.15, 0.38, 0.62, 0.82)
            .map { (durationMs * it).toLong().coerceAtMost(durationMs - WINDOW_MS) }
    }

    suspend fun run(
        factory: DataSource.Factory,
        mediaUri: Uri,
        subtitleFile: File,
        durationMs: Long,
        audio: AudioTrackRef? = null
    ): AutoSyncOutcome = withContext(Dispatchers.Default) {
        if (Build.VERSION.SDK_INT < 23) return@withContext AutoSyncOutcome.Failed("Needs Android 6 or newer")
        val cues = try {
            SubtitleCueParser.parse(subtitleFile.readText())
        } catch (e: Exception) {
            return@withContext AutoSyncOutcome.Failed("Could not read subtitle file")
        }
        if (cues.size < 20) return@withContext AutoSyncOutcome.Failed("Subtitle has too few lines")
        val starts = windowStarts(durationMs)
        if (starts.isEmpty()) return@withContext AutoSyncOutcome.Failed("Unknown video length")

        val estimates = ArrayList<WindowEstimate>()
        var lastError: String? = null
        val job = currentCoroutineContext()[Job]
        val cancelled = { job?.isActive == false }
        val ds = Media3MediaDataSource(factory, mediaUri)
        try {
            for (start in starts) {
                ensureActive()
                try {
                    // runInterruptible interrupts the blocking reads (TDLib waits) as soon as the job is cancelled.
                    val rms = runInterruptible(Dispatchers.IO) {
                        AudioEnergyExtractor.extract(ds, start, WINDOW_MS, audio, cancelled)
                    }
                    val speech = SpeechDetector.detect(rms)
                    SubtitleAligner.alignWindow(speech, start, cues, MAX_SHIFT_MS)?.let { estimates.add(it) }
                } catch (e: AudioEnergyExtractor.Unsupported) {
                    lastError = e.message
                    // A track the device cannot decode will not decode in the next window either.
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    ensureActive()
                    lastError = "interrupted"
                    break
                } catch (e: java.io.IOException) {
                    ensureActive()
                    lastError = "read error"
                } catch (e: RuntimeException) {
                    // MediaCodec.CodecException, IllegalStateException, IllegalArgumentException from the platform decoder.
                    ensureActive()
                    lastError = "decoder error"
                    break
                }
            }
        } finally {
            ds.close()
        }
        if (estimates.isEmpty()) {
            return@withContext AutoSyncOutcome.Failed(
                if (lastError != null) "Cannot read this audio on this device ($lastError)" else "No usable speech found"
            )
        }
        val result = SubtitleAligner.combine(estimates)
        when {
            result.driftSuspected -> AutoSyncOutcome.Drifting(result)
            !result.confident -> AutoSyncOutcome.NotConfident(result)
            kotlin.math.abs(result.offsetMs) <= ALREADY_OK_MS -> AutoSyncOutcome.AlreadyInSync(result)
            else -> AutoSyncOutcome.Synced(result.offsetMs, result)
        }
    }
}
