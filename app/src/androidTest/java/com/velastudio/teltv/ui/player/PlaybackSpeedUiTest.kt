package com.velastudio.teltv.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import org.junit.Before
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Shell-owned shared directory survives Gradle uninstalling the test application.
        val output = device.executeShellCommand("screencap -p /sdcard/Download/$name.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }

    }

    @Test fun dpadCanChooseFourTimesAndMenuFitsScreen() {
        var chosen = 0f
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlaybackSpeedDialog(1f, { chosen = it }, {})
            }
        }
        compose.onNodeWithText("Selected: 1x").assertIsFocused()
        compose.onNodeWithText("4x").assertExists()
        screenshot("playback-speed-menu")
        repeat(5) {
            compose.onNodeWithText("Playback speed").performKeyInput {
                keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
            }
        }
        compose.onNodeWithText("4x").assertIsFocused().assertIsDisplayed()
        screenshot("playback-speed-menu-four-times")
        compose.onNodeWithText("4x").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.runOnIdle { assertEquals(4f, chosen, 0f) }
    }

    @Test fun backDismissesWithoutChangingRate() {
        var dismissed = false
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlaybackSpeedDialog(3f, { fail("Back must not select a rate") }, { dismissed = true })
            }
        }
        compose.onNodeWithText("Selected: 3x").assertIsFocused()
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitUntil(5000) { dismissed }
    }

    @Test fun longTitleDoesNotHideSpeedControl() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                PlaybackControlsOverlay(
                    title = "A very long episode title repeated across a small TV screen ".repeat(8),
                    isPlaying = false, currentPositionMs = 1000, durationMs = 100000,
                    skipIncrementMs = 10000, visible = true,
                    onPlayPause = {}, onSkipBack = {}, onSkipForward = {},
                    onOpenTracks = {}, onCycleAspectRatio = {}, onOpenExternal = null,
                    autoPlayNext = true, onToggleAutoPlay = {}, playbackSpeed = 4f,
                    onOpenPlaybackSpeed = {}
                )
            }
        }
        compose.onNodeWithText("Playback: 4x").assertIsDisplayed()
        screenshot("playback-speed-controls")
    }
}
