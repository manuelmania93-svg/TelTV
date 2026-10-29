package com.velastudio.teltv.ui.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.data.local.VideoIndexEntity
import com.velastudio.teltv.data.local.WatchStateEntity
import com.velastudio.teltv.data.local.WatchlistEntity
import com.velastudio.teltv.player.PlaybackStorageGuard
import com.velastudio.teltv.telegram.CacheManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The central storage-full failure path executed for real on the emulator, with controlled
 * seams: playback error -> position persisted to watch_state -> cache cleared -> player
 * re-prepared and played. Asserts the ordering (position lands BEFORE the cache clear runs),
 * the resume, and that protected state (favorites, watch history, downloaded subtitles)
 * survives. The TDLib send and the free-space reading are fakes, so this proves the app's
 * wiring - not TDLib's deletion behavior under an actually full disk.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class StorageFullRecoveryTest {

    @Test
    fun playbackErrorOnFullDiskPersistsPositionClearsCacheAndReprepares() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as TelTvApp

        val mediaId = "storage-full-recovery-test"
        // Deliberately NOT a tdlib:// URI: keeps the test off the real Telegram client
        // (no cancelDownload); the guard only needs the index row for position mapping.
        val streamUrl = "http://127.0.0.1:9/unreachable.mp4"
        app.database.videoIndexDao().insertAll(
            listOf(
                VideoIndexEntity(
                    mediaId = mediaId, chatId = 1L, position = 0, messageId = 1L,
                    title = "Recovery Test", subtitle = null, category = null,
                    durationMs = 3_600_000L, sizeBytes = null, thumbnailFileId = null,
                    streamUrl = streamUrl, addedAtEpochSec = 1_700_000_000L
                )
            )
        )
        app.database.watchlistDao().add(WatchlistEntity(mediaId, 1_700_000_000L))
        val subtitlesDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val subtitleFile = File(subtitlesDir, "keep-me.srt")
            .apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHallo") }
        val junkFile = File(context.cacheDir, "junk.tmp").apply { writeText("x") }

        val playerBox = arrayOfNulls<ExoPlayer>(1)
        val states = CopyOnWriteArrayList<Int>()
        instrumentation.runOnMainSync {
            val exo = ExoPlayer.Builder(context).build()
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    states += playbackState
                }
            })
            exo.setMediaItem(MediaItem.fromUri(streamUrl))
            exo.seekTo(12_345L)
            exo.playWhenReady = true
            playerBox[0] = exo
        }
        val player = playerBox[0]!!

        // Free space: critically low for the trigger reading, "recovered" afterwards so the
        // resume wait exits quickly instead of sitting out the full 15 seconds.
        val freeReadings = AtomicInteger(0)
        val fakeFreeSpace = {
            if (freeReadings.incrementAndGet() <= 1) 100L * 1024 * 1024 else 1_000_000_000L
        }

        // Records, for every OptimizeStorage call, whether the position was already persisted
        // at that moment - the ordering proof for persist-then-clear.
        val positionPersistedWhenCleanupRan = CopyOnWriteArrayList<Boolean>()
        val fakeSend: suspend (TdApi.Function<*>) -> TdApi.Object = { request ->
            if (request is TdApi.OptimizeStorage) {
                positionPersistedWhenCleanupRan += app.database.watchStateDao().get(mediaId) != null
            }
            TdApi.Ok()
        }

        val guard = PlaybackStorageGuard(
            context, app,
            playerProvider = { player },
            freeSpaceBytes = fakeFreeSpace,
            cacheManagerFactory = { CacheManager(fakeSend, context) }
        )

        guard.onPlaybackError()

        val deadline = System.currentTimeMillis() + 30_000
        var persisted: WatchStateEntity? = null
        while (System.currentTimeMillis() < deadline) {
            persisted = app.database.watchStateDao().get(mediaId)
            if (persisted != null &&
                positionPersistedWhenCleanupRan.isNotEmpty() &&
                states.contains(Player.STATE_BUFFERING)
            ) break
            delay(200)
        }

        guard.stop()
        instrumentation.runOnMainSync { player.release() }

        assertNotNull("position persisted to watch_state", persisted)
        assertEquals(12_345L, persisted!!.positionMs)
        assertTrue("cache clear ran", positionPersistedWhenCleanupRan.isNotEmpty())
        assertTrue(
            "position was persisted BEFORE the cache clear ran",
            positionPersistedWhenCleanupRan.all { it }
        )
        assertTrue("player re-prepared after the error", states.contains(Player.STATE_BUFFERING))
        assertTrue("downloaded subtitles survive", subtitleFile.exists())
        assertFalse("other cache entries are cleared", junkFile.exists())
        assertNotNull("favorites stay untouched", app.database.watchlistDao().get(mediaId))
    }
}
