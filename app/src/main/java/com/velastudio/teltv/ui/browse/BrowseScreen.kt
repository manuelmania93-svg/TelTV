package com.velastudio.teltv.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
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
    fastModeEnabled: Boolean = false,
    showPinnedVideos: Boolean = true,
    onToggleShowPinned: () -> Unit = {},
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
    onStartMarathonFrom: (MediaItem) -> Unit = {},
    onPlayFromBeginning: (MediaItem) -> Unit = {},
    onToggleWatched: (MediaItem) -> Unit = {}
) {
    val items = pagingFlow.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()
    var actionItem by remember { mutableStateOf<MediaItem?>(null) }
    var showMarathonDialog by remember { mutableStateOf(false) }
    var marathonModeEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(gridState, items.itemCount) {
        snapshotFlow { gridState.isNearEnd(deviceProfile.prefetchDistance) }
            .collect { nearEnd -> if (nearEnd) onLoadMore() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channelTitle, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { marathonModeEnabled = !marathonModeEnabled },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (marathonModeEnabled) androidx.compose.ui.graphics.Color(0xFF29B6F6) else androidx.compose.ui.graphics.Color(0xFF202735)
                    )
                ) {
                    Icon(Icons.Filled.PlaylistPlay, contentDescription = "Marathon Mode")
                    Spacer(Modifier.width(8.dp))
                    Text(if (marathonModeEnabled) "Marathon: Click to Start" else "Marathon Mode: OFF")
                }
                Button(onClick = onToggleSort) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                    Spacer(Modifier.width(8.dp))
                    Text(if (isAscending) "Newest First" else "Oldest First (Ep 1)")
                }
            }
        }
        Spacer(Modifier.height(16.dp))



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
                    message = "Could not load this channel.",
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
                        thumbnailLoader = if (fastModeEnabled) null else thumbnailLoader,
                    enableTmdb = !fastModeEnabled,
                        resumeFraction = resumeFractionFor(media.id),
                        onClick = {
                            if (marathonModeEnabled) {
                                onStartMarathonFrom(media)
                            } else {
                                onOpenItem(media)
                            }
                        },
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

    // Item Action Dialog
    actionItem?.let { media ->
        val isWatched = (resumeFractionFor(media.id) ?: 0f) >= 0.90f
        Dialog(onDismissRequest = { actionItem = null }) {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .width(420.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Color(0xFF1E2638))
                    .padding(24.dp)
            ) {
                Text(
                    "Episode Options",
                    style = MaterialTheme.typography.titleLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    media.title,
                    maxLines = 2,
                    color = androidx.compose.ui.graphics.Color(0xFFB0BEC5),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = {
                        actionItem = null
                        onPlayFromBeginning(media)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF2B354B),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF29B6F6)
                    )
                ) {
                    Text("↺  Play from Beginning", color = androidx.compose.ui.graphics.Color.White)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        actionItem = null
                        onToggleWatched(media)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF2B354B),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF29B6F6)
                    )
                ) {
                    Text(
                        if (isWatched) "Mark as Unwatched" else "✓  Mark as Watched",
                        color = androidx.compose.ui.graphics.Color.White
                    )
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        actionItem = null
                        onStartMarathonFrom(media)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF2B354B),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF29B6F6)
                    )
                ) {
                    Text("▶️  Start Marathon from Here", color = androidx.compose.ui.graphics.Color.White)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        actionItem = null
                        onAddToPlaylist(media)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF2B354B),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF29B6F6)
                    )
                ) {
                    Text("+  Add to Playlist", color = androidx.compose.ui.graphics.Color.White)
                }

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = {
                        actionItem = null
                        onAddToWatchLater(media)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF2B354B),
                        focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF29B6F6)
                    )
                ) {
                    Text("⏱️  Add to Watch Later", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    } else {
                    OutlinedButton(onClick = { onPinVideo(media); actionItem = null }) { Text("Pin in Channel") }
                }
            }
        }
    }

    // Marathon Options Dialog (Safely INSIDE BrowseScreen)
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

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
