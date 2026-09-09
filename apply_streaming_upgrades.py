import os
import re

# 1. Create MediaTitleCleaner.kt
os.makedirs("app/src/main/java/com/velastudio/teltv/util", exist_ok=True)
with open("app/src/main/java/com/velastudio/teltv/util/MediaTitleCleaner.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.util

object MediaTitleCleaner {
    private val EXT_REGEX = Regex("(?i)\\\\.(mkv|mp4|avi|mov|wmv|flv|webm|ts)$")
    private val TAG_REGEX = Regex("(?i)\\\\b(1080p|720p|2160p|4k|uhd|bluray|webrip|web-dl|web|hdtv|x264|x265|hevc|av1|aac|dts|ddp5\\\\.1|ac3|sub|dub|dual-audio)\\\\b.*")
    private val BRACKETS_REGEX = Regex("\\[.*?\\]|\\(.*?\\)")
    private val CHANNEL_HANDLE = Regex("@\\\\w+|https?://\\\\S+|t\\\\.me/\\\\S+")

    fun clean(rawTitle: String): String {
        var t = rawTitle.replace(EXT_REGEX, "")
        t = t.replace(CHANNEL_HANDLE, "")
        t = t.replace(BRACKETS_REGEX, "")
        t = t.replace(".", " ").replace("_", " ")
        t = t.replace(TAG_REGEX, "")
        t = t.replace(Regex("\\\\s+"), " ").trim()
        return if (t.isNotBlank()) t else rawTitle
    }
}
''')
print("✅ MediaTitleCleaner created!")

# 2. Add getNextInChannel to VideoIndexDao in LocalDb.kt
db_path = "app/src/main/java/com/velastudio/teltv/data/local/LocalDb.kt"
with open(db_path, "r", encoding="utf-8") as f:
    db_code = f.read()

if "getNextInChannel" not in db_code:
    target = 'suspend fun newestMessageId(chatId: Long): Long?'
    addition = '''suspend fun newestMessageId(chatId: Long): Long?

    @Query("SELECT * FROM video_index WHERE chatId = :chatId AND position > :currentPosition ORDER BY position ASC LIMIT 1")
    suspend fun getNextInChannel(chatId: Long, currentPosition: Int): VideoIndexEntity?'''
    db_code = db_code.replace(target, addition)
    with open(db_path, "w", encoding="utf-8") as f:
        f.write(db_code)
    print("✅ LocalDb.kt updated with getNextInChannel!")

# 3. Upgrade PlaybackService with tuned LoadControl and TrackSelector
service_path = "app/src/main/java/com/velastudio/teltv/player/PlaybackService.kt"
with open(service_path, "r", encoding="utf-8") as f:
    service_code = f.read()

if "DefaultLoadControl" not in service_code:
    # Add imports
    service_code = service_code.replace(
        "import androidx.media3.exoplayer.source.DefaultMediaSourceFactory",
        "import androidx.media3.exoplayer.source.DefaultMediaSourceFactory\nimport androidx.media3.exoplayer.DefaultLoadControl\nimport androidx.media3.exoplayer.trackselection.DefaultTrackSelector"
    )
    # Replace ExoPlayer builder
    old_builder = '''        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes('''

    new_builder = '''        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 40_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setTargetBufferBytes(35 * 1024 * 1024) // 35MB heap limit for TV boxes
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val trackSelector = DefaultTrackSelector(this)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes('''

    service_code = service_code.replace(old_builder, new_builder)
    with open(service_path, "w", encoding="utf-8") as f:
        f.write(service_code)
    print("✅ PlaybackService.kt updated with tuned LoadControl & TrackSelector!")
