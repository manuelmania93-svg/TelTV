package com.velastudio.teltv.telegram

import kotlinx.coroutines.runBlocking
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

    @Test(expected = IllegalArgumentException::class)
    fun negativeTrimLimitIsRejected() = runBlocking {
        CacheManager({ error("No TDLib call expected") }).trimToLimit(-1L)
    }
}
