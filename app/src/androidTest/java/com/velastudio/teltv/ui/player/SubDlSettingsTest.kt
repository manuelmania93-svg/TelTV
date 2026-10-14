package com.velastudio.teltv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.ui.settings.SubDlSettingsSection
import com.velastudio.teltv.util.SubDlKeyStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubDlSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun encryptedKeyPersistsAndRemovalDisablesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            SubDlKeyStore.save(context, "")
            assertEquals("", SubDlKeyStore.read(context))
            SubDlKeyStore.save(context, "mock-key-for-device-test")
            assertEquals("mock-key-for-device-test", SubDlKeyStore.read(context))
            val stored = context.getSharedPreferences("subdl_credentials", 0).getString("encrypted_key", "")!!
            assertFalse(stored.contains("mock-key-for-device-test"))
            SubDlKeyStore.save(context, "")
            assertEquals("", SubDlKeyStore.read(context))
        } finally { SubDlKeyStore.save(context, "") }
    }
    @Test fun settingsLayoutAndKeyDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SubDlKeyStore.save(context, "")
        compose.activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.setContent { androidx.tv.material3.MaterialTheme { SubDlSettingsSection() } }
        compose.waitForIdle()
        compose.onNodeWithText("Add API key").performClick()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        // Capture the non-secret settings card, not the protected key entry dialog.
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/subdl-settings.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
