package com.velastudio.teltv.telegram

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.BaseDataSource
import org.drinkless.tdlib.TdApi
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch

/**
 * Lets ExoPlayer play a Telegram video without downloading the whole file first.
 *
 * How it works: TDLib supports downloading an arbitrary byte offset/limit of a file
 * (`TdApi.DownloadFile(fileId, priority, offset, limit, synchronous)`) and reports progress via
 * `UpdateFile`. We ask TDLib to prioritize the byte range ExoPlayer just requested, block until
 * enough of it is on disk, then read directly from TDLib's local file path. This is the same
 * approach Telegram's own official clients use for in-chat video playback/seeking.
 */
class TdLibDataSource(
    private val client: RawTdClient,
    private val fileId: Int
) : BaseDataSource(true) {

    private var file: RandomAccessFile? = null
    private var bytesRemaining: Long = 0
    private var readPosition: Long = 0

    override fun open(dataSpec: DataSpec): Long {
        val position = dataSpec.position
        val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.length

        val tdFile = client.downloadRangeBlocking(fileId, position, length)
        file = RandomAccessFile(tdFile.local.path, "r").also { it.seek(position) }
        readPosition = position
        bytesRemaining = if (length == -1L) tdFile.size - position else length

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val read = file!!.read(buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesRemaining -= read
        readPosition += read
        bytesTransferred(read)
        return read
    }

    override fun getUri() = null

    override fun close() {
        file?.close()
        file = null
    }
}

/**
 * Minimal blocking bridge over TelegramClient for the small set of calls the data source needs
 * synchronously (Media3's DataSource contract is blocking, unlike the rest of the app's
 * coroutine-based TDLib wrapper).
 */
class RawTdClient(private val telegram: TelegramClient) {
    fun downloadRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File {
        val latch = CountDownLatch(1)
        var resultFile: TdApi.File? = null
        // Ask TDLib to fetch this byte range with high priority (32 = highest) and block
        // (synchronous = true) until at least this chunk is available, then hand back the
        // TdApi.File descriptor (which contains the on-disk `local.path`).
        // telegram.downloadFileRangeSync always invokes this callback exactly once, with null
        // on failure -- that's what lets the latch release below even when the download errors
        // out, instead of hanging this (ExoPlayer loading) thread forever.
        telegram.downloadFileRangeSync(fileId, offset, limit) { f ->
            resultFile = f
            latch.countDown()
        }
        latch.await()
        return resultFile ?: error("TDLib file download failed for fileId=$fileId (offset=$offset, limit=$limit)")
    }
}
