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
            cacheManager.trimToLimit(limit)
            cacheManager.maybeEmergencyClear(applicationContext.filesDir.usableSpace)
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
