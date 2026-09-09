package com.velastudio.teltv.telegram

import com.velastudio.teltv.util.DeviceCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Bridges TDLib thumbnail downloads to Compose's `AsyncImage`/Coil without ever letting an
 * off-screen row keep downloading in the background -- the single biggest cause of "channel with
 * 1,000s of videos scrolls fine for a second, then grinds to a halt" on a low-RAM box: every row
 * that's ever been on screen quietly kept fetching, and Coil's own memory cache filled up with
 * full-size decoded bitmaps for thumbnails nobody's looking at anymore.
 *
 * Usage from a Composable, per grid cell:
 * ```
 * DisposableEffect(fileId) {
 *     val job = thumbnailLoader.request(fileId) { path -> localPath = path }
 *     onDispose { job.cancel() }
 * }
 * ```
 * Cancelling the returned [Job] cancels the coroutine *and* tells TDLib to stop prioritizing that
 * download (see [TelegramClient.cancelDownload]), so it goes back to background/idle priority
 * instead of continuing to compete with whatever the player or the next visible row needs.
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
        cached(fileId)?.let {
            onResolved(it)
            return Job().apply { complete() }
        }
        return scope.launch {
            runCatching { telegram.downloadThumbnail(fileId) }
                .onSuccess { path ->
                    cache(fileId, path)
                    onResolved(path)
                }
                .onFailure { Timber.w(it, "Thumbnail download failed for fileId=%d", fileId) }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) {
                    telegram.cancelDownload(fileId)
                }
            }
        }
    }
}
