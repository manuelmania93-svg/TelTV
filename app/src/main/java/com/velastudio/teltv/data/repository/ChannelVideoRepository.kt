package com.velastudio.teltv.data.repository

import timber.log.Timber

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.velastudio.teltv.data.local.ChannelSyncStateDao
import com.velastudio.teltv.data.local.ChannelSyncStateEntity
import com.velastudio.teltv.data.local.VideoIndexDao
import com.velastudio.teltv.data.local.VideoIndexEntity
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.SourceType
import com.velastudio.teltv.telegram.TelegramClient
import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The "channel has thousands of videos, TV has 2GB RAM" problem has two halves, and both matter:
 *
 * 1. Never hold the whole list in memory. [videoPager] is backed by Room's own PagingSource, so
 *    Compose only ever inflates the couple of pages actually visible/near-visible, no matter how
 *    big the channel is.
 * 2. Never make the person wait on Telegram before they see anything. Reopening a channel paints
 *    instantly from whatever's already cached in [VideoIndexDao] (from a previous visit), while
 *    [ensureNextPage] fetches further/newer pages from TDLib in the background and Room's own
 *    invalidation mechanism pushes the new rows into the already-visible PagingSource.
 *
 * A per-channel [Mutex] stops the "user scrolls fast, fires five overlapping fetches for the same
 * page" pile-up that's the other classic cause of a low-RAM TV box grinding to a halt.
 */
