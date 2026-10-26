package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the contract the CI version stamping (build.yml) relies on:
 * stamped version names like "0.3.59.313001" must survive [AppUpdater.cleanVersion]
 * and compare as strictly newer than the plain base "0.3.59" via [AppUpdater.isNewer],
 * so the in-app updater offers every new rolling build.
 */
class AppUpdaterVersionTest {

    @Test
    fun cleanVersionStripsReleaseNameDecoration() {
        assertEquals("0.3.59.313001", AppUpdater.cleanVersion("TelTV v0.3.59.313001"))
        assertEquals("0.3.59", AppUpdater.cleanVersion("TelTV v0.3.59"))
        assertEquals("0.3.59.313001", AppUpdater.cleanVersion("0.3.59.313001"))
    }

    @Test
    fun stampedVersionIsNewerThanBaseAndOlderStamps() {
        assertTrue(AppUpdater.isNewer("0.3.59.313001", "0.3.59"))
        assertTrue(AppUpdater.isNewer("0.3.59.313002", "0.3.59.313001"))
    }

    @Test
    fun baseAndSameStampAreNotNewer() {
        assertFalse(AppUpdater.isNewer("0.3.59", "0.3.59.313001"))
        assertFalse(AppUpdater.isNewer("0.3.59.313001", "0.3.59.313001"))
    }
}
