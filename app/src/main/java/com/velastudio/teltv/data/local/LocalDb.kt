package com.velastudio.teltv.data.local

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val type: String,
    val displayName: String,
    val host: String?,
    val port: Int?,
    val shareName: String?,
    val folder: String?,
    val serverUrl: String?,
    val username: String?,
    val password: String?,
    val domain: String?
)

/**
 * Watch state now carries enough display metadata (title/thumbnail/etc.) to render the
 * "Continue Watching" row on Home instantly, without re-hitting Telegram just to redraw a row of
 * cards. That round-trip is exactly the kind of thing that stalls on a 2GB-RAM TV box.
 */
@Entity(tableName = "watch_state")
data class WatchStateEntity(
    @PrimaryKey val mediaId: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatchedEpochSec: Long,
    val title: String = "",
    val subtitle: String? = null,
    val sourceType: String = "TELEGRAM",
    val chatId: Long? = null,
    val thumbnailFileId: Int? = null,
    val streamUrl: String = "",
    // true once positionMs is within FINISHED_THRESHOLD of durationMs -- excluded from
    // "Continue Watching" (nothing left to continue) but still counts for "recently watched".
    val finished: Boolean = false
)

/** Local mirror of a "favorite"/watchlist item, same idea as VelaTV's Watchlist tab. */
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val mediaId: String,
    val addedEpochSec: Long
)

/**
 * Locally cached copy of a channel's video list, keyed by a stable `position` (its rank in
 * Telegram's own newest-first message order) rather than message id, so Room's PagingSource can
 * hand pages straight to the UI -- including on a cold app start, before TDLib has even finished
 * connecting. This is the fix for "channel has thousands of videos + 2GB RAM TV": we never hold
 * more than one page of [com.velastudio.teltv.data.model.MediaItem] in memory at a time, and
 * reopening a channel you've browsed before paints instantly from disk while a background refresh
 * quietly checks for anything new.
 */
@Entity(
    tableName = "video_index",
    indices = [Index(value = ["chatId", "position"])]
)
data class VideoIndexEntity(
    @PrimaryKey val mediaId: String,
    val chatId: Long,
    val position: Int,          // 0 = newest; stable ordering key for paging
    val messageId: Long,        // TDLib message id, used as the pagination cursor
    val title: String,
    val subtitle: String?,
    val category: String?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val thumbnailFileId: Int?,
    val streamUrl: String,
    val addedAtEpochSec: Long
)

/** One row per channel: where pagination left off, and whether we've reached the end. */
@Entity(tableName = "channel_sync_state")
data class ChannelSyncStateEntity(
    @PrimaryKey val chatId: Long,
    val oldestLoadedMessageId: Long = 0L,
    val itemCount: Int = 0,
    val fullyLoaded: Boolean = false,
    val lastSyncedEpochSec: Long = 0L
)

/** Recent on-device search terms, shown as quick-pick chips so typing on a remote is rare. */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val lastUsedEpochSec: Long
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources")
    suspend fun getAll(): List<SourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WatchStateDao {
    @Query("SELECT * FROM watch_state WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): WatchStateEntity?

    @Query(
        """SELECT * FROM watch_state WHERE finished = 0 AND positionMs > 15000
           ORDER BY lastWatchedEpochSec DESC LIMIT :limit"""
    )
    suspend fun continueWatching(limit: Int = 20): List<WatchStateEntity>

    @Query("SELECT * FROM watch_state ORDER BY lastWatchedEpochSec DESC LIMIT :limit")
    suspend fun recentlyWatched(limit: Int = 20): List<WatchStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: WatchStateEntity)

    @Query("DELETE FROM watch_state WHERE mediaId = :mediaId")
    suspend fun remove(mediaId: String)
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY addedEpochSec DESC")
    suspend fun getAll(): List<WatchlistEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: WatchlistEntity)

    @Query("DELETE FROM watchlist WHERE mediaId = :mediaId")
    suspend fun remove(mediaId: String)
}

@Dao
interface VideoIndexDao {
    /**
     * Room generates a `PagingSource` implementation for this query. Compose's `collectAsLazyPagingItems()`
     * then only ever inflates the ~2-3 pages actually on/near screen -- scrolling through a
     * 5,000-video channel costs the same memory as scrolling through a 50-video one.
     */
    @Query("SELECT * FROM video_index WHERE chatId = :chatId ORDER BY position ASC")
    fun pagingSource(chatId: Long): PagingSource<Int, VideoIndexEntity>

    @Query("SELECT COUNT(*) FROM video_index WHERE chatId = :chatId")
    suspend fun count(chatId: Long): Int

    @Query("SELECT * FROM video_index WHERE mediaId = :mediaId")
    suspend fun getByMediaId(mediaId: String): VideoIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VideoIndexEntity>)

    /** Patches the title on an existing row without disturbing its position or cursor fields. */
    @Query("UPDATE video_index SET title = :title WHERE mediaId = :mediaId")
    suspend fun updateTitle(mediaId: String, title: String)

    /** messageId of position=0 (the newest cached item), used as the refresh cursor. */
    @Query("SELECT messageId FROM video_index WHERE chatId = :chatId ORDER BY position ASC LIMIT 1")
    suspend fun newestMessageId(chatId: Long): Long?

    /** Shifts all existing positions down to make room for new items prepended at the top. */
    @Query("UPDATE video_index SET position = position + :shift WHERE chatId = :chatId")
    suspend fun shiftPositions(chatId: Long, shift: Int)

    @Query("DELETE FROM video_index WHERE chatId = :chatId")
    suspend fun clearChannel(chatId: Long)

    /** Cheap local title search (instant, no network) while a fuller server search runs alongside. */
    @Query(
        """SELECT * FROM video_index WHERE title LIKE '%' || :query || '%'
           ORDER BY addedAtEpochSec DESC LIMIT :limit"""
    )
    suspend fun searchLocal(query: String, limit: Int = 50): List<VideoIndexEntity>
}

@Dao
interface ChannelSyncStateDao {
    @Query("SELECT * FROM channel_sync_state WHERE chatId = :chatId")
    suspend fun get(chatId: Long): ChannelSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChannelSyncStateEntity)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY lastUsedEpochSec DESC LIMIT :limit")
    suspend fun recent(limit: Int = 10): List<SearchHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Database(
    entities = [
        SourceEntity::class,
        WatchStateEntity::class,
        WatchlistEntity::class,
        VideoIndexEntity::class,
        ChannelSyncStateEntity::class,
        SearchHistoryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class TelTvDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun watchStateDao(): WatchStateDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun videoIndexDao(): VideoIndexDao
    abstract fun channelSyncStateDao(): ChannelSyncStateDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile private var INSTANCE: TelTvDatabase? = null

        fun get(context: Context): TelTvDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TelTvDatabase::class.java,
                    "teltv.db"
                )
                    // Pre-1.0 scaffold, no real users/data yet -- destructive migration is the
                    // right tradeoff over hand-writing Migration objects for a schema that's
                    // still moving. Switch to real Migrations before shipping.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
