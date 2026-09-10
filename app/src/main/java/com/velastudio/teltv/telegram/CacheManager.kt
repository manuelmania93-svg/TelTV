package com.velastudio.teltv.telegram

import org.drinkless.tdlib.TdApi

/**
 * Video cache handling, backed entirely by TDLib's built-in storage optimizer -- no need to
 * reinvent an LRU cache. TDLib already tracks every downloaded file, when it was last accessed,
 * and its size, and `OptimizeStorage` will delete least-recently-used files until total size is
 * back under a given limit. That's exactly "auto-clear once the cache is full".
 *
 * The actual auto-clear toggle/limit setting lives in
 * [com.velastudio.teltv.worker.CachePrefs] (shared by Settings and [CacheTrimWorker]) --
 * this class used to also declare its own DataStore keys for the same setting, but they were
 * never wired to an actual DataStore and were dead code, so they've been removed rather than
 * left as a third, misleading definition.
 */
class CacheManager(private val telegramSend: suspend (TdApi.Function<*>) -> TdApi.Object) {

    companion object {
        const val DEFAULT_LIMIT_BYTES = 500L * 1024 * 1024 // 500MB max streaming cache // 5 GB, matches Manny's default
    }

    /** Current on-disk cache size, for display in Settings. */
    suspend fun getCurrentSizeBytes(): Long {
        val stats = telegramSend(TdApi.GetStorageStatisticsFast()) as TdApi.StorageStatisticsFast
        return stats.filesSize
    }

    /** "Clear cache" button in Settings -- deletes everything TDLib has cached locally. */
    suspend fun clearAllNow() {
        telegramSend(
            TdApi.OptimizeStorage(
                0L,                 // size: shrink to 0 bytes = delete everything cached
                0, 0, 0,
                arrayOf(),          // fileTypes: empty = all types
                longArrayOf(), longArrayOf(),
                false, 20
            )
        )
    }

    /** Runs automatically (see [maybeAutoClear]) to keep the cache under the configured limit. */
    suspend fun trimToLimit(limitBytes: Long) {
        telegramSend(
            TdApi.OptimizeStorage(
                limitBytes,
                0,                  // ttl: 0 = no age-based limit, size-based only
                -1, 0,
                arrayOf(),
                longArrayOf(), longArrayOf(),
                false, 20
            )
        )
    }

    /** Call this periodically (e.g. on app foreground / after each playback session ends). */
    suspend fun maybeAutoClear(limitBytes: Long = DEFAULT_LIMIT_BYTES) {
        if (getCurrentSizeBytes() > limitBytes) {
            trimToLimit(limitBytes)
        }
    }
}
