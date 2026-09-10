package com.velastudio.teltv.worker

import android.content.Context
import androidx.work.*
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.CacheManager
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Runs the emergency cache check periodically in the background (not just when Settings
 * happens to be open), so the TV can recover when its storage becomes critically full.
 * stuck downloading-then-immediately-evicting on the next launch. WorkManager, not a raw
 * coroutine/alarm, so this survives process death and respects battery/doze on boxes that have
 * one (some Android TV sticks do idle-optimize even though they're plugged in).
 */
class CacheTrimWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TelTvApp ?: return Result.success()
        val prefs = CachePrefs(applicationContext)
        val enabled = prefs.autoClearEnabled.first()
        if (!enabled) return Result.success()

        return runCatching {
            val cacheManager = CacheManager(app.telegramClient::execute)
            cacheManager.maybeEmergencyClear(applicationContext.filesDir.usableSpace)
        }.fold(
            onSuccess = { Result.success() },
            // Transient TDLib/network hiccup -- WorkManager will retry with backoff rather than
            // us silently giving up and letting the cache grow unchecked.
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
