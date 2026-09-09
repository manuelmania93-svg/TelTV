package com.velastudio.teltv.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

/**
 * Standalone confirmation dialog for clearing the cache, used by both [CacheSettingsSection]
 * (in Settings) and the quick-clear button on the Home screen.  Keeps the destructive-action
 * warning text in one place so it can't drift between the two entry points.
 */
@Composable
fun ClearCacheConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(24.dp)) {
                Text("Clear cache?", style = androidx.tv.material3.MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("This deletes every downloaded video file from this device. Nothing on Telegram is affected, but you'll re-download anything you watch again.")
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = {
                        onDismiss()
                        onConfirm()
                    }) { Text("Clear cache") }
                }
            }
        }
    }
}

/**
 * Cache section of Settings:
 *   - Shows current cache size (from TDLib's real storage stats, not an estimate).
 *   - "Clear cache now" button -> confirmation dialog -> CacheManager.clearAllNow(). The dialog
 *     matters because this is a destructive, irreversible action one D-pad click away on a
 *     screen you're otherwise just scrolling through -- an accidental press used to just wipe
 *     everything with no chance to back out.
 *   - Toggle + stepped +/- control for automatic clearing once the cache crosses a limit ->
 *     defaults to 5GB, adjustable 1-20GB, persisted via DataStore and enforced by
 *     CacheManager.maybeAutoClear(). This used to be a plain Material (phone/touch) Slider,
 *     which doesn't have reliable D-pad focus/key handling -- it was the one drag-based control
 *     in an otherwise all-remote-navigable app. Stepped buttons behave like every other control
 *     here: focusable, single D-pad press per step.
 */
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

    Column(Modifier.padding(24.dp)) {
        Text("Cache", style = androidx.tv.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("Currently using ${"%.2f".format(currentSizeBytes / 1024.0 / 1024.0 / 1024.0)} GB")

        Spacer(Modifier.height(16.dp))
        Button(onClick = { showClearConfirm = true }) { Text("Clear cache now") }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Auto-clear when cache is full")
            Spacer(Modifier.width(12.dp))
            Switch(checked = autoClearEnabled, onCheckedChange = onToggleAutoClear)
        }

        if (autoClearEnabled) {
            Spacer(Modifier.height(12.dp))
            Text("Limit: ${limitGb.toInt()} GB")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(label = "\u2212", enabled = limitGb > 1f) {
                    onLimitChanged((limitGb - 1f).coerceIn(1f, 20f))
                }
                Spacer(Modifier.width(16.dp))
                Text("${limitGb.toInt()} GB", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(16.dp))
                StepButton(label = "+", enabled = limitGb < 20f) {
                    onLimitChanged((limitGb + 1f).coerceIn(1f, 20f))
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
    Button(onClick = onClick, enabled = enabled) {
        Text(label, style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
    }
}

/**
 * Playback section of Settings: lets Manny pick 5s or 10s as the skip-back/skip-forward amount
 * used everywhere in the player (see PlaybackPrefs + PlaybackControlsOverlay).
 */
@Composable
fun PlaybackSettingsSection(
    skipIncrementMs: Long,
    onSkipIncrementChanged: (Long) -> Unit
) {
    Column(Modifier.padding(24.dp)) {
        Text("Playback", style = androidx.tv.material3.MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("Skip amount")
        Spacer(Modifier.height(8.dp))
        Row {
            SkipChoiceButton("5s", selected = skipIncrementMs == 5_000L) { onSkipIncrementChanged(5_000L) }
            Spacer(Modifier.width(12.dp))
            SkipChoiceButton("10s", selected = skipIncrementMs == 10_000L) { onSkipIncrementChanged(10_000L) }
        }
    }
}

@Composable
private fun SkipChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(if (selected) "\u2713 $label" else label)
    }
}
