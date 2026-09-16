import sys

# 1. Patch MainActivity.kt: Clear stale fileId when currentMediaId changes
with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "r", encoding="utf-8") as f:
    ma = f.read()

old_ma = """                        LaunchedEffect(currentMediaId) {
                            isResolving = true
                            val existingState = app.database.watchStateDao().get(currentMediaId)"""

new_ma = """                        LaunchedEffect(currentMediaId) {
                            isResolving = true
                            fileId = null // Clear stale fileId so previous episode is not passed downstream
                            val existingState = app.database.watchStateDao().get(currentMediaId)"""

assert old_ma in ma, "old_ma target string not found in MainActivity.kt"
ma = ma.replace(old_ma, new_ma, 1)

with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "w", encoding="utf-8") as f:
    f.write(ma)

# 2. Patch PlayerScreen.kt: Bind MediaController once on Unit, trigger playback through LaunchedEffect(controller, fileId, directUri)
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "r", encoding="utf-8") as f:
    ps = f.read()

old_ps_launch = """    LaunchedEffect(fileId) {
        currentPositionMs = resumePositionMs
        durationMs = 0L
        autoPlayTriggered = false
        showAutoPlayOverlay = false
        controller?.let { mc ->
            val uri = resolvedUri()
            if (uri != null) {
                playerErrorMessage = null
                mc.setMediaItem(ExoMediaItem.fromUri(uri))
                mc.seekTo(resumePositionMs)
                mc.prepare()
                mc.playWhenReady = true
                mc.play()
            }
        }
    }"""

new_ps_launch = """    LaunchedEffect(controller, fileId, directUri) {
        val mc = controller ?: return@LaunchedEffect
        val uri = resolvedUri() ?: return@LaunchedEffect
        currentPositionMs = resumePositionMs
        durationMs = 0L
        autoPlayTriggered = false
        showAutoPlayOverlay = false
        playerErrorMessage = null
        mc.setMediaItem(ExoMediaItem.fromUri(uri))
        mc.seekTo(resumePositionMs)
        mc.prepare()
        mc.playWhenReady = true
        mc.play()
    }"""

assert old_ps_launch in ps, "old_ps_launch target string not found in PlayerScreen.kt"
ps = ps.replace(old_ps_launch, new_ps_launch, 1)

old_ps_disp_head = "    DisposableEffect(fileId, directUri) {"
new_ps_disp_head = "    DisposableEffect(Unit) {"
assert old_ps_disp_head in ps, "old_ps_disp_head target string not found in PlayerScreen.kt"
ps = ps.replace(old_ps_disp_head, new_ps_disp_head, 1)

old_ps_listener_add = """        controllerFuture.addListener({
            val mediaController = controllerFuture.get()
            controller = mediaController
            mediaController.addListener(listener)

            val uri = resolvedUri()
            if (uri != null) {
                playerErrorMessage = null
                mediaController.setMediaItem(ExoMediaItem.fromUri(uri))
                mediaController.seekTo(resumePositionMs)
                mediaController.prepare()
                mediaController.playWhenReady = true
                mediaController.play()
            } else {
                playerErrorMessage = "Couldn't find anything to play."
            }
        }, MoreExecutors.directExecutor())"""

new_ps_listener_add = """        controllerFuture.addListener({
            val mediaController = controllerFuture.get()
            mediaController.addListener(listener)
            controller = mediaController
        }, MoreExecutors.directExecutor())"""

assert old_ps_listener_add in ps, "old_ps_listener_add target string not found in PlayerScreen.kt"
ps = ps.replace(old_ps_listener_add, new_ps_listener_add, 1)

old_ps_on_dispose = """        onDispose {
            controller?.let { mediaController ->
                mediaController.removeListener(listener)
                onPositionUpdate(mediaController.currentPosition, mediaController.duration.coerceAtLeast(0))
                val activeUri = mediaController.currentMediaItem?.localConfiguration?.uri?.toString()
                val outgoingUri = resolvedUri()?.toString()
                if (activeUri == null || activeUri == outgoingUri) {
                    mediaController.stop()
                }
            }
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }"""

new_ps_on_dispose = """        onDispose {
            controller?.let { mediaController ->
                mediaController.removeListener(listener)
                onPositionUpdate(mediaController.currentPosition, mediaController.duration.coerceAtLeast(0))
                mediaController.stop()
            }
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }"""

assert old_ps_on_dispose in ps, "old_ps_on_dispose target string not found in PlayerScreen.kt"
ps = ps.replace(old_ps_on_dispose, new_ps_on_dispose, 1)

with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "w", encoding="utf-8") as f:
    f.write(ps)

# 3. Bump version in app/build.gradle.kts to 59 / 0.3.30
with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    bg = f.read()

assert "versionCode = 58" in bg, "versionCode 58 not found in app/build.gradle.kts"
bg = bg.replace("versionCode = 58", "versionCode = 59").replace('"0.3.29"', '"0.3.30"')

with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(bg)

print("Patch applied successfully!")
