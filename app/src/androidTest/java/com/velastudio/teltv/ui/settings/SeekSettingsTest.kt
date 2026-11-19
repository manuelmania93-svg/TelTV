package com.velastudio.teltv.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.ui.player.PlaybackPrefs
import com.velastudio.teltv.player.SeekSteps
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SeekSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyChoiceIsSelectableAndRemoteActivatesMinutes() {
        var selected = 10_000L
        compose.setContent {
            var amount by remember { mutableStateOf(10_000L) }
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)
            ) {
                androidx.tv.material3.MaterialTheme {
                    PlaybackSettingsSection(amount) { amount = it; selected = it }
                }
            }
        }
        SeekSteps.choicesMs.forEach { amount ->
            val node = compose.onNodeWithText(SeekSteps.label(amount), substring = true)
            node.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            node.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(amount, selected) }
        }
    }

    @Test fun dpadReachesBothRowsWithoutTouchInput() {
        var selected = 10_000L
        compose.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)
            ) {
                androidx.tv.material3.MaterialTheme {
                    PlaybackSettingsSection(selected) { selected = it }
                }
            }
        }
        compose.onNodeWithText("5 seconds", substring = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput {
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
            keyDown(Key.DirectionRight); keyUp(Key.DirectionRight)
        }
        compose.onNodeWithText("30 seconds", substring = true).assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(30_000L, selected) }
        compose.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
        compose.onNodeWithText("5 minutes", substring = true).assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(300_000L, selected) }
        compose.onRoot().performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.onNodeWithText("2 minutes", substring = true).assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(120_000L, selected) }
        compose.onRoot().performKeyInput { keyDown(Key.DirectionLeft); keyUp(Key.DirectionLeft) }
        compose.onNodeWithText("1 minute", substring = true).assertIsFocused()
        compose.onRoot().performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(60_000L, selected) }
    }

    @Test fun minutePlayerButtonsActivateBothDirections() {
        var amount by mutableStateOf(60_000L)
        var backs = 0
        var forwards = 0
        compose.setContent {
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)
            ) {
                androidx.tv.material3.MaterialTheme {
                    com.velastudio.teltv.ui.player.PlaybackControlsOverlay(
                        title = "Minute skip controls", isPlaying = false,
                        currentPositionMs = 600_000L, durationMs = 3_600_000L,
                        skipIncrementMs = amount, visible = true,
                        onPlayPause = {}, onSkipBack = { backs++ }, onSkipForward = { forwards++ },
                        onOpenTracks = {}, onCycleAspectRatio = {}, onOpenExternal = null,
                        autoPlayNext = true, onToggleAutoPlay = {}
                    )
                }
            }
        }
        listOf(60_000L, 120_000L, 300_000L, 30_000L).forEachIndexed { index, choice ->
            compose.runOnIdle { amount = choice }
            listOf("Skip back", "Skip forward").forEach { direction ->
                val node = compose.onNodeWithContentDescription("$direction ${SeekSteps.label(choice)}")
                node.assertIsDisplayed().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                node.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            }
            compose.runOnIdle {
                assertEquals(index + 1, backs)
                assertEquals(index + 1, forwards)
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/teltv-minute-player-controls.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test fun minuteChoicesPersistThroughNewPreferenceInstance() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = PlaybackPrefs(context)
        val previous = prefs.skipIncrementMs.first()
        try {
            SeekSteps.choicesMs.forEach { amount ->
                prefs.setSkipIncrementMs(amount)
                assertEquals(amount, PlaybackPrefs(context).skipIncrementMs.first())
            }
        } finally { prefs.setSkipIncrementMs(previous) }
    }

    @Test fun captureFullTvSkipChoices() {
        compose.setContent {
            androidx.tv.material3.MaterialTheme { PlaybackSettingsSection(30_000L) {} }
        }
        val activity = compose.activity
        val dm = activity.resources.displayMetrics
        val widthPx = (1280 * dm.density).toInt()
        val heightPx = (720 * dm.density).toInt()
        activity.runOnUiThread {
            val view = androidx.compose.ui.platform.ComposeView(activity)
            view.setContent {
                androidx.tv.material3.MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) {
                        PlaybackSettingsSection(30_000L) {}
                    }
                }
            }
            activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                .addView(view, android.view.ViewGroup.LayoutParams(widthPx, heightPx))
            view.tag = "seek-tv-capture"
        }
        compose.waitForIdle()
        val latch = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        activity.runOnUiThread {
            try {
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val view = root.findViewWithTag<androidx.compose.ui.platform.ComposeView>("seek-tv-capture")
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, widthPx, heightPx)
                val bitmap = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "teltv-seek-choices-tv.png")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                }
                val uri = activity.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw AssertionError("MediaStore insert failed")
                activity.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                root.removeView(view)
            } catch (t: Throwable) { failure = t }
            latch.countDown()
        }
        assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS))
        failure?.let { throw AssertionError("TV seek render failed", it) }
    }
}
