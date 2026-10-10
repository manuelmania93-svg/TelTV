package com.velastudio.teltv.sync

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Lets [android.media.MediaExtractor] read through the same Media3 [DataSource] the player uses,
 * so Telegram (`tdlib://`), WebDAV, SMB and plain http items all work without a separate path.
 * It reuses the open stream for sequential reads and reopens at a new position for seeks.
 */
@UnstableApi
@RequiresApi(23)
class Media3MediaDataSource(
    private val factory: DataSource.Factory,
    private val uri: Uri
) : MediaDataSource() {
    private var source: DataSource? = null
    private var streamPos = -1L
    private var size = -1L

    @Synchronized
    override fun getSize(): Long {
        if (size >= 0) return size
        val probe = factory.createDataSource()
        try {
            val len = probe.open(DataSpec(uri))
            size = if (len == C.LENGTH_UNSET.toLong()) -1L else len
        } catch (e: IOException) {
            size = -1L
        } finally {
            runCatching { probe.close() }
        }
        return size
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
        if (source == null || position != streamPos) {
            runCatching { source?.close() }
            source = factory.createDataSource().also {
                it.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            }
            streamPos = position
        }
        val n = source!!.read(buffer, offset, size)
        if (n == C.RESULT_END_OF_INPUT) return -1
        streamPos += n
        return n
    }

    @Synchronized
    override fun close() {
        runCatching { source?.close() }
        source = null
        streamPos = -1L
    }
}
