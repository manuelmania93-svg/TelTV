package com.velastudio.teltv.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.util.MediaTitleCleaner
import com.velastudio.teltv.util.TmdbMetadata
import com.velastudio.teltv.util.TmdbMetadataProvider
import com.velastudio.teltv.ui.theme.TelTvMuted
import com.velastudio.teltv.ui.theme.TelTvPanel
import com.velastudio.teltv.ui.theme.TelTvPanelFocused
import com.velastudio.teltv.ui.theme.TelTvYellow

val POSTER_CARD_WIDTH = 190.dp
val POSTER_CARD_HEIGHT = 260.dp
private val POSTER_THUMB_HEIGHT = 175.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    subtitle: String? = null,
    thumbnailFileId: Int?,
    thumbnailLoader: ThumbnailLoader?,
    resumeFraction: Float? = null,
    enableTmdb: Boolean = true,
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

    LaunchedEffect(title, enableTmdb) {
        if (enableTmdb) tmdbMeta = TmdbMetadataProvider.getMetadata(title)
    }

    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(3.dp, TelTvYellow),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        colors = CardDefaults.colors(
            containerColor = TelTvPanel,
            focusedContainerColor = TelTvPanelFocused
        ),
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .height(POSTER_CARD_HEIGHT)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(Modifier.fillMaxSize()) {
            val imageSource = localThumbPath ?: tmdbMeta?.posterUrl
            if (imageSource != null) {
                AsyncImage(
                    model = imageSource,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(POSTER_THUMB_HEIGHT)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(POSTER_THUMB_HEIGHT)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1E2638), Color(0xFF111622))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tv,
                        contentDescription = null,
                            tint = TelTvYellow.copy(alpha = 0.75f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            // Rating badge if available
            tmdbMeta?.rating?.let { rating ->
                Text(
                    text = "★ ${"%.1f".format(rating)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Watched indicator badge
            if (resumeFraction != null && resumeFraction >= 0.90f) {
                Text(
                    text = "✓ Watched",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color(0xFF1B5E20).copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // High-contrast resume progress bar
            if (resumeFraction != null && resumeFraction in 0.02f..0.89f) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 85.dp)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(resumeFraction.coerceIn(0f, 1f))
                            .background(Color(0xFFE50914))
                    )
                }
            }

            // Title and metadata container
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(85.dp)
                    .background(TelTvPanel)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                val clean = remember(title) { MediaTitleCleaner.clean(title) }
                Text(
                    text = clean,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                val subText = subtitle ?: tmdbMeta?.year
                if (subText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subText,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = TelTvMuted
                    )
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
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161D27))
            .focusable()
    )
}

fun parseThumbnailFileId(thumbUrl: String?): Int? =
    thumbUrl?.removePrefix("tdlib://thumb/")?.toIntOrNull()
