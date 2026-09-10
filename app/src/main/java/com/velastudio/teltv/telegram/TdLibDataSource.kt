package com.velastudio.teltv.telegram

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.drinkless.tdlib.TdApi
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

        // 1. Fetch file info for size
        val tdFile = client.downloadRangeBlocking(fileId, position, 2 * 1024 * 1024L)
        localPath = tdFile.local.path.takeIf { it.isNotBlank() }

        val totalSize = tdFile.size
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (totalSize - position).coerceAtLeast(0)
        }

        if (localPath != null) {
            runCatching {
                file = RandomAccessFile(localPath, "r").also { it.seek(position) }
            }
        }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val currentFile = file

        if (currentFile != null) {
            // Read from growing local sparse file
            var read = currentFile.read(buffer, offset, toRead)
            if (read == -1) {
                // Wait briefly for chunk download
                client.downloadRangeBlocking(fileId, readPosition, 2 * 1024 * 1024L)
                read = currentFile.read(buffer, offset, toRead)
            }
            if (read > 0) {
                bytesRemaining -= read
                readPosition += read
                bytesTransferred(read)
                return read
            }
        }

        return C.RESULT_END_OF_INPUT
    }

    override fun getUri() = null

    override fun close() {
        file?.close()
        file = null
    }
}

class RawTdClient(private val telegram: TelegramClient) {
    fun downloadRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File {
        val latch = CountDownLatch(1)
        var resultFile: TdApi.File? = null
        val chunkLimit = if (limit <= 0) 2 * 1024 * 1024L else minOf(limit, 4 * 1024 * 1024L)
        telegram.downloadFileRangeSync(fileId, offset, chunkLimit) { f ->
            resultFile = f
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return resultFile ?: TdApi.File().apply {
            id = fileId
            size = 10L * 1024 * 1024 * 1024
            local = TdApi.LocalFile()
        }
    }
}
