package com.velastudio.teltv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * The one poster-card shape used everywhere media is shown as a tappable tile: Home's
 * Continue Watching/Pinned/folder rows, Browse's grid, and Search's results grid. Before this,
 * each of those three screens had its own card -- different size, different aspect ratio, and
 * only Browse actually showed artwork -- which made Home and Search look unfinished by
 * comparison and meant nothing on Home was visually recognizable from across the room the way
 * a poster is. Same shape, same thumbnail wiring, same resume-progress treatment everywhere now.
 */
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
    contentDescription: String = title
) {
    var localThumbPath by remember(thumbnailFileId) { mutableStateOf<String?>(null) }

    DisposableEffect(thumbnailFileId, thumbnailLoader) {
        val job = if (thumbnailFileId != null && thumbnailLoader != null) {
            thumbnailLoader.request(thumbnailFileId) { path -> localThumbPath = path }
        } else null
        onDispose { job?.cancel() }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .width(POSTER_CARD_WIDTH)
            .height(POSTER_CARD_HEIGHT)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(Modifier.fillMaxSize()) {
            if (localThumbPath != null) {
                AsyncImage(
                    model = localThumbPath,
                    contentDescription = null, // described at the Card level above
                    modifier = Modifier.fillMaxWidth().height(POSTER_THUMB_HEIGHT)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth().height(POSTER_THUMB_HEIGHT)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
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
                Text(title, maxLines = 2, color = Color.White)
                subtitle?.let {
                    Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Color.White)
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

/** Shared "tdlib://thumb/<fileId>" parsing so every screen extracts the file id the same way. */
fun parseThumbnailFileId(thumbnailUrl: String?): Int? =
    thumbnailUrl?.removePrefix("tdlib://thumb/")?.toIntOrNull()
