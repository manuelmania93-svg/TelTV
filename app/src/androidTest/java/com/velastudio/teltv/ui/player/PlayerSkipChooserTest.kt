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
class PlayerSkipChooserTest {
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

    private fun awaitFocused(text: String) {
        compose.waitUntil(5000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().any {
                it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
            }
        }
    }

    @Test fun skipButtonOpensChooserAndPickedStepApplies() {
        var amount by mutableStateOf(60_000L)
        var chooserOpen by mutableStateOf(false)
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
                        skipIncrementMs = amount, visible = !chooserOpen,
                        onPlayPause = {}, onSkipBack = {}, onSkipForward = {},
                        onOpenTracks = {}, onCycleAspectRatio = {}, onOpenExternal = null,
                        autoPlayNext = true, onToggleAutoPlay = {},
                        onOpenSkipChooser = { chooserOpen = true }
                    )
                    if (chooserOpen) {
                        SkipIntervalDialog(
                            selectedMs = amount,
                            onSelect = { ms -> amount = ms; chooserOpen = false },
                            onDismiss = { chooserOpen = false }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(longTitle, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Skip: 1 min").assertIsDisplayed()
        screenshot("teltv-marquee-title")

        // Open the chooser: title shown, current step marked and focused, every step listed.
        // Options can sit below the dialog's scroll viewport, so existence is asserted here;
        // visibility is asserted only after DPAD-scrolling to the option (TV interaction).
        val pill = compose.onNodeWithText("Skip: 1 min")
        pill.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        pill.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Skip interval").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Skip interval").assertIsDisplayed()
        awaitFocused("Selected: 1 minute")
        SeekSteps.choicesMs.forEach { ms ->
            val expected = (if (ms == 60_000L) "Selected: " else "") + SeekSteps.label(ms)
            compose.onNodeWithText(expected).assertExists()
        }
        screenshot("teltv-skip-chooser-open")

        // DPAD down twice from the selected 1 minute lands on 5 minutes; select it.
        repeat(2) {
            compose.onNodeWithText("Skip interval").performKeyInput {
                keyDown(Key.DirectionDown); keyUp(Key.DirectionDown)
            }
        }
        awaitFocused("5 minutes")
        compose.onNodeWithText("5 minutes").assertIsDisplayed()
        screenshot("teltv-skip-chooser-five-min")
        compose.onNodeWithText("5 minutes").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Skip interval").assertDoesNotExist()
        compose.onNodeWithText("Skip: 5 min").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip forward 5 minutes").assertExists()
        compose.onNodeWithContentDescription("Skip back 5 minutes").assertExists()
        compose.runOnIdle { assertEquals(300_000L, amount) }

        // Reopen and DPAD up to 10 seconds to prove repeated choice works both directions.
        // Reopen with the same focus + DPAD-center sequence that opened it the first
        // time: a semantics performClick on the TV pill does not reach its onClick.
        val pill5 = compose.onNodeWithText("Skip: 5 min")
        pill5.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        pill5.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Skip interval").fetchSemanticsNodes().isNotEmpty()
        }
        awaitFocused("Selected: 5 minutes")
        repeat(4) {
            compose.onNodeWithText("Skip interval").performKeyInput {
                keyDown(Key.DirectionUp); keyUp(Key.DirectionUp)
            }
        }
        awaitFocused("10 seconds")
        compose.onNodeWithText("10 seconds").assertIsDisplayed()
        compose.onNodeWithText("10 seconds").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Skip: 10s").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip forward 10 seconds").assertExists()
        compose.runOnIdle { assertEquals(10_000L, amount) }
        screenshot("teltv-skip-chooser-picked")
    }
}
