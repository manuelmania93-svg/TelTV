package com.velastudio.teltv.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "Last cleanup freed X" line in Storage & Cache: renders when a cleanup actually freed
 * space, hidden before the first one. Screenshot lands in the CI artifact for pixel review.
 */
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

    private fun showSection(lastFreed: Long?, lastAt: Long?) {
        compose.setContent {
            androidx.tv.material3.MaterialTheme {
                CacheSettingsSection(
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
            }
        }
        compose.waitForIdle()
    }

    @Test fun freedSpaceNoticeVisibleAfterCleanup() {
        showSection(1_610_612_736L, System.currentTimeMillis() - 2 * 3_600_000L)
        compose.onNodeWithText("Last cleanup freed 1.5 GB (2 h ago)").assertIsDisplayed()
        screenshot("teltv-settings-cleanup-notice")
    }

    @Test fun freedSpaceNoticeHiddenBeforeFirstCleanup() {
        showSection(null, null)
        compose.onNodeWithText("Storage & Cache").assertIsDisplayed()
        compose.onNodeWithText("Last cleanup freed", substring = true).assertDoesNotExist()
    }
}
