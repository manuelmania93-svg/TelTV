package com.velastudio.teltv.telegram

import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Resolves TDLib thumbnail paths for visible Compose cards.
 * Cancelling a card's Job removes its listener and stops awaiting the result.
 * The shared TDLib download is left running because another card may need it.
 * Resolved paths are bounded; decoded bitmap caching is handled by Coil.
 */
class ThumbnailLoader(
    private val telegram: TelegramClient,
    private val scope: CoroutineScope,
    private val deviceProfile: DeviceCapabilities.Profile
) {
    /** fileId -> local file path, bounded so it can't grow unbounded across a long session. */
    private val resolvedPaths = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean =
            size > maxCachedEntries
    }
    private val maxCachedEntries =
        if (deviceProfile.isConstrained) 150 else 500

    @Synchronized
    private fun cached(fileId: Int): String? = resolvedPaths[fileId]

    @Synchronized
    private fun cache(fileId: Int, path: String) {
        resolvedPaths[fileId] = path
    }

    fun request(fileId: Int, onResolved: (String) -> Unit): Job {
        if (fileId <= 0) return Job().apply { complete() }
        cached(fileId)?.takeIf { java.io.File(it).exists() }?.let {
            onResolved(it)
            return Job().apply { complete() }
        }
        return scope.launch {
            runCatching { telegram.downloadThumbnail(fileId) }
                .onSuccess { path ->
                    if (path.isNotBlank() && java.io.File(path).exists()) {
                        cache(fileId, path)
                        onResolved(path)
                    }
                }
                .onFailure { Timber.w(it, "Thumbnail download failed for fileId=%d", fileId) }
        }
    }
}
