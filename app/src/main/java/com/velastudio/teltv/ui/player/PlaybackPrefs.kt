package com.velastudio.teltv.ui.player

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playbackDataStore by preferencesDataStore(name = "playback_prefs")

/** Lets Settings offer a simple "Skip amount: 5s / 10s" choice, used by the player controls. */
class PlaybackPrefs(private val context: Context) {
    companion object {
        val KEY_SKIP_MS = longPreferencesKey("skip_increment_ms")
        val KEY_AUTOPLAY = booleanPreferencesKey("autoplay_enabled")
        val KEY_DIALOGUE_BOOST = booleanPreferencesKey("dialogue_boost")
        val KEY_FAST_MODE = booleanPreferencesKey("fast_mode_enabled")
        val KEY_SHOW_PINNED = booleanPreferencesKey("show_pinned_videos")
        val KEY_SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
        val KEY_SUBTITLE_COLOR = stringPreferencesKey("subtitle_color")
        const val DEFAULT_SKIP_MS = 10_000L
        const val DEFAULT_AUTOPLAY = true
    }

    val dialogueBoostEnabled: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_DIALOGUE_BOOST] ?: false
    }

    suspend fun setDialogueBoostEnabled(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_DIALOGUE_BOOST] = enabled }
    }

    val subtitleSize: Flow<String> = context.playbackDataStore.data.map {
        it[KEY_SUBTITLE_SIZE] ?: "LARGE"
    }

    suspend fun setSubtitleSize(size: String) {
        context.playbackDataStore.edit { it[KEY_SUBTITLE_SIZE] = size }
    }

    val subtitleColor: Flow<String> = context.playbackDataStore.data.map {
        it[KEY_SUBTITLE_COLOR] ?: "WHITE"
    }

    suspend fun setSubtitleColor(color: String) {
        context.playbackDataStore.edit { it[KEY_SUBTITLE_COLOR] = color }
    }

    val autoPlayEnabled: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_AUTOPLAY] ?: DEFAULT_AUTOPLAY
    }

    suspend fun setAutoPlayEnabled(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_AUTOPLAY] = enabled }
    }

    val skipIncrementMs: Flow<Long> = context.playbackDataStore.data.map {
        it[KEY_SKIP_MS] ?: DEFAULT_SKIP_MS
    }

    suspend fun setSkipIncrementMs(ms: Long) {
        context.playbackDataStore.edit { it[KEY_SKIP_MS] = ms }
    }

    val fastModeEnabled: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_FAST_MODE] ?: false
    }

    suspend fun setFastModeEnabled(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_FAST_MODE] = enabled }
    }


    val showPinnedVideos: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_SHOW_PINNED] ?: true
    }

    suspend fun setShowPinnedVideos(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_SHOW_PINNED] = enabled }
    }

}