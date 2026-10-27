package com.velastudio.teltv.ui.home

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrElse
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.telegram.TelegramClient
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.theme.TelTvTheme
import com.velastudio.teltv.util.DeviceCapabilities
import com.velastudio.teltv.util.TmdbMetadata
import com.velastudio.teltv.util.TmdbMetadataProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the production HomeScreen card through Android's window input routing, not
 * performKeyInput on an already-open dialog. No Telegram session or media is needed. */
@RunWith(AndroidJUnit4::class)
class HomeRemoveOpeningGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val title = "Opening gesture test episode"

    private fun key(action: Int, downTime: Long, code: Int, repeat: Int = 0) {
        assertTrue("System key injection failed: action=$action code=$code repeat=$repeat",
            automation.injectInputEvent(KeyEvent(downTime, SystemClock.uptimeMillis(), action,
                code, repeat, 0, KeyEvent.KEYCODE_UNKNOWN, 0,
                if (repeat > 0) KeyEvent.FLAG_LONG_PRESS else 0, InputDevice.SOURCE_DPAD), true))
    }

    private fun press(code: Int) {
        val down = SystemClock.uptimeMillis()
        key(KeyEvent.ACTION_DOWN, down, code)
        key(KeyEvent.ACTION_UP, down, code)
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        Thread.sleep(150)
        AutoCloseInputStream(automation.executeShellCommand(
            "screencap -p /sdcard/Download/$name.png")).use { it.readBytes() }
    }

    @Test fun openingHoldReleaseDoesNotCancelAndFreshPressStillWorks() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Seed only this synthetic title so the real card never needs an artwork network call.
        val field = TmdbMetadataProvider::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(TmdbMetadataProvider) as MutableMap<String, TmdbMetadata>
        cache[title] = TmdbMetadata(null, null, null, null, null)
        val loader = ThumbnailLoader(TelegramClient(compose.activity), scope,
            DeviceCapabilities.profile(compose.activity))
        var removed = 0
        var resumed = 0
        try {
            compose.setContent {
                TelTvTheme {
                    HomeScreen(
                        thumbnailLoader = loader,
                        continueWatching = emptyList(),
                        recentlyWatched = listOf(ContinueWatchingEntry("synthetic-1", title, 0.5f)),
                        favorites = emptyList(), pinned = HomeRow("Pinned", emptyList()),
                        allChannels = HomeRow("Channels", emptyList()), folderRows = emptyList(),
                        otherSources = HomeRow("Other", emptyList()),
                        onOpenEntry = {}, onResumeWatching = { resumed++ },
                        onRemoveFromHistory = { removed++ }, onRemoveFavorite = {},
                        onOpenSearch = {}, onOpenSettings = {}
                    )
                }
            }
            compose.waitForIdle()
            // A release update popup is unrelated to this test; close it if it arrived.
            compose.onAllNodesWithText("Later").fetchSemanticsNodes().firstOrNull()?.let {
                compose.onNodeWithText("Later").performClick()
            }
            compose.onNodeWithContentDescription(title).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithContentDescription(title).assertIsFocused()
            val down = SystemClock.uptimeMillis()
            key(KeyEvent.ACTION_DOWN, down, KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(550)
            key(KeyEvent.ACTION_DOWN, down, KeyEvent.KEYCODE_DPAD_CENTER, repeat = 1)
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Remove video?").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().any {
                    it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
                }
            }
            screenshot("opening-gesture-before-release")
            key(KeyEvent.ACTION_UP, down, KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitForIdle()
            screenshot("opening-gesture-after-release")
            compose.onNodeWithText("Remove video?").assertIsDisplayed()
            assertEquals("Opening gesture must not remove the item", 0, removed)
            assertEquals("Long press must not resume playback", 0, resumed)
            // A new complete gesture on focused Cancel must still work exactly once.
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithText("Remove video?").assertDoesNotExist()
            assertEquals(0, removed)
            assertEquals(0, resumed)
        } finally {
            scope.cancel()
            cache.remove(title)
        }
    }
}
