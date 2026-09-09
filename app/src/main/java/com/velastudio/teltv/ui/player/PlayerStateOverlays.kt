package com.velastudio.teltv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Shown whenever ExoPlayer reports STATE_BUFFERING -- streaming over SMB/WebDAV/Telegram can
 * stall for real, and previously there was nothing on screen to distinguish "still loading" from
 * a frozen app. Small and centered, doesn't block the rest of the screen.
 */
@Composable
fun BufferingIndicator() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Color.White)
    }
}

/**
 * Shown when playback fails outright (network drop, unreachable NAS/WebDAV host, TDLib error,
 * unsupported stream, etc.). Previously a failure here just left a black or frozen frame with no
 * indication anything went wrong and no way to recover except blindly hitting Back. Retry
 * re-prepares the same item from the current position; Back leaves the player entirely.
 */
@Composable
fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
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
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onBack) { Text("Back") }
                Button(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}
