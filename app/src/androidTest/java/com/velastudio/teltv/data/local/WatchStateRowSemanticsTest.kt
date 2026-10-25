package com.velastudio.teltv.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-Room semantics for watch_state (CI emulator, same pattern as PlayerFavoriteTest).
 * Locks in the row behavior the restore fix relies on - including that a REGRESSING write
 * flips finished back to false. That flip is what makes legitimate rewind work; the
 * multi-episode rollback bug is fixed at the restore path (route argument), not by
 * blocking regressing writes here.
 */
@RunWith(AndroidJUnit4::class)
class WatchStateRowSemanticsTest {

    private fun db(name: String): TelTvDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(name)
        return Room.databaseBuilder(context, TelTvDatabase::class.java, name).build()
    }

    private fun row(mediaId: String, positionMs: Long, durationMs: Long, epochSec: Long) = WatchStateEntity(
        mediaId = mediaId,
        positionMs = positionMs,
        durationMs = durationMs,
        lastWatchedEpochSec = epochSec,
        title = mediaId,
        finished = durationMs > 0 && positionMs >= durationMs * 0.95
    )

    @Test fun `finishedRowLeavesContinueWatchingAndStaysInWatchHistory`() = runBlocking {
        val db = db("watch-semantics-1.db")
        try {
            val dao = db.watchStateDao()
            dao.upsert(row("tg:1:5", 1_800_000, 1_800_000, epochSec = 100))
            assertTrue(dao.continueWatching().isEmpty())
            assertEquals(listOf("tg:1:5"), dao.recentlyWatched().map { it.mediaId })
        } finally { db.close() }
    }

    @Test fun `regressingWriteFlipsFinishedBack_rewindSemanticsPreserved`() = runBlocking {
        val db = db("watch-semantics-2.db")
        try {
            val dao = db.watchStateDao()
            dao.upsert(row("tg:1:5", 1_800_000, 1_800_000, epochSec = 100))
            // Rewind to 50%: the player writes exactly like this every 5s.
            dao.upsert(row("tg:1:5", 900_000, 1_800_000, epochSec = 200))
            val stored = dao.get("tg:1:5")!!
            assertFalse(stored.finished)
            assertEquals(listOf("tg:1:5"), dao.continueWatching().map { it.mediaId })
        } finally { db.close() }
    }

    @Test fun `watchHistoryOrdersByLastWriteNotByEpisode`() = runBlocking {
        val db = db("watch-semantics-3.db")
        try {
            val dao = db.watchStateDao()
            dao.upsert(row("tg:1:5", 1_800_000, 1_800_000, epochSec = 100))
            dao.upsert(row("tg:1:6", 1_800_000, 1_800_000, epochSec = 200))
            dao.upsert(row("tg:1:5", 60_000, 1_800_000, epochSec = 300)) // stale replay bumps old episode
            assertEquals(listOf("tg:1:5", "tg:1:6"), dao.recentlyWatched().map { it.mediaId })
        } finally { db.close() }
    }
}