class ChannelVideoRepository(
    private val telegram: TelegramClient,
    private val videoIndexDao: VideoIndexDao,
    private val syncStateDao: ChannelSyncStateDao,
    private val deviceProfile: DeviceCapabilities.Profile
) {
    private val loadLocks = mutableMapOf<Long, Mutex>()
    private fun lockFor(chatId: Long) = synchronized(loadLocks) {
        loadLocks.getOrPut(chatId) { Mutex() }
    }

    /** Paged, memory-bounded stream of a channel's videos, newest first. */
    fun videoPager(chatId: Long, ascending: Boolean = true): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(
                pageSize = deviceProfile.pageSize,
                prefetchDistance = deviceProfile.prefetchDistance,
                enablePlaceholders = false,
                initialLoadSize = deviceProfile.pageSize
            ),
            pagingSourceFactory = {
                if (ascending) videoIndexDao.pagingSource(chatId)
                else videoIndexDao.pagingSourceDesc(chatId)
            }
        ).flow.map { pagingData -> pagingData.map { it.toMediaItem() } }

    /**
     * Call when the grid is getting close to the end of what's loaded (or on first open). Fetches
     * one more page from Telegram and merges it into Room; a no-op once [ChannelSyncStateEntity
     * .fullyLoaded] is true. Safe to call repeatedly/concurrently -- guarded per-channel.
     */
    suspend fun ensureNextPage(chatId: Long, forceContinue: Boolean = false) {
        val lock = lockFor(chatId)
        if (lock.isLocked) return
        lock.withLock {
            val state = syncStateDao.get(chatId) ?: ChannelSyncStateEntity(chatId = chatId)
            if (state.fullyLoaded && !forceContinue) return@withLock

            val page = telegram.getVideoMessages(
                chatId = chatId,
                fromMessageId = state.oldestLoadedMessageId,
                limit = deviceProfile.pageSize
            )
            val fetched = page.items
            if (fetched.isEmpty()) {
                if (page.nextFromMessageId == 0L) {
                    syncStateDao.upsert(state.copy(fullyLoaded = true))
                }
                return@withLock
            }

            val newItems = fetched.filter { videoIndexDao.getByMediaId(it.id) == null }
            if (newItems.isNotEmpty()) {
                val startPosition = state.itemCount
                val entities = newItems.mapIndexed { i, item ->
                    item.toEntity(chatId = chatId, position = startPosition + i)
                }
                videoIndexDao.insertAll(entities)
            }

            val nextCursor = if (page.nextFromMessageId != 0L) page.nextFromMessageId else (fetched.minOfOrNull { it.id.substringAfterLast(':').toLongOrNull() ?: 0L } ?: 0L)
            syncStateDao.upsert(
                state.copy(
                    oldestLoadedMessageId = if (nextCursor > 0L) nextCursor else state.oldestLoadedMessageId,
                    itemCount = state.itemCount + newItems.size,
                    fullyLoaded = page.nextFromMessageId == 0L && fetched.isEmpty(),
                    lastSyncedEpochSec = System.currentTimeMillis() / 1000
                )
            )
        }
    }

    /**
     * Rapidly streams up to [maxBatches] * 100 videos into SQLite in the background
     * without blocking UI rendering. Stops automatically once the folder is fully cached.
     */
    suspend fun preloadRemaining(chatId: Long, maxBatches: Int = 300) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var consecutiveStalls = 0
            for (batch in 0 until maxBatches) {
                val state = syncStateDao.get(chatId)
                val force = (state?.fullyLoaded == true && state.itemCount < 1500)
                val countBefore = state?.itemCount ?: 0
                ensureNextPage(chatId, forceContinue = force)
                val stateAfter = syncStateDao.get(chatId) ?: break
                if (stateAfter.fullyLoaded && !force) break
                if (stateAfter.itemCount == countBefore) {
                    consecutiveStalls++
                    if (consecutiveStalls >= 3) break
                } else {
                    consecutiveStalls = 0
                }
                kotlinx.coroutines.delay(20)
            }
        }
    }

    /**
     * Fetches the newest page from Telegram and merges it into the local cache two ways:
     *
     * 1. **New items** (messageId not yet in Room): prepended at positions 0..N-1, with all
     *    existing rows shifted down by N to keep position a stable newest-first rank. Room's
     *    paging invalidation then pushes the new rows into the already-visible grid.
     *
     * 2. **Title corrections** (item already cached but title changed -- covers the case where
     *    the app updated and fixed the caption-vs-filename title logic): updates just the title
     *    column so stale "Video 12345" or blank-filename rows self-correct on next open without
     *    a full cache wipe.
     *
     * Safe to call on every channel open: the per-channel Mutex means it won't pile up if the
     * user navigates in/out quickly, and it's a no-op if nothing has changed.
     */
    suspend fun refreshNewest(chatId: Long) {
        val lock = lockFor(chatId)
        if (lock.isLocked) return
        lock.withLock {
            if (videoIndexDao.count(chatId) == 0) return@withLock // nothing cached yet; let ensureNextPage handle first load

            val fetched = runCatching {
                telegram.getVideoMessages(chatId = chatId, fromMessageId = 0L, limit = deviceProfile.pageSize).items
            }.onFailure { Timber.w(it, "refreshNewest failed for chatId=%d", chatId) }
                .getOrDefault(emptyList())
            if (fetched.isEmpty()) return@withLock

            // Split into brand-new items vs already-cached items that may have a stale title.
            val cachedIds = fetched.map { it.id }.filter { videoIndexDao.getByMediaId(it) != null }.toSet()
            val brandNew = fetched.filter { it.id !in cachedIds }

            // Fix stale titles on already-cached rows (e.g. after the caption-title fix shipped).
            for (item in fetched) {
                if (item.id in cachedIds) {
                    val cached = videoIndexDao.getByMediaId(item.id) ?: continue
                    if (cached.title != item.title) {
                        videoIndexDao.updateTitle(item.id, item.title)
                    }
                }
            }

            // Prepend genuinely new items: shift all existing positions down, then insert at top.
            if (brandNew.isNotEmpty()) {
                videoIndexDao.shiftPositions(chatId, brandNew.size)
                val newEntities = brandNew.mapIndexed { i, item ->
                    item.toEntity(chatId = chatId, position = i)
                }
                videoIndexDao.insertAll(newEntities)

                // Update sync state: item count grows, but the oldest cursor is unchanged
                // (we only prepended to the top, not appended to the bottom).
                val state = syncStateDao.get(chatId) ?: ChannelSyncStateEntity(chatId = chatId)
                syncStateDao.upsert(
                    state.copy(
                        itemCount = state.itemCount + brandNew.size,
                        lastSyncedEpochSec = System.currentTimeMillis() / 1000
                    )
                )
            }
        }
    }
}

private fun MediaItem.toEntity(chatId: Long, position: Int): VideoIndexEntity {
    val messageId = id.substringAfterLast(':').toLongOrNull() ?: 0L
    return VideoIndexEntity(
        mediaId = id,
        chatId = chatId,
        position = position,
        messageId = messageId,
        title = title,
        subtitle = subtitle,
        category = category,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        thumbnailFileId = thumbnailUrl?.removePrefix("tdlib://thumb/")?.toIntOrNull(),
        streamUrl = streamUrl,
        addedAtEpochSec = addedAtEpochSec
    )
}

private fun VideoIndexEntity.toMediaItem(): MediaItem = MediaItem(
    id = mediaId,
    sourceType = SourceType.TELEGRAM,
    title = title,
    subtitle = subtitle,
    category = category,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    thumbnailUrl = thumbnailFileId?.let { "tdlib://thumb/$it" },
    streamUrl = streamUrl,
    addedAtEpochSec = addedAtEpochSec
)
