package com.velastudio.teltv.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.velastudio.teltv.player.SubtitleSync
import com.velastudio.teltv.player.SubtitleTiming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedSubtitlePrefsTest {
    @Test fun resetAndCustomSettingsPersistAndStayIsolated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = "speed-test-${System.nanoTime()}"
        val prefs = PlaybackPrefs(context)
        assertEquals(SubtitleTiming.SCALE_23976_TO_25, prefs.getSubtitleSync(video, "sub-a").scale, 0.0)
        prefs.setSubtitleSync(video, "sub-a", SubtitleSync())
        val reopened = PlaybackPrefs(context)
        assertEquals(SubtitleSync(), reopened.getSubtitleSync(video, "sub-a"))
        assertEquals(SubtitleTiming.SCALE_23976_TO_25, reopened.getSubtitleSync(video, "sub-b").scale, 0.0)
        assertEquals(SubtitleTiming.SCALE_23976_TO_25, reopened.getSubtitleSync("$video-other", "sub-a").scale, 0.0)
        val custom = SubtitleSync(2100, 1.04271)
        reopened.setSubtitleSync(video, "sub-a", custom)
        assertEquals(custom, PlaybackPrefs(context).getSubtitleSync(video, "sub-a"))
        reopened.setSubtitleSync(video, "sub-a", SubtitleSync())
        assertEquals(SubtitleSync(), PlaybackPrefs(context).getSubtitleSync(video, "sub-a"))
    }
}
