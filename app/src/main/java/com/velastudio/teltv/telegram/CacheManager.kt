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
        context?.cacheDir?.listFiles()?.forEach { cacheEntry ->
            if (cacheEntry.name != "subtitles" && !cacheEntry.deleteRecursively()) {
                throw IOException("Could not clear app cache entry: ${cacheEntry.name}")
            }
        }
    }

    suspend fun trimToLimit(limitBytes: Long) {
        require(limitBytes >= 0L) { "Cache limit must not be negative" }
        val appCacheSize = context?.cacheDir?.let(::directorySizeBytes) ?: 0L
        val telegramLimitBytes = telegramLimitFor(limitBytes, appCacheSize)
        telegramSend(
            TdApi.OptimizeStorage(
                telegramLimitBytes, 0, -1, 0,
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
