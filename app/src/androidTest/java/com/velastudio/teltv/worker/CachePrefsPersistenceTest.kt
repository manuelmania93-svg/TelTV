package com.velastudio.teltv.worker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The freed-space record behind the settings line: proves recordCleanup() actually persists
 * through the real on-device DataStore and the flows the UI collects read it back. This is the
 * persistence leg of the notice; the cleanup-to-record path itself only runs on a real cleanup.
 */
@RunWith(AndroidJUnit4::class)
class CachePrefsPersistenceTest {

    @Test
    fun recordCleanupPersistsFreedBytesAndTimestamp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = CachePrefs(context)

        prefs.recordCleanup(1_610_612_736L, 1_761_200_000_000L)

        assertEquals(1_610_612_736L, prefs.lastCleanupFreedBytes.first())
        assertEquals(1_761_200_000_000L, prefs.lastCleanupAtMs.first())

        // And a second write overwrites rather than stacking.
        prefs.recordCleanup(500_000_000L, 1_761_300_000_000L)
        assertEquals(500_000_000L, prefs.lastCleanupFreedBytes.first())
        assertEquals(1_761_300_000_000L, prefs.lastCleanupAtMs.first())
    }
}
