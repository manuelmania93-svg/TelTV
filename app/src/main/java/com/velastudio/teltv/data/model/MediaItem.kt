package com.velastudio.teltv.data.model

/** Where a piece of media physically comes from. */
enum class SourceType { TELEGRAM, NAS_SMB, WEBDAV, LOCAL, GOOGLE_DRIVE }

/**
 * A single playable item in the library, regardless of which backend it came from.
 * The Telegram-backed items are produced by the titan_vault index; NAS/WebDAV/local
 * items are produced by walking those sources directly on-device.
 */
data class MediaItem(
    val id: String,                 // stable id: e.g. "tg:<channel_id>:<message_id>"
    val sourceType: SourceType,
    val title: String,
    val subtitle: String? = null,   // e.g. season/episode, folder path
    val category: String? = null,   // Movies / Series / Anime / Uncategorized, from tags
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val thumbnailUrl: String? = null,
    val streamUrl: String,          // resolved at browse-time: backend stream URL or smb/webdav/file URI
    val addedAtEpochSec: Long = 0L,
    val resumePositionMs: Long = 0L
)

data class MediaCategory(
    val name: String,
    val itemCount: Int
)

/** A configured external source stored locally. */
data class MediaSource(
    val id: String,
    val type: SourceType,
    val displayName: String,
    // NAS
    val host: String? = null,
    val port: Int? = null,
    val shareName: String? = null,
    val folder: String? = null,
    // WebDAV
    val serverUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val domain: String? = null
)
