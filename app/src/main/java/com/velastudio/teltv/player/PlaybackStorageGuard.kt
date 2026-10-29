package com.velastudio.teltv.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.data.local.WatchStateEntity
import com.velastudio.teltv.telegram.CacheManager
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import com.velastudio.teltv.worker.CachePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Watches free disk space while a video plays and keeps TDLib's download cache from filling the
 * TV's storage mid-playback. The decision logic lives in [StorageGuardPolicy]; this class is the
 * Android wiring around it.
 *
 * Owned by [PlaybackService] (same process, main-thread player access), started in
 * [PlaybackService.onCreate] and stopped in [PlaybackService.onDestroy]. It respects the same
 * "auto clear cache" toggle as the hourly [com.velastudio.teltv.worker.CacheTrimWorker] --
 * someone who turned automatic clearing off in Settings gets no surprise evictions here either.
 */
@UnstableApi
class PlaybackStorageGuard(
    private val context: Context,
    private val app: TelTvApp,
    private val playerProvider: () -> Player?,
    private val freeSpaceBytes: () -> Long = { context.filesDir.usableSpace },
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastEvictAtMs = 0L
    private var lastEmergencyAtMs = 0L
    private var lastPreflightAtMs = 0L
    private var lastMediaItemId: String? = null

    fun start() {
        scope.launch {
            while (true) {
                delay(StorageGuardPolicy.CHECK_INTERVAL_MS)
                runCatching { checkOnce() }
                    .onFailure { Timber.w(it, "Storage guard check failed") }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }

    /**
     * Called from [PlaybackService] when playback actually fails. A full disk surfaces exactly
     * this way (TDLib can no longer write the next chunk), and the polling loop alone would
     * leave the player stuck on an error. If free space confirms a full disk, this runs the
     * same emergency clear-and-resume immediately instead of waiting for the next check;
     * the position is persisted first and playback resumes from it after re-preparing.
     */
    fun onPlaybackError() {
        scope.launch {
            runCatching { handlePlaybackError() }
                .onFailure { Timber.w(it, "Storage-full recovery after playback error failed") }
        }
    }

    private suspend fun handlePlaybackError() {
        if (!CachePrefs(context).autoClearEnabled.first()) return
        val player = playerProvider() ?: return
        if (player.currentMediaItem == null) return
        val freeBytes = freeSpaceBytes()
        val now = nowMs()
        if (!StorageGuardPolicy.shouldEmergencyOnPlaybackError(freeBytes, lastEmergencyAtMs, now)) return
        lastEmergencyAtMs = now
        lastEvictAtMs = now
        Timber.w(
            "Playback failed with a full disk (%d bytes free); clearing cache and resuming immediately",
            freeBytes
        )
        emergencyClearAndResume(player, freeBytes, reprepare = true)
    }

    private suspend fun checkOnce() {
        val player = playerProvider() ?: return
        val mediaItem = player.currentMediaItem ?: return
        if (!CachePrefs(context).autoClearEnabled.first()) return

        val freeBytes = freeSpaceBytes()
        val now = nowMs()

        // Pre-download check: a new video just started. If free space is already low, make room
        // before the download ramps up instead of waiting for the mid-playback tripwires.
        if (mediaItem.mediaId != lastMediaItemId) {
            lastMediaItemId = mediaItem.mediaId
            if (StorageGuardPolicy.shouldPreflightEvict(freeBytes, lastPreflightAtMs, now)) {
                lastPreflightAtMs = now
                Timber.i(
                    "Making room before playback (%d bytes free); evicting cached files older than %ds",
                    freeBytes, StorageGuardPolicy.EVICT_IMMUNITY_SECONDS
                )
                evictAndRecordFreed(freeBytes)
                return
            }
        }
        when (StorageGuardPolicy.decideAction(freeBytes, lastEvictAtMs, lastEmergencyAtMs, now)) {
            StorageGuardPolicy.Action.NONE -> Unit
            StorageGuardPolicy.Action.EVICT_OLD_FILES -> {
                lastEvictAtMs = now
                Timber.w(
                    "Low storage during playback (%d bytes free); evicting cached files older than %ds",
                    freeBytes, StorageGuardPolicy.EVICT_IMMUNITY_SECONDS
                )
                evictAndRecordFreed(freeBytes)
            }
            StorageGuardPolicy.Action.EMERGENCY_CLEAR_AND_RESUME -> {
                lastEmergencyAtMs = now
                lastEvictAtMs = now
                emergencyClearAndResume(player, freeBytes)
            }
        }
    }

    /**
     * Last resort: the playing file itself is what no longer fits. Snapshot and persist the
     * position first (so even an app death mid-cleanup loses nothing), then stop the download,
     * wipe everything wipeable -- including the already-watched prefix of the current file --
     * and resume from the snapshot. [com.velastudio.teltv.telegram.TdLibDataSource] re-downloads
     * forward from whatever offset it is opened at, so playback continues with a brief rebuffer
     * instead of a permanent "storage full" stall.
     */
    private suspend fun emergencyClearAndResume(player: Player, freeBytes: Long, reprepare: Boolean = false) {
        val mediaItem = player.currentMediaItem ?: return
        val streamUrl = mediaItem.localConfiguration?.uri?.toString() ?: return
        val isTelegram = mediaItem.localConfiguration?.uri?.scheme ==
            TdLibAwareDataSourceFactory.TDLIB_SCHEME
        val fileId = mediaItem.localConfiguration?.uri?.lastPathSegment?.toIntOrNull()

        val wasPlaying = player.playWhenReady
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.coerceAtLeast(0L)

        Timber.w(
            "Critically low storage during playback (%d bytes free); clearing cache and resuming %s at %d ms",
            freeBytes, streamUrl, positionMs
        )

        withContext(Dispatchers.IO) {
            runCatching { persistPosition(streamUrl, positionMs, durationMs) }
                .onFailure { Timber.w(it, "Could not force-save position before cache clear") }
        }

        player.pause()
        if (isTelegram && fileId != null) {
            app.telegramClient.cancelDownload(fileId)
        }

        withContext(Dispatchers.IO) {
            runCatching { cacheManager().clearAllNow() }
                .onFailure { Timber.w(it, "Emergency cache clear failed") }
        }

        // OptimizeStorage finishes asynchronously inside TDLib; give the freed bytes a moment to
        // actually show up, but never wait forever -- resuming also works while cleanup runs.
        val deadline = nowMs() + StorageGuardPolicy.RESUME_WAIT_MS
        while (nowMs() < deadline &&
            freeSpaceBytes() < StorageGuardPolicy.RECOVERED_FREE_BYTES
        ) {
            delay(500)
        }

        player.seekTo(positionMs)
        // After an actual playback error the player sits in STATE_IDLE; a bare play() cannot
        // recover it - the source must be re-prepared (loads forward from the seek position).
        if (reprepare) player.prepare()
        if (wasPlaying) player.play()
    }

    /**
     * Maps the playing `tdlib://file/<id>` URI back to its video_index row (MainActivity keeps
     * streamUrl updated to the live session fileId) and writes the position to watch_state, so
     * "Continue Watching" resumes exactly here even if the process dies during the cleanup.
     * No-op for items the local index does not know -- the player screen's own 5-second
     * position ticker is the fallback in that case.
     */
    private suspend fun persistPosition(streamUrl: String, positionMs: Long, durationMs: Long) {
        val index = app.database.videoIndexDao().getByStreamUrl(streamUrl) ?: return
        val dao = app.database.watchStateDao()
        val existing = dao.get(index.mediaId)
        val base = existing ?: WatchStateEntity(
            mediaId = index.mediaId,
            positionMs = 0L,
            durationMs = durationMs,
            lastWatchedEpochSec = 0L,
            title = index.title,
            subtitle = index.subtitle,
            sourceType = "TELEGRAM",
            chatId = index.chatId,
            thumbnailFileId = index.thumbnailFileId,
            streamUrl = index.streamUrl
        )
        dao.upsert(
            base.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                lastWatchedEpochSec = nowMs() / 1000,
                finished = durationMs > 0 && positionMs >= durationMs * 0.95
            )
        )
    }

    /**
     * Evicts old cached media and, when space actually comes back, records the freed bytes so
     * Settings can show how much TelTV recovered. OptimizeStorage finishes asynchronously
     * inside TDLib, so the measurement waits a moment for the freed bytes to show up.
     */
    private suspend fun evictAndRecordFreed(freeBytesBefore: Long) {
        withContext(Dispatchers.IO) {
            cacheManager().evictAllButRecent(StorageGuardPolicy.EVICT_IMMUNITY_SECONDS)
        }
        delay(2_000)
        val freed = (freeSpaceBytes() - freeBytesBefore).coerceAtLeast(0L)
        if (freed > 0L) {
            withContext(Dispatchers.IO) {
                runCatching { CachePrefs(context).recordCleanup(freed, nowMs()) }
                    .onFailure { Timber.w(it, "Could not record freed space") }
            }
        }
    }

    private fun cacheManager() = CacheManager(app.telegramClient::execute, context)
}
