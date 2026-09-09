package com.velastudio.teltv.ui.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackDataStore by preferencesDataStore(name = "playback_prefs")

/** Lets Settings offer a simple "Skip amount: 5s / 10s" choice, used by the player controls. */
class PlaybackPrefs(private val context: Context) {
    companion object {
        val KEY_SKIP_MS = longPreferencesKey("skip_increment_ms")
        const val DEFAULT_SKIP_MS = 10_000L
    }

    val skipIncrementMs: Flow<Long> = context.playbackDataStore.data.map {
        it[KEY_SKIP_MS] ?: DEFAULT_SKIP_MS
    }

    suspend fun setSkipIncrementMs(ms: Long) {
        context.playbackDataStore.edit { it[KEY_SKIP_MS] = ms }
    }
}
