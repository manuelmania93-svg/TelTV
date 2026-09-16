# 1. Patch PlaybackPrefs.kt: Add 4:3 aspect ratio preference
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlaybackPrefs.kt", "r", encoding="utf-8") as f:
    pp = f.read()

old_pp_import = "import androidx.datastore.preferences.core.booleanPreferencesKey"
new_pp_import = "import androidx.datastore.preferences.core.booleanPreferencesKey\nimport androidx.datastore.preferences.core.intPreferencesKey"
pp = pp.replace(old_pp_import, new_pp_import, 1)

old_pp_key = "        val KEY_SKIP_MS = longPreferencesKey(\"skip_increment_ms\")"
new_pp_key = "        val KEY_SKIP_MS = longPreferencesKey(\"skip_increment_ms\")\n        val KEY_ASPECT_RATIO_4X3 = intPreferencesKey(\"aspect_ratio_4x3\")"
pp = pp.replace(old_pp_key, new_pp_key, 1)

old_pp_body = """    val showPinnedVideos: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_SHOW_PINNED] ?: true
    }

    suspend fun setShowPinnedVideos(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_SHOW_PINNED] = enabled }
    }"""

new_pp_body = """    val showPinnedVideos: Flow<Boolean> = context.playbackDataStore.data.map {
        it[KEY_SHOW_PINNED] ?: true
    }

    suspend fun setShowPinnedVideos(enabled: Boolean) {
        context.playbackDataStore.edit { it[KEY_SHOW_PINNED] = enabled }
    }

    val aspectRatio4x3: Flow<Int> = context.playbackDataStore.data.map {
        it[KEY_ASPECT_RATIO_4X3] ?: 0
    }

    suspend fun setAspectRatio4x3(mode: Int) {
        context.playbackDataStore.edit { it[KEY_ASPECT_RATIO_4X3] = mode }
    }"""

pp = pp.replace(old_pp_body, new_pp_body, 1)
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlaybackPrefs.kt", "w", encoding="utf-8") as f:
    f.write(pp)

# 2. Patch PlayerScreen.kt: Detect 4:3 videos, apply saved mode, preserve widescreen
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "r", encoding="utf-8") as f:
    ps = f.read()

old_ps_aspect_decl = "    var aspectRatioIndex by remember { mutableStateOf(0) } // 0=FIT, 1=ZOOM, 2=FILL"
new_ps_aspect_decl = """    val savedAspect4x3 by prefs.aspectRatio4x3.collectAsState(initial = 0)
    var isCurrentVideo4x3 by remember { mutableStateOf(false) }
    var aspectRatioIndex by remember { mutableStateOf(0) } // 0=FIT, 1=ZOOM, 2=FILL"""
ps = ps.replace(old_ps_aspect_decl, new_ps_aspect_decl, 1)

old_ps_listener = """            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) {
                    durationMs = player.duration
                }
            }"""

new_ps_listener = """            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) {
                    durationMs = player.duration
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val pixelRatio = if (videoSize.pixelWidthHeightRatio > 0f) videoSize.pixelWidthHeightRatio else 1.0f
                    val aspect = (videoSize.width.toFloat() / videoSize.height.toFloat()) * pixelRatio
                    val is4x3 = aspect in 1.1f..1.55f
                    isCurrentVideo4x3 = is4x3
                    if (is4x3) {
                        aspectRatioIndex = savedAspect4x3
                    } else {
                        aspectRatioIndex = 0
                    }
                }
            }"""
ps = ps.replace(old_ps_listener, new_ps_listener, 1)

old_ps_cycle = """    fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % 3
        seekingText = when (aspectRatioIndex) {
            1 -> "Aspect: Zoom to Fill (Crop)"
            2 -> "Aspect: Stretch"
            else -> "Aspect: Fit (Original)"
        }
        seekingIsForward = true
    }"""

new_ps_cycle = """    fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % 3
        seekingText = when (aspectRatioIndex) {
            1 -> "Aspect: Zoom to Fill (Crop)"
            2 -> "Aspect: Stretch"
            else -> "Aspect: Fit (Original)"
        }
        seekingIsForward = true
        if (isCurrentVideo4x3) {
            coroutineScope.launch { prefs.setAspectRatio4x3(aspectRatioIndex) }
        }
    }"""
ps = ps.replace(old_ps_cycle, new_ps_cycle, 1)

with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "w", encoding="utf-8") as f:
    f.write(ps)

# 3. Bump version in app/build.gradle.kts to 60 / 0.3.31
with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    bg = f.read()

assert "versionCode = 59" in bg, "versionCode 59 not found"
bg = bg.replace("versionCode = 59", "versionCode = 60").replace('"0.3.30"', '"0.3.31"')
with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(bg)

print("Patch applied successfully!")
