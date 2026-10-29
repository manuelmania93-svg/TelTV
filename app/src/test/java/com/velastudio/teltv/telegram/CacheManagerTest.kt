package com.velastudio.teltv.telegram

import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertThrows
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheManagerTest {

    @Test
    fun telegramLimitAccountsForAppCache() {
        assertEquals(700L, CacheManager.telegramLimitFor(1_000L, 300L))
    }

    @Test
    fun telegramLimitNeverGoesBelowZero() {
        assertEquals(0L, CacheManager.telegramLimitFor(300L, 500L))
    }

    @Test
    fun trimBelowLimitDoesNotOptimize() = runBlocking {
        val calls = mutableListOf<TdApi.Function<*>>()
        val manager = CacheManager({ request ->
            calls += request
            TdApi.StorageStatisticsFast(500L, 1, 0L, 0L, 0L)
        })
        manager.trimToLimit(1_000L)
        assertEquals(1, calls.size)
        assertTrue(calls.single() is TdApi.GetStorageStatisticsFast)
    }

    @Test
    fun trimAboveLimitUsesSizeWithoutAgeOrCountPurge() = runBlocking {
        var optimize: TdApi.OptimizeStorage? = null
        val manager = CacheManager({ request ->
            when (request) {
                is TdApi.GetStorageStatisticsFast -> TdApi.StorageStatisticsFast(2_000L, 3, 0L, 0L, 0L)
                is TdApi.OptimizeStorage -> {
                    optimize = request
                    TdApi.Ok()
                }
                else -> error("Unexpected request")
            }
        })
        manager.trimToLimit(1_000L)
        assertEquals(1_000L, optimize!!.size)
        assertEquals(Int.MAX_VALUE, optimize!!.ttl)
        assertEquals(Int.MAX_VALUE, optimize!!.count)
        assertEquals(60, optimize!!.immunityDelay)
    }

    @Test
    fun evictAllButRecentTargetsZeroSizeWithImmunity() = runBlocking {
        var optimize: TdApi.OptimizeStorage? = null
        val manager = CacheManager({ request ->
            when (request) {
                is TdApi.OptimizeStorage -> {
                    optimize = request
                    TdApi.Ok()
                }
                else -> error("Unexpected request")
            }
        })
        manager.evictAllButRecent(600)
        assertEquals(0L, optimize!!.size)
        assertEquals(Int.MAX_VALUE, optimize!!.ttl)
        assertEquals(Int.MAX_VALUE, optimize!!.count)
        assertEquals(600, optimize!!.immunityDelay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeImmunityIsRejected() = runBlocking {
        CacheManager({ error("No TDLib call expected") }).evictAllButRecent(-1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTrimLimitIsRejected() = runBlocking {
        CacheManager({ error("No TDLib call expected") }).trimToLimit(-1L)
    }

    @Test
    fun proactiveEvictionSkipsWhenFreeSpaceIsHealthy() = runBlocking {
        val calls = mutableListOf<TdApi.Function<*>>()
        val manager = CacheManager({ request ->
            calls += request
            TdApi.Ok()
        })
        assertEquals(false, manager.maybeProactiveEvict(CacheManager.PROACTIVE_FREE_SPACE_BYTES))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun proactiveEvictionRemovesDayOldMediaWhenSpaceRunsLow() = runBlocking {
        var optimize: TdApi.OptimizeStorage? = null
        val manager = CacheManager({ request ->
            when (request) {
                is TdApi.OptimizeStorage -> {
                    optimize = request
                    TdApi.Ok()
                }
                else -> error("Unexpected request")
            }
        })
        assertEquals(true, manager.maybeProactiveEvict(CacheManager.PROACTIVE_FREE_SPACE_BYTES - 1))
        assertEquals(CacheManager.PROACTIVE_IMMUNITY_SECONDS, optimize!!.immunityDelay)
        assertEquals(0L, optimize!!.size)
    }

    @Test
    fun appCacheClearKeepsDownloadedSubtitles() {
        val cacheDir = createTempDir()
        try {
            val subtitles = File(cacheDir, "subtitles").apply { mkdirs() }
            File(subtitles, "movie-de.srt").writeText("1\n00:00:01,000 --> 00:00:02,000\nHallo")
            File(cacheDir, "exoplayer").apply { mkdirs() }
            File(cacheDir, "thumbs.tmp").writeText("x")

            CacheManager.clearAppCacheExceptSubtitles(cacheDir)

            assertTrue(File(subtitles, "movie-de.srt").exists())
            assertEquals(listOf("subtitles"), cacheDir.list()!!.toList())
        } finally {
            cacheDir.setWritable(true)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun appCacheClearThrowsInsteadOfSilentlySkippingAnUndeletableEntry() {
        val cacheDir = createTempDir()
        try {
            File(cacheDir, "stuck.tmp").writeText("x")
            cacheDir.setWritable(false)

            assertThrows(java.io.IOException::class.java) {
                CacheManager.clearAppCacheExceptSubtitles(cacheDir)
            }
            assertTrue(File(cacheDir, "stuck.tmp").exists())
        } finally {
            cacheDir.setWritable(true)
            cacheDir.deleteRecursively()
        }
    }
}
