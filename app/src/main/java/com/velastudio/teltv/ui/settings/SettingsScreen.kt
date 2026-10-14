package com.velastudio.teltv.ui.settings

import kotlinx.coroutines.launch

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.tv.material3.*
import com.velastudio.teltv.data.local.PlaylistEntity
import com.velastudio.teltv.ui.theme.TelTvMuted
import com.velastudio.teltv.ui.theme.TelTvPanel
import com.velastudio.teltv.ui.theme.TelTvPanelFocused
import com.velastudio.teltv.ui.theme.TelTvYellow

@Composable
fun ClearCacheConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(TelTvPanelFocused)
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
    cacheSizeAvailable: Boolean,
    freeStorageBytes: Long,
    totalStorageBytes: Long,
    cacheLimitBytes: Long,
    autoClearEnabled: Boolean,
    onToggleAutoClear: (Boolean) -> Unit,
    onSelectLimit: (Long) -> Unit,
    onClearNow: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelTvPanel)
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
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                StorageMetric(
                    label = "Cache used",
                    value = if (cacheSizeAvailable) formatGigabytes(currentSizeBytes) else "Unavailable",
                    valueColor = TelTvYellow
                )
                StorageMetric(
                    label = "TV storage free",
                    value = "${formatGigabytes(freeStorageBytes)} / ${formatGigabytes(totalStorageBytes)}",
                    valueColor = Color.White
                )
            }

            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.height(14.dp))
            Text("Max Streaming Cache Limit", color = TelTvMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val limits = listOf(
                    500L * 1024 * 1024 to "500 MB",
                    1024L * 1024 * 1024 to "1 GB",
                    2L * 1024 * 1024 * 1024 to "2 GB",
                    5L * 1024 * 1024 * 1024 to "5 GB"
                )
                limits.forEach { (limit, label) ->
                    SkipChoiceButton(label = label, selected = cacheLimitBytes == limit) {
                        onSelectLimit(limit)
                    }
                }
            }

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
                Text("Automatically manage cache and clear when TV storage is almost full", color = Color.White)
                Spacer(Modifier.width(16.dp))
                Switch(checked = autoClearEnabled, onCheckedChange = onToggleAutoClear)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Downloaded subtitles are kept during cache clearing so playback is not interrupted.",
                color = TelTvMuted,
                style = MaterialTheme.typography.bodySmall
            )
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
fun PlaylistSettingsSection(
    playlists: List<PlaylistEntity>,
    onCreate: (String) -> Unit,
    onPlay: (PlaylistEntity) -> Unit,
    onDelete: (PlaylistEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelTvPanel)
            .padding(24.dp)
    ) {
        Column {
            Text("Playlists & Marathons", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Create an ordered queue for a full series marathon.", color = TelTvMuted)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { androidx.compose.material3.Text("Playlist name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TelTvYellow,
                        unfocusedBorderColor = TelTvMuted
                    ),
                    modifier = Modifier.width(300.dp)
                )
                Button(
                    onClick = { onCreate(name.trim()); name = "" },
                    enabled = name.isNotBlank()
                ) { Text("Create") }
            }
            Spacer(Modifier.height(16.dp))
            playlists.forEach { playlist ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(playlist.name, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onPlay(playlist) }) { Text("Play All") }
                        Button(onClick = { onDelete(playlist) }) { Text("Delete") }
                    }
                }
            }
            if (playlists.isEmpty()) {
                Text("No playlists yet. Create one, then add episodes with a long-press in a channel.", color = TelTvMuted)
            }
        }
    }
}

@Composable
private fun StorageMetric(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, color = TelTvMuted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

private fun formatGigabytes(bytes: Long): String =
    "%.2f GB".format((bytes.coerceAtLeast(0L)).toDouble() / 1024.0 / 1024.0 / 1024.0)

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
fun AppInfoSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "TelTV",
            color = TelTvYellow,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "App creator: Manuel Mania",
            color = TelTvMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            text = "Copyright © 2026",
            color = TelTvMuted,
            style = MaterialTheme.typography.bodySmall
        )
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

