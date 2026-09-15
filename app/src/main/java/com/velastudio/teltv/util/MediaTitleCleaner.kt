package com.velastudio.teltv.util

object MediaTitleCleaner {
    private val EXT_REGEX = Regex("(?i)\\.(mkv|mp4|avi|mov|wmv|flv|webm|ts)$")
    private val CHANNEL_HANDLE = Regex("@[a-zA-Z0-9_]+|https?://\\S+|t\\.me/\\S+")
    private val BRACKETS_REGEX = Regex("\\[[^\\]]*\\]|\\([^\\)]*\\)")
    // Match each quality/codec tag on its own. The previous trailing `.*` swallowed the rest of
    // the filename, so titles like "Show.1080p.S01E02.WEBRip" lost S01E02 and broke next-episode
    // matching plus subtitle/TMDB lookup.
    private val TAG_REGEX = Regex(
        "(?i)\\b(?:1080p|720p|2160p|4k|uhd|bluray|webrip|web-dl|web|hdtv|x264|x265|hevc|av1|aac|dts|ddp5\\.1|ac3|remux|sub|dub|dual[- ]audio)\\b"
    )

    fun clean(rawTitle: String): String {
        var t = rawTitle.replace(EXT_REGEX, "")
        t = t.replace(CHANNEL_HANDLE, "")
        t = t.replace(BRACKETS_REGEX, "")
        t = t.replace(".", " ").replace("_", " ")
        t = t.replace(TAG_REGEX, "")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.replace(Regex("(?i)\\s+(s\\d{1,2}e\\d{1,2})"), " - $1")
        return if (t.isNotBlank()) t else rawTitle
    }
}
