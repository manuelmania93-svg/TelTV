package com.velastudio.teltv.ui.home

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.telegram.TelegramClient
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.theme.TelTvTheme
import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeCacheClearTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun quickClearShowsBusyAndEnablesRetry() {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(400)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val clearing = mutableStateOf(false)
        val loader = ThumbnailLoader(TelegramClient(compose.activity), scope,
            DeviceCapabilities.profile(compose.activity))
        var requests = 0
        try {
            val home: @Composable () -> Unit = {
                TelTvTheme {
                    HomeScreen(thumbnailLoader = loader,
                        continueWatching = emptyList(), recentlyWatched = emptyList(), favorites = emptyList(),
                        pinned = HomeRow("Pinned", emptyList()), allChannels = HomeRow("Channels", emptyList()),
                        folderRows = emptyList(), otherSources = HomeRow("Other", emptyList()),
                        onOpenEntry = {}, onResumeWatching = {}, onRemoveFromHistory = {},
                        onRemoveFavorite = {}, onOpenSearch = {}, onOpenSettings = {},
                        onQuickClearCache = { requests++; clearing.value = true },
                        isClearingCache = clearing.value)
                }
            }
            compose.setContent {
                val dm = compose.activity.resources.displayMetrics
                val fit = minOf(dm.widthPixels / dm.density / 1280f, dm.heightPixels / dm.density / 720f)
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Box(Modifier.requiredSize(1280.dp, 720.dp).graphicsLayer {
                        scaleX = fit; scaleY = fit
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    }) { home() }
                }
            }
            compose.waitForIdle()
            compose.onAllNodesWithText("Later").fetchSemanticsNodes().firstOrNull()?.let {
                compose.onNodeWithText("Later").performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            compose.onNodeWithContentDescription("Clear cache").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Clear Cache").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Clearing...").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Clear Cache?").assertDoesNotExist()
            assertEquals(1, requests)
            captureTvHome(home)
            compose.runOnIdle { clearing.value = false }
            compose.onNodeWithContentDescription("Clear cache").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("Clear Cache").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(2, requests)
        } finally { scope.cancel() }
    }
    private fun captureTvHome(content: @Composable () -> Unit) {
        val activity = compose.activity
        val dm = activity.resources.displayMetrics
        val widthPx = (1280 * dm.density).toInt()
        val heightPx = (720 * dm.density).toInt()
        activity.runOnUiThread {
            val view = androidx.compose.ui.platform.ComposeView(activity)
            view.setContent {
                androidx.tv.material3.MaterialTheme {
                    Box(Modifier.fillMaxSize()) {
                        content()
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
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "teltv-home-manual-clearing-tv.png")
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

}
