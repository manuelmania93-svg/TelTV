package com.velastudio.teltv.telegram

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

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
        if (initialFile.size <= 0L) {
            throw IOException("TDLib returned no size for file $fileId")
        }
        val totalSize = initialFile.size

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (totalSize - position).coerceAtLeast(0)
        }

        localPath = initialFile.local?.path?.takeIf { it.isNotBlank() }
            ?: throw IOException("TDLib did not provide a local path for file $fileId")

        // 2. Open file if already present
        file = RandomAccessFile(File(localPath!!), "r").also { it.seek(position) }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        var attempts = 0
        while (attempts < 3) {
            val currentRaf = file
            if (currentRaf != null) {
                val read = currentRaf.read(buffer, offset, toRead)
                if (read > 0) {
                    bytesRemaining -= read
                    readPosition += read
                    bytesTransferred(read)
                    return read
                }
            }

            file?.close()
            file = null
            val updated = client.downloadRangeBlocking(fileId, readPosition, 2 * 1024 * 1024L)
            val path = updated.local?.path?.takeIf { it.isNotBlank() }
            if (path != null) {
                localPath = path
                file = RandomAccessFile(File(path), "r").also { it.seek(readPosition) }
            }
            attempts++
        }

        throw IOException("TDLib did not provide bytes at offset $readPosition for file $fileId")
    }

    override fun getUri() = null

    override fun close() {
        runCatching { file?.close() }
        file = null
    }
}

class RawTdClient(private val telegram: TelegramClient) {
    fun downloadRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File {
        return telegram.downloadFileRangeBlocking(fileId, offset, limit)
            ?: throw IOException("TDLib download failed for file $fileId at offset $offset")
    }
}
