package com.velastudio.teltv.ui.player

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

import com.velastudio.teltv.ui.player.PlaybackPrefs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.OutlinedButtonDefaults
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
import com.velastudio.teltv.util.OnlineSubtitle
import com.velastudio.teltv.util.OnlineSubtitleProvider

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
    initialSeconds: Int = 5,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit
) {
    var secondsRemaining by remember(initialSeconds) { mutableStateOf(initialSeconds) }
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
            .background(Color.Transparent), // No dimming of the background video
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier
                .padding(end = 28.dp, bottom = 28.dp)
                .background(Color(0xE6141820), RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(min = 260.dp, max = 340.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Next in ${secondsRemaining}s",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = nextTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlayNow,
                    modifier = Modifier
                        .focusRequester(playNowFocusRequester)
                        .height(34.dp),
                    shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFFFFC107),
                        focusedContainerColor = Color(0xFFFFD54F),
                        contentColor = Color.Black,
                        focusedContentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text("▶ Play Now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(34.dp),
                    shape = OutlinedButtonDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = OutlinedButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White,
                        focusedContentColor = Color.White
                    ),
                    border = OutlinedButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))),
                        focusedBorder = Border(BorderStroke(1.dp, Color.White))
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelMedium)
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
    videoTitle: String = "",
    onSelectOnlineSubtitle: (OnlineSubtitle) -> Unit = {},
    syncOffsetMs: Long = 0L,
    onAdjustSyncOffset: (Long) -> Unit = {},
    prefs: PlaybackPrefs? = null,
    onDismiss: () -> Unit
) {
    if (controller == null) return
    val coroutineScope = rememberCoroutineScope()

    var currentTracks by remember { mutableStateOf(controller.currentTracks) }
    androidx.compose.runtime.DisposableEffect(controller) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                currentTracks = tracks
            }
        }
        controller.addListener(listener)
        onDispose {
            controller.removeListener(listener)
        }
    }

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

    var onlineSubtitles by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var isLoadingOnlineSubs by remember { mutableStateOf(false) }

    LaunchedEffect(videoTitle) {
        if (videoTitle.isNotBlank()) {
            isLoadingOnlineSubs = true
            onlineSubtitles = OnlineSubtitleProvider.searchSubtitles(videoTitle)
            isLoadingOnlineSubs = false
        }
    }

    // Default to Online Subs if video has 0 embedded subtitle tracks
    var selectedTab by remember { mutableStateOf(if (subtitleTracks.size <= 1) 2 else 0) } // 0 = Audio, 1 = Embedded, 2 = Online Subs

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(500.dp)
                .background(Color(0xFF222222), RoundedCornerShape(16.dp))
                .padding(24.dp)
        ) {
            Text("Audio & Subtitles", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { selectedTab = 0 }) {
                    Text(if (selectedTab == 0) "● Audio (${audioTracks.size})" else "Audio (${audioTracks.size})")
                }
                Button(onClick = { selectedTab = 1 }) {
                    Text(if (selectedTab == 1) "● Embedded (${subtitleTracks.size - 1})" else "Embedded (${subtitleTracks.size - 1})")
                }
                Button(onClick = { selectedTab = 2 }) {
                    Text(if (selectedTab == 2) "● Online (${if (isLoadingOnlineSubs) "..." else onlineSubtitles.size})" else "Online (${if (isLoadingOnlineSubs) "..." else onlineSubtitles.size})")
                }
            }

            Spacer(Modifier.height(14.dp))

            // Subtitle Tuning Panel (Sync Offset & Styling) shown for Embedded & Online tabs
            if (selectedTab == 1 || selectedTab == 2) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sync: ${if (syncOffsetMs >= 0) "+${syncOffsetMs}ms" else "${syncOffsetMs}ms"}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { onAdjustSyncOffset(syncOffsetMs - 250L) }) { Text("-250ms") }
                            Button(onClick = { onAdjustSyncOffset(0L) }) { Text("0s") }
                            Button(onClick = { onAdjustSyncOffset(syncOffsetMs + 250L) }) { Text("+250ms") }
                        }
                    }

                    if (prefs != null) {
                        val subSize by prefs.subtitleSize.collectAsState(initial = "LARGE")
                        val subColor by prefs.subtitleColor.collectAsState(initial = "WHITE")
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Color:", color = Color.LightGray, fontSize = 12.sp)
                                Button(onClick = {
                                    coroutineScope.launch {
                                        prefs.setSubtitleColor(if (subColor == "WHITE") "YELLOW" else "WHITE")
                                    }
                                }) {
                                    Text(if (subColor == "YELLOW") "● Yellow" else "○ White")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Size:", color = Color.LightGray, fontSize = 12.sp)
                                Button(onClick = {
                                    val next = when (subSize) {
                                        "NORMAL" -> "LARGE"
                                        "LARGE" -> "XLARGE"
                                        else -> "NORMAL"
                                    }
                                    coroutineScope.launch { prefs.setSubtitleSize(next) }
                                }) {
                                    Text(subSize)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            } else if (selectedTab == 0 && prefs != null) {
                // Dialogue Boost toggle for Audio tab
                val dialogueBoost by prefs.dialogueBoostEnabled.collectAsState(initial = false)
                androidx.tv.material3.Card(
                    onClick = { coroutineScope.launch { prefs.setDialogueBoostEnabled(!dialogueBoost) } },
                    colors = androidx.tv.material3.CardDefaults.colors(
                        containerColor = if (dialogueBoost) Color(0xFF2E7D32).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f),
                        focusedContainerColor = if (dialogueBoost) Color(0xFF43A047) else Color(0xFF29B6F6)
                    ),
                    shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(8.dp)),
                    scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dialogue Boost (Night Mode)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("Normalizes volume & clarifies speech", color = Color.LightGray, fontSize = 11.sp)
                        }
                        Text(if (dialogueBoost) "ON" else "OFF", color = if (dialogueBoost) Color(0xFF81C784) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            if (selectedTab == 2) {
                if (isLoadingOnlineSubs) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.CircularProgressIndicator(color = Color(0xFF29B6F6))
                            Spacer(Modifier.height(12.dp))
                            Text("Searching OpenSubtitles database...", color = Color.LightGray, fontSize = 14.sp)
                        }
                    }
                } else if (onlineSubtitles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No online subtitles found for this title.", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 240.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(onlineSubtitles) { sub ->
                            androidx.tv.material3.Card(
                                onClick = {
                                    onSelectOnlineSubtitle(sub)
                                    onDismiss()
                                },
                                colors = androidx.tv.material3.CardDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Color(0xFF29B6F6)
                                ),
                                shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(8.dp)),
                                scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.02f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.langDisplay, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        Text(sub.fileName, color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                                    }
                                    Icon(Icons.Filled.Check, contentDescription = "Download & Select", tint = Color.White.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            } else {
                val activeList = if (selectedTab == 0) audioTracks else subtitleTracks
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(activeList) { track ->
                        androidx.tv.material3.Card(
                            onClick = {
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
                                // Removed onDismiss() so user can verify checkmark move and set both Audio & Subs
                            },
                            colors = androidx.tv.material3.CardDefaults.colors(
                                containerColor = if (track.isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                                focusedContainerColor = Color(0xFF29B6F6)
                            ),
                            shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(8.dp)),
                            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.02f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
