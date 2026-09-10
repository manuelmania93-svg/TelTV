package com.velastudio.teltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.settings.ClearCacheConfirmDialog
import kotlinx.coroutines.launch

data class HomeRow(val title: String, val entries: List<HomeEntry>)
data class HomeEntry(val id: String, val name: String, val subtitle: String? = null, val thumbnailFileId: Int? = null)
data class ContinueWatchingEntry(
    val mediaId: String,
    val title: String,
    val progressFraction: Float,
    val thumbnailFileId: Int? = null
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    thumbnailLoader: ThumbnailLoader,
    continueWatching: List<ContinueWatchingEntry>,
    pinned: HomeRow,
    allChannels: HomeRow,
    folderRows: List<HomeRow>,
    otherSources: HomeRow,
    isLoading: Boolean = false,
    onOpenEntry: (HomeEntry) -> Unit,
    onResumeWatching: (mediaId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuickClearCache: (() -> Unit)? = null
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.velastudio.teltv.util.UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler { (context as? android.app.Activity)?.finishAffinity() }

    LaunchedEffect(Unit) {
        updateInfo = com.velastudio.teltv.util.AppUpdater.checkForUpdate()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer()
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        // App Header Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "TelTV",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF29B6F6)
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E2638))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Telegram TV",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onOpenSearch,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFF29B6F6)
                        )
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Search", color = Color.White)
                    }

                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFF29B6F6)
                        )
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings", color = Color.White)
                    }

                    Button(
                        onClick = { (context as? android.app.Activity)?.finishAffinity() },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFFE53935)
                        )
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Exit App", tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Exit", color = Color.White)
                    }

                    if (onQuickClearCache != null) {
                        Button(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFF1E2638),
                                focusedContainerColor = Color(0xFFEF5350)
                            )
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear cache", tint = Color.White)
                        }
                    }
                }
            }
        }

        // Continue Watching Row
        if (continueWatching.isNotEmpty()) {
            item {
                ContinueWatchingRow(continueWatching, thumbnailLoader, onResumeWatching)
            }
        }

        // Pinned Channels Row
        if (pinned.entries.isNotEmpty()) {
            item {
                HomeRowView(pinned, thumbnailLoader, onOpenEntry)
            }
        }

        // All Channels & Groups Row
        if (allChannels.entries.isNotEmpty()) {
            item {
                HomeRowView(allChannels, thumbnailLoader, onOpenEntry)
            }
        }

        // Chat Folders Rows
        items(folderRows) { row ->
            if (row.entries.isNotEmpty()) {
                HomeRowView(row, thumbnailLoader, onOpenEntry)
            }
        }

        // Other Sources Row
        if (otherSources.entries.isNotEmpty()) {
            item {
                HomeRowView(otherSources, thumbnailLoader, onOpenEntry)
            }
        }

        // Informative Empty / Loading State
        if (continueWatching.isEmpty() && pinned.entries.isEmpty() && allChannels.entries.isEmpty() && folderRows.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF161D27))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            tint = Color(0xFF29B6F6),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = if (isLoading) "Loading your Telegram channels…" else "No Channels Found",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (isLoading) "Please wait a moment while TDLib syncs your chats."
                                   else "Join Telegram channels or groups with videos on your phone, then return here!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }
        }
    }

    if (updateInfo != null) {
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            onDownload = {
                coroutineScope.launch {
                    isDownloading = true
                    com.velastudio.teltv.util.AppUpdater.downloadAndInstall(context, updateInfo!!.downloadUrl) { p ->
                        downloadProgress = p
                    }
                    isDownloading = false
                }
            },
            onDismiss = { updateInfo = null }
        )
    }

    if (showClearConfirm && onQuickClearCache != null) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = onQuickClearCache
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContinueWatchingRow(
    entries: List<ContinueWatchingEntry>,
    thumbnailLoader: ThumbnailLoader,
    onResumeWatching: (mediaId: String) -> Unit
) {
    Column {
        Text(
            "Continue Watching",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(modifier = Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(entries, key = { it.mediaId }) { entry ->
                PosterCard(
                    title = entry.title,
                    thumbnailFileId = entry.thumbnailFileId,
                    thumbnailLoader = thumbnailLoader,
                    resumeFraction = entry.progressFraction,
                    onClick = { onResumeWatching(entry.mediaId) }
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeRowView(row: HomeRow, thumbnailLoader: ThumbnailLoader, onOpenEntry: (HomeEntry) -> Unit) {
    Column {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        LazyRow(modifier = Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.entries, key = { it.id }) { entry ->
                PosterCard(
                    title = entry.name,
                    subtitle = entry.subtitle,
                    thumbnailFileId = entry.thumbnailFileId,
                    thumbnailLoader = thumbnailLoader,
                    enableTmdb = false,
                    onClick = { onOpenEntry(entry) }
                )
            }
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    updateInfo: com.velastudio.teltv.util.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(onClick = {}) {
            Column(Modifier.padding(24.dp)) {
                Text("Update Available (v${updateInfo.versionName})", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(updateInfo.changelog)
                Spacer(Modifier.height(16.dp))
                if (isDownloading) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onDismiss) { Text("Later") }
                        Button(onClick = onDownload) { Text("Update Now") }
                    }
                }
            }
        }
    }
}
