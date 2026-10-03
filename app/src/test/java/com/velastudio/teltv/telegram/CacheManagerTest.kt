package com.velastudio.teltv.telegram

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
}