@Composable
fun AppUpdateSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var statusMessage by remember { 
        mutableStateOf<String?>("Installed version: v${com.velastudio.teltv.BuildConfig.VERSION_NAME} (build ${com.velastudio.teltv.BuildConfig.VERSION_CODE})") 
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelTvPanel)
            .padding(24.dp)
    ) {
        Column {
            Text(
                "Software Update",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Download the latest APK build directly from GitHub and install it on this TV.",
                color = TelTvMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(14.dp))

            Text(
                text = statusMessage ?: "",
                color = if (isDownloading) TelTvYellow else Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    if (isChecking || isDownloading) return@Button
                    isChecking = true
                    statusMessage = "Connecting to GitHub..."
                    scope.launch {
                        val result = com.velastudio.teltv.util.AppUpdater.checkForUpdateResult(force = true)
                        isChecking = false
                        when (result) {
                            is com.velastudio.teltv.util.UpdateCheckResult.Available -> {
                                statusMessage = "Found update! Downloading APK..."
                                isDownloading = true
                                val installResult = com.velastudio.teltv.util.AppUpdater.downloadAndInstall(context, result.info.downloadUrl) { progress ->
                                    downloadProgress = progress
                                }
                                isDownloading = false
                                statusMessage = when (installResult) {
                                    com.velastudio.teltv.util.InstallResult.INSTALLER_OPENED -> "Opening package installer..."
                                    com.velastudio.teltv.util.InstallResult.PERMISSION_REQUIRED -> "Grant install permission, return here, then click again."
                                    com.velastudio.teltv.util.InstallResult.FAILED -> "Download or installer failed. Please retry."
                                }
                            }
                            is com.velastudio.teltv.util.UpdateCheckResult.Failed -> statusMessage = result.message
                            com.velastudio.teltv.util.UpdateCheckResult.UpToDate -> statusMessage = "Already on latest version."
                        }
                    }
                },
                enabled = !isChecking && !isDownloading,
                colors = ButtonDefaults.colors(
                    containerColor = Color(0xFF212B3A),
                    focusedContainerColor = TelTvYellow
                )
            ) {
                Text(
                    when {
                        isDownloading -> "Downloading: ${(downloadProgress * 100).toInt()}%"
                        isChecking -> "Connecting..."
                        else -> "🔄 Check for Updates & Install"
                    },
                    color = Color.White
                )
            }

            if (isDownloading) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = downloadProgress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = TelTvYellow
                )
            }
        }
    }
}


@Composable
fun PerformanceSettingsSection(
    fastModeEnabled: Boolean,
    onToggleFastMode: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelTvPanel)
            .padding(24.dp)
    ) {
        Column {
            Text(
                "Performance & Memory",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Disables image thumbnails and background poster lookups. Greatly reduces RAM consumption and makes scrolling 1,000+ episode series instantaneous on low-RAM TV sticks.",
                color = TelTvMuted,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Fast Mode (Disable Thumbnails)",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (fastModeEnabled) "ON (Minimal RAM & instant 60 FPS scrolling)" else "OFF (Thumbnails active)",
                        color = if (fastModeEnabled) TelTvYellow else TelTvMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = fastModeEnabled,
                    onCheckedChange = onToggleFastMode
                )
            }
        }
    }
}

/** The real key is never loaded into a text field or Compose saved state. */
@Composable
fun SubDlSettingsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var configured by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        configured = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.velastudio.teltv.util.SubDlKeyStore.read(context).isNotBlank() }.getOrDefault(false)
        }
    }
    fun save(value: String) {
        busy = true
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.velastudio.teltv.util.SubDlKeyStore.save(context, value) }.isSuccess
            }
            busy = false
            if (ok) {
                configured = value.isNotBlank(); entered = ""; editing = false
                message = if (configured) "Key saved on this device." else "SubDL disabled. Key removed."
            } else message = "Could not save the key. Use a key of at most 200 bytes, without spaces."
        }
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(TelTvPanel).padding(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SubDL subtitles (optional)", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(if (configured) "German source active. Key stored encrypted on this device."
                else "Inactive. Add your SubDL API key to include German subtitles.", color = TelTvMuted)
            Text("Other sources and automatic subtitle download stay on. SubDL limits may apply.", color = TelTvMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { entered = ""; editing = true; message = null }, enabled = !busy) {
                    Text(if (configured) "Replace API key" else "Add API key")
                }
                if (configured) Button(onClick = { save("") }, enabled = !busy) { Text("Remove key") }
            }
            message?.let { Text(it, color = TelTvYellow) }
        }
    }
    if (editing) Dialog(onDismissRequest = { if (!busy) { entered = ""; editing = false } }) {
        // Prevent screenshots/screen recording while typing a persistent secret.
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Column(Modifier.width(480.dp).clip(RoundedCornerShape(16.dp)).background(TelTvPanelFocused).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("SubDL API key", style = MaterialTheme.typography.titleLarge, color = Color.White)
            OutlinedTextField(value = entered, onValueChange = { if (it.length <= 200) entered = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrect = false),
                label = { androidx.compose.material3.Text("API key") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White))
            Text("Enter it here, not in chat. A saved key is never shown again.", color = TelTvMuted)
            message?.let { Text(it, color = TelTvYellow) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = {
                    // Explicit user action only. Never watch the clipboard or persist its contents.
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    val pasted = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                    if (pasted.isNotBlank() && pasted.length <= 200 && pasted.none { it.isWhitespace() }) {
                        entered = pasted
                        message = null
                    } else message = "Clipboard has no valid API key."
                }, enabled = !busy) { Text("Paste") }
                Button(onClick = { entered = ""; editing = false }, enabled = !busy) { Text("Cancel") }
                Button(onClick = { save(entered) }, enabled = !busy && entered.isNotBlank()) { Text("Save key") }
            }
        }
    }
}
