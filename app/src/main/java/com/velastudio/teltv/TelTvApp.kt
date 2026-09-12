package com.velastudio.teltv

import android.app.Application
import android.graphics.Bitmap
import android.os.StrictMode
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.velastudio.teltv.data.local.TelTvDatabase
import com.velastudio.teltv.data.repository.ChannelVideoRepository
import com.velastudio.teltv.telegram.TelegramClient
import com.velastudio.teltv.util.CrashLogger
import com.velastudio.teltv.util.DeviceCapabilities
import com.velastudio.teltv.worker.CacheTrimWorker
import timber.log.Timber

class TelTvApp : Application(), ImageLoaderFactory {
    var startupError: String? = null
        private set

    lateinit var telegramClient: TelegramClient
        private set

    lateinit var database: TelTvDatabase
        private set

    lateinit var deviceProfile: DeviceCapabilities.Profile
        private set

    lateinit var channelVideoRepository: ChannelVideoRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Install before any other startup work so initialization crashes are captured too.
        CrashLogger.install(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            enableStrictMode()
        } else {
            Timber.plant(CrashLogger.ReleaseTree())
        }

        try {
            deviceProfile = DeviceCapabilities.profile(this)
            telegramClient = TelegramClient(this)
            database = TelTvDatabase.get(this)
            channelVideoRepository = ChannelVideoRepository(
                telegram = telegramClient,
                videoIndexDao = database.videoIndexDao(),
                syncStateDao = database.channelSyncStateDao(),
                deviceProfile = deviceProfile
            )

            try {
                CacheTrimWorker.schedule(this)
            } catch (wEx: Throwable) {
                Timber.w(wEx, "WorkManager schedule deferred")
            }
        } catch (startupThrowable: Throwable) {
            startupError = CrashLogger.record(this, Thread.currentThread(), startupThrowable)
            Log.e("TelTV_STARTUP", "Application initialization failed", startupThrowable)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val cacheBytes = if (::deviceProfile.isInitialized) {
            deviceProfile.imageMemoryCacheBytes
        } else {
            16L * 1024 * 1024
        }
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(
                        cacheBytes
                            .coerceAtMost(Int.MAX_VALUE.toLong())
                            .toInt()
                    )
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(60L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }
}
