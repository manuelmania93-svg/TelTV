package com.velastudio.teltv.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    @Test fun removeButtonHasInitialFocus() {
        showDialog()
        compose.onNodeWithText("Remove from history").assertIsFocused()
    }

    @Test fun dpadMovesBetweenRemoveAndCancel() {
        showDialog()
        compose.onNodeWithText("Remove from history").assertIsFocused()
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Cancel").assertIsFocused()
        dpad(Key.DirectionLeft)
        compose.onNodeWithText("Remove from history").assertIsFocused()
    }

    @Test fun dpadCenterOnRemoveInvokesRemove() {
        var removed = false
        showDialog(onRemove = { removed = true })
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
        dpad(Key.DirectionRight)
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertTrue(dismissed)
        assertFalse(removed)
    }
}
