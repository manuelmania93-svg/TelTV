package com.velastudio.teltv.util

import com.velastudio.teltv.data.local.VideoIndexEntity
import org.junit.Assert.*
import org.junit.Test

class HybridEpisodeMatcherSnapshotTest {
    private fun v(id: String, title: String, msg: Long) = VideoIndexEntity(
        mediaId = id, chatId = 1L, position = 0, messageId = msg, title = title, subtitle = null,
        category = null, durationMs = null, sizeBytes = null, thumbnailFileId = null,
        streamUrl = "tdlib://file/$msg", addedAtEpochSec = 0L
    )

    private val lib = listOf(
        v("a", "Naruto - 049", 1), v("b", "Naruto - 050", 2), v("c", "Naruto - 051", 3),
        v("d", "Show S01E09", 4), v("e", "Show S01E10", 5), v("f", "Show S02E01", 6)
    )

    @Test fun nextAndPreviousFromOneSnapshot() {
        val cache = HybridEpisodeMatcher.SignatureCache()
        val cur = lib[1]
        assertEquals("c", HybridEpisodeMatcher.nextFromSnapshot(cur, lib, cache)?.mediaId)
        assertEquals("a", HybridEpisodeMatcher.previousFromSnapshot(cur, lib, cache)?.mediaId)
    }

    @Test fun seasonRollover() {
        assertEquals("f", HybridEpisodeMatcher.nextFromSnapshot(lib[4], lib)?.mediaId)
        assertEquals("e", HybridEpisodeMatcher.previousFromSnapshot(lib[5], lib)?.mediaId)
    }

    @Test fun noMatchReturnsNullSoCallerCanUseTimelineFallback() {
        assertNull(HybridEpisodeMatcher.nextFromSnapshot(lib[2], lib.take(3)))
    }

    @Test fun cacheParsesEachTitleOnce() {
        val cache = HybridEpisodeMatcher.SignatureCache()
        val first = cache.of(lib[0])
        assertSame(first, cache.of(lib[0]))
        // unparseable titles are cached too
        val plain = v("z", "Just a movie", 9)
        assertNull(cache.of(plain))
        assertNull(cache.of(plain))
    }
}
