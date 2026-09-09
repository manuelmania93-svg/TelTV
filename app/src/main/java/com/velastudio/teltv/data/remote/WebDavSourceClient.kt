package com.velastudio.teltv.data.remote

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.MediaSource
import com.velastudio.teltv.data.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts")

/** Nextcloud / ownCloud / any generic WebDAV server, same fields as VelaTV's "Private cloud". */
class WebDavSourceClient {

    private fun client(source: MediaSource): OkHttpSardine {
        val sardine = OkHttpSardine()
        if (!source.username.isNullOrBlank()) {
            sardine.setCredentials(source.username, source.password.orEmpty())
        }
        return sardine
    }

    suspend fun listVideos(source: MediaSource, maxDepth: Int = 4): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val sardine = client(source)
            val root = source.serverUrl.orEmpty()
            val results = mutableListOf<MediaItem>()

            fun walk(url: String, depth: Int) {
                if (depth > maxDepth) return
                val resources = runCatching { sardine.list(url) }
                    .onFailure { Timber.e(it, "WebDAV list failed for %s", url) }
                    .getOrNull() ?: return
                for (r in resources) {
                    if (r.href.toString().trimEnd('/') == url.trimEnd('/')) continue // self
                    val childUrl = r.href.toString()
                    if (r.isDirectory) {
                        walk(childUrl, depth + 1)
                    } else {
                        val ext = r.name.substringAfterLast('.', "").lowercase()
                        if (ext in VIDEO_EXTENSIONS) {
                            results += MediaItem(
                                id = "webdav:${source.id}:${childUrl.hashCode()}",
                                sourceType = SourceType.WEBDAV,
                                title = r.name.substringBeforeLast('.'),
                                subtitle = source.displayName,
                                category = source.displayName,
                                sizeBytes = r.contentLength,
                                streamUrl = childUrl,
                                addedAtEpochSec = System.currentTimeMillis() / 1000
                            )
                        }
                    }
                }
            }
            walk(root, 0)
            results
        }

    suspend fun testConnection(source: MediaSource): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sardine = client(source)
            require(sardine.exists(source.serverUrl.orEmpty())) { "Server not reachable" }
        }.onFailure { Timber.e(it, "WebDAV testConnection failed for %s", source.serverUrl) }
    }
}
