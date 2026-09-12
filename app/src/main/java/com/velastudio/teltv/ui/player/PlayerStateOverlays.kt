package com.velastudio.teltv.ui.player

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
                            onDismiss()
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

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close")
            }
        }
    }
}
