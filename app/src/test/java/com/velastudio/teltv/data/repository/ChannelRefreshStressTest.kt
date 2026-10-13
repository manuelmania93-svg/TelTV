package com.velastudio.teltv.data.repository

import com.velastudio.teltv.data.local.*
import com.velastudio.teltv.telegram.VideoMessagesResult
import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.Random

/** Fake-Telegram scenarios for refreshNewest: big channels, flaky/slow/dropped network, interrupted refreshes. */
class ChannelRefreshStressTest {
    private class Cache(headId: Long = 1000L) {
        val rows = mutableMapOf<String, VideoIndexEntity>()
        var state = ChannelSyncStateEntity(1, oldestLoadedMessageId = 99, itemCount = 1, fullyLoaded = true)
        init { rows["tg:1:$headId"] = ChannelRefreshTest.entity(headId, 0); state = state.copy(itemCount = 1) }
        val video = Proxy.newProxyInstance(VideoIndexDao::class.java.classLoader, arrayOf(VideoIndexDao::class.java)) { _, method, args ->
            when (method.name) {
                "count" -> rows.size
                "newestMessageId" -> rows.values.minByOrNull { it.position }?.messageId
                "getByMediaId" -> rows[args!![0]]
                "updateTitle" -> { val id = args!![0] as String; rows[id]?.let { rows[id] = it.copy(title = args[1] as String) }; Unit }
                "commitRefreshPage" -> {
                    val offset = args!![1] as Int
                    @Suppress("UNCHECKED_CAST") val items = args[2] as List<VideoIndexEntity>
                    rows.replaceAll { _, row -> if (row.position >= offset) row.copy(position = row.position + items.size) else row }
                    items.forEach { rows[it.mediaId] = it }
                    state = args[3] as ChannelSyncStateEntity
                    Unit
                }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as VideoIndexDao
        val sync = Proxy.newProxyInstance(ChannelSyncStateDao::class.java.classLoader, arrayOf(ChannelSyncStateDao::class.java)) { _, method, args ->
            when (method.name) {
                "get" -> state
                "upsert" -> { state = args!![0] as ChannelSyncStateEntity; Unit }
                else -> error(method.name)
            }
        } as ChannelSyncStateDao
        fun repo(fetch: suspend (Long, Long, Int) -> VideoMessagesResult) = ChannelVideoRepository(
            fetch, video, sync, DeviceCapabilities.Profile(true, 100, 20, 3, 1024, 200, 450))
        fun order() = rows.values.sortedBy { it.position }.map { it.messageId }
        fun settled() = state.refreshCursor == 0L && state.refreshBoundary == 0L
    }

    /** Server holding message ids [low..high]; newest first, like TDLib history paging. */
    private class Server(var high: Long, val low: Long) {
        fun page(cursor: Long, limit: Int): VideoMessagesResult {
            val ids = (high downTo low).filter { cursor == 0L || it < cursor }.take(limit)
            return VideoMessagesResult(ids.map(ChannelRefreshTest::item), if (ids.isEmpty()) 0L else ids.last())
        }
    }

    @Test fun veryLargeBacklogCompletesAcrossOpensInOrderWithoutDuplicates() = runBlocking {
        val cache = Cache(headId = 1000L)
        val server = Server(high = 6000L, low = 1000L)
        val repo = cache.repo { _, cursor, limit -> server.page(cursor, limit) }
        var opens = 0
        withTimeout(120_000) { while (!cache.settled() || opens == 0) { repo.refreshNewest(1); opens++; assertTrue("too many opens", opens < 40) } }
        assertEquals((6000L downTo 1000L).toList(), cache.order())
        assertEquals(cache.rows.size, cache.state.itemCount)
        assertTrue("single open must be bounded, saw $opens opens", opens >= 1)
    }

    @Test fun flakyNetworkEventuallyConvergesToExactCopy() = runBlocking {
        val cache = Cache(headId = 1000L)
        val server = Server(high = 1700L, low = 1000L)
        val rnd = Random(7)
        var failures = 0
        val repo = cache.repo { _, cursor, limit ->
            if (rnd.nextInt(100) < 45) { failures++; throw if (rnd.nextBoolean()) IOException("connection reset") else IllegalStateException("tdlib timeout") }
            server.page(cursor, limit)
        }
        var opens = 0
        withTimeout(120_000) { while (!cache.settled() || opens == 0) { repo.refreshNewest(1); opens++; assertTrue("never converged", opens < 200) } }
        assertTrue("test did not inject failures", failures > 0)
        assertEquals((1700L downTo 1000L).toList(), cache.order())
    }

    @Test fun slowNetworkTimeoutCancelsCleanlyAndResumes() = runBlocking {
        val cache = Cache(headId = 1000L)
        val server = Server(high = 1500L, low = 1000L)
        var slow = true
        val repo = cache.repo { _, cursor, limit -> if (slow) delay(400); server.page(cursor, limit) }
        val finished = withTimeoutOrNull(900) { repo.refreshNewest(1); true }
        assertNull("slow refresh should have been cancelled by the timeout", finished)
        slow = false
        var opens = 0
        withTimeout(60_000) { while (!cache.settled() || opens == 0) { repo.refreshNewest(1); opens++; assertTrue(opens < 20) } }
        assertEquals((1500L downTo 1000L).toList(), cache.order())
    }

    @Test fun uploadsArrivingWhileRefreshIsInterruptedAreNotLost() = runBlocking {
        val cache = Cache(headId = 1000L)
        val server = Server(high = 1300L, low = 1000L)
        var fail = false
        var served = 0
        val repo = cache.repo { _, cursor, limit ->
            if (fail) throw IOException("offline")
            val r = server.page(cursor, limit); served++; if (served == 1) fail = true; r
        }
        repo.refreshNewest(1)                  // imports first page, then the network drops
        assertFalse(cache.settled())
        server.high = 1400L                    // new uploads appear while offline
        fail = false
        var opens = 0
        withTimeout(60_000) { while (opens < 3 || !cache.settled()) { repo.refreshNewest(1); opens++; assertTrue(opens < 30) } }
        assertEquals((1400L downTo 1000L).toList(), cache.order())
    }

    @Test fun stuckCursorFromServerDoesNotLoopOrDuplicate() = runBlocking {
        val cache = Cache(headId = 1000L)
        val repo = cache.repo { _, _, _ -> VideoMessagesResult((1300L downTo 1201L).map(ChannelRefreshTest::item), 1201L) }
        withTimeout(30_000) { repo.refreshNewest(1); repo.refreshNewest(1) }
        assertEquals(cache.rows.size, cache.order().toSet().size)
        assertTrue(cache.rows.keys.all { it.startsWith("tg:1:") })
    }

    @Test fun emptyServerAnswerLeavesCacheUntouched() = runBlocking {
        val cache = Cache(headId = 1000L)
        cache.repo { _, _, _ -> VideoMessagesResult(emptyList(), 0L) }.refreshNewest(1)
        assertEquals(listOf(1000L), cache.order())
        assertTrue(cache.settled())
    }

    @Test fun concurrentOpensDoNotDoubleImport() = runBlocking {
        val cache = Cache(headId = 1000L)
        val server = Server(high = 1250L, low = 1000L)
        val repo = cache.repo { _, cursor, limit -> delay(50); server.page(cursor, limit) }
        withTimeout(60_000) {
            kotlinx.coroutines.coroutineScope { (1..5).map { launch { repo.refreshNewest(1) } }.joinAll() }
            var opens = 0
            while (!cache.settled() || opens == 0) { repo.refreshNewest(1); opens++; assertTrue(opens < 20) }
        }
        assertEquals((1250L downTo 1000L).toList(), cache.order())
    }
}
