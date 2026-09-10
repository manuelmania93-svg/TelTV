package com.velastudio.teltv

import android.app.Application
import android.graphics.Bitmap
import android.os.StrictMode
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

        // Global TV Crash Handler: Catch fatal exceptions and log them
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("TelTV_CRASH", "FATAL CRASH DETECTED: ", throwable)
            // Save last crash to SharedPreferences so the app can display it on next boot
            val prefs = getSharedPreferences("teltv_crash", android.content.Context.MODE_PRIVATE)
            val stackTrace = android.util.Log.getStackTraceString(throwable)
            prefs.edit().putString("last_crash", stackTrace).commit()
            defaultHandler?.uncaughtException(thread, throwable)
        }


        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            enableStrictMode()
        } else {
            Timber.plant(CrashLogger.ReleaseTree())
        }
        CrashLogger.install(this)

        deviceProfile = DeviceCapabilities.profile(this)
        telegramClient = TelegramClient(this)
        database = TelTvDatabase.get(this)
        channelVideoRepository = ChannelVideoRepository(
            telegram = telegramClient,
            videoIndexDao = database.videoIndexDao(),
            syncStateDao = database.channelSyncStateDao(),
            deviceProfile = deviceProfile
        )

        CacheTrimWorker.schedule(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(
                        deviceProfile.imageMemoryCacheBytes
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
