package com.velastudio.teltv.ui.player

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.ui.settings.SubDlSettingsSection
import com.velastudio.teltv.util.SubDlKeyStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
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
        val add = compose.onNodeWithText("Add API key")
        add.assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        add.performKeyInput { keyDown(Key.DirectionCenter); keyUp(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cancel").assertExists().performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
        // Capture the non-secret settings card, not the protected key entry dialog.
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/subdl-settings.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
