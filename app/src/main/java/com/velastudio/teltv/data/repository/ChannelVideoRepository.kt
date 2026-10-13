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
    private val fetchPage: suspend (Long, Long, Int) -> com.velastudio.teltv.telegram.VideoMessagesResult,
    private val videoIndexDao: VideoIndexDao,
    private val syncStateDao: ChannelSyncStateDao,
    private val deviceProfile: DeviceCapabilities.Profile
) {
    constructor(telegram: TelegramClient, videoIndexDao: VideoIndexDao, syncStateDao: ChannelSyncStateDao,
                deviceProfile: DeviceCapabilities.Profile) : this(
        { chat, cursor, limit -> telegram.getVideoMessages(chat, cursor, limit) },
        videoIndexDao, syncStateDao, deviceProfile)

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
                initialLoadSize = deviceProfile.pageSize,
                maxSize = maxOf(deviceProfile.pageSize * 3, deviceProfile.pageSize + 2 * deviceProfile.prefetchDistance)
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

            val page = try {
                fetchPage(chatId, state.oldestLoadedMessageId, deviceProfile.pageSize)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Failed to synchronize videos for chatId=%d", chatId)
                return@withLock
            }
            val fetched = page.items
            if (fetched.isEmpty()) {
                if (page.nextFromMessageId == 0L) {
                    syncStateDao.upsert(
                        state.copy(
                            fullyLoaded = true,
                            lastSyncedEpochSec = System.currentTimeMillis() / 1000
                        )
                    )
                } else if (page.nextFromMessageId != state.oldestLoadedMessageId) {
                    syncStateDao.upsert(
                        state.copy(
                            oldestLoadedMessageId = page.nextFromMessageId,
                            lastSyncedEpochSec = System.currentTimeMillis() / 1000
                        )
                    )
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
                kotlinx.coroutines.delay(120)
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

            var state = syncStateDao.get(chatId) ?: ChannelSyncStateEntity(chatId = chatId, itemCount = videoIndexDao.count(chatId))
            if (state.refreshBoundary == 0L) {
                state = state.copy(refreshBoundary = videoIndexDao.newestMessageId(chatId) ?: return@withLock,
                    refreshCursor = 0L, refreshInserted = 0)
            }
            // Each page is committed with its checkpoint. Cancellation/errors cannot hide a gap:
            // the next open resumes behind the already imported prefix, not from its new head.
            // Bound both memory and work per open on small TVs; later opens continue the checkpoint.
            repeat(50) {
                val page = try {
                    fetchPage(chatId, state.refreshCursor, deviceProfile.pageSize)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "refreshNewest failed for chatId=%d", chatId)
                    return@withLock
                }
                val fetched = page.items
                val brandNew = mutableListOf<MediaItem>()
                for (item in fetched) {
                    val cached = videoIndexDao.getByMediaId(item.id)
                    if (cached == null && (item.id.substringAfterLast(':').toLongOrNull() ?: 0L) > state.refreshBoundary) brandNew += item
                    else if (cached != null && cached.title != item.title) videoIndexDao.updateTitle(item.id, item.title)
                }
                val reachedBoundary = fetched.any { it.id.substringAfterLast(':').toLongOrNull()?.let { id -> id <= state.refreshBoundary } == true }
                val done = reachedBoundary || page.nextFromMessageId == 0L
                val stalled = !done && page.nextFromMessageId == state.refreshCursor
                val offset = state.refreshInserted
                val updated = state.copy(
                    itemCount = state.itemCount + brandNew.size,
                    lastSyncedEpochSec = System.currentTimeMillis() / 1000,
                    refreshCursor = if (done) 0L else page.nextFromMessageId,
                    refreshBoundary = if (done) 0L else state.refreshBoundary,
                    refreshInserted = if (done) 0 else offset + brandNew.size
                )
                videoIndexDao.commitRefreshPage(chatId, offset,
                    brandNew.mapIndexed { index, item -> item.toEntity(chatId, offset + index) }, updated)
                state = updated
                if (done || stalled) return@withLock
                kotlinx.coroutines.delay(120)
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
