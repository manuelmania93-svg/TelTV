package com.velastudio.teltv.worker

import android.content.Context
import androidx.work.*
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.CacheManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CacheTrimWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TelTvApp ?: return Result.success()
        val prefs = CachePrefs(applicationContext)
        val enabled = prefs.autoClearEnabled.first()
        if (!enabled) return Result.success()

        return runCatching {
            val cacheManager = CacheManager(app.telegramClient::execute, applicationContext)
            val limit = prefs.limitBytes.first()
            val freeBefore = applicationContext.filesDir.usableSpace

            var cleaned = false
            if (cacheManager.getCurrentSizeBytes() > limit) {
                cacheManager.trimToLimit(limit)
                cleaned = true
            }
            val freeAfterTrim = applicationContext.filesDir.usableSpace
            if (freeAfterTrim < CacheManager.EMERGENCY_FREE_SPACE_BYTES) {
                cacheManager.clearAllNow()
                cleaned = true
            } else if (cacheManager.maybeProactiveEvict(freeAfterTrim)) {
                // Not yet critical, but below the proactive floor: remove day-old media now so
                // the emergency wipe stays the rare last resort.
                cleaned = true
            }

            if (cleaned) {
                // OptimizeStorage finishes asynchronously inside TDLib; give the freed bytes a
                // moment to show up before measuring what the cleanup actually recovered.
                kotlinx.coroutines.delay(2_000)
                val freed = (applicationContext.filesDir.usableSpace - freeBefore).coerceAtLeast(0L)
                if (freed > 0L) {
                    prefs.recordCleanup(freed, System.currentTimeMillis())
                    Timber.i("Cache cleanup freed %d bytes", freed)
                }
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Timber.e(it, "Cache trim failed, will retry")
                Result.retry()
            }
        )
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "cache_trim_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheTrimWorker>(1, TimeUnit.HOURS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
