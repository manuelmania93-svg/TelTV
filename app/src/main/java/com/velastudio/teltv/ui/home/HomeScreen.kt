package com.velastudio.teltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.*
import com.velastudio.teltv.R
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.settings.ClearCacheConfirmDialog
import com.velastudio.teltv.ui.theme.TelTvBlack
import com.velastudio.teltv.ui.theme.TelTvMuted
import com.velastudio.teltv.ui.theme.TelTvPanel
import com.velastudio.teltv.ui.theme.TelTvPanelFocused
import com.velastudio.teltv.ui.theme.TelTvWhite
import com.velastudio.teltv.ui.theme.TelTvYellow
import kotlinx.coroutines.launch

data class HomeRow(val title: String, val entries: List<HomeEntry>)
data class HomeEntry(val id: String, val name: String, val subtitle: String? = null, val thumbnailFileId: Int? = null)
data class ContinueWatchingEntry(
    val mediaId: String,
    val title: String,
    val progressFraction: Float,
    val thumbnailFileId: Int? = null
)

enum class HomeFilterMode { ALL, PINNED_ONLY, FOLDERS_ONLY }

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    thumbnailLoader: ThumbnailLoader,
    continueWatching: List<ContinueWatchingEntry>,
    recentlyWatched: List<ContinueWatchingEntry>,
    watchLater: List<ContinueWatchingEntry>,
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
    var dismissedVersion by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var filterMode by remember { mutableStateOf(HomeFilterMode.ALL) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler {
        (context as? android.app.Activity)?.finishAffinity()
    }

    LaunchedEffect(Unit) {
        val info = com.velastudio.teltv.util.AppUpdater.checkForUpdate()
        if (info != null && info.versionName != dismissedVersion) {
            updateInfo = info
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .focusRestorer()
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // App Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = "TelTV logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(84.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "TelTV",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = TelTvYellow
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Copyright 2026 Manuel Durnig",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelTvMuted
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TelTvPanel)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Telegram TV",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelTvMuted
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onOpenSearch,
                        colors = ButtonDefaults.colors(
                            containerColor = TelTvPanel,
                            focusedContainerColor = TelTvYellow
                        )
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = TelTvWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Search", color = TelTvWhite)
                    }

                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.colors(
                            containerColor = TelTvPanel,
                            focusedContainerColor = TelTvYellow
                        )
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TelTvWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings", color = TelTvWhite)
                    }

                    Button(
                        onClick = { (context as? android.app.Activity)?.finishAffinity() },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFFE53935)
                        )
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Exit App", tint = Color.White)
                        Spacer(Modifier.width(8.dp))
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

        // View Filter Tabs: All / Pinned Only / Folders Only
        item {
            Row(
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterTabButton(
                    label = "All",
                    icon = Icons.Filled.ViewHeadline,
                    selected = filterMode == HomeFilterMode.ALL,
                    onClick = { filterMode = HomeFilterMode.ALL }
                )
                FilterTabButton(
                    label = "Pinned Only",
                    icon = Icons.Filled.PushPin,
                    selected = filterMode == HomeFilterMode.PINNED_ONLY,
                    onClick = { filterMode = HomeFilterMode.PINNED_ONLY }
                )
                FilterTabButton(
                    label = "Folders Only",
                    icon = Icons.Filled.Folder,
                    selected = filterMode == HomeFilterMode.FOLDERS_ONLY,
                    onClick = { filterMode = HomeFilterMode.FOLDERS_ONLY }
                )
            }
        }

        // Continue Watching Row (hidden if in FOLDERS_ONLY)
        if (filterMode != HomeFilterMode.FOLDERS_ONLY && continueWatching.isNotEmpty()) {
            item {
                ContinueWatchingRow(continueWatching, thumbnailLoader, onResumeWatching)
            }
        }

        if (filterMode != HomeFilterMode.FOLDERS_ONLY && recentlyWatched.isNotEmpty()) {
            item { ContinueWatchingRow(recentlyWatched, thumbnailLoader, onResumeWatching, title = "Recently Watched") }
        }

        if (filterMode != HomeFilterMode.FOLDERS_ONLY && watchLater.isNotEmpty()) {
            item { ContinueWatchingRow(watchLater, thumbnailLoader, onResumeWatching, title = "Watch Later") }
        }

        // Pinned Channels Row (hidden if in FOLDERS_ONLY)
        if (filterMode != HomeFilterMode.FOLDERS_ONLY && pinned.entries.isNotEmpty()) {
            item {
                HomeRowView(pinned, thumbnailLoader, onOpenEntry)
            }
        }

        // Chat Folders Shelves (hidden if in PINNED_ONLY)
        if (filterMode != HomeFilterMode.PINNED_ONLY) {
            items(folderRows, key = { it.title }) { row ->
                if (row.entries.isNotEmpty()) {
                    HomeRowView(row, thumbnailLoader, onOpenEntry)
                }
            }
        }

        // All Channels & Groups (only shown in ALL mode)
        if (filterMode == HomeFilterMode.ALL && allChannels.entries.isNotEmpty()) {
            item {
                HomeRowView(allChannels, thumbnailLoader, onOpenEntry)
            }
        }

        // Other Sources Row
        if (filterMode == HomeFilterMode.ALL && otherSources.entries.isNotEmpty()) {
            item {
                HomeRowView(otherSources, thumbnailLoader, onOpenEntry)
            }
        }

        // Feedback / Empty state
        val hasContent = (filterMode == HomeFilterMode.ALL && (pinned.entries.isNotEmpty() || allChannels.entries.isNotEmpty() || folderRows.isNotEmpty())) ||
                         (filterMode == HomeFilterMode.PINNED_ONLY && pinned.entries.isNotEmpty()) ||
                         (filterMode == HomeFilterMode.FOLDERS_ONLY && folderRows.isNotEmpty())

        if (!hasContent && !isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
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
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = when (filterMode) {
                                HomeFilterMode.PINNED_ONLY -> "No Pinned Channels Found"
                                HomeFilterMode.FOLDERS_ONLY -> "No Chat Folders Found"
                                else -> "No Channels Found"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (filterMode) {
                                HomeFilterMode.PINNED_ONLY -> "Pin your favorite movie or media channels in Telegram to see them here!"
                                HomeFilterMode.FOLDERS_ONLY -> "Create folders (e.g. Movies, Series) in Telegram Settings > Folders."
                                else -> "Join channels with videos to start streaming!"
                            },
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
            onDismiss = {
                dismissedVersion = updateInfo?.versionName
                updateInfo = null
            }
        )
    }

    if (showClearConfirm && onQuickClearCache != null) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = onQuickClearCache
        )
    }
}

@Composable
private fun FilterTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF004D73) else Color(0xFF1E2638),
            focusedContainerColor = Color(0xFF29B6F6)
        )
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContinueWatchingRow(
    entries: List<ContinueWatchingEntry>,
    thumbnailLoader: ThumbnailLoader,
    onResumeWatching: (mediaId: String) -> Unit,
    title: String = "Continue Watching"
) {
    Column {
        Text(
            title,
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
                    enableTmdb = true,
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
        androidx.activity.compose.BackHandler(onBack = onDismiss)
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
