package com.velastudio.teltv.data.remote

import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.MediaSource
import com.velastudio.teltv.data.model.SourceType
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Properties

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts")

/**
 * Browses a Windows share / NAS over SMB, same fields as VelaTV's "Network share (NAS)"
 * screen: server address, port, share name, optional subfolder, username/password/domain.
 * Guest access is used when username is blank.
 */
class SmbSourceClient {

    private fun buildContext(source: MediaSource): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.connTimeout", "10000")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val user = source.username.orEmpty()
        val pass = source.password.orEmpty()
        return if (user.isBlank()) {
            base.withCredentials(jcifs.smb.NtlmPasswordAuthenticator())
        } else {
            base.withCredentials(NtlmPasswordAuthenticator(source.domain.orEmpty(), user, pass))
        }
    }

    private fun rootUrl(source: MediaSource): String {
        val port = source.port?.takeIf { it != 445 }?.let { ":$it" } ?: ""
        val folder = source.folder?.trim('/')?.let { "$it/" } ?: ""
        return "smb://${source.host}$port/${source.shareName}/$folder"
    }

    /** Recursively lists playable video files under the configured share/folder. */
    suspend fun listVideos(source: MediaSource, maxDepth: Int = 4): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val ctx = buildContext(source)
            val results = mutableListOf<MediaItem>()
            fun walk(url: String, depth: Int) {
                if (depth > maxDepth) return
                val dir = SmbFile(url, ctx)
                val children = runCatching { dir.listFiles() }
                    .onFailure { Timber.e(it, "SMB listFiles failed for %s", url) }
                    .getOrNull() ?: return
                for (f in children) {
                    when {
                        f.isDirectory -> walk(f.canonicalPath, depth + 1)
                        f.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS -> {
                            results += MediaItem(
                                id = "smb:${source.id}:${f.canonicalPath.hashCode()}",
                                sourceType = SourceType.NAS_SMB,
                                title = f.name.substringBeforeLast('.'),
                                subtitle = source.displayName,
                                category = source.displayName,
                                sizeBytes = runCatching { f.length() }.getOrNull(),
                                streamUrl = f.canonicalPath,
                                addedAtEpochSec = System.currentTimeMillis() / 1000
                            )
                        }
                    }
                }
            }
            walk(rootUrl(source), 0)
            results
        }

    /** "Test and connect" support, same as VelaTV's connection test button. */
    suspend fun testConnection(source: MediaSource): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ctx = buildContext(source)
            val dir = SmbFile(rootUrl(source), ctx)
            require(dir.exists()) { "Share not reachable or path does not exist" }
        }.onFailure { Timber.e(it, "SMB testConnection failed for %s", source.host) }
    }
}
