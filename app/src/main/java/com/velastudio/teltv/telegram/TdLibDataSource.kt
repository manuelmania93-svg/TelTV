package com.velastudio.teltv.telegram

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TdLibDataSource(
    private val client: RawTdClient,
    private val fileId: Int
) : BaseDataSource(true) {

    private var file: RandomAccessFile? = null
    private var bytesRemaining: Long = 0
    private var readPosition: Long = 0
    private var localPath: String? = null

    override fun open(dataSpec: DataSpec): Long {
        val position = dataSpec.position
        readPosition = position

        // 1. Ask TDLib to prioritize downloading from position
        val initialFile = client.downloadRangeBlocking(fileId, position, 4 * 1024 * 1024L)
        val totalSize = if (initialFile.size > 0) initialFile.size else 5L * 1024 * 1024 * 1024

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (totalSize - position).coerceAtLeast(0)
        }

        localPath = initialFile.local?.path?.takeIf { it.isNotBlank() }

        // 2. Open file if already present
        localPath?.let { path ->
            runCatching {
                file = RandomAccessFile(File(path), "r").also { it.seek(position) }
            }
        }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        // If file not open yet, wait up to 4s for TDLib to create the sparse file
        if (file == null) {
            val updated = client.downloadRangeBlocking(fileId, readPosition, 2 * 1024 * 1024L)
            val path = updated.local?.path?.takeIf { it.isNotBlank() }
            if (path != null) {
                localPath = path
                runCatching {
                    file = RandomAccessFile(File(path), "r").also { it.seek(readPosition) }
                }
            }
        }

        val currentRaf = file
        if (currentRaf != null) {
            var attempts = 0
            while (attempts < 20) {
                val read = currentRaf.read(buffer, offset, toRead)
                if (read > 0) {
                    bytesRemaining -= read
                    readPosition += read
                    bytesTransferred(read)
                    return read
                }
                // If EOF reached, wait 150ms for next chunk to arrive over network
                Thread.sleep(150)
                client.triggerChunkDownload(fileId, readPosition, 2 * 1024 * 1024L)
                attempts++
            }
        }

        return C.RESULT_END_OF_INPUT
    }

    override fun getUri() = null

    override fun close() {
        runCatching { file?.close() }
        file = null
    }
}

class RawTdClient(private val telegram: TelegramClient) {
    fun downloadRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File {
        val cached = telegram.fileCache[fileId]
        if (cached?.local?.path?.isNotBlank() == true) {
            triggerChunkDownload(fileId, offset, limit)
            return cached
        }
        val latch = CountDownLatch(1)
        var result: TdApi.File = cached ?: TdApi.File().apply { id = fileId }

        val listener: (TdApi.File) -> Unit = { f ->
            if (!f.local?.path.isNullOrBlank()) {
                result = f
                latch.countDown()
            }
        }

        telegram.registerFileListener(fileId, listener)
        triggerChunkDownload(fileId, offset, limit)
        telegram.executeAsync(TdApi.GetFile(fileId))
        latch.await(4000, TimeUnit.MILLISECONDS)
        telegram.unregisterFileListener(fileId, listener)

        return telegram.fileCache[fileId] ?: result
    }

    fun triggerChunkDownload(fileId: Int, offset: Long, limit: Long) {
        val chunkLimit = if (limit <= 0) 2 * 1024 * 1024L else minOf(limit, 4 * 1024 * 1024L)
        telegram.executeAsync(TdApi.DownloadFile(fileId, 32, offset, chunkLimit, false))
    }
}
