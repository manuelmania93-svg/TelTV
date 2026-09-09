import os

# 1. MediaTitleCleaner.kt
os.makedirs("app/src/main/java/com/velastudio/teltv/util", exist_ok=True)
with open("app/src/main/java/com/velastudio/teltv/util/MediaTitleCleaner.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.util

object MediaTitleCleaner {
    private val EXT_REGEX = Regex("(?i)\\\\.(mkv|mp4|avi|mov|wmv|flv|webm|ts)$")
    private val CHANNEL_HANDLE = Regex("@[a-zA-Z0-9_]+|https?://\\\\S+|t\\\\.me/\\\\S+")
    private val BRACKETS_REGEX = Regex("\\\\[[^\\\\]]*\\\\]|\\\\([^\\\\)]*\\\\)")
    private val TAG_REGEX = Regex("(?i)\\\\b(1080p|720p|2160p|4k|uhd|bluray|webrip|web-dl|web|hdtv|x264|x265|hevc|av1|aac|dts|ddp5\\\\.1|ac3|remux|sub|dub|dual[- ]audio)\\\\b.*")

    fun clean(rawTitle: String): String {
        var t = rawTitle.replace(EXT_REGEX, "")
        t = t.replace(CHANNEL_HANDLE, "")
        t = t.replace(BRACKETS_REGEX, "")
        t = t.replace(TAG_REGEX, "")
        t = t.replace(".", " ").replace("_", " ")
        t = t.replace(Regex("\\\\s+"), " ").trim()
        t = t.replace(Regex("(?i)\\\\s+(s\\\\d{1,2}e\\\\d{1,2})"), " - $1")
        return if (t.isNotBlank()) t else rawTitle
    }
}
''')
print("✅ 1/5 MediaTitleCleaner.kt created!")

# 2. PlaybackService.kt
with open("app/src/main/java/com/velastudio/teltv/player/PlaybackService.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.velastudio.teltv.MainActivity
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import timber.log.Timber

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as TelTvApp
        val dataSourceFactory = TdLibAwareDataSourceFactory(app.telegramClient, this)

        // Low-RAM TV buffer tuning: 35-second lookahead, strict 35MB memory ceiling
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 35_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setTargetBufferBytes(35 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(this)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .build()

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e(error, "Player error (errorCode=%s)", error.errorCodeName)
            }
        })
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
''')
print("✅ 2/5 PlaybackService.kt updated!")

# 3. PlayerStateOverlays.kt
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerStateOverlays.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun BufferingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator(color = Color.White)
    }
}

@Composable
fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Playback failed", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                Button(onClick = onOpenExternal) { Text("Open in External Player") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

/**
 * On-screen seeking HUD pill showing relative seek jumps (e.g. +10s, -30s).
 */
