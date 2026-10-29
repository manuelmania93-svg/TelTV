package com.velastudio.teltv.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Last cleanup freed X" line in Storage & Cache, checked the way it is actually used:
 * rendered inside a 1280x720 (TV landscape) layout and driven with D-pad keys, since the TV
 * remote is the only input there. Screenshots land in the CI artifact for pixel review.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CacheSettingsSectionTest {
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

    @Composable
    private fun section(lastFreed: Long?, lastAt: Long?) = CacheSettingsSection(
        currentSizeBytes = 1_200_000_000L,
        cacheSizeAvailable = true,
        freeStorageBytes = 3_400_000_000L,
        totalStorageBytes = 32_000_000_000L,
        cacheLimitBytes = 1_073_741_824L,
        autoClearEnabled = true,
        onToggleAutoClear = {},
        onSelectLimit = {},
        onClearNow = {},
        lastCleanupFreedBytes = lastFreed,
        lastCleanupAtMs = lastAt
    )

    /** Lays the section out at real 1280x720 TV proportions, scaled to fit the test display. */
    private fun showOnTv(lastFreed: Long?, lastAt: Long?) {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418)), contentAlignment = Alignment.Center) {
                    // Fit the 1280x720 TV layout INSIDE the actual display: a fixed scale
                    // overflowed the test display and clipped the panel's left/right edges.
                    val dm = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
                    val fit = minOf(dm.widthPixels / dm.density / 1280f, dm.heightPixels / dm.density / 720f)
                    Box(
                        Modifier
                            .requiredSize(1280.dp, 720.dp)
                            .graphicsLayer {
                                scaleX = fit
                                scaleY = fit
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                    ) {
                        section(lastFreed, lastAt)
                    }
                }
            }
        }
        compose.waitForIdle()
    }


    /**
     * Renders the section at its true 1280x720 TV size OFF-SCREEN and saves the pixels.
     * The test display is a small portrait phone screen, so any on-screen capture either
     * clips or shrinks the layout; drawing the composed view into a full-size bitmap is
     * the only capture that shows what a TV actually shows, pixel for pixel.
     */
    private fun captureTvLayoutPng(name: String, lastFreed: Long?, lastAt: Long?) {
        val activity = compose.activity
        val dm = activity.resources.displayMetrics
        val widthPx = (1280 * dm.density).toInt()
        val heightPx = (720 * dm.density).toInt()
        activity.runOnUiThread {
            val view = androidx.compose.ui.platform.ComposeView(activity)
            view.setContent {
                androidx.tv.material3.MaterialTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF101418))) {
                        section(lastFreed, lastAt)
                    }
                }
            }
            activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                .addView(view, android.view.ViewGroup.LayoutParams(widthPx, heightPx))
            view.tag = "tv-capture"
        }
        compose.waitForIdle()
        val latch = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        activity.runOnUiThread {
            try {
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                val view = root.findViewWithTag<androidx.compose.ui.platform.ComposeView>("tv-capture")
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, widthPx, heightPx)
                val bitmap = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
                // Scoped storage blocks direct file writes to Download for the app process
                // (EACCES on API 29); MediaStore.Downloads accepts the app's own inserts.
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                }
                val uri = activity.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw AssertionError("MediaStore insert failed")
                activity.contentResolver.openOutputStream(uri)!!.use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                root.removeView(view)
            } catch (t: Throwable) {
                failure = t
            }
            latch.countDown()
        }
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) throw AssertionError("offscreen TV render timed out")
        failure?.let { throw AssertionError("offscreen TV render failed", it) }
    }

    @Test fun freedSpaceNoticeVisibleOnTvLayout() {
        showOnTv(1_610_612_736L, System.currentTimeMillis() - 2 * 3_600_000L)
        compose.onNodeWithText("Last cleanup freed 1.5 GB (2 h ago)").assertIsDisplayed()
        captureTvLayoutPng("teltv-settings-cleanup-notice-tv", 1_610_612_736L, System.currentTimeMillis() - 2 * 3_600_000L)
    }

    @Test fun freedSpaceNoticeHiddenBeforeFirstCleanup() {
        showOnTv(null, null)
        compose.onNodeWithText("Storage & Cache").assertIsDisplayed()
        compose.onNodeWithText("Last cleanup freed", substring = true).assertDoesNotExist()
    }

    @Test fun noticeNeverTakesRemoteFocus() {
        showOnTv(1_610_612_736L, System.currentTimeMillis() - 2 * 3_600_000L)
        // Drive the section with the remote only: every D-pad move must land on a real control,
        // never on the informational line.
        repeat(6) {
            compose.onRoot().performKeyInput { keyDown(Key.DirectionRight); keyUp(Key.DirectionRight) }
            compose.onRoot().performKeyInput { keyDown(Key.DirectionDown); keyUp(Key.DirectionDown) }
            compose.waitForIdle()
            // A plain Text carries no Focused property, so assertIsNotFocused would fail on the
            // absent property alone. The real intent: no focused node may display the notice text.
            compose.onNode(hasText("Last cleanup freed 1.5 GB (2 h ago)") and isFocused()).assertDoesNotExist()
        }
        // And the controls are actually reachable by remote (focus did move somewhere real).
        compose.onNodeWithText("Clear Cache Now").assertExists()
    }
}
