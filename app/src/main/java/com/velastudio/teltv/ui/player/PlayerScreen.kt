package com.velastudio.teltv.ui.player

import com.velastudio.teltv.R

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
import com.velastudio.teltv.player.SubtitleSyncOffset
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import com.velastudio.teltv.util.MediaTitleCleaner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    showName: String? = null,
    resumePositionMs: Long,
    nextTitle: String? = null,
    onPlayPrevious: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    autoPlayByDefault: Boolean = false,
    syncKey: String? = null,
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
    val latestOnPositionUpdate by rememberUpdatedState(onPositionUpdate)
    val latestOnPlayNext by rememberUpdatedState(onPlayNext)
    val latestNextTitle by rememberUpdatedState(nextTitle)
    val latestOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val latestForeground by rememberUpdatedState(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var connectionAttempt by remember { mutableStateOf(0) }
    var recoveryRequest by remember { mutableStateOf(0) }
    var needsRecovery by remember(fileId, directUri) { mutableStateOf(false) }
    var savedMediaItem by remember(fileId, directUri) { mutableStateOf<ExoMediaItem?>(null) }
    var savedPosition by remember(fileId, directUri) { mutableStateOf(resumePositionMs) }
    var savedPlayIntent by remember(fileId, directUri) { mutableStateOf(true) }
    var savedTracks by remember(fileId, directUri) { mutableStateOf<androidx.media3.common.TrackSelectionParameters?>(null) }
    var disconnectedRebindUsed by remember { mutableStateOf(false) }

    fun snapshotPlayback() {
        controller?.takeIf { it.isConnected && it.mediaItemCount > 0 }?.let { mc ->
            savedMediaItem = mc.currentMediaItem
            savedPosition = mc.currentPosition.coerceAtLeast(0L)
            savedPlayIntent = mc.playWhenReady
            savedTracks = mc.trackSelectionParameters
            currentPositionMs = savedPosition
            latestOnPositionUpdate(savedPosition, mc.duration.coerceAtLeast(0L))
        }
    }
    var durationMs by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var playerErrorMessage by remember { mutableStateOf<String?>(null) }
    var showTrackSelector by remember(fileId, directUri) { mutableStateOf(false) }
    var showPlaybackSpeed by remember(fileId, directUri) { mutableStateOf(false) }
    // Playback rate is not the subtitle timing scale. Reset only for a different video.
    var playbackSpeed by remember(fileId, directUri) { mutableStateOf(PlaybackSpeedPolicy.DEFAULT) }
    var showAutoPlayOverlay by remember { mutableStateOf(false) }
    var autoPlayTriggered by remember(fileId, directUri) { mutableStateOf(false) }
    var autoPlayCancelled by remember(fileId, directUri) { mutableStateOf(false) }
    var autoSubtitleAppliedUri by remember { mutableStateOf<String?>(null) }
    val preferredSubtitleLanguages = remember(context) {
        val configuration = context.resources.configuration
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            List(configuration.locales.size()) { configuration.locales[it].toLanguageTag() }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(configuration.locale?.toLanguageTag())
        }
    }

    fun resolvedUri(): String? =
        directUri ?: fileId?.let { TdLibAwareDataSourceFactory.uriForFile(it).toString() }

    // One cancellable recovery per foreground attempt. Stop/dispose/media changes cancel it.
    LaunchedEffect(controller, fileId, directUri, foreground, recoveryRequest) {
        val mc = controller?.takeIf { it.isConnected } ?: return@LaunchedEffect
        if (!foreground) return@LaunchedEffect
        val uri = resolvedUri() ?: return@LaunchedEffect
        val recovering = needsRecovery
        if (recovering && fileId != null && directUri == null) {
            isBuffering = true
            val app = context.applicationContext as com.velastudio.teltv.TelTvApp
            if (!app.telegramClient.awaitConnectionReady(15_000L)) {
                isBuffering = false
                playerErrorMessage = "Telegram is not connected yet. Check the network and retry."
                return@LaunchedEffect
            }
        }
        if (!mc.isConnected) return@LaunchedEffect
        val item = savedMediaItem ?: ExoMediaItem.fromUri(uri)
        playerErrorMessage = null
        if (!recovering) {
            durationMs = 0L
            autoPlayTriggered = false
            showAutoPlayOverlay = false
            autoSubtitleAppliedUri = null
        }
        savedTracks?.let { mc.trackSelectionParameters = it }
        mc.setMediaItem(item, savedPosition)
        mc.prepare()
        mc.playbackParameters = PlaybackSpeedPolicy.parameters(playbackSpeed)
        mc.playWhenReady = savedPlayIntent
        if (recovering) {
            val ready = withTimeoutOrNull(20_000L) {
                while (mc.isConnected && mc.playerError == null && mc.playbackState != Player.STATE_READY) delay(100L)
                mc.isConnected && mc.playerError == null && mc.playbackState == Player.STATE_READY
            } == true
            if (ready) {
                needsRecovery = false
                disconnectedRebindUsed = false
            } else {
                mc.takeIf { it.isConnected }?.pause()
                isBuffering = false
                playerErrorMessage = "Playback could not reconnect. Check the network and retry."
            }
        }
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val savedAutoPlay by prefs.autoPlayEnabled.collectAsState(initial = true)
    var autoPlayNext by remember { mutableStateOf(true) }
    LaunchedEffect(savedAutoPlay) {
        autoPlayNext = savedAutoPlay
    }
    val savedAspect4x3 by prefs.aspectRatio4x3.collectAsState(initial = 0)
    var isCurrentVideo4x3 by remember { mutableStateOf(false) }
    var aspectRatioIndex by remember { mutableStateOf(0) } // 0=FIT, 1=ZOOM, 2=FILL

    // Subtitle styling & audio enhancements
    val subSize by prefs.subtitleSize.collectAsState(initial = "LARGE")
    val subColor by prefs.subtitleColor.collectAsState(initial = "WHITE")
    val dialogueBoost by prefs.dialogueBoostEnabled.collectAsState(initial = false)
    var loudnessEnhancer by remember { mutableStateOf<android.media.audiofx.LoudnessEnhancer?>(null) }

    var activeSubtitleFile by remember(fileId, directUri) { mutableStateOf<java.io.File?>(null) }
    var activeSubtitleId by remember(fileId, directUri) { mutableStateOf<String?>(null) }
    var subtitleSelectionRevision by remember(fileId, directUri) { mutableStateOf(0) }
    var subtitleSyncOffsetMs by remember(fileId, directUri) { mutableStateOf(0L) }
    var subtitleScale by remember(fileId, directUri) { mutableStateOf(1.0) }

    // Never apply an old per-video correction to an unknown/new subtitle.
    LaunchedEffect(fileId, directUri) {
        SubtitleSyncOffset.set(com.velastudio.teltv.player.SubtitleSync())
    }

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



    fun applySubtitleFile(file: java.io.File, lang: String, label: String) {
        val mediaController = controller ?: return
        if (file.exists()) {
            mediaController.trackSelectionParameters = mediaController.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguages(lang)
                .build()
            val subUri = Uri.fromFile(file)
            val subConfig = ExoMediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(lang)
                .setLabel(label)
                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build()

            val currentMediaItem = mediaController.currentMediaItem
            if (currentMediaItem != null) {
                val currentPos = mediaController.currentPosition
                val playIntent = mediaController.playWhenReady
                val updatedMediaItem = currentMediaItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subConfig))
                    .build()
                mediaController.setMediaItem(updatedMediaItem, currentPos)
                mediaController.prepare()
                mediaController.playWhenReady = playIntent
                seekingText = "Subtitles: $label"
            }
        }
    }

    suspend fun attachOnlineSubtitle(sub: OnlineSubtitle, expectedRevision: Int = subtitleSelectionRevision): Boolean {
        val mediaController = controller ?: return false
        if (mediaController.currentMediaItem == null) return false
        val startedUri = mediaController.currentMediaItem?.localConfiguration?.uri
        val startedVideo = resolvedUri()
        seekingText = "Downloading ${sub.langDisplay}..."
        val file = OnlineSubtitleProvider.downloadSubtitle(context, sub)
        if (file == null || !file.exists()) {
            seekingText = "Failed to download subtitle"
            return false
        }
        val subtitleId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SubtitleSelectionPolicy.fingerprint(file.readBytes())
        }
        val saved = syncKey?.let { prefs.getSubtitleSync(it, subtitleId) }
            ?: com.velastudio.teltv.player.SubtitleSync()
        if (controller !== mediaController || resolvedUri() != startedVideo ||
            subtitleSelectionRevision != expectedRevision ||
            mediaController.currentMediaItem?.localConfiguration?.uri != startedUri) {
            return false // A later manual selection/video change wins over this download.
        }
        activeSubtitleId = subtitleId
        subtitleSyncOffsetMs = saved.offsetMs
        subtitleScale = saved.scale
        SubtitleSyncOffset.set(saved)
        autoSubtitleAppliedUri = startedUri?.toString() // Do not reselect embedded after reload.
        activeSubtitleFile = file
        applySubtitleFile(file, sub.lang, sub.langDisplay)
        seekingText = "Subtitles: ${sub.langDisplay}"
        return true
    }

    fun applySubtitleSync(offsetMs: Long, scale: Double) {
        val offset = com.velastudio.teltv.player.SubtitleTiming.clampOffset(offsetMs)
        val newScale = com.velastudio.teltv.player.SubtitleTiming.clampScale(scale)
        val sync = com.velastudio.teltv.player.SubtitleSync(offset, newScale)
        subtitleSyncOffsetMs = offset
        subtitleScale = newScale
        SubtitleSyncOffset.set(sync)
        val subtitleId = activeSubtitleId
        if (subtitleId != null) syncKey?.let { key ->
            coroutineScope.launch { prefs.setSubtitleSync(key, subtitleId, sync) }
        }
        controller?.let { mediaController ->
            if (mediaController.currentMediaItem != null) {
                mediaController.seekTo(
                    com.velastudio.teltv.player.SubtitleTiming.refreshSeekPositionMs(
                        mediaController.currentPosition, mediaController.duration.takeIf { it > 0 } ?: 0L
                    )
                )
                seekingText = "Subtitles: ${com.velastudio.teltv.player.SubtitleTiming.formatOffset(offset)}, " +
                    com.velastudio.teltv.player.SubtitleTiming.formatScale(newScale)
            }
        }
    }

    fun adjustSubtitleSync(newOffset: Long) = applySubtitleSync(newOffset, subtitleScale)

    fun adjustSubtitleScale(newScale: Double) = applySubtitleSync(subtitleSyncOffsetMs, newScale)

    /** The audio track ExoPlayer is playing now: its position among audio tracks and language. */
    fun selectedAudioTrack(): com.velastudio.teltv.sync.AudioTrackRef? {
        val groups = controller?.currentTracks?.groups ?: return null
        var ordinal = 0
        var firstSeen: com.velastudio.teltv.sync.AudioTrackRef? = null
        for (group in groups) {
            if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
            val ref = com.velastudio.teltv.sync.AudioTrackRef(ordinal, group.mediaTrackGroup.getFormat(0).language)
            if (group.isSelected) return ref
            if (firstSeen == null) firstSeen = ref
            ordinal++
        }
        return firstSeen
    }

    suspend fun verifyAndAttachSubtitle(sub: OnlineSubtitle, candidates: List<OnlineSubtitle>, confirmed: Boolean): com.velastudio.teltv.sync.SubtitlePickResult {
        subtitleSelectionRevision++ // Manual choice cancels any pending automatic attachment.
        val revision = subtitleSelectionRevision
        val started = resolvedUri()
        val startedAudio = selectedAudioTrack()
        fun stillCurrent() = controller?.currentMediaItem?.localConfiguration?.uri?.toString() == started &&
            startedAudio == selectedAudioTrack() && subtitleSelectionRevision == revision
        if (confirmed) {
            return if (stillCurrent() && attachOnlineSubtitle(sub, revision))
                com.velastudio.teltv.sync.SubtitlePickResult.Applied("Selected manually")
            else com.velastudio.teltv.sync.SubtitlePickResult.Failed("Video changed or download failed")
        }
        val length = durationMs.takeIf { it > 0 } ?: controller?.duration?.takeIf { it > 0 } ?: 0L
        val app = context.applicationContext as com.velastudio.teltv.TelTvApp
        val factory = com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory(app.telegramClient, context)
        seekingText = "Checking subtitle identity and timing..."
        val speech = if (started != null) kotlinx.coroutines.withTimeoutOrNull(45_000) {
            com.velastudio.teltv.sync.SubtitleSpeechProbe.sample(factory, Uri.parse(started), length, startedAudio)
        }.orEmpty() else emptyList()
        val choices = (listOf(sub) + candidates.filter {
            com.velastudio.teltv.util.SubtitleLanguages.normalize(it.lang) ==
                com.velastudio.teltv.util.SubtitleLanguages.normalize(sub.lang) && it.episodeEvidence?.titleMatch == true
        }).distinctBy { it.id }.take(3)
        var uncertain: Pair<OnlineSubtitle, String>? = null
        for (candidate in choices) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val result = com.velastudio.teltv.sync.SubtitleCandidateVerifier.check(context, candidate, candidates, length, speech)
            if (!stillCurrent()) return com.velastudio.teltv.sync.SubtitlePickResult.Failed("Video or audio track changed; check discarded")
            if (result.confident) {
                return if (attachOnlineSubtitle(candidate, revision)) com.velastudio.teltv.sync.SubtitlePickResult.Applied(result.message)
                    else com.velastudio.teltv.sync.SubtitlePickResult.Failed("Download failed")
            }
            if (!result.rejected && uncertain == null) uncertain = candidate to result.message
        }
        return uncertain?.let { com.velastudio.teltv.sync.SubtitlePickResult.Uncertain(it.first, it.second) }
            ?: com.velastudio.teltv.sync.SubtitlePickResult.Failed("No candidate passed the checks. Pick another result or search another episode title. Nothing changed.")
    }

    // Once per logical video, independent of the picker and playback speed. Preferences are read
    // before searching. Manual selection/Off while the network is busy prevents auto-attachment.
    LaunchedEffect(fileId, directUri, title, showName) {
        val started = resolvedUri() ?: return@LaunchedEffect
        val revision = subtitleSelectionRevision
        val language = prefs.subtitleLanguage.first()
        while (controller?.currentMediaItem?.localConfiguration?.uri?.toString() != started ||
            controller?.playbackState != Player.STATE_READY) delay(100)
        if (subtitleSelectionRevision != revision) return@LaunchedEffect
        try {
            val result = OnlineSubtitleProvider.searchSubtitlesDetailed(title, language, showName)
            if (resolvedUri() != started || subtitleSelectionRevision != revision) return@LaunchedEffect
            val best = SubtitleSelectionPolicy.best(result.subtitles, language)
            if (best != null) attachOnlineSubtitle(best, revision)
            else seekingText = result.reason ?: "No ${com.velastudio.teltv.util.SubtitleLanguages.displayName(language)} subtitle found. Search in Subtitles."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Automatic subtitle search failed")
            if (resolvedUri() == started && subtitleSelectionRevision == revision)
                seekingText = "Automatic subtitle download failed. Manual search is available."
        }
    }

    fun currentSyncTarget() = com.velastudio.teltv.sync.AutoSyncTarget(
        mediaUri = resolvedUri(),
        subtitlePath = activeSubtitleFile?.absolutePath,
        audio = selectedAudioTrack()
    )

    suspend fun autoSyncSubtitle(): String {
        val file = activeSubtitleFile ?: return "Select an online subtitle first"
        val uri = resolvedUri() ?: return "No media to analyse"
        val app = context.applicationContext as com.velastudio.teltv.TelTvApp
        val factory = com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory(app.telegramClient, context)
        val length = durationMs.takeIf { it > 0 } ?: controller?.duration?.takeIf { it > 0 } ?: 0L
        // Identity captured now; checked again before anything is applied.
        val started = currentSyncTarget()
        // Prefer the video's own embedded text subtitle as reference (language-agnostic, not fooled by
        // music). Falls back to audio loudness when the file has none or the platform cannot read it.
        val embedded = com.velastudio.teltv.sync.EmbeddedReferenceSync.run(factory, Uri.parse(uri), file, length)
        val outcome = embedded.takeIf { it is com.velastudio.teltv.sync.AutoSyncOutcome.Synced || it is com.velastudio.teltv.sync.AutoSyncOutcome.AlreadyInSync }
            ?: com.velastudio.teltv.sync.SubtitleAutoSync.run(factory, Uri.parse(uri), file, length, started.audio)
        val decision = com.velastudio.teltv.sync.AutoSyncPolicy.decide(
            outcome, subtitleSyncOffsetMs, started, currentSyncTarget()
        )
        decision.newOffsetMs?.let { adjustSubtitleSync(it) }
        return decision.message
    }

    /** Aligns the active subtitle to another downloaded subtitle that the user says is in sync. */
    suspend fun syncToReferenceSubtitle(reference: OnlineSubtitle): String {
        val target = activeSubtitleFile ?: return "Select an online subtitle first"
        val started = currentSyncTarget()
        val refFile = OnlineSubtitleProvider.downloadSubtitle(context, reference)
            ?: return "Could not download the reference subtitle"
        if (refFile.absolutePath == target.absolutePath) return "Pick a different subtitle as the reference"
        val decision = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.velastudio.teltv.sync.ReferenceSyncPolicy.decide(refFile.readText(), target.readText())
        }
        if (started != currentSyncTarget()) return "Sync discarded: the video or subtitle changed"
        decision.sync?.let { applySubtitleSync(it.offsetMs, it.scale) }
        return decision.message
    }

    fun openInExternalPlayer() {
        val streamUri = resolvedUri() ?: return
        if (!PlaybackPolicy.canOpenExternally(streamUri)) {
            android.widget.Toast.makeText(context, "Telegram streams can only be played inside TelTV.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
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

    DisposableEffect(connectionAttempt, fileId, directUri) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(mediaController: MediaController) {
                    if (controller !== mediaController) return
                    // Cached snapshot is retained even when the service is gone.
                    controller = null
                    needsRecovery = true
                    isBuffering = false
                    if (foreground && !disconnectedRebindUsed) {
                        disconnectedRebindUsed = true
                        connectionAttempt++
                    } else if (foreground) {
                        playerErrorMessage = "Playback service disconnected. Retry to reconnect."
                    }
                }
            }).buildAsync()

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val mediaController = controller ?: return
                val mediaUri = mediaController.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                if (autoSubtitleAppliedUri == mediaUri) return
                val textTracks = tracks.groups
                    .filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                    .flatMap { group ->
                        (0 until group.length)
                            .filter { group.isTrackSupported(it) }
                            .map { trackIndex -> Triple(group, trackIndex, group.getTrackFormat(trackIndex)) }
                    }
                if (textTracks.isEmpty()) return
                autoSubtitleAppliedUri = mediaUri

                val forcedMatch = preferredSubtitleLanguages.firstNotNullOfOrNull { preferredLanguage ->
                    textTracks.firstOrNull { (_, _, format) ->
                        format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED != 0 &&
                            OnlineSubtitleProvider.isPreferredLanguage(format.language, listOf(preferredLanguage))
                    }
                }
                val fallbackMatch = preferredSubtitleLanguages.firstNotNullOfOrNull { preferredLanguage ->
                    textTracks.firstOrNull { (_, _, format) ->
                        OnlineSubtitleProvider.isPreferredLanguage(format.language, listOf(preferredLanguage))
                    }
                }
                val subtitleMatch = forcedMatch ?: fallbackMatch ?: return
                val (group, trackIndex) = subtitleMatch
                val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
                mediaController.trackSelectionParameters = mediaController.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(override)
                    .build()
                Timber.i(
                    "Auto-selected %s embedded subtitle for TV language preference",
                    if (forcedMatch != null) "forced" else "full"
                )
            }
            override fun onEvents(player: Player, events: Player.Events) {
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
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    val next = latestOnPlayNext
                    if (latestForeground && autoPlayNext && !autoPlayCancelled && next != null && latestNextTitle != null) {
                        showAutoPlayOverlay = false
                        next()
                    } else if (latestForeground) {
                        showAutoPlayOverlay = false
                        latestOnPlaybackEnded()
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

        var disposed = false
        val connectTimeout = coroutineScope.launch {
            delay(10_000L)
            if (!controllerFuture.isDone) {
                MediaController.releaseFuture(controllerFuture)
                isBuffering = false
                playerErrorMessage = "Playback service did not respond. Retry to reconnect."
            }
        }
        controllerFuture.addListener({
            if (!disposed) {
                try {
                    val mediaController = controllerFuture.get()
                    mediaController.addListener(listener)
                    controller = mediaController
                } catch (_: java.util.concurrent.CancellationException) {
                    // The screen was closed before the service connected.
                } catch (error: Exception) {
                    Timber.e(error, "Could not connect playback controller")
                    isBuffering = false
                    playerErrorMessage = "Could not connect playback service. Retry to reconnect."
                }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            connectTimeout.cancel()
            controller?.let { mediaController ->
                mediaController.removeListener(listener)
                if (mediaController.isConnected) {
                    if (!needsRecovery) latestOnPositionUpdate(mediaController.currentPosition, mediaController.duration.coerceAtLeast(0L))
                    // Rebind attempts only replace null/disconnected controllers.
                    mediaController.stop()
                }
            }
            MediaController.releaseFuture(controllerFuture)
            controller = null
        }
    }

    DisposableEffect(lifecycleOwner, fileId, directUri) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!needsRecovery) snapshotPlayback()
                    foreground = false
                    needsRecovery = true
                    controller?.takeIf { it.isConnected }?.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    disconnectedRebindUsed = false
                    if (controller?.isConnected != true) connectionAttempt++
                    else if (needsRecovery) recoveryRequest++
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        while (true) {
            if (foreground && mediaController.isConnected && !needsRecovery) {
                currentPositionMs = mediaController.currentPosition
                savedPosition = currentPositionMs.coerceAtLeast(0L)
                savedMediaItem = mediaController.currentMediaItem ?: savedMediaItem
                savedTracks = mediaController.trackSelectionParameters
                savedPlayIntent = mediaController.playWhenReady
            }
            delay(500)
        }
    }

    LaunchedEffect(controller, isPlaying, autoPlayNext, onPlayNext, foreground) {
        val mediaController = controller ?: return@LaunchedEffect
        var tickCount = 0
        while (true) {
            delay(1000L) // 1-second precision tracking
            val dur = mediaController.duration
            val pos = mediaController.currentPosition
            if (dur > 0) {
                tickCount++
                if (tickCount >= 5) {
                    tickCount = 0
                    latestOnPositionUpdate(pos, dur)
                }
                // Autoplay trigger
                if (dur > 60_000L && pos > 0L) {
                    if (PlaybackPolicy.shouldOfferNext(dur, pos, mediaController.isPlaying, foreground, autoPlayNext, onPlayNext != null, autoPlayTriggered || autoPlayCancelled) && !showAutoPlayOverlay) {
                        autoPlayTriggered = true
                        showAutoPlayOverlay = true
                    }
                }
            }
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, showTrackSelector, showPlaybackSpeed) {
        if (controlsVisible && isPlaying && !showTrackSelector && !showPlaybackSpeed && !showAutoPlayOverlay) {
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
        if (isCurrentVideo4x3) {
            coroutineScope.launch { prefs.setAspectRatio4x3(aspectRatioIndex) }
        }
    }

    fun togglePlayPause() {
        val mediaController = controller ?: return
        if (mediaController.playWhenReady) {
            mediaController.pause()
        } else {
            mediaController.play()
        }
        savedPlayIntent = mediaController.playWhenReady
        controlsVisible = true
    }

    fun retryPlayback() {
        // Preserve subtitle configuration, track selection, position and pause intent.
        if (!needsRecovery) snapshotPlayback()
        needsRecovery = true
        playerErrorMessage = null
        disconnectedRebindUsed = false
        if (controller?.isConnected != true) connectionAttempt++ else recoveryRequest++
    }

    LaunchedEffect(controller) {
        val mediaController = controller ?: return@LaunchedEffect
        playerViewRef?.let { pv ->
            if (pv.player !== mediaController) {
                pv.player = mediaController
            }
        }
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
                (android.view.LayoutInflater.from(ctx).inflate(R.layout.teltv_player_view, null, false) as PlayerView).apply {
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    val shutter = findViewById<android.view.View>(androidx.media3.ui.R.id.exo_shutter)
                    (shutter?.parent as? android.view.ViewGroup)?.removeView(shutter)
                    playerViewRef = this
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
                if (view.player !== controller) {
                    view.player = controller
                }
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
            visible = controlsVisible && !showTrackSelector && !showPlaybackSpeed && !showAutoPlayOverlay,
            onPlayPause = ::togglePlayPause,
            onSkipBack = { seekRelative(forward = false) },
            onSkipForward = { seekRelative(forward = true) },
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onOpenTracks = { showTrackSelector = true },
            playbackSpeed = playbackSpeed,
            onOpenPlaybackSpeed = { showPlaybackSpeed = true },
            onCycleAspectRatio = ::cycleAspectRatio,
            onOpenExternal = if (PlaybackPolicy.canOpenExternally(resolvedUri())) ::openInExternalPlayer else null,
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

        if (showPlaybackSpeed) {
            PlaybackSpeedDialog(
                selected = playbackSpeed,
                onSelect = { speed ->
                    controller?.takeIf { it.isConnected }?.let { mc ->
                        mc.playbackParameters = PlaybackSpeedPolicy.parameters(speed)
                        playbackSpeed = mc.playbackParameters.speed
                    }
                    showPlaybackSpeed = false
                    controlsVisible = true
                    runCatching { playPauseFocusRequester.requestFocus() }
                },
                onDismiss = {
                    showPlaybackSpeed = false
                    controlsVisible = true
                    runCatching { playPauseFocusRequester.requestFocus() }
                }
            )
        }

        if (showTrackSelector) {
            TrackSelectorDialog(
                controller = controller,
                videoTitle = title,
                showName = showName,
                onVerifySubtitle = ::verifyAndAttachSubtitle,
                onSelectEmbeddedSubtitle = {
                    subtitleSelectionRevision++
                    activeSubtitleFile = null
                    activeSubtitleId = null
                    subtitleSyncOffsetMs = 0L
                    subtitleScale = 1.0
                    SubtitleSyncOffset.set(com.velastudio.teltv.player.SubtitleSync())
                },
                syncOffsetMs = subtitleSyncOffsetMs,
                onAdjustSyncOffset = ::adjustSubtitleSync,
                subtitleScale = subtitleScale,
                onAdjustSubtitleScale = ::adjustSubtitleScale,
                onlineSubtitleSelected = activeSubtitleFile != null,
                onAutoSync = ::autoSyncSubtitle,
                onSyncToReference = ::syncToReferenceSubtitle,
                prefs = prefs,
                onDismiss = { showTrackSelector = false }
            )
        }

        if (showAutoPlayOverlay && nextTitle != null && onPlayNext != null) {
            val remainingSec = maxOf(1, ((durationMs - currentPositionMs) / 1000).toInt())
            AutoPlayCountdownOverlay(
                nextTitle = nextTitle?.let { MediaTitleCleaner.clean(it) } ?: "Next Episode",
                initialSeconds = remainingSec,
                countdownRunning = foreground && isPlaying && autoPlayNext,
                onPlayNow = {
                    showAutoPlayOverlay = false
                    onPlayNext()
                },
                onCancel = {
                    showAutoPlayOverlay = false
                    autoPlayCancelled = true
                    controlsVisible = true
                }
            )
        }

        playerErrorMessage?.let { message ->
            PlaybackErrorOverlay(
                message = message,
                onRetry = ::retryPlayback,
                onBack = onBack,
                onOpenExternal = if (PlaybackPolicy.canOpenExternally(resolvedUri())) ::openInExternalPlayer else null
            )
        }
    }
}
