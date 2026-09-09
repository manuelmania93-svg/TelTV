import os
import re

# 1. Create TmdbMetadataProvider.kt
with open("app/src/main/java/com/velastudio/teltv/util/TmdbMetadataProvider.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TmdbMetadata(
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Float?,
    val year: String?,
    val overview: String?
)

object TmdbMetadataProvider {
    private val client = OkHttpClient.Builder().build()
    private val cache = ConcurrentHashMap<String, TmdbMetadata?>()
    private const val API_KEY = "e6931fc8ba77a2818c3a9f931e088b3f"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    private val EPISODE_REGEX = Regex("(?i)\\\\s*-\\\\s*s\\\\d{1,2}e\\\\d{1,2}.*|\\\\s+s\\\\d{1,2}e\\\\d{1,2}.*|\\\\s+season\\\\s+\\\\d+.*|\\\\s+episode\\\\s+\\\\d+.*")

    fun extractQuery(rawTitle: String): String {
        val clean = MediaTitleCleaner.clean(rawTitle)
        val stripped = clean.replace(EPISODE_REGEX, "").trim()
        return if (stripped.isNotBlank()) stripped else clean
    }

    suspend fun getMetadata(title: String): TmdbMetadata? {
        val query = extractQuery(title)
        if (query.isBlank()) return null
        if (cache.containsKey(query)) return cache[query]

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://api.themoviedb.org/3/search/multi?api_key=$API_KEY&query=$encoded"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    cache[query] = null
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) {
                    cache[query] = null
                    return@withContext null
                }
                val first = results.getJSONObject(0)
                val posterPath = first.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                val backdropPath = first.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                val rating = first.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f }
                val releaseDate = first.optString("release_date").ifBlank { first.optString("first_air_date") }
                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                val overview = first.optString("overview").takeIf { it.isNotBlank() }

                val meta = TmdbMetadata(
                    posterUrl = posterPath?.let { "$IMAGE_BASE$it" },
                    backdropUrl = backdropPath?.let { "$IMAGE_BASE$it" },
                    rating = rating,
                    year = year,
                    overview = overview
                )
                cache[query] = meta
                meta
            } catch (e: Exception) {
                cache[query] = null
                null
            }
        }
    }
}
''')
print("✅ 1/6 TmdbMetadataProvider.kt created!")

# 2. Update LocalDb.kt with pagingSourceDesc
with open("app/src/main/java/com/velastudio/teltv/data/local/LocalDb.kt", "r", encoding="utf-8") as f:
    db_code = f.read()

if "pagingSourceDesc" not in db_code:
    target = '@Query("SELECT * FROM video_index WHERE chatId = :chatId ORDER BY position ASC")\n    fun pagingSource(chatId: Long): PagingSource<Int, VideoIndexEntity>'
    replacement = target + '\n\n    @Query("SELECT * FROM video_index WHERE chatId = :chatId ORDER BY position DESC")\n    fun pagingSourceDesc(chatId: Long): PagingSource<Int, VideoIndexEntity>'
    db_code = db_code.replace(target, replacement)
    with open("app/src/main/java/com/velastudio/teltv/data/local/LocalDb.kt", "w", encoding="utf-8") as f:
        f.write(db_code)
print("✅ 2/6 LocalDb.kt updated with descending sort query!")

# 3. Update ChannelVideoRepository.kt
with open("app/src/main/java/com/velastudio/teltv/data/repository/ChannelVideoRepository.kt", "r", encoding="utf-8") as f:
    repo_code = f.read()

old_pager = '''    fun videoPager(chatId: Long): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(
                pageSize = deviceProfile.pageSize,
                prefetchDistance = deviceProfile.prefetchDistance,
                enablePlaceholders = true,
                initialLoadSize = deviceProfile.pageSize
            ),
            pagingSourceFactory = { videoIndexDao.pagingSource(chatId) }
        ).flow.map { pagingData -> pagingData.map { it.toMediaItem() } }'''

new_pager = '''    fun videoPager(chatId: Long, ascending: Boolean = true): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(
                pageSize = deviceProfile.pageSize,
                prefetchDistance = deviceProfile.prefetchDistance,
                enablePlaceholders = true,
                initialLoadSize = deviceProfile.pageSize
            ),
            pagingSourceFactory = {
                if (ascending) videoIndexDao.pagingSource(chatId)
                else videoIndexDao.pagingSourceDesc(chatId)
            }
        ).flow.map { pagingData -> pagingData.map { it.toMediaItem() } }'''

if old_pager in repo_code:
    repo_code = repo_code.replace(old_pager, new_pager)
    with open("app/src/main/java/com/velastudio/teltv/data/repository/ChannelVideoRepository.kt", "w", encoding="utf-8") as f:
        f.write(repo_code)
print("✅ 3/6 ChannelVideoRepository.kt updated with ascending/descending toggle!")

# 4. Update MediaCard.kt to support TMDb Posters & Long-Click
with open("app/src/main/java/com/velastudio/teltv/ui/common/MediaCard.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.util.MediaTitleCleaner
import com.velastudio.teltv.util.TmdbMetadata
import com.velastudio.teltv.util.TmdbMetadataProvider

val POSTER_CARD_WIDTH = 180.dp
val POSTER_CARD_HEIGHT = 240.dp
private val POSTER_THUMB_HEIGHT = 160.dp

@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    thumbnailFileId: Int?,
    thumbnailLoader: ThumbnailLoader?,
    resumeFraction: Float? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String = title
) {
    var localThumbPath by remember(thumbnailFileId) { mutableStateOf<String?>(null) }
    var tmdbMeta by remember(title) { mutableStateOf<TmdbMetadata?>(null) }

    DisposableEffect(thumbnailFileId, thumbnailLoader) {
        val job = if (thumbnailFileId != null && thumbnailLoader != null) {
            thumbnailLoader.request(thumbnailFileId) { path -> localThumbPath = path }
        } else null
        onDispose { job?.cancel() }
    }

    LaunchedEffect(title) {
        tmdbMeta = TmdbMetadataProvider.getMetadata(title)
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .height(POSTER_CARD_HEIGHT)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(Modifier.fillMaxSize()) {
            val imageSource = tmdbMeta?.posterUrl ?: localThumbPath
            if (imageSource != null) {
                AsyncImage(
                    model = imageSource,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(POSTER_THUMB_HEIGHT)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(POSTER_THUMB_HEIGHT)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            tmdbMeta?.rating?.let { rating ->
                Text(
                    text = "★ ${"%.1f".format(rating)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            if (resumeFraction != null && resumeFraction > 0.02f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(resumeFraction.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }

            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                val clean = remember(title) { MediaTitleCleaner.clean(title) }
                Text(clean, maxLines = 2, color = Color.White)
                val subText = subtitle ?: tmdbMeta?.year
                subText?.let {
                    Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
fun PosterCardPlaceholder() {
    Box(
        Modifier
            .width(POSTER_CARD_WIDTH)
            .height(POSTER_CARD_HEIGHT)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

fun parseThumbnailFileId(thumbUrl: String?): Int? =
    thumbUrl?.removePrefix("tdlib://thumb/")?.toIntOrNull()
''')
print("✅ 4/6 MediaCard.kt updated with TMDb and long-click!")

