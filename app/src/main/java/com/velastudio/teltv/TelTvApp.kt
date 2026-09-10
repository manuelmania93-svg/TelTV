package com.velastudio.teltv

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

import android.os.StrictMode
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

        // Logging + crash capture first, before anything else can fail -- there's no live
        // logcat on most Android TV boxes once the app is off a dev machine, so this (plus
        // CrashLogger below) is often the only record of what actually happened.
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
        // Auth flow is started/collected from the login screen (MainActivity), which shows
        // phone-number / code / 2FA prompts based on TdApi.AuthorizationState updates.

        CacheTrimWorker.schedule(this)
    }

    /**
     * Debug-only. Flags disk/network-on-main-thread and leaked Closeables/registrations --
     * cheap to catch here, expensive to chase down later once several subsystems (Room, OkHttp,
     * jcifs, TDLib's JNI callbacks) could all be the culprit. Never enabled in release: the
     * penalty dialogs/logs aren't free, and untriaged violations in the field are just noise.
     */
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

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
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


    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
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

}
