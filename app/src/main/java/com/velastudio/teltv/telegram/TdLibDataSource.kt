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
    private var downloadedUpTo: Long = 0
    private var totalFileSize: Long = 0
    private var isFullyDownloaded: Boolean = false

    companion object {
        private const val CHUNK_SIZE = 4 * 1024 * 1024L
    }

    override fun open(dataSpec: DataSpec): Long {
        val position = dataSpec.position
        readPosition = position

        val initialFile = downloadRangeOrThrow(position, CHUNK_SIZE)
        if (initialFile.size <= 0L) {
            throw IOException("TDLib returned no size for file $fileId")
        }
        totalFileSize = initialFile.size
        updateDownloadedBoundary(initialFile, position)

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            (totalFileSize - position).coerceAtLeast(0)
        }

        val resolvedPath = localPathOrThrow(initialFile)
        file = RandomAccessFile(File(resolvedPath), "r").also { it.seek(position) }

        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L || readPosition >= totalFileSize) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        var attempts = 0

        while (readPosition < totalFileSize && attempts < 60) {
            if (!isFullyDownloaded && readPosition >= downloadedUpTo) {
                val updated = downloadRangeOrThrow(readPosition, CHUNK_SIZE)
                updateDownloadedBoundary(updated, readPosition)
                val path = localPathOrThrow(updated)
                file?.close()
                file = RandomAccessFile(File(path), "r").also { it.seek(readPosition) }
            }

            val available = if (isFullyDownloaded) {
                bytesRemaining
            } else {
                (downloadedUpTo - readPosition).coerceAtLeast(0)
            }

            if (available > 0L) {
                val bytesToRead = minOf(toRead.toLong(), available).toInt()
                val currentRaf = file ?: throw IOException("File not open for fileId=$fileId")
                val read = currentRaf.read(buffer, offset, bytesToRead)
                if (read > 0) {
                    bytesRemaining -= read
                    readPosition += read
                    bytesTransferred(read)
                    return read
                }
            }

            attempts++
            Thread.sleep(100)
        }

        if (readPosition >= totalFileSize) return C.RESULT_END_OF_INPUT
        throw IOException("Buffer underrun: TDLib download timed out at offset $readPosition for file $fileId")
    }

    override fun getUri() = TdLibAwareDataSourceFactory.uriForFile(fileId)

    override fun close() {
        runCatching { file?.close() }
        file = null
    }

    private fun updateDownloadedBoundary(tdFile: TdApi.File, requestedOffset: Long) {
        val local = tdFile.local
        if (local != null) {
            if (local.isDownloadingCompleted) {
                isFullyDownloaded = true
                downloadedUpTo = totalFileSize
            } else {
                // Strictly respect the physical byte range downloaded by TDLib
                val upTo = local.downloadOffset + local.downloadedPrefixSize
                downloadedUpTo = minOf(totalFileSize, upTo)
            }
        }
    }

    private fun downloadRangeOrThrow(position: Long, limit: Long): TdApi.File = try {
        client.downloadRangeBlocking(fileId, position, limit)
    } catch (error: NullPointerException) {
        throw IOException("TDLib returned an incomplete file response for file $fileId at offset $position", error)
    }

    private fun localPathOrThrow(downloadedFile: TdApi.File): String =
        downloadedFile.local?.path?.takeIf { it.isNotBlank() }
            ?: throw IOException("TDLib did not provide a local path for file $fileId")
}

class RawTdClient(private val telegram: TelegramClient) {
    fun downloadRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File {
        return telegram.downloadFileRangeBlocking(fileId, offset, limit)
            ?: throw IOException("TDLib download failed for file $fileId at offset $offset: ${telegram.getLastDownloadError(fileId) ?: "unknown error"}")
    }
}