# 5. Update PlaybackControlsOverlay.kt & PlayerScreen.kt for Aspect Ratio
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlaybackControlsOverlay.kt", "r", encoding="utf-8") as f:
    controls_code = f.read()

if "AspectRatio" not in controls_code:
    controls_code = controls_code.replace(
        'import androidx.compose.material.icons.filled.Subtitles',
        'import androidx.compose.material.icons.filled.Subtitles\nimport androidx.compose.material.icons.filled.AspectRatio'
    )
    controls_code = controls_code.replace(
        'onOpenTracks: () -> Unit,\n    onOpenExternal: () -> Unit',
        'onOpenTracks: () -> Unit,\n    onCycleAspectRatio: () -> Unit,\n    onOpenExternal: () -> Unit'
    )
    old_buttons = '''                        ControlButton(
                            icon = Icons.Filled.Subtitles,
                            contentDescription = "Audio & Subtitles",
                            onClick = onOpenTracks
                        )'''
    new_buttons = '''                        ControlButton(
                            icon = Icons.Filled.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            onClick = onCycleAspectRatio
                        )
                        ControlButton(
                            icon = Icons.Filled.Subtitles,
                            contentDescription = "Audio & Subtitles",
                            onClick = onOpenTracks
                        )'''
    controls_code = controls_code.replace(old_buttons, new_buttons)
    with open("app/src/main/java/com/velastudio/teltv/ui/player/PlaybackControlsOverlay.kt", "w", encoding="utf-8") as f:
        f.write(controls_code)

