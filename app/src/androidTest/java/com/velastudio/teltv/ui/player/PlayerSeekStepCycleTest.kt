package com.velastudio.teltv.ui.player

import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.player.SeekSteps
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerSeekStepCycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun landscape() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Wait for the Android render thread to present the tested Compose frame.
        Thread.sleep(250)
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name.png")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test fun cycleButtonWalksEveryStepAndSkipButtonsFollow() {
        var amount by mutableStateOf(10_000L)
        val longTitle = "Staffel 04 Episode 03 - Kein Empfang - Als ich gross war - " +
            "Der gierige sehr lange Dateiname der sonst abgeschnitten wird und niemand lesen kann"
        compose.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)
            ) {
                androidx.tv.material3.MaterialTheme {
                    PlaybackControlsOverlay(
                        title = longTitle, isPlaying = true,
                        currentPositionMs = 2_179_000L, durationMs = 0L,
                        skipIncrementMs = amount, visible = true,
                        onPlayPause = {}, onSkipBack = {}, onSkipForward = {},
                        onOpenTracks = {}, onCycleAspectRatio = {}, onOpenExternal = null,
                        autoPlayNext = true, onToggleAutoPlay = {},
                        onCycleSkipStep = { amount = SeekSteps.next(amount) }
                    )
                }
            }
        }
        compose.onNodeWithText(longTitle, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Skip: 10s").assertIsDisplayed()
        screenshot("teltv-marquee-title")

        val badgeOrder = listOf("30s", "1 min", "2 min", "5 min", "5s", "10s")
        val msOrder = listOf(30_000L, 60_000L, 120_000L, 300_000L, 5_000L, 10_000L)
        badgeOrder.forEachIndexed { index, badge ->
            val node = compose.onNodeWithText(
                "Skip: ${SeekSteps.badge(msOrder[(index + msOrder.size - 1) % msOrder.size])}"
            )
            node.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            node.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            compose.onNodeWithText("Skip: $badge").assertIsDisplayed()
            compose.onNodeWithContentDescription("Skip forward ${SeekSteps.label(msOrder[index])}").assertExists()
            compose.onNodeWithContentDescription("Skip back ${SeekSteps.label(msOrder[index])}").assertExists()
        }
        compose.runOnIdle { assertEquals(10_000L, amount) }
        screenshot("teltv-seek-step-cycle")
    }
}
