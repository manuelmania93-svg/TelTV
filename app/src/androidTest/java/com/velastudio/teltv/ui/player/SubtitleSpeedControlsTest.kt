package com.velastudio.teltv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SubtitleSpeedControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun presetsAndResetAreDisplayedAndRemoteOperable() {
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        var chosen = 0.0
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.DarkGray).padding(24.dp)) {
                    Column(Modifier.width(320.dp)) {
                        SubtitleSpeedControls(true, 0.95904) { chosen = it }
                    }
                }
            }
        }
        val first = compose.onNodeWithText("23.976>25 (x0.959)")
        val reverse = compose.onNodeWithText("25>23.976 (x1.043)")
        first.assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        first.assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(23.976 / 25, chosen, 0.0) }
        first.performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        reverse.assertIsDisplayed().assertIsFocused().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(25 / 23.976, chosen, 0.0) }
        val reset = compose.onNodeWithText("Reset")
        reset.assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        reset.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(1.0, chosen, 0.0) }
        compose.onNodeWithText("-0.001").assertIsDisplayed()
        compose.onNodeWithText("+0.001").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        Thread.sleep(300)
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/subtitle-speed-controls.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