# Update PlayerScreen.kt
with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "r", encoding="utf-8") as f:
    ps_code = f.read()

if "aspectRatioIndex" not in ps_code:
    ps_code = ps_code.replace(
        'var showAutoPlayOverlay by remember { mutableStateOf(false) }',
        '''var showAutoPlayOverlay by remember { mutableStateOf(false) }
    var aspectRatioIndex by remember { mutableStateOf(0) } // 0=FIT, 1=ZOOM, 2=FILL'''
    )
    
    ps_code = ps_code.replace(
        '''    fun togglePlayPause() {''',
        '''    fun cycleAspectRatio() {
        aspectRatioIndex = (aspectRatioIndex + 1) % 3
        seekingText = when (aspectRatioIndex) {
            1 -> "Aspect: Zoom to Fill (Crop)"
            2 -> "Aspect: Stretch"
            else -> "Aspect: Fit (Original)"
        }
        seekingIsForward = true
    }

    fun togglePlayPause() {'''
    )

    old_pv = '''        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                PlayerView(ctx).apply {
                    useController = false
                }
            },
            update = { view -> view.player = controller }
        )'''

    new_pv = '''        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: Context ->
                PlayerView(ctx).apply {
                    useController = false
                    subtitleView?.apply {
                        setFractionalTextSize(0.065f) // Large readable subtitles for TV
                        setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.parseColor("#80000000"),
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                                android.graphics.Color.BLACK,
                                android.graphics.Typeface.DEFAULT_BOLD
                            )
                        )
                    }
                }
            },
            update = { view ->
                view.player = controller
                view.resizeMode = when (aspectRatioIndex) {
                    1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            }
        )'''
    ps_code = ps_code.replace(old_pv, new_pv)

    ps_code = ps_code.replace(
        'onOpenTracks = { showTrackSelector = true },',
        'onOpenTracks = { showTrackSelector = true },\n            onCycleAspectRatio = ::cycleAspectRatio,'
    )
    with open("app/src/main/java/com/velastudio/teltv/ui/player/PlayerScreen.kt", "w", encoding="utf-8") as f:
        f.write(ps_code)
print("✅ 5/6 PlayerScreen.kt & PlaybackControlsOverlay.kt updated with Aspect Ratio & Subtitle Styling!")

# 6. Update BrowseScreen.kt & MainActivity.kt for Sort Toggle
with open("app/src/main/java/com/velastudio/teltv/ui/browse/BrowseScreen.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.ui.browse

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
''')

# Update MainActivity.kt for Browse sort toggle wiring
with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "r", encoding="utf-8") as f:
    main_c = f.read()

old_browse = '''                        BrowseScreen(
                            channelTitle = title,
                            pagingFlow = remember(chatId) { app.channelVideoRepository.videoPager(chatId) },'''

new_browse = '''                        var isAscending by remember { mutableStateOf(true) }
                        BrowseScreen(
                            channelTitle = title,
                            isAscending = isAscending,
                            onToggleSort = { isAscending = !isAscending },
                            pagingFlow = remember(chatId, isAscending) { app.channelVideoRepository.videoPager(chatId, isAscending) },'''

if old_browse in main_c:
    main_c = main_c.replace(old_browse, new_browse)
    with open("app/src/main/java/com/velastudio/teltv/MainActivity.kt", "w", encoding="utf-8") as f:
        f.write(main_c)
print("✅ 6/6 BrowseScreen.kt & MainActivity.kt updated with chronological sort toggle!")
