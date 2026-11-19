package com.velastudio.teltv.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.telegram.AppStorageUsage
import com.velastudio.teltv.worker.CachePrefs
import com.velastudio.teltv.worker.IdleCleanupPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class StorageMaintenanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val sample = AppStorageUsage(128L * 1024 * 1024, 1600L * 1024 * 1024,
        8L * 1024 * 1024, 2L * 1024 * 1024, 1024 * 1024, 512 * 1024)

    @Test fun storageShowsDatabaseSeparatelyAndRefreshIsExplicit() {
        var refreshes = 0
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                StorageUsageSection(sample, false) { refreshes++ }
            }
        }
        compose.onNodeWithText("Telegram database / support files / logs", substring = true).assertExists()
        compose.onNodeWithText("TelTV library / favorites / history database", substring = true).assertExists()
        compose.onNodeWithText("Refresh storage breakdown").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("Not enabled in this safe test build", substring = true).assertExists()
        capture("teltv-data-breakdown-tv.png") { StorageUsageSection(sample, false) {} }
    }

    @Test fun allIntervalAndRetentionChoicesActivateByRemote() {
        var selectedHours by mutableStateOf(4L)
        var selectedAge by mutableStateOf(14_400L)
        compose.setContent {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                androidx.tv.material3.MaterialTheme {
                    IdleCleanupSection(selectedHours, selectedAge, { selectedHours = it }, { selectedAge = it })
                }
            }
        }
        IdleCleanupPolicy.intervals.forEach { amount ->
            val node = compose.onNodeWithText("$amount hours", substring = true)
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            node.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(amount, selectedHours) }
        }
        IdleCleanupPolicy.retentions.forEach { amount ->
            // 4 hours exists both as interval and retention, so use the final matching node.
            val nodes = compose.onAllNodesWithText(IdleCleanupPolicy.retentionLabel(amount), substring = true)
            val node = nodes[nodes.fetchSemanticsNodes().lastIndex]
                .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            node.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(amount, selectedAge) }
        }
        capture("teltv-periodic-cleanup-tv.png") { IdleCleanupSection(4L, 14_400L, {}, {}) }
    }

    @Test fun newPolicyPreferencesPersistWithoutChangingExistingLimitOrToggle() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = CachePrefs(context)
        val oldHours = prefs.sweepHours.first()
        val oldAge = prefs.retentionSeconds.first()
        val oldLimit = prefs.limitBytes.first()
        val oldEnabled = prefs.autoClearEnabled.first()
        try {
            prefs.setAutoClearEnabled(false)
            IdleCleanupPolicy.intervals.forEach {
                prefs.setSweepHours(it)
                assertEquals(it, CachePrefs(context).sweepHours.first())
            }
            IdleCleanupPolicy.retentions.forEach {
                prefs.setRetentionSeconds(it)
                assertEquals(it, CachePrefs(context).retentionSeconds.first())
            }
            assertEquals(oldLimit, prefs.limitBytes.first())
            assertFalse(prefs.autoClearEnabled.first())
        } finally {
            prefs.setSweepHours(oldHours); prefs.setRetentionSeconds(oldAge)
            prefs.setAutoClearEnabled(oldEnabled)
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        val activity = compose.activity
        val density = activity.resources.displayMetrics.density
        val width = (1280 * density).toInt()
        val height = (720 * density).toInt()
        lateinit var view: androidx.compose.ui.platform.ComposeView
        activity.runOnUiThread {
            view = androidx.compose.ui.platform.ComposeView(activity)
            view.setContent { androidx.tv.material3.MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF101418)).padding(24.dp)) { content() }
            } }
            activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                .addView(view, android.view.ViewGroup.LayoutParams(width, height))
        }
        compose.waitForIdle()
        val done = java.util.concurrent.CountDownLatch(1)
        var failure: Throwable? = null
        activity.runOnUiThread {
            try {
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                val image = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                }
                val uri = activity.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                activity.contentResolver.openOutputStream(uri)!!.use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                (view.parent as android.view.ViewGroup).removeView(view)
            } catch (e: Throwable) { failure = e }
            done.countDown()
        }
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
        failure?.let { throw AssertionError("TV render failed", it) }
    }
}
