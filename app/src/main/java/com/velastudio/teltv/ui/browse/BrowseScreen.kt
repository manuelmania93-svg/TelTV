package com.velastudio.teltv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.OutlinedButton
import androidx.compose.ui.window.Dialog
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.common.PosterCardPlaceholder
import com.velastudio.teltv.ui.common.parseThumbnailFileId
import com.velastudio.teltv.util.DeviceCapabilities
import com.velastudio.teltv.util.isNearEnd
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(
    channelTitle: String,
    pinnedVideo: MediaItem?,
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    pagingFlow: Flow<androidx.paging.PagingData<MediaItem>>,
    thumbnailLoader: ThumbnailLoader,
    deviceProfile: DeviceCapabilities.Profile,
    resumeFractionFor: (mediaId: String) -> Float?,
    onLoadMore: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onPinVideo: (MediaItem) -> Unit,
    onUnpinVideo: (MediaItem) -> Unit,
    onAddToPlaylist: (MediaItem) -> Unit,
    onAddToWatchLater: (MediaItem) -> Unit,
    onCreateMarathon: () -> Unit,
    activeMarathonName: String? = null,
    onResumeMarathon: () -> Unit = {},
    onDeleteMarathon: () -> Unit = {},
    onStartMarathonFrom: (MediaItem) -> Unit = {}
) {
    val items = pagingFlow.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    var actionItem by remember { mutableStateOf<MediaItem?>(null) }
    var showMarathonDialog by remember { mutableStateOf(false) }

    // Trigger onLoadMore only when the user actually scrolls near the end, not on initial item mount
    LaunchedEffect(gridState) {
        snapshotFlow { 
            val layout = gridState.layoutInfo
            val total = layout.totalItemsCount
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 4
        }.collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channelTitle, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCreateMarathon) {
                    Text("Series Marathon")
                }
                Button(onClick = onToggleSort) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                    Spacer(Modifier.width(8.dp))
                    Text(if (isAscending) "Newest First" else "Oldest First (S01E01)")
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        pinnedVideo?.let { pinned ->
            Column(Modifier.fillMaxWidth()) {
                Text("Pinned Video", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PosterCard(
                    title = pinned.title,
                    subtitle = "Pinned in this channel",
                    thumbnailFileId = parseThumbnailFileId(pinned.thumbnailUrl),
                    thumbnailLoader = thumbnailLoader,
                    onClick = { onOpenItem(pinned) },
                    onLongClick = { actionItem = pinned }
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        val refreshState = items.loadState.refresh

        when {
            items.itemCount == 0 && refreshState is androidx.paging.LoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
                return@Column
            }
            items.itemCount == 0 && refreshState is androidx.paging.LoadState.Error -> {
                ErrorState(
                    message = "Couldn't load this channel.",
                    onRetry = { items.retry() }
                )
                return@Column
            }
            items.itemCount == 0 && refreshState is androidx.paging.LoadState.NotLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing here yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { it.id }
            ) { index ->
                val media = items[index]
                if (media != null) {
                    PosterCard(
                        title = media.title,
                        subtitle = media.subtitle,
                        thumbnailFileId = parseThumbnailFileId(media.thumbnailUrl),
                        thumbnailLoader = thumbnailLoader,
                        resumeFraction = resumeFractionFor(media.id),
                        onClick = { onOpenItem(media) },
                        onLongClick = { actionItem = media }
                    )
                } else {
                    PosterCardPlaceholder()
                }
            }

            if (items.loadState.append is androidx.paging.LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            }
        }
    }

    actionItem?.let { media ->
        Dialog(onDismissRequest = { actionItem = null }) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .width(360.dp)
                    .background(androidx.compose.ui.graphics.Color(0xFF202735), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Text("Add video", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(media.title, maxLines = 2)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { onStartMarathonFrom(media); actionItem = null }) { Text("▶️ Start Marathon from Here") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onAddToPlaylist(media); actionItem = null }) { Text("Add to Playlist") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onAddToWatchLater(media); actionItem = null }) { Text("Watch Later") }
                Spacer(Modifier.height(8.dp))
                if (pinnedVideo?.id == media.id) {
                    OutlinedButton(onClick = { onUnpinVideo(media); actionItem = null }) { Text("Unpin from Channel") }
                } else {
                    OutlinedButton(onClick = { onPinVideo(media); actionItem = null }) { Text("Pin in Channel") }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }

        // Marathon Options Dialog
        if (showMarathonDialog) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.tv.material3.Card(
                    onClick = {},
                    colors = androidx.tv.material3.CardDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF202020)
                    ),
                    shape = androidx.tv.material3.CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                    modifier = Modifier.width(440.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🎬 Marathon: $channelTitle",
                            style = MaterialTheme.typography.titleLarge,
                            color = androidx.compose.ui.graphics.Color.White
                        )
                        Spacer(Modifier.height(20.dp))

                        if (activeMarathonName != null) {
                            Button(
                                onClick = {
                                    showMarathonDialog = false
                                    onResumeMarathon()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("▶️ Resume Marathon")
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    showMarathonDialog = false
                                    onCreateMarathon()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🔄 Restart from Beginning (Ep 1)")
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    showMarathonDialog = false
                                    onDeleteMarathon()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🗑️ Clear / Remove Marathon")
                            }
                        } else {
                            Button(
                                onClick = {
                                    showMarathonDialog = false
                                    onCreateMarathon()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("▶️ Start Marathon from Beginning")
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showMarathonDialog = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}
