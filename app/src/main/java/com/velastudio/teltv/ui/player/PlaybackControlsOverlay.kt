package com.velastudio.teltv.ui.player
import androidx.compose.material3.LinearProgressIndicator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.LinearProgressIndicator
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.concurrent.TimeUnit

/**
 * The overlay you'd expect from any decent video player: title, seek bar with elapsed/remaining
 * time, and play/pause + skip-back/skip-forward. Skip amount matches the Settings choice (5s or
 * 10s -- see PlaybackPrefs). Shows on any D-pad/remote input, auto-hides after a few seconds of
 * inactivity during playback.
 */
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
    onSkipForward: () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        startY = 0.4f
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 28.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(12.dp))

                val progress = if (durationMs > 0) currentPositionMs.toFloat() / durationMs else 0f
                LinearProgressIndicator(
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
    val size = if (large) 72.dp else 56.dp
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
