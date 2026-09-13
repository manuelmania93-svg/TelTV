package com.velastudio.teltv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.velastudio.teltv.ui.theme.TelTvYellow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Button
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
    onPlayNext: (() -> Unit)? = null,
    onOpenTracks: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onOpenExternal: () -> Unit,
    autoPlayNext: Boolean,
    onToggleAutoPlay: () -> Unit,
    playPauseModifier: Modifier = Modifier
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
                            icon = Icons.Filled.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            onClick = onCycleAspectRatio
                        )
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
                        large = true,
                        modifier = playPauseModifier
                    )
                    Spacer(Modifier.width(32.dp))
                    ControlButton(
                        icon = if (skipIncrementMs >= 10_000) Icons.Filled.Forward10 else Icons.Filled.Forward5,
                        contentDescription = "Skip forward",
                        onClick = onSkipForward
                    )
                    if (onPlayNext != null) {
                        Spacer(Modifier.width(32.dp))
                        ControlButton(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Next Episode",
                            onClick = onPlayNext
                        )
                    }
                    Spacer(Modifier.width(32.dp))
                    Button(
                        onClick = onToggleAutoPlay,
                        colors = ButtonDefaults.colors(
                            containerColor = if (autoPlayNext) TelTvYellow else Color.White.copy(alpha = 0.15f),
                            focusedContainerColor = if (autoPlayNext) Color(0xFFFFD54F) else Color.White,
                            contentColor = if (autoPlayNext) Color.Black else Color.White,
                            focusedContentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (autoPlayNext) "Autoplay: ON" else "Autoplay: OFF",
                            fontWeight = FontWeight.Bold
                        )
                    }
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
    large: Boolean = false,
    active: Boolean = false,
    modifier: Modifier = Modifier
) {
    val size = if (large) 72.dp else 52.dp
    var isFocused by remember { mutableStateOf(false) }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .onFocusChanged { isFocused = it.isFocused }
            .background(
                color = when {
                    isFocused && active -> TelTvYellow
                    isFocused -> Color.White
                    active -> TelTvYellow.copy(alpha = 0.85f)
                    else -> Color.White.copy(alpha = 0.15f)
                },
                shape = CircleShape
            ),
        colors = IconButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (active && !isFocused) Color.Black else Color.White,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Color.Black
        )
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (isFocused || (active && !isFocused)) Color.Black else Color.White
        )
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
