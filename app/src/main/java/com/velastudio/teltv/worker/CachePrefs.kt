package com.velastudio.teltv.worker

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.velastudio.teltv.telegram.CacheManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cacheDataStore by preferencesDataStore(name = "cache_prefs")

/**
 * Single source of truth for the cache auto-clear settings shown in Settings
 * ([com.velastudio.teltv.ui.settings.CacheSettingsSection]) and enforced in the background by
 * [CacheTrimWorker].
 *
 * Previously these lived in two disconnected places: Settings held the toggle/limit as local
 * Compose `remember` state (never persisted, reset on every screen visit), while
 * [CacheTrimWorker] read from this same DataStore file/keys -- which nothing ever wrote to, so
 * it always ran with defaults regardless of what the person chose. Both sides now go through
 * this one class, which is the only thing that touches [cacheDataStore].
 *
 * (There was also a third, unused set of key definitions on [CacheManager]'s companion object
 * that never backed an actual DataStore -- removed as part of this fix rather than left as a
 * third, misleading definition of the same setting.)
 */
class CachePrefs(private val context: Context) {
    companion object {
        val KEY_AUTO_CLEAR_ENABLED = stringPreferencesKey("auto_clear_enabled")
        val KEY_LIMIT_BYTES = longPreferencesKey("auto_clear_limit_bytes")
    }

    /** Defaults to on, matching CacheTrimWorker's prior "!= \"false\"" fallback behavior. */
    val autoClearEnabled: Flow<Boolean> = context.cacheDataStore.data.map { prefs ->
        prefs[KEY_AUTO_CLEAR_ENABLED] != "false"
    }

    val limitBytes: Flow<Long> = context.cacheDataStore.data.map { prefs ->
        prefs[KEY_LIMIT_BYTES] ?: CacheManager.DEFAULT_LIMIT_BYTES
    }

    suspend fun setAutoClearEnabled(enabled: Boolean) {
        context.cacheDataStore.edit { it[KEY_AUTO_CLEAR_ENABLED] = if (enabled) "true" else "false" }
    }

    suspend fun setLimitBytes(bytes: Long) {
        context.cacheDataStore.edit { it[KEY_LIMIT_BYTES] = bytes }
    }
}
