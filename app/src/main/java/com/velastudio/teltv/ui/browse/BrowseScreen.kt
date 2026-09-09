package com.velastudio.teltv.ui.browse
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material.CircularProgressIndicator
import androidx.tv.material3.Button
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

/**
 * Video grid for a single channel/folder, backed by [androidx.paging.PagingData] so opening a
 * channel with 3,000 videos costs the same memory as opening one with 30: only the visible
 * window (plus a small prefetch margin, tuned per-device in [DeviceCapabilities]) is ever
 * inflated into Composables or has its thumbnail downloaded.
 *
 * Three things specifically target the "endless loading" / "silently stuck" complaints:
 *  - Placeholder cells render immediately (Room's PagingSource reports the true item count even
 *    for pages it hasn't fetched yet), so scrolling never hits a dead stop waiting on network --
 *    it shows a placeholder card that fills in the moment its page arrives.
 *  - [onLoadMore] fires once the grid is [DeviceCapabilities.Profile.prefetchDistance] items from
 *    the end, well before the person actually reaches it, so the next page from Telegram usually
 *    arrives before it's needed rather than after.
 *  - A failed initial load or a failed "load more" (dead network, TDLib timeout, etc.) shows an
 *    explicit error state with a Retry button instead of leaving a spinner running forever or an
 *    append silently never happening again.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(
    channelTitle: String,
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
        Text(channelTitle, style = MaterialTheme.typography.headlineSmall)
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
            state = gridState,
            // Scrolling into a video and pressing Back should land you back on the same
            // thumbnail, not the top-left of the grid -- especially important here since a
            // channel can have thousands of items and a full-count grid without this would
            // make "back out and try the next one over" tediously slow on a remote.
            modifier = Modifier.focusRestorer(),
            columns = GridCells.Fixed(deviceProfile.gridColumns),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
                val media = items[index]
                if (media == null) {
                    PosterCardPlaceholder()
                } else {
                    PosterCard(
                        title = media.title,
                        subtitle = media.subtitle,
                        thumbnailFileId = parseThumbnailFileId(media.thumbnailUrl),
                        thumbnailLoader = thumbnailLoader,
                        resumeFraction = resumeFractionFor(media.id),
                        onClick = { onOpenItem(media) }
                    )
                }
            }

            val appendState = items.loadState.append
            if (appendState is androidx.paging.LoadState.Loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                }
            }
            if (appendState is androidx.paging.LoadState.Error) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Couldn't load more.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { items.retry() }) { Text("Retry") }
                        }
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
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
