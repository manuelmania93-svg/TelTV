package com.velastudio.teltv.ui.search
import androidx.compose.material3.OutlinedTextField

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.OutlinedTextField
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.common.parseThumbnailFileId
import com.velastudio.teltv.util.DeviceCapabilities
import com.velastudio.teltv.util.rememberDebounced

/**
 * Search across the whole library (every pinned channel + folder channel), not just the one
 * you're currently browsing. Two layers, both already fired off by the time the person finishes
 * typing:
 *
 *  1. [onLocalQueryChanged] -- an instant, on-device title match against whatever's already in
 *     the Room cache (`VideoIndexDao.searchLocal`). Shows results in well under a frame, covers
 *     anything you've scrolled past before.
 *  2. [onRemoteQueryChanged] -- a debounced (device-tuned, see [DeviceCapabilities.searchDebounceMs])
 *     call out to Telegram for videos that were never paged into the local cache. Debouncing
 *     matters most here: a naive "search on every keystroke" is the classic way to make a TV
 *     search screen feel sluggish, since every keystroke would otherwise fire a fresh set of
 *     TDLib calls across every channel.
 *
 * Recent searches ([recentQueries]) are shown as tappable chips so returning to a previous search
 * needs zero on-screen typing -- typing on a remote control is the slowest possible input method,
 * so the fewer characters someone has to enter, the better.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchScreen(
    deviceProfile: DeviceCapabilities.Profile,
    thumbnailLoader: ThumbnailLoader,
    recentQueries: List<String>,
    localResults: List<MediaItem>,
    remoteResults: List<MediaItem>,
    isSearchingRemote: Boolean,
    onLocalQueryChanged: (String) -> Unit,
    onRemoteQueryChanged: (String) -> Unit,
    onRecentQueryPicked: (String) -> Unit,
    onOpenItem: (MediaItem) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val fireRemoteSearch = rememberDebounced(deviceProfile.searchDebounceMs, query) { q ->
        onRemoteQueryChanged(q)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Search", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { newValue ->
                query = newValue
                onLocalQueryChanged(newValue) // instant, on-device -- no debounce needed
                if (newValue.length >= 2) fireRemoteSearch()
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onRemoteQueryChanged(query) }),
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Movie, show, or channel name...") }
        )

        if (query.isBlank() && recentQueries.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("Recent searches", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentQueries.take(6).forEach { recent ->
                    Button(onClick = {
                        query = recent
                        onLocalQueryChanged(recent)
                        onRemoteQueryChanged(recent)
                    }) { Text(recent) }
                }
            }
        }

        val combined = remember(localResults, remoteResults) {
            (localResults + remoteResults).distinctBy { it.id }
        }

        Spacer(Modifier.height(20.dp))
        if (query.isNotBlank()) {
            if (isSearchingRemote && combined.isEmpty()) {
                Text("Searching your channels...", style = MaterialTheme.typography.bodyMedium)
            } else if (combined.isEmpty()) {
                Text("No matches yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(deviceProfile.gridColumns),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(combined, key = { it.id }) { media ->
                        PosterCard(
                            title = media.title,
                            subtitle = media.subtitle,
                            thumbnailFileId = parseThumbnailFileId(media.thumbnailUrl),
                            thumbnailLoader = thumbnailLoader,
                            onClick = { onOpenItem(media) }
                        )
                    }
                }
            }
        }
    }
}
