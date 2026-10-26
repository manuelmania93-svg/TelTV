package com.velastudio.teltv.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the TV-remote bug where the remove-from-history confirmation
 * could not be operated on a real TV. The confirmation is now an in-composition overlay
 * in the home window (a separate Dialog window never received D-pad/OK keys on the TCL TV):
 * a single focusable overlay root handles all keys, and the Cancel/Remove selection is a
 * visible border + selected semantics instead of Compose focus.
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
        // Exactly the player helpers' form: executeShellCommand on this setup does not
        // interpret shell chains - "mkdir -p X && screencap ..." ran nothing after the &&.
        // /sdcard/Download already exists (the player PNGs land there).
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

    /**
     * Waits until the overlay root observably holds window focus. Only then do injected
     * remote keys take the overlay's onKeyEvent path - like a real TV, where the user
     * presses keys after the overlay has appeared and settled.
     */
    private fun awaitOverlayFocused() {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("remove-entry-overlay-root").fetchSemanticsNodes()
                .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
        }
    }

    private fun nodeFocused(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes()
            .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    /**
     * TVs are never in touch mode. Without this the CI emulator performs no directional
     * focus handling at the view root and injected D-pad events die at the decor view
     * before reaching Compose - a harness artifact, not app behavior.
     */
    private fun prepareRealKeyRoute() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    /**
     * Real window input route via the shell - the same input pipeline `adb shell input
     * keyevent` and a TV remote's keys take. UiAutomation.injectInputEvent is silently
     * refused on this CI image (BACK-TEST-EVIDENCE recorded down=false up=false while the
     * shell fallback worked), so real-key tests inject through the shell.
     */
    private fun inject(keyName: String) {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        AutoCloseInputStream(ua.executeShellCommand("input keyevent $keyName")).use { it.readBytes() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    /** One D-pad UP: absorbed by view-root focus search if no view holds focus yet, and
     *  trapped as a no-op by the overlay otherwise. Establishes the window's key route
     *  without changing any state. */
    private fun kickWindowKeyRoute() {
        inject("KEYCODE_DPAD_UP")
    }

    /** Programmatic focus requests silently no-op until the window is focus-ready (same
     *  reason the overlay steals focus in a loop), so retry until the grant is OBSERVED. */
    private fun awaitNodeFocused(text: String, request: () -> Unit) {
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching { request() }
            nodeFocused(text)
        }
    }

    @Test fun cancelIsSelectedByDefaultAndDialogRenders() {
        showDialog()
        // Non-destructive default: Cancel is selected first; Remove is one D-pad press away.
        compose.onNodeWithText("Cancel").assertIsSelected()
        compose.onNodeWithText("Remove from history").assertIsNotSelected()
        compose.onNodeWithText("Remove video?").assertIsDisplayed()
        compose.onNodeWithText("Remove from history").assertIsDisplayed()
        screenshot("remove-entry-dialog")
    }

    @Test fun dpadMovesBetweenCancelAndRemove() {
        showDialog()
        compose.onNodeWithText("Cancel").assertIsSelected()
        dpad(Key.DirectionLeft)
        compose.onNodeWithText("Remove from history").assertIsSelected()
        compose.onNodeWithText("Cancel").assertIsNotSelected()
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Cancel").assertIsSelected()
        screenshot("remove-entry-dialog-remove-focused")
    }

    @Test fun dpadCenterOnRemoveInvokesRemove() {
        var removed = false
        showDialog(onRemove = { removed = true })
        dpad(Key.DirectionLeft)
        compose.onNodeWithText("Remove from history").assertIsSelected()
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
        compose.onNodeWithText("Cancel").assertIsSelected()
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
        awaitOverlayFocused()
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
        compose.onNodeWithText("Cancel").assertIsSelected()
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
        compose.onNodeWithText("Cancel").assertIsSelected()
        dpad(Key.DirectionRight)
        compose.onNodeWithText("Remove from history").assertIsSelected()
        screenshot("remove-entry-dialog-remove-selected-right")
    }

    /**
     * The failure mode this overlay fixes only appeared with REAL window-level key events on
     * the TV, so exercise that exact route: UiAutomation.injectInputEvent goes through the
     * actual input pipeline to the focused window, like the remote - not Compose's synthetic
     * performKeyInput.
     */
    @Test fun realKeyInjectionSelectsRemoveAndActivatesIt() {
        var removed = false
        var dismissed = false
        showDialog(onRemove = { removed = true }, onDismiss = { dismissed = true })
        prepareRealKeyRoute()
        awaitOverlayFocused()
        kickWindowKeyRoute()

        inject("KEYCODE_DPAD_RIGHT")
        compose.onNodeWithText("Remove from history").assertIsSelected()
        inject("KEYCODE_DPAD_CENTER")
        compose.waitForIdle()
        val evidence = "removed=$removed; dismissed=$dismissed"
        System.out.println("REAL-KEY-TEST-EVIDENCE: $evidence")
        assertTrue(evidence, removed)
        assertFalse(dismissed)
    }

    @Test fun upDownKeysNeitherActivateNorDismiss() {
        var removed = false
        var dismissed = false
        showDialog(onRemove = { removed = true }, onDismiss = { dismissed = true })
        awaitOverlayFocused()
        dpad(Key.DirectionUp)
        dpad(Key.DirectionDown)
        compose.waitForIdle()
        assertFalse(removed)
        assertFalse(dismissed)
        compose.onNodeWithText("Remove video?").assertIsDisplayed()
    }

    /**
     * Focus hand-back after Cancel/Back: the long-pressed card must regain remote focus,
     * otherwise dismissing the overlay drops the user to the screen default and they lose
     * their place in the row. Uses the real window input route (UiAutomation), like the remote.
     */
    @Test fun backFromOverlayRestoresFocusToTheCard() {
        lateinit var showOverlay: androidx.compose.runtime.MutableState<Boolean>
        val cardFocusHolder = arrayOfNulls<androidx.compose.ui.focus.FocusRequester>(1)
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                val cardFocus = remember {
                    FocusRequester().also { cardFocusHolder[0] = it }
                }
                val scope = rememberCoroutineScope()
                showOverlay = remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box(Modifier) {
                    androidx.compose.material3.Button(
                        onClick = {},
                        modifier = Modifier.focusRequester(cardFocus)
                    ) {
                        androidx.compose.material3.Text("history-card")
                    }
                    if (showOverlay.value) {
                        RemoveEntryDialog(
                            title = "Some Episode - S01E01",
                            actionLabel = "Remove from history",
                            onRemove = {},
                            onDismiss = {
                                showOverlay.value = false
                                restoreFocusWithRetry(scope, listOf(cardFocus))
                            }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        prepareRealKeyRoute()
        awaitNodeFocused("history-card") { cardFocusHolder[0]!!.requestFocus() }
        compose.onNodeWithText("history-card").assertIsFocused()
        compose.runOnIdle { showOverlay.value = true }
        compose.waitForIdle()
        compose.onNodeWithText("Remove video?").assertIsDisplayed()
        awaitOverlayFocused()
        compose.onNodeWithText("history-card").assertIsNotFocused()

        inject("KEYCODE_BACK")

        compose.onNodeWithText("Remove video?").assertDoesNotExist()
        // Observed grant, not just requests: the production restoreFocusWithRetry
        // re-requests for its full window; this waits for the card to really hold focus.
        compose.waitUntil(timeoutMillis = 3_000) { nodeFocused("history-card") }
        compose.onNodeWithText("history-card").assertIsFocused()
    }

    /**
     * Focus hand-back after Remove: the deleted card's focus requester detaches during the
     * removal recomposition, so focus must walk to the nearest remaining card - modelled here
     * by deleting the focused item from the list while the overlay closes.
     */
    @Test fun removeMovesFocusToNearestRemainingCard() {
        lateinit var showOverlay: androidx.compose.runtime.MutableState<Boolean>
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                val items = remember { androidx.compose.runtime.mutableStateListOf("A", "B") }
                val requesters = remember { mutableMapOf<String, FocusRequester>() }
                val scope = rememberCoroutineScope()
                showOverlay = remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box(Modifier) {
                    androidx.compose.foundation.layout.Row {
                        items.forEach { label ->
                            androidx.compose.material3.Button(
                                onClick = {},
                                modifier = Modifier.focusRequester(
                                    requesters.getOrPut(label) { FocusRequester() }
                                )
                            ) {
                                androidx.compose.material3.Text("card-$label")
                            }
                        }
                    }
                    if (showOverlay.value) {
                        RemoveEntryDialog(
                            title = "Some Episode - S01E01",
                            actionLabel = "Remove from history",
                            onRemove = {
                                items.remove("A")
                                showOverlay.value = false
                                // Mirrors the production wrapper: after a removal the card's
                                // own requester is excluded because it only detaches once the
                                // deletion recomposes.
                                restoreFocusWithRetry(scope, listOfNotNull(requesters["B"]))
                            },
                            onDismiss = { showOverlay.value = false }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        prepareRealKeyRoute()
        compose.runOnIdle { showOverlay.value = true }
        compose.waitForIdle()
        awaitOverlayFocused()
        kickWindowKeyRoute()

        inject("KEYCODE_DPAD_RIGHT")
        compose.onNodeWithText("Remove from history").assertIsSelected()
        inject("KEYCODE_DPAD_CENTER")
        compose.waitForIdle()

        compose.onNodeWithText("Remove video?").assertDoesNotExist()
        compose.onNodeWithText("card-A").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 3_000) { nodeFocused("card-B") }
        compose.onNodeWithText("card-B").assertIsFocused()
    }

    /**
     * The original failing timing, kept as a regression test against the REAL production
     * structure: a focus-gated LazyColumn of focusable cards (exactly HomeScreen's
     * `focusProperties { canFocus = removeRequest == null }` subtree gate) with the overlay
     * as a sibling. A card holds focus; the overlay appears; OK presses injected immediately
     * through the real input route (a fast remote press) must never activate the content
     * behind while the overlay is open, the card's focus must be cleared, and the card must
     * be unable to regain focus while gated. The harness keeps the overlay open on dismiss
     * calls, so every assertion isolates the "overlay open" window; dismissal and refocus
     * after a real close are covered by backFromOverlayRestoresFocusToTheCard. Back must
     * dismiss exactly once regardless of focus state, and the overlay must acquire focus on
     * its own, observably and bounded.
     */
    @Test fun keysInjectedDuringOverlayFocusHandoffCannotActivateUnderlyingContent() {
        var cardClicks = 0
        var tabClicks = 0
        var overlayRemoved = false
        var dismissCalls = 0
        lateinit var showOverlay: androidx.compose.runtime.MutableState<Boolean>
        val cardFocusHolder = arrayOfNulls<FocusRequester>(1)
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                val cardFocus = remember {
                    FocusRequester().also { cardFocusHolder[0] = it }
                }
                showOverlay = remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box {
                    // Same gate as production HomeScreen's LazyColumn.
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.focusProperties { canFocus = !showOverlay.value }
                    ) {
                        item {
                            androidx.compose.material3.Button(
                                onClick = { cardClicks++ },
                                modifier = Modifier.focusRequester(cardFocus)
                            ) {
                                androidx.compose.material3.Text("shelf-card")
                            }
                        }
                        item {
                            androidx.compose.material3.Button(onClick = { tabClicks++ }) {
                                androidx.compose.material3.Text("filter-tab")
                            }
                        }
                    }
                    if (showOverlay.value) {
                        RemoveEntryDialog(
                            title = "Some Episode - S01E01",
                            actionLabel = "Remove from history",
                            onRemove = { overlayRemoved = true },
                            // Deliberately NOT closing: isolates the overlay-open window.
                            onDismiss = { dismissCalls++ }
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        prepareRealKeyRoute()
        awaitNodeFocused("shelf-card") { cardFocusHolder[0]!!.requestFocus() }

        compose.runOnIdle { showOverlay.value = true }
        compose.waitForIdle()
        // Overlay is visible; its focus grant may still be in flight. The previously focused
        // card must already be unfocused and unable to regain focus under the gate.
        compose.waitUntil(timeoutMillis = 3_000) { !nodeFocused("shelf-card") }
        compose.onNodeWithText("shelf-card").assertIsNotFocused()
        compose.runOnIdle { cardFocusHolder[0]!!.requestFocus() }
        compose.waitForIdle()
        compose.runOnIdle { cardFocusHolder[0]!!.requestFocus() }
        compose.waitForIdle()
        compose.onNodeWithText("shelf-card").assertIsNotFocused()

        // Immediate OK presses - the exact timing that previously reached the content behind.
        // Safe outcomes while the overlay is open: keys hit nothing (grant in flight) or hit
        // the overlay's default Cancel (a dismiss call). Never a click behind, never Remove.
        repeat(3) { inject("KEYCODE_DPAD_CENTER") }

        assertEquals("underlying card activated while the overlay was open", 0, cardClicks)
        assertEquals("underlying tab activated while the overlay was open", 0, tabClicks)
        assertFalse("Remove must never trigger without the overlay holding focus first", overlayRemoved)
        compose.onNodeWithText("Remove video?").assertIsDisplayed()

        // Back escape works in every focus state - activity BackHandler before the grant,
        // the root's onKeyEvent after - and fires exactly once per press.
        val before = dismissCalls
        inject("KEYCODE_BACK")
        compose.waitUntil(timeoutMillis = 3_000) { dismissCalls == before + 1 }
        compose.onNodeWithText("Remove video?").assertIsDisplayed()

        // No extra user action: the overlay acquires focus by itself, observably, bounded.
        awaitOverlayFocused()
    }

    /**
     * Back safety in touch mode (the emulator default, no real key route prepared): injected
     * D-pad keys never reach Compose here, but Back must STILL dismiss the overlay through
     * the activity-level BackHandler. This is the focus-grant-failed worst case: the user is
     * never stuck with a dialog they cannot close.
     */
    @Test fun backDismissesOverlayInTouchModeWithoutFocusGrant() {
        var dismissed = false
        showDialog(onDismiss = { dismissed = true })
        // Deliberately no prepareRealKeyRoute() and no awaitOverlayFocused().
        inject("KEYCODE_BACK")
        compose.waitUntil(timeoutMillis = 3_000) { dismissed }
    }

    /**
     * The overlay acquires window focus by itself after appearing - no extra user action -
     * observably and within a bounded time.
     */
    @Test fun overlayAcquiresFocusWithoutUserAction() {
        showDialog()
        awaitOverlayFocused()
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
        awaitOverlayFocused()
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
