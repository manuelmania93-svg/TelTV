package com.velastudio.teltv.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.settings.ClearCacheConfirmDialog

/**
 * Home layout, organized the way you actually use Telegram rather than a flat file browser:
 *   - "Continue Watching": last-played videos with their resume point, so re-opening the app
 *     goes straight back into whatever you were watching -- this is the row people expect first,
 *     ahead of even Pinned.
 *   - "Pinned" row: your pinned channels, first among the browse rows.
 *   - One row per chat folder ("Movies", "Anime", ...), auto-populated from your real Telegram
 *     folders instead of you having to recreate categories inside the app.
 *   - "Other sources" row: NAS / WebDAV / local, same idea as VelaTV, kept secondary.
 *
 * All rows use the same [PosterCard] shape as Browse and Search -- Home used to be text-only
 * cards with no artwork, which made "Continue Watching" (the row people rely on most) the one
 * place in the app you couldn't recognize a title at a glance from across the room.
 */
data class HomeRow(val title: String, val entries: List<HomeEntry>)
data class HomeEntry(val id: String, val name: String, val subtitle: String? = null, val thumbnailFileId: Int? = null)
data class ContinueWatchingEntry(
    val mediaId: String,
    val title: String,
    val progressFraction: Float,
    val thumbnailFileId: Int? = null
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    thumbnailLoader: ThumbnailLoader,
    continueWatching: List<ContinueWatchingEntry>,
    pinned: HomeRow,
    folderRows: List<HomeRow>,
    otherSources: HomeRow,
    onOpenEntry: (HomeEntry) -> Unit,
    onResumeWatching: (mediaId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * When non-null, a "Clear cache" button is placed in the header row alongside Search and
     * Settings.  Being a regular LazyColumn item it is fully part of the D-pad focus order --
     * no Box overlay, no focus-intercept risk from a fillMaxSize sibling.  Pressing it shows
     * [ClearCacheConfirmDialog] before anything is deleted.
     */
    onQuickClearCache: (() -> Unit)? = null
) {
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        // Without this, coming back to Home from Browse/Player/Search always puts focus back
        // on the top-left item (the search/settings buttons), instead of wherever the person
        // actually was -- a real papercut on a remote once there are more than a couple of rows.
        modifier = Modifier.fillMaxSize().focusRestorer().padding(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TelTV", style = androidx.tv.material3.MaterialTheme.typography.headlineLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    Button(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    // Quick-clear sits last in the header row so D-pad right from Settings
                    // reaches it naturally, and D-pad left from it returns to Settings.
                    if (onQuickClearCache != null) {
                        Button(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear cache")
                        }
                    }
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item { ContinueWatchingRow(continueWatching, thumbnailLoader, onResumeWatching) }
        }

        if (pinned.entries.isNotEmpty()) {
            item { HomeRowView(pinned, thumbnailLoader, onOpenEntry) }
        }
        items(folderRows) { row -> HomeRowView(row, thumbnailLoader, onOpenEntry) }
        if (otherSources.entries.isNotEmpty()) {
            item { HomeRowView(otherSources, thumbnailLoader, onOpenEntry) }
        }
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
        Text("Continue Watching", style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
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
        Text(row.title, style = androidx.tv.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        LazyRow(modifier = Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.entries, key = { it.id }) { entry ->
                PosterCard(
                    title = entry.name,
                    subtitle = entry.subtitle,
                    thumbnailFileId = entry.thumbnailFileId,
                    thumbnailLoader = thumbnailLoader,
                    onClick = { onOpenEntry(entry) }
                )
            }
        }
    }
}
