package com.velastudio.teltv.ui.player

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.data.local.TelTvDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerFavoriteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun landscape() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }
    private fun database(name: String): TelTvDatabase = Room.databaseBuilder(
        compose.activity, TelTvDatabase::class.java, name
    ).build()
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test fun togglePersistsAcrossDatabaseReopenAndDoesNotChangeAnotherVideo() = runBlocking {
        val name = "favorite-toggle-test.db"
        compose.activity.deleteDatabase(name)
        var db = database(name)
        try {
            val dao = db.watchlistDao()
            assertNull(dao.get("tg:1:1"))
            assertTrue(dao.toggle("tg:1:1"))
            assertTrue(dao.toggle("tg:1:2"))
            assertEquals(2, dao.getAll().size)
            db.close()
            db = database(name)
            assertNotNull(db.watchlistDao().get("tg:1:1"))
            assertFalse(db.watchlistDao().toggle("tg:1:1"))
            assertNull(db.watchlistDao().get("tg:1:1"))
            assertNotNull(db.watchlistDao().get("tg:1:2"))
            assertEquals(listOf("tg:1:2"), db.watchlistDao().getAll().map { it.mediaId })
        } finally {
            db.close()
            compose.activity.deleteDatabase(name)
        }
        Unit
    }

    @Test fun heartIsReachableFromSubtitlesAndTogglesWithDpad() {
        var favorite by mutableStateOf(false)
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlaybackControlsOverlay(
                    title = "A long episode title ".repeat(20), isPlaying = false,
                    currentPositionMs = 1000, durationMs = 100000, skipIncrementMs = 10000,
                    visible = true, onPlayPause = {}, onSkipBack = {}, onSkipForward = {},
                    onOpenTracks = {}, onCycleAspectRatio = {}, onOpenExternal = null,
                    autoPlayNext = true, onToggleAutoPlay = {},
                    isFavorite = favorite, onToggleFavorite = { favorite = !favorite }
                )
            }
        }
        compose.onNodeWithContentDescription("Audio & Subtitles").performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.RequestFocus
        )
        compose.onNodeWithContentDescription("Audio & Subtitles").performKeyInput {
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        compose.onNodeWithContentDescription("Add to favorites").assertIsFocused().assertIsDisplayed()
        screenshot("favorite-controls-off")
        compose.onNodeWithContentDescription("Add to favorites").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.onNodeWithContentDescription("Remove from favorites").assertIsFocused().assertIsDisplayed()
        screenshot("favorite-controls-on")
        compose.onNodeWithContentDescription("Remove from favorites").performClick()
        compose.onNodeWithContentDescription("Add to favorites").assertExists()
    }

    @Test fun loadingOrSavingHeartDoesNotAcceptAnotherClick() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlayerFavoriteButton(false, false) { fail("Disabled heart must not save") }
            }
        }
        compose.onNodeWithContentDescription("Add to favorites").assertIsNotEnabled()
    }
}
