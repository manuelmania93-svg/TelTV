package com.velastudio.teltv.ui.player

import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_MS = 4000L

/**
 * How often we persist the resume position while playing, independent of the seek-bar UI poll.
 * Cheap TV boxes get killed by the system far more eagerly than a phone -- if we only wrote the
 * position on a clean `onDispose`, a low-memory kill mid-episode would silently lose the resume
 * point and "Continue Watching" would be wrong or missing next time. 5s is frequent enough that
 * losing progress is never noticeable, and infrequent enough not to be its own source of jank.
 */
private const val POSITION_SAVE_INTERVAL_MS = 5_000L

/**
 * Full-screen playback with a standard set of controls: play/pause, skip back/forward (5s or
 * 10s, from Settings via [PlaybackPrefs]), and a seek bar with elapsed/remaining time.
 *
 * The actual ExoPlayer lives in [PlaybackService], not here -- this screen just binds a
 * [MediaController] to it. That's what gives playback a real lifecycle (survives this Composable
 * being torn down on rotation/navigation, gets audio focus handling and system
 * media-notification/remote-control-key support for free from Media3) instead of the screen
 * owning a one-off player that background/foreground transitions and process quirks on cheap TV
 * boxes could easily leave in a bad state.
 *
 * For Telegram items, `fileId` streams straight from TDLib via a `tdlib://file/<id>` URI (see
 * [TdLibAwareDataSourceFactory]) so playback starts immediately and seeking triggers a
 * prioritized re-download of the target byte range -- no "download whole file first" wait like
 * naive Telegram players do. NAS/WebDAV/local items use a plain URI and ExoPlayer's default data
 * source, handled by the same factory.
 *
 * ExoPlayer's own `useController=false` here -- we render our own Compose overlay
 * ([PlaybackControlsOverlay]) instead, so the skip amount, styling, and TV remote key handling
 * are all under our control rather than fighting Media3's default XML-based controller.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    fileId: Int?,
    directUri: String?,
    title: String,
    resumePositionMs: Long,
    onPositionUpdate: (positionMs: Long, durationMs: Long) -> Unit,
    onPlaybackEnded: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
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

    // Shared by the initial DisposableEffect setup and by the error overlay's Retry button, so
    // "retry" replays the exact same source instead of duplicating this uri-resolution logic.
    fun resolvedUri(): String? =
        directUri ?: fileId?.let { TdLibAwareDataSourceFactory.uriForFile(it).toString() }

    // Connect to PlaybackService and hand it the item to play. Torn down in onDispose --
    // releasing the *controller*, not the player itself, which is what would let the player
    // survive this screen being disposed if we ever wanted that; today onBack/onPlaybackEnded
    // also explicitly stop playback, see below, since this app has no background-audio mode.
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
                if (playbackState == Player.STATE_ENDED) onPlaybackEnded()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // Network drop mid-stream, unreachable NAS/WebDAV host, a TDLib download that
                // failed, an unsupported codec -- all land here. Surface it instead of leaving a
                // frozen/black frame with no way to tell what happened or recover from it.
                isBuffering = false
                playerErrorMessage = error.errorCodeName.takeIf { it.isNotBlank() }
                    ?: error.message
                    ?: "Unknown playback error"
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
                // This screen owns the item it started -- stop it here rather than letting it
                // keep running in PlaybackService after the user has navigated away.
                mediaController.stop()
            }
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }

    // Pause when the Activity goes into the background (multitasking to another app, screen
    // off on some TV boxes) instead of leaving decode/network running untended -- see
    // PlaybackService's own onTaskRemoved for the "app fully swiped away" case.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Poll position for the seek bar -- ExoPlayer/MediaController has no continuous position
    // callback.
    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        while (true) {
            currentPositionMs = mediaController.currentPosition
            delay(500)
        }
    }

    // Persist the resume point periodically, not just on a clean exit -- see
    // POSITION_SAVE_INTERVAL_MS above for why this matters on low-RAM TV boxes.
    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        while (true) {
            delay(POSITION_SAVE_INTERVAL_MS)
            if (mediaController.duration > 0) {
                onPositionUpdate(mediaController.currentPosition, mediaController.duration)
            }
        }
    }

    // Auto-hide controls after a few seconds of inactivity, but only while actually playing.
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    fun skipBack() {
        controller?.let { it.seekTo((it.currentPosition - skipIncrementMs).coerceAtLeast(0)) }
        controlsVisible = true
    }
    fun skipForward() {
        controller?.let { it.seekTo((it.currentPosition + skipIncrementMs).coerceAtMost(it.duration.coerceAtLeast(0))) }
        controlsVisible = true
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
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false

                // This screen maps D-pad keys straight to actions rather than moving Compose
                // focus between the on-screen icons/buttons (see class doc: "TV remote key
                // handling are all under our control"). That means a Button drawn here only
                // actually does something if its action is wired in here too -- it is never
                // reachable by "focus it, then press center" the way Buttons on every other
                // screen in this app are. When the error overlay is up, DPAD_CENTER/ENTER maps
                // to Retry (the primary recovery action) instead of play/pause.
                if (playerErrorMessage != null) {
                    return@onKeyEvent when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            retryPlayback(); true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            onBack(); true
                        }
                        else -> true // swallow other keys so a stray skip/play doesn't fire on a broken stream
                    }
                }

                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        skipBack(); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        skipForward(); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        if (!controlsVisible) controlsVisible = true else togglePlayPause()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        togglePlayPause(); true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (controlsVisible) { controlsVisible = false; true }
                        else { onBack(); true }
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        controlsVisible = true; true
                    }
                    else -> false
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                PlayerView(ctx).apply {
                    useController = false // we draw our own controls below
                }
            },
            update = { view -> view.player = controller }
        )

        PlaybackControlsOverlay(
            title = title,
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            skipIncrementMs = skipIncrementMs,
            visible = controlsVisible,
            onPlayPause = ::togglePlayPause,
            onSkipBack = ::skipBack,
            onSkipForward = ::skipForward
        )

        if (isBuffering && playerErrorMessage == null) {
            BufferingIndicator()
        }

        playerErrorMessage?.let { message ->
            PlaybackErrorOverlay(
                message = message,
                onRetry = ::retryPlayback,
                onBack = onBack
            )
        }
    }
}
