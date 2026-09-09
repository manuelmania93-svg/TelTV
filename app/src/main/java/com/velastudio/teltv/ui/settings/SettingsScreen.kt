package com.velastudio.teltv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*

@Composable
fun ClearCacheConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E2638))
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "Clear Cache?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "This deletes all downloaded video files from this device. Nothing on Telegram is deleted.",
                    color = Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.colors(containerColor = Color(0xFF2D3748))
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                    Button(
                        onClick = {
                            onDismiss()
                            onConfirm()
                        },
                        colors = ButtonDefaults.colors(containerColor = Color(0xFFEF5350))
                    ) {
                        Text("Clear Cache", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun CacheSettingsSection(
    currentSizeBytes: Long,
    autoClearEnabled: Boolean,
    limitGb: Float,
    onToggleAutoClear: (Boolean) -> Unit,
    onLimitChanged: (Float) -> Unit,
    onClearNow: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF161D27))
            .padding(24.dp)
    ) {
        Column {
            Text(
                "Storage & Cache",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Currently using: ${"%.2f".format(currentSizeBytes / 1024.0 / 1024.0 / 1024.0)} GB",
                color = Color(0xFF29B6F6),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { showClearConfirm = true },
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF2D3748),
                    focusedContainerColor = Color(0xFFEF5350)
                )
            ) {
                Text("Clear Cache Now", color = Color.White)
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-clear cache when limit is reached", color = Color.White)
                Spacer(Modifier.width(16.dp))
                Switch(checked = autoClearEnabled, onCheckedChange = onToggleAutoClear)
            }

            if (autoClearEnabled) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Limit: ", color = Color(0xFFB0BEC5))
                    Spacer(Modifier.width(12.dp))
                    StepButton(label = "−", enabled = limitGb > 1f) {
                        onLimitChanged((limitGb - 1f).coerceIn(1f, 20f))
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "${limitGb.toInt()} GB",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.width(16.dp))
                    StepButton(label = "+", enabled = limitGb < 20f) {
                        onLimitChanged((limitGb + 1f).coerceIn(1f, 20f))
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = onClearNow
        )
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFF212B3A),
            focusedContainerColor = Color(0xFF29B6F6)
        )
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
fun PlaybackSettingsSection(
    skipIncrementMs: Long,
    onSkipIncrementChanged: (Long) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF161D27))
            .padding(24.dp)
    ) {
        Column {
            Text(
                "Player Controls",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text("Remote Skip Amount (Left / Right keys)", color = Color(0xFFB0BEC5))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkipChoiceButton("5 seconds", selected = skipIncrementMs == 5_000L) {
                    onSkipIncrementChanged(5_000L)
                }
                SkipChoiceButton("10 seconds", selected = skipIncrementMs == 10_000L) {
                    onSkipIncrementChanged(10_000L)
                }
            }
        }
    }
}

@Composable
private fun SkipChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF004D73) else Color(0xFF212B3A),
            focusedContainerColor = Color(0xFF29B6F6)
        )
    ) {
        Text(
            if (selected) "✓ $label" else label,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
