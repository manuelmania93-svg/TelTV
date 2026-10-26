package com.velastudio.teltv.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the TV-remote bug where the remove-from-history confirmation
 * dialog could not be navigated: the dialog's wrapping Card was clickable, so it
 * grabbed the D-pad focus itself and neither "Remove from history" nor "Cancel"
 * could ever be focused (only Back dismissed the dialog).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RemoveEntryDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(250)
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = device.executeShellCommand("screencap -p /sdcard/Download/$name.png")
        AutoCloseInputStream(output).use { it.readBytes() }
    }

    private fun showDialog(onRemove: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                RemoveEntryDialog(
                    title = "Guardians of the Galaxy Vol. 3 - 2023",
                    actionLabel = "Remove from history",
                    onRemove = onRemove,
                    onDismiss = onDismiss
                )
            }
        }
        compose.waitForIdle()
    }

    private fun dpad(key: Key) {
        compose.onNodeWithText("Remove video?").performKeyInput {
            keyDown(key); keyUp(key)
        }
        compose.waitForIdle()
    }

    @Test fun cancelButtonHasInitialFocusAndDialogRenders() {
        showDialog()
        // Non-destructive default: Cancel is focused first; Remove is one D-pad-left away.
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onNodeWithText("Remove video?").assertIsDisplayed()
        compose.onNodeWithText("Remove from history").assertIsDisplayed()
        screenshot("remove-entry-dialog")
    }

    @Test fun dpadMovesBetweenCancelAndRemove() {
        showDialog()
        compose.onNodeWithText("Cancel").assertIsFocused()
        dpad(Key.DirectionLeft)
        compose.onNodeWithText("Remove from history").assertIsFocused()
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Cancel").assertIsFocused()
        screenshot("remove-entry-dialog-remove-focused")
    }

    @Test fun dpadCenterOnRemoveInvokesRemove() {
        var removed = false
        showDialog(onRemove = { removed = true })
        dpad(Key.DirectionLeft)
        compose.onNodeWithText("Remove from history").assertIsFocused()
        compose.onNodeWithText("Remove from history").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertTrue(removed)
    }

    @Test fun dpadCenterOnCancelDismissesWithoutRemoving() {
        var removed = false
        var dismissed = false
        showDialog(onRemove = { removed = true }, onDismiss = { dismissed = true })
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onNodeWithText("Cancel").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertTrue(dismissed)
        assertFalse(removed)
    }

    @Test fun backDismissesWithoutRemoving() {
        var removed = false
        var dismissed = false
        showDialog(onRemove = { removed = true }, onDismiss = { dismissed = true })
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation

        fun focusedWindow(): String {
            val out = ua.executeShellCommand(
                "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"
            )
            return AutoCloseInputStream(out).use { String(it.readBytes()) }
                .trim().replace(Regex("\\s+"), " ")
        }

        // Diagnostic run: record injection results and window focus so a failure
        // is explainable instead of a bare AssertionError.
        val focusedBefore = focusedWindow()
        // Route 1: inject through UiAutomation (the real input pipeline, like the TV remote).
        val downOk = ua.injectInputEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK), true)
        val upOk = ua.injectInputEvent(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK), true)
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        val dismissedByAutomation = dismissed
        if (!dismissed) {
            // Route 2 (fallback): shell-level injection, the same route adb input takes.
            AutoCloseInputStream(ua.executeShellCommand("input keyevent KEYCODE_BACK")).use { it.readBytes() }
            compose.waitForIdle()
            Thread.sleep(500)
            compose.waitForIdle()
        }
        val evidence = "injectInputEvent down=$downOk up=$upOk; " +
            "dismissedByAutomation=$dismissedByAutomation; dismissed=$dismissed; " +
            "focusedBefore=$focusedBefore; focusedAfter=${focusedWindow()}"
        System.out.println("BACK-TEST-EVIDENCE: $evidence")
        assertTrue(evidence, dismissed)
        assertFalse(removed)
    }

    @Test fun singleOkPressActivatesExactlyOnce() {
        var dismissCount = 0
        showDialog(onDismiss = { dismissCount++ })
        compose.onNodeWithText("Cancel").assertIsFocused()
        // One full press (down + up) must activate the selection exactly once; a stray
        // key-up must not re-trigger the action.
        compose.onNodeWithText("Cancel").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals(1, dismissCount)
    }

    @Test fun dpadRightAlsoMovesSelectionToRemove() {
        showDialog()
        compose.onNodeWithText("Cancel").assertIsFocused()
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Remove from history").assertIsFocused()
        screenshot("remove-entry-dialog-remove-selected-right")
    }

    @Test fun upDownKeysNeitherActivateNorDismiss() {
        var removed = false
        var dismissed = false
        showDialog(onRemove = { removed = true }, onDismiss = { dismissed = true })
        dpad(Key.DirectionUp)
        dpad(Key.DirectionDown)
        compose.waitForIdle()
        assertFalse(removed)
        assertFalse(dismissed)
        compose.onNodeWithText("Remove video?").assertIsDisplayed()
    }

    @Test fun dpadKeysDoNotReachContentBehindDialog() {
        var backgroundClicks = 0
        var dismissed = false
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                androidx.compose.material3.Button(onClick = { backgroundClicks++ }) {
                    androidx.compose.material3.Text("background-action")
                }
                RemoveEntryDialog(
                    title = "Some Episode - S01E01",
                    actionLabel = "Remove from history",
                    onRemove = {},
                    onDismiss = { dismissed = true }
                )
            }
        }
        compose.waitForIdle()
        dpad(Key.DirectionLeft)
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Cancel").performKeyInput {
            keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertTrue(dismissed)
        assertEquals(0, backgroundClicks)
    }
}
