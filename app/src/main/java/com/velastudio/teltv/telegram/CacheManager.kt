package com.velastudio.teltv.telegram

import android.content.Context
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.io.IOException

class CacheManager(
    private val telegramSend: suspend (TdApi.Function<*>) -> TdApi.Object,
    private val context: Context? = null
) {
    companion object {
        const val DEFAULT_LIMIT_BYTES = 1024L * 1024 * 1024 // 1 GB default
        const val EMERGENCY_FREE_SPACE_BYTES = 512L * 1024 * 1024

        /** Below this much free space the hourly worker starts making room before it gets critical. */
        const val PROACTIVE_FREE_SPACE_BYTES = 2L * 1024 * 1024 * 1024

        /** Proactive eviction only removes cached media untouched for at least a day. */
        const val PROACTIVE_IMMUNITY_SECONDS = 24 * 60 * 60

        /**
         * Deletes every entry in the app's cache dir except downloaded subtitles, which are
         * user data (fetched per video, painful to lose) and never part of any cleanup. Pure
         * file operation so the protection is unit-testable on the JVM; throws IOException
         * naming the entry when one cannot be removed, so a half-cleared cache is never
         * reported as cleared.
         */
        internal fun clearAppCacheExceptSubtitles(cacheDir: File) {
            cacheDir.listFiles()?.forEach { cacheEntry ->
                if (cacheEntry.name != "subtitles" && !cacheEntry.deleteRecursively()) {
                    throw IOException("Could not clear app cache entry: ${cacheEntry.name}")
                }
            }
        }

        internal fun telegramLimitFor(totalLimitBytes: Long, appCacheBytes: Long): Long {
            require(totalLimitBytes >= 0L) { "Cache limit must not be negative" }
            require(appCacheBytes >= 0L) { "App cache size must not be negative" }
            return (totalLimitBytes - appCacheBytes).coerceAtLeast(0L)
        }
    }

    suspend fun getCurrentSizeBytes(): Long {
        val result = telegramSend(TdApi.GetStorageStatisticsFast())
        val tdlibSize = (result as? TdApi.StorageStatisticsFast)?.filesSize
            ?: throw IOException("TDLib returned unexpected storage statistics: ${result.javaClass.simpleName}")
        val appCacheSize = context?.cacheDir?.let(::directorySizeBytes) ?: 0L
        return safeAdd(tdlibSize, appCacheSize)
    }

    suspend fun clearAllNow() {
        telegramSend(
            TdApi.OptimizeStorage(
                0L, 0, 0, 0,
                arrayOf(),
                longArrayOf(), longArrayOf(),
                false, 50
            )
        )
        context?.cacheDir?.let(::clearAppCacheExceptSubtitles)
    }

    /** Idle maintenance only. Never reset databases or delete media paths directly. */
    suspend fun clearReusableNow(): Boolean = idleMaintenance {
        clearAllNow()
        optimizeThumbnails(0L, 0)
    }

    /** TTL and size are independent limits. Thumbnail budget is part of the existing total. */
    suspend fun sweepReusable(limitBytes: Long, retentionSeconds: Long): Boolean = idleMaintenance {
        require(limitBytes >= 0)
        val ttl = if (retentionSeconds == Long.MAX_VALUE) Int.MAX_VALUE else
            retentionSeconds.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val thumbnailsLimit = minOf(60L * 1024 * 1024, limitBytes)
        val appCache = context?.cacheDir?.let(::directorySizeBytes) ?: 0L
        telegramSend(TdApi.OptimizeStorage(
            (limitBytes - appCache - thumbnailsLimit).coerceAtLeast(0L), ttl, Int.MAX_VALUE, 60,
            arrayOf(), longArrayOf(), longArrayOf(), false, 50
        ))
        optimizeThumbnails(thumbnailsLimit, ttl)
    }

    private suspend fun optimizeThumbnails(limit: Long, ttl: Int) {
        telegramSend(TdApi.OptimizeStorage(
            limit, ttl, Int.MAX_VALUE, if (limit == 0L && ttl == 0) 0 else 60,
            arrayOf<TdApi.FileType>(TdApi.FileTypeThumbnail()),
            longArrayOf(), longArrayOf(), false, 50
        ))
    }

    private suspend fun idleMaintenance(action: suspend () -> Unit): Boolean {
        val gate = StorageUseGate.shared
        if (!gate.tryBeginCleanup()) return false
        // Cancellation must not release the gate while TDLib is still deleting files.
        // Its request has no cancellable deletion handle. Wait for the response first.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
            try { action(); true } finally { gate.endCleanup() }
        }
    }

    suspend fun trimToLimit(limitBytes: Long) {
        require(limitBytes >= 0L) { "Cache limit must not be negative" }
        if (getCurrentSizeBytes() <= limitBytes) return
        val appCacheSize = context?.cacheDir?.let(::directorySizeBytes) ?: 0L
        val telegramLimitBytes = telegramLimitFor(limitBytes, appCacheSize)
        telegramSend(
            TdApi.OptimizeStorage(
                telegramLimitBytes, Int.MAX_VALUE, Int.MAX_VALUE, 60,
                arrayOf(),
                longArrayOf(), longArrayOf(),
                false, 50
            )
        )
        if (appCacheSize > limitBytes) {
            Timber.w(
                "App cache (%d bytes) exceeds configured combined cache limit (%d bytes); preserving subtitle files",
                appCacheSize,
                limitBytes
            )
        }
    }

    /**
     * Evicts every TDLib-cached file that has not been used in the last [immunitySeconds]
     * seconds, regardless of how big the cache currently is. Unlike [trimToLimit], which only
     * acts once a configured size limit is exceeded, this is the playback guard's "make room now"
     * call: the immunity window is what protects the file currently being watched (TDLib keeps
     * downloading it while it plays), everything older is deleted oldest-first by TDLib.
     */
    suspend fun evictAllButRecent(immunitySeconds: Int) {
        require(immunitySeconds >= 0) { "Immunity window must not be negative" }
        telegramSend(
            TdApi.OptimizeStorage(
                0L, Int.MAX_VALUE, Int.MAX_VALUE, immunitySeconds,
                arrayOf(),
                longArrayOf(), longArrayOf(),
                false, 50
            )
        )
    }

    suspend fun maybeAutoClear(limitBytes: Long = DEFAULT_LIMIT_BYTES) {
        if (getCurrentSizeBytes() > limitBytes) {
            trimToLimit(limitBytes)
        }
    }

    suspend fun maybeEmergencyClear(usableSpaceBytes: Long) {
        if (usableSpaceBytes < EMERGENCY_FREE_SPACE_BYTES) {
            clearAllNow()
        }
    }

    /**
     * Frees space before storage gets critical: once the device drops below
     * [PROACTIVE_FREE_SPACE_BYTES], evict cached media that has not been touched in
     * [PROACTIVE_IMMUNITY_SECONDS], so the [EMERGENCY_FREE_SPACE_BYTES] wipe stays the rare
     * last resort. Returns true when an eviction was requested. Only TDLib-cached media files
     * are removed; login, favorites, watch history and subtitles are never touched.
     */
    suspend fun maybeProactiveEvict(freeSpaceBytes: Long): Boolean {
        if (freeSpaceBytes >= PROACTIVE_FREE_SPACE_BYTES) return false
        evictAllButRecent(PROACTIVE_IMMUNITY_SECONDS)
        return true
    }

    private fun directorySizeBytes(directory: File): Long {
        if (!directory.exists()) return 0L
        if (!directory.isDirectory) return directory.length()
        return directory.walkTopDown()
            .filter { it.isFile }
            .fold(0L) { total, file -> safeAdd(total, file.length()) }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }
}
