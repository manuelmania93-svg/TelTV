package com.velastudio.teltv.data.repository

import androidx.paging.PagingSource
import com.velastudio.teltv.data.local.*
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.SourceType
import com.velastudio.teltv.telegram.VideoMessagesResult
import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ChannelRefreshTest {
    private class Cache {
        val rows = mutableMapOf<String, VideoIndexEntity>()
        var state = ChannelSyncStateEntity(1, oldestLoadedMessageId = 99, itemCount = 1, fullyLoaded = true)
        init { rows["tg:1:100"] = entity(100, 0) }
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
    }
    @Test fun refreshImportsMultiplePagesWithoutChangingOldestCursor() = runBlocking {
        val cache = Cache()
        val all = (350L downTo 100L).map(::item)
        val repo = cache.repo { _, cursor, limit ->
            val page = all.filter { it.id.substringAfterLast(':').toLong() < if (cursor == 0L) Long.MAX_VALUE else cursor }.take(limit)
            VideoMessagesResult(page, page.lastOrNull()?.id?.substringAfterLast(':')?.toLong() ?: 0)
        }
        repo.refreshNewest(1)
        assertEquals(251, cache.rows.size)
        assertEquals(251, cache.state.itemCount)
        assertEquals(99L, cache.state.oldestLoadedMessageId)
        assertTrue(cache.state.fullyLoaded)
        assertEquals(0L, cache.state.refreshBoundary)
        assertEquals((350L downTo 100L).toList(), cache.rows.values.sortedBy { it.position }.map { it.messageId })
    }
    @Test fun failedSecondPageResumesPastImportedHead() = runBlocking {
        val cache = Cache()
        var fail = true
        val repo = cache.repo { _, cursor, _ ->
            if (cursor != 0L && fail) throw IllegalStateException("offline")
            val ids = if (cursor == 0L) 300L downTo 201L else 200L downTo 100L
            VideoMessagesResult(ids.map(::item), if (cursor == 0L) 201 else 100)
        }
        repo.refreshNewest(1)
        assertEquals(201L, cache.state.refreshCursor)
        assertEquals(100L, cache.state.refreshBoundary)
        fail = false
        repo.refreshNewest(1)
        assertEquals(201, cache.rows.size)
        assertEquals((300L downTo 100L).toList(), cache.rows.values.sortedBy { it.position }.map { it.messageId })
    }
    @Test fun cancellationIsNotSwallowed() = runBlocking {
        val cache = Cache()
        try { cache.repo { _, _, _ -> throw CancellationException("stopped") }.refreshNewest(1); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, cache.rows.size) }
    }
    companion object {
        fun item(id: Long) = MediaItem(id = "tg:1:$id", sourceType = SourceType.TELEGRAM, title = "Video $id", streamUrl = "tdlib://file/$id")
        fun entity(id: Long, position: Int) = VideoIndexEntity("tg:1:$id", 1, position, id, "Video $id", null, null, null, null, null, "tdlib://file/$id", 0)
    }
}
