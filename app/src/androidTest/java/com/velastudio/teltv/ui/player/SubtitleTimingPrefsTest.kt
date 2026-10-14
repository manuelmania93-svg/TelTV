package com.velastudio.teltv.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.player.SubtitleSync
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitleTimingPrefsTest {
    @Test fun aDifferentSubtitleStartsCleanAndOriginalRestores() = runBlocking {
        val prefs = PlaybackPrefs(InstrumentationRegistry.getInstrumentation().targetContext)
        val video = "prefs-test-${System.nanoTime()}"
        val correction = SubtitleSync(1200, 1.04271)
        try {
            prefs.setSubtitleSync(video, "release-a", correction)
            assertEquals(correction, prefs.getSubtitleSync(video, "release-a"))
            assertEquals(SubtitleSync(), prefs.getSubtitleSync(video, "release-b"))
            assertEquals(SubtitleSync(), prefs.getSubtitleSync("other-$video", "release-a"))
            assertEquals(correction, prefs.getSubtitleSync(video, "release-a"))
            prefs.setSubtitleSync(video, "release-a", SubtitleSync())
            assertEquals(SubtitleSync(), prefs.getSubtitleSync(video, "release-a"))
        } finally {
            prefs.setSubtitleSync(video, "release-a", SubtitleSync())
        }
    }
}
