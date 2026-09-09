package com.velastudio.teltv.ui.browse

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
    isAscending: Boolean,
    onToggleSort: () -> Unit,
    pagingFlow: Flow<androidx.paging.PagingData<MediaItem>>,
    thumbnailLoader: ThumbnailLoader,
    deviceProfile: DeviceCapabilities.Profile,
    resumeFractionFor: (mediaId: String) -> Float?,
    onLoadMore: () -> Unit,
    onOpenItem: (MediaItem) -> Unit
) {
    val items = pagingFlow.collectAsLazyPagingItems()
    val gridState = rememberLazyGridState()

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
            Button(onClick = onToggleSort) {
                Icon(Icons.Filled.SwapVert, contentDescription = "Sort")
                Spacer(Modifier.width(8.dp))
                Text(if (isAscending) "Newest First" else "Oldest First (S01E01)")
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
                        onClick = { onOpenItem(media) }
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
