package com.velastudio.teltv.ui.common

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
