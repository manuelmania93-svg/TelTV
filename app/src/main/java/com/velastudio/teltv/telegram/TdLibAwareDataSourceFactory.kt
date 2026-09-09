package com.velastudio.teltv.telegram

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * The one [DataSource.Factory] the player is built with, for every source type.
 *
 * Before this, [com.velastudio.teltv.ui.player.PlayerScreen] special-cased Telegram items by
 * hand-building a `ProgressiveMediaSource` with a raw `DataSource.Factory { TdLibDataSource(...) }`
 * only for those, and relying on ExoPlayer's own default source for direct/NAS/WebDAV URIs. That
 * meant the player itself had to know which kind of item it was showing.
 *
 * Now every item is just `MediaItem.fromUri(uri)` -- `tdlib://file/<fileId>` for Telegram,
 * a normal `http(s)/smb/file` URI for everything else -- and this factory picks the right
 * backing [DataSource] per open() call based on the URI scheme. That's what lets a single
 * long-lived ExoPlayer (owned by [com.velastudio.teltv.player.PlaybackService], not a
 * Composable) play items from either source without extra wiring at the call site, and it's
 * also what makes a plain `MediaController.setMediaItem(...)` call (from the UI, across process
 * boundaries) sufficient -- no custom object needs to cross that boundary.
 */
@UnstableApi
class TdLibAwareDataSourceFactory(
    private val telegram: TelegramClient,
    context: Context
) : DataSource.Factory {

    private val defaultFactory = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = SchemeSwitchingDataSource(telegram, defaultFactory.createDataSource())

    private class SchemeSwitchingDataSource(
        private val telegram: TelegramClient,
        private val defaultDataSource: DataSource
    ) : DataSource {

        private var active: DataSource = defaultDataSource
        private var pendingTransferListener: TransferListener? = null

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            active = if (uri.scheme == TDLIB_SCHEME) {
                val fileId = uri.lastPathSegment?.toIntOrNull()
                    ?: throw IOException("Malformed tdlib URI, expected tdlib://file/<id>: $uri")
                TdLibDataSource(RawTdClient(telegram), fileId)
            } else {
                defaultDataSource
            }
            // TdLibDataSource is recreated per open() (fileId is only known at this point), so
            // any listener registered on the factory needs to be re-attached to it here too --
            // otherwise bandwidth/transfer stats would silently stop working for Telegram items
            // specifically, which would be a confusing, source-dependent gap to debug later.
            pendingTransferListener?.let { active.addTransferListener(it) }
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active.read(buffer, offset, length)

        override fun getUri(): Uri? = active.uri

        override fun addTransferListener(transferListener: TransferListener) {
            pendingTransferListener = transferListener
            defaultDataSource.addTransferListener(transferListener)
        }

        override fun close() = active.close()
    }

    companion object {
        const val TDLIB_SCHEME = "tdlib"

        fun uriForFile(fileId: Int): Uri = Uri.parse("$TDLIB_SCHEME://file/$fileId")
    }
}