@Composable
fun SeekingFeedbackBadge(seekText: String?, isForward: Boolean) {
    AnimatedVisibility(
        visible = seekText != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = seekText ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Binge-watching auto-play countdown overlay.
 */
@Composable
fun AutoPlayCountdownOverlay(
    nextTitle: String,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    var secondsRemaining by remember { mutableStateOf(5) }
    val playNowFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playNowFocusRequester.requestFocus()
        while (secondsRemaining > 0) {
            delay(1000)
            secondsRemaining -= 1
        }
        onPlayNow()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier
                .padding(48.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(16.dp))
                .padding(28.dp)
                .widthIn(min = 340.dp, max = 460.dp)
        ) {
            Text(
                text = "Next Episode in ${secondsRemaining}s...",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = nextTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray,
                maxLines = 2
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onPlayNow,
                    modifier = Modifier.focusRequester(playNowFocusRequester)
                ) {
                    Text("Play Now")
                }
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

data class TrackItem(
    val title: String,
    val isSelected: Boolean,
    val group: Tracks.Group?,
    val trackIndex: Int
)

/**
 * D-pad modal sheet for Audio languages & Subtitles.
 */
@Composable
fun TrackSelectorDialog(
    controller: MediaController?,
    onDismiss: () -> Unit
) {
    if (controller == null) return

    val currentTracks = controller.currentTracks
    val audioTracks = remember(currentTracks) {
        val list = mutableListOf<TrackItem>()
        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.let { Locale(it).displayLanguage } ?: format.label ?: "Audio #${list.size + 1}"
                    val label = if (format.label != null && format.language != null) "$lang (${format.label})" else lang
                    list.add(TrackItem(label, group.isTrackSelected(i), group, i))
                }
            }
        }
        list
    }

    val subtitleTracks = remember(currentTracks) {
        val list = mutableListOf<TrackItem>()
        val anySelected = currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        list.add(TrackItem("Off", !anySelected, null, -1))

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    val format = group.getTrackFormat(i)
                    val lang = format.language?.let { Locale(it).displayLanguage } ?: format.label ?: "Subtitle #${list.size}"
                    val label = if (format.label != null && format.language != null) "$lang (${format.label})" else lang
                    list.add(TrackItem(label, group.isTrackSelected(i), group, i))
                }
            }
        }
        list
    }

    var selectedTab by remember { mutableStateOf(0) } // 0 = Audio, 1 = Subtitles

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .background(Color(0xFF222222), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text("Audio & Subtitles", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { selectedTab = 0 }) {
                    Text(if (selectedTab == 0) "● Audio (${audioTracks.size})" else "Audio (${audioTracks.size})")
                }
                Button(onClick = { selectedTab = 1 }) {
                    Text(if (selectedTab == 1) "● Subtitles (${subtitleTracks.size - 1})" else "Subtitles (${subtitleTracks.size - 1})")
                }
            }

            Spacer(Modifier.height(16.dp))

            val activeList = if (selectedTab == 0) audioTracks else subtitleTracks
            LazyColumn(
                modifier = Modifier.heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeList) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (track.isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                if (selectedTab == 0 && track.group != null) {
                                    val override = TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)
                                    controller.trackSelectionParameters = controller.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(override)
                                        .build()
                                } else if (selectedTab == 1) {
                                    if (track.group == null) {
                                        controller.trackSelectionParameters = controller.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                    } else {
                                        val override = TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex)
                                        controller.trackSelectionParameters = controller.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(override)
                                            .build()
                                    }
                                }
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(track.title, color = Color.White, fontSize = 16.sp)
                        if (track.isSelected) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color.White)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
''')
print("✅ 3/5 PlayerStateOverlays.kt updated!")

# 4. PlaybackControlsOverlay.kt
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlaybackControlsOverlay.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.velastudio.teltv.util.MediaTitleCleaner
import java.util.concurrent.TimeUnit

@Composable
fun PlaybackControlsOverlay(
    title: String,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    skipIncrementMs: Long,
    visible: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenExternal: () -> Unit
) {
    val cleanTitle = remember(title) { MediaTitleCleaner.clean(title) }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        startY = 0.35f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cleanTitle, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ControlButton(
                            icon = Icons.Filled.Subtitles,
                            contentDescription = "Audio & Subtitles",
                            onClick = onOpenTracks
                        )
                        ControlButton(
                            icon = Icons.Filled.OpenInNew,
                            contentDescription = "Open in External Player",
                            onClick = onOpenExternal
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                val progress = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(currentPositionMs), color = Color.White)
                    Text(formatMs(durationMs), color = Color.White)
                }

                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ControlButton(
                        icon = if (skipIncrementMs >= 10_000) Icons.Filled.Replay10 else Icons.Filled.Replay5,
                        contentDescription = "Skip back",
                        onClick = onSkipBack
                    )
                    Spacer(Modifier.width(32.dp))
                    ControlButton(
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        onClick = onPlayPause,
                        large = true
                    )
                    Spacer(Modifier.width(32.dp))
                    ControlButton(
                        icon = if (skipIncrementMs >= 10_000) Icons.Filled.Forward10 else Icons.Filled.Forward5,
                        contentDescription = "Skip forward",
                        onClick = onSkipForward
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    large: Boolean = false
) {
    val size = if (large) 72.dp else 52.dp
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(Color.White.copy(alpha = 0.15f), CircleShape)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White)
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
''')
print("✅ 4/5 PlaybackControlsOverlay.kt updated!")

