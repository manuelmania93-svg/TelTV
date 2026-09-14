package com.velastudio.teltv.telegram

import android.content.Context
import org.drinkless.tdlib.TdApi

class CacheManager(
    private val telegramSend: suspend (TdApi.Function<*>) -> TdApi.Object,
    private val context: Context? = null
) {
    companion object {
        const val DEFAULT_LIMIT_BYTES = 1024L * 1024 * 1024 // 1 GB default
        const val EMERGENCY_FREE_SPACE_BYTES = 512L * 1024 * 1024
    }

    suspend fun getCurrentSizeBytes(): Long {
        val tdlibSize = runCatching {
            val stats = telegramSend(TdApi.GetStorageStatisticsFast()) as TdApi.StorageStatisticsFast
            stats.filesSize
        }.getOrDefault(0L)

        val appCacheSize = runCatching {
            context?.cacheDir?.walkTopDown()?.filter { it.isFile }?.map { it.length() }?.sum() ?: 0L
        }.getOrDefault(0L)

        return tdlibSize + appCacheSize
    }

    suspend fun clearAllNow() {
        runCatching {
            telegramSend(
                TdApi.OptimizeStorage(
                    0L, 0, 0, 0,
                    arrayOf(),
                    longArrayOf(), longArrayOf(),
                    false, 50
                )
            )
        }
        runCatching {
            context?.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    suspend fun trimToLimit(limitBytes: Long) {
        runCatching {
            telegramSend(
                TdApi.OptimizeStorage(
                    limitBytes, 0, -1, 0,
                    arrayOf(),
                    longArrayOf(), longArrayOf(),
                    false, 50
                )
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
}
