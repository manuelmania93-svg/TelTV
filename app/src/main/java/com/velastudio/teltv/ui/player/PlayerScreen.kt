package com.velastudio.teltv.ui.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.MoreExecutors
import com.velastudio.teltv.player.PlaybackService
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import com.velastudio.teltv.util.MediaTitleCleaner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.velastudio.teltv.util.OnlineSubtitle
import com.velastudio.teltv.util.OnlineSubtitleProvider
import timber.log.Timber

private const val CONTROLS_AUTO_HIDE_MS = 7000L
private const val POSITION_SAVE_INTERVAL_MS = 5000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    fileId: Int?,
    directUri: String?,
    title: String,
    resumePositionMs: Long,
    nextTitle: String? = null,
    onPlayPrevious: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    autoPlayByDefault: Boolean = false,
    onPositionUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    onPlaybackEnded: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { PlaybackPrefs(context) }
    val skipIncrementMs by prefs.skipIncrementMs.collectAsState(initial = PlaybackPrefs.DEFAULT_SKIP_MS)

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableStateOf(resumePositionMs) }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var playerErrorMessage by remember { mutableStateOf<String?>(null) }
    var showTrackSelector by remember { mutableStateOf(false) }
    var showAutoPlayOverlay by remember { mutableStateOf(false) }
    val savedAutoPlay by prefs.autoPlayEnabled.collectAsState(initial = true)
    var autoPlayNext by remember { mutableStateOf(true) }
    LaunchedEffect(savedAutoPlay) {
        autoPlayNext = savedAutoPlay
    }
    var aspectRatioIndex by remember { mutableStateOf(0) } // 0=FIT, 1=ZOOM, 2=FILL

    // Subtitle styling & audio enhancements
    val subSize by prefs.subtitleSize.collectAsState(initial = "LARGE")
    val subColor by prefs.subtitleColor.collectAsState(initial = "WHITE")
    val dialogueBoost by prefs.dialogueBoostEnabled.collectAsState(initial = false)
    var loudnessEnhancer by remember { mutableStateOf<android.media.audiofx.LoudnessEnhancer?>(null) }

    var activeSubtitleFile by remember { mutableStateOf<java.io.File?>(null) }
    var activeSubtitleLang by remember { mutableStateOf("en") }
    var activeSubtitleLabel by remember { mutableStateOf("Online") }
    var subtitleSyncOffsetMs by remember { mutableStateOf(0L) }

    val subFontSize = when (subSize) {
        "NORMAL" -> 0.050f
        "XLARGE" -> 0.082f
        else -> 0.065f
    }
    val subFontColor = when (subColor) {
        "YELLOW" -> android.graphics.Color.parseColor("#FFE500")
        else -> android.graphics.Color.WHITE
    }

    LaunchedEffect(dialogueBoost) {
        try {
            if (dialogueBoost) {
                if (loudnessEnhancer == null) {
                    val enhancer = android.media.audiofx.LoudnessEnhancer(0)
                    enhancer.setTargetGain(1200)
                    enhancer.enabled = true
                    loudnessEnhancer = enhancer
                }
            } else {
                loudnessEnhancer?.enabled = false
                loudnessEnhancer?.release()
                loudnessEnhancer = null
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "LoudnessEnhancer not available")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
        }
    }

    // Seeking feedback state
    var seekingText by remember { mutableStateOf<String?>(null) }
    var seekingIsForward by remember { mutableStateOf(true) }
    var lastSeekTimestamp by remember { mutableStateOf(0L) }

    fun resolvedUri(): String? =
        directUri ?: fileId?.let { TdLibAwareDataSourceFactory.uriForFile(it).toString() }

    fun applySubtitleFile(file: java.io.File, lang: String, label: String, offsetMs: Long = 0L) {
        val mediaController = controller ?: return
        val targetFile = if (offsetMs != 0L) {
            OnlineSubtitleProvider.shiftSubtitle(file, offsetMs)
        } else file

        if (targetFile.exists()) {
            val subUri = Uri.fromFile(targetFile)
            val subConfig = ExoMediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(lang)
                .setLabel(label)
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()

            val currentMediaItem = mediaController.currentMediaItem
            if (currentMediaItem != null) {
                val currentPos = mediaController.currentPosition
                val isPlayingNow = mediaController.isPlaying
                val updatedMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subConfig))
                    .build()
                mediaController.setMediaItem(updatedMediaItem, currentPos)
                mediaController.prepare()
                mediaController.playWhenReady = isPlayingNow
                seekingText = if (offsetMs != 0L) "Sync: ${if (offsetMs > 0) "+${offsetMs}ms" else "${offsetMs}ms"}" else "Subtitles: $label"
            }
        }
    }

    fun attachOnlineSubtitle(sub: OnlineSubtitle) {
        val mediaController = controller ?: return
        coroutineScope.launch {
            seekingText = "Downloading ${sub.langDisplay}..."
            val file = OnlineSubtitleProvider.downloadSubtitle(context, sub)
            if (file != null && file.exists()) {
                activeSubtitleFile = file
                activeSubtitleLang = sub.lang
                activeSubtitleLabel = sub.langDisplay
                subtitleSyncOffsetMs = 0L
                applySubtitleFile(file, sub.lang, sub.langDisplay, 0L)
            } else {
                seekingText = "Failed to download subtitle"
            }
        }
    }

    fun adjustSubtitleSync(newOffset: Long) {
        subtitleSyncOffsetMs = newOffset
        activeSubtitleFile?.let { file ->
            applySubtitleFile(file, activeSubtitleLang, activeSubtitleLabel, newOffset)
        }
    }

    fun openInExternalPlayer() {
        val streamUri = resolvedUri() ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(streamUri), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open in External Player"))
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Could not open external player")
        }
    }

    DisposableEffect(fileId, directUri) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.duration > 0 && player.duration != androidx.media3.common.C.TIME_UNSET) {
                    durationMs = player.duration
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    if (autoPlayNext && onPlayNext != null && nextTitle != null) {
                        showAutoPlayOverlay = true
                    } else {
                        onPlaybackEnded()
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isBuffering = false
                Timber.e(
                    error,
                    "Playback failed: code=%s, uri=%s, fileId=%s",
                    error.errorCodeName,
                    resolvedUri(),
                    fileId
                )
                playerErrorMessage = buildString {
                    append(error.errorCodeName)
                    error.cause?.let { cause ->
                        append(": ")
                        append(cause::class.java.simpleName)
                        cause.message?.takeIf { it.isNotBlank() }?.let {
                            append(" - ")
                            append(it)
                        }
                    }
                }
            }
        }

        controllerFuture.addListener({
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
            } else {
                playerErrorMessage = "Couldn't find anything to play."
            }
        }, MoreExecutors.directExecutor())

        onDispose {
            controller?.let { mediaController ->
                mediaController.removeListener(listener)
                onPositionUpdate(mediaController.currentPosition, mediaController.duration.coerceAtLeast(0))
                mediaController.stop()
            }
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        while (true) {
            currentPositionMs = mediaController.currentPosition
            delay(500)
        }
    }

    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        while (true) {
            delay(POSITION_SAVE_INTERVAL_MS)
            if (mediaController.duration > 0) {
                onPositionUpdate(mediaController.currentPosition, mediaController.duration)
            }
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, showTrackSelector) {
        if (controlsVisible && isPlaying && !showTrackSelector && !showAutoPlayOverlay) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekingText) {
        if (seekingText != null) {
            delay(1000)
            seekingText = null
        }
    }

    fun seekRelative(forward: Boolean) {
        val now = System.currentTimeMillis()
        val isRapid = (now - lastSeekTimestamp) < 600
        lastSeekTimestamp = now
        val stepMs = if (isRapid) 30_000L else skipIncrementMs

        controller?.let {
            val target = if (forward) {
                (it.currentPosition + stepMs).coerceAtMost(it.duration.coerceAtLeast(0))
            } else {
                (it.currentPosition - stepMs).coerceAtLeast(0)
            }
            it.seekTo(target)
            seekingIsForward = forward
            seekingText = "${if (forward) "+" else "-"}${stepMs / 1000}s"
        }
        controlsVisible = true
    }

    fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % 3
        seekingText = when (aspectRatioIndex) {
            1 -> "Aspect: Zoom to Fill (Crop)"
            2 -> "Aspect: Stretch"
            else -> "Aspect: Fit (Original)"
        }
        seekingIsForward = true
    }

    fun togglePlayPause() {
        controller?.let { it.playWhenReady = !it.playWhenReady }
        controlsVisible = true
    }

    fun retryPlayback() {
        val mediaController = controller ?: return
        val uri = resolvedUri() ?: return
        playerErrorMessage = null
        mediaController.setMediaItem(ExoMediaItem.fromUri(uri))
        mediaController.seekTo(currentPositionMs)
        mediaController.prepare()
        mediaController.playWhenReady = true
    }

    val focusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            kotlinx.coroutines.delay(100)
            runCatching { playPauseFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

                if (showTrackSelector) {
                    if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        showTrackSelector = false
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }

                if (showAutoPlayOverlay) {
                    if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                        showAutoPlayOverlay = false
                        onPlaybackEnded()
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }

                if (playerErrorMessage != null) {
                    return@onKeyEvent when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            retryPlayback(); true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            onBack(); true
                        }
                        else -> true
                    }
                }

                if (!controlsVisible) {
                    return@onKeyEvent when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            seekRelative(forward = false); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            seekRelative(forward = true); true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            seekRelative(forward = false); true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            seekRelative(forward = true); true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                            controlsVisible = true; true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                            togglePlayPause(); true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            onBack(); true
                        }
                        else -> false
                    }
                } else {
                    return@onKeyEvent when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            controlsVisible = false; true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                            togglePlayPause(); true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            seekRelative(forward = false); true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            seekRelative(forward = true); true
                        }
                        else -> false // Let D-pad Left, Right, Up, Down, Center pass straight through to the buttons!
                    }
                }            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    keepScreenOn = true
                    keepScreenOn = true
                    subtitleView?.apply {
                        setFractionalTextSize(0.065f) // Large readable subtitles for TV
                        setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.parseColor("#80000000"),
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                android.graphics.Color.BLACK,
                                android.graphics.Typeface.DEFAULT_BOLD
                            )
                        )
                    }
                }
            },
            update = { view ->
                view.player = controller
                view.resizeMode = when (aspectRatioIndex) {
                    1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                view.subtitleView?.apply {
                    setFractionalTextSize(subFontSize)
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            subFontColor,
                            android.graphics.Color.parseColor("#99000000"),
                            android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK,
                            android.graphics.Typeface.DEFAULT_BOLD
                        )
                    )
                }
            }
        )

        PlaybackControlsOverlay(
            title = title,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            skipIncrementMs = skipIncrementMs,
            visible = controlsVisible && !showTrackSelector && !showAutoPlayOverlay,
            onPlayPause = ::togglePlayPause,
            onSkipBack = { seekRelative(forward = false) },
            onSkipForward = { seekRelative(forward = true) },
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onOpenTracks = { showTrackSelector = true },
            onCycleAspectRatio = ::cycleAspectRatio,
            onOpenExternal = ::openInExternalPlayer,
            autoPlayNext = autoPlayNext,
            playPauseModifier = Modifier.focusRequester(playPauseFocusRequester),
            onToggleAutoPlay = {
                val newState = !autoPlayNext
                autoPlayNext = newState
                coroutineScope.launch {
                    prefs.setAutoPlayEnabled(newState)
                }
                controlsVisible = true
                seekingText = if (newState) "Autoplay: ON" else "Autoplay: OFF"
                seekingIsForward = newState
            }
        )

        SeekingFeedbackBadge(seekText = seekingText, isForward = seekingIsForward)

        if (isBuffering && playerErrorMessage == null) {
            BufferingIndicator()
        }

        if (showTrackSelector) {
            TrackSelectorDialog(
                controller = controller,
                videoTitle = title,
                onSelectOnlineSubtitle = ::attachOnlineSubtitle,
                syncOffsetMs = subtitleSyncOffsetMs,
                onAdjustSyncOffset = ::adjustSubtitleSync,
                prefs = prefs,
                onDismiss = { showTrackSelector = false }
            )
        }

        if (showAutoPlayOverlay && nextTitle != null && onPlayNext != null) {
            AutoPlayCountdownOverlay(
                nextTitle = MediaTitleCleaner.clean(nextTitle),
                onPlayNow = {
                    showAutoPlayOverlay = false
                    onPlayNext()
                },
                onCancel = {
                    showAutoPlayOverlay = false
                    onPlaybackEnded()
                }
            )
        }

        playerErrorMessage?.let { message ->
            PlaybackErrorOverlay(
                message = message,
                onRetry = ::retryPlayback,
                onBack = onBack,
                onOpenExternal = ::openInExternalPlayer
            )
        }
    }
}