# 5. PlayerScreen.kt & MainActivity.kt
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.player

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

private const val CONTROLS_AUTO_HIDE_MS = 4000L
private const val POSITION_SAVE_INTERVAL_MS = 5000L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    fileId: Int?,
    directUri: String?,
    title: String,
    resumePositionMs: Long,
    nextTitle: String? = null,
    onPlayNext: (() -> Unit)? = null,
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
    var showTrackSelector by remember { mutableStateOf(false) }
    var showAutoPlayOverlay by remember { mutableStateOf(false) }

    // Seeking feedback state
    var seekingText by remember { mutableStateOf<String?>(null) }
    var seekingIsForward by remember { mutableStateOf(true) }
    var lastSeekTimestamp by remember { mutableStateOf(0L) }

    fun resolvedUri(): String? =
        directUri ?: fileId?.let { TdLibAwareDataSourceFactory.uriForFile(it).toString() }

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
                    if (onPlayNext != null && nextTitle != null) {
                        showAutoPlayOverlay = true
                    } else {
                        onPlaybackEnded()
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
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

                when (keyEvent.nativeKeyEvent.keyCode) {
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
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (controlsVisible) {
                            showTrackSelector = true
                        } else {
                            controlsVisible = true
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
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
                    useController = false
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
            visible = controlsVisible && !showTrackSelector && !showAutoPlayOverlay,
            onPlayPause = ::togglePlayPause,
            onSkipBack = { seekRelative(forward = false) },
            onSkipForward = { seekRelative(forward = true) },
            onOpenTracks = { showTrackSelector = true },
            onOpenExternal = ::openInExternalPlayer
        )

        SeekingFeedbackBadge(seekText = seekingText, isForward = seekingIsForward)

        if (isBuffering && playerErrorMessage == null) {
            BufferingIndicator()
        }

        if (showTrackSelector) {
            TrackSelectorDialog(controller = controller, onDismiss = { showTrackSelector = false })
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
''')
print("✅ 5/5 PlayerScreen.kt updated!")

# 6. Wire next video in MainActivity.kt
with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "r", encoding="utf-8") as f:
    main_code = f.read()

old_call = '''                        PlayerScreen(
                            fileId = fileId,
                            directUri = null,
                            title = title,
                            resumePositionMs = resumeMs,
                            onPositionUpdate = { positionMs, durationMs ->'''

new_call = '''                        var nextEntity by remember { mutableStateOf<com.velastudio.teltv.data.local.VideoIndexEntity?>(null) }
                        LaunchedEffect(mediaId) {
                            val cur = app.database.videoIndexDao().getByMediaId(mediaId)
                            if (cur != null) {
                                nextEntity = app.database.videoIndexDao().getNextInChannel(cur.chatId, cur.position)
                            }
                        }

                        PlayerScreen(
                            fileId = fileId,
                            directUri = null,
                            title = title,
                            resumePositionMs = resumeMs,
                            nextTitle = nextEntity?.title,
                            onPlayNext = nextEntity?.let { next ->
                                {
                                    navController.navigate("player/${URLEncoder.encode(next.mediaId, "UTF-8")}") {
                                        popUpTo("player/{mediaId}") { inclusive = true }
                                    }
                                }
                            },
                            onPositionUpdate = { positionMs, durationMs ->'''

if old_call in main_code:
    main_code = main_code.replace(old_call, new_call)
    with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "w", encoding="utf-8") as f:
        f.write(main_code)
    print("✅ MainActivity.kt wired with auto-play next episode!")
else:
    print("ℹ️ MainActivity.kt already wired or modified.")
