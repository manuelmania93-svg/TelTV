package com.velastudio.teltv.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Never write ZIP entry paths to disk, and never guess a file from a season pack. */
object SubDlArchive {
    const val MAX_FILE = 2 * 1024 * 1024
    const val MAX_DOWNLOAD = 4 * 1024 * 1024
    fun extract(bytes: ByteArray, expectedName: String): ByteArray? {
        if (bytes.size > MAX_DOWNLOAD) return null
        val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
        val selected = if (!zip) bytes.takeIf { it.size <= MAX_FILE } else {
            val files = mutableListOf<Pair<String, ByteArray>>()
            var total = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                var entries = 0
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (++entries > 100) return null
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 8 * 1024 * 1024 || output.size() + n > MAX_FILE) return null
                        output.write(buffer, 0, n)
                    }
                    if (!entry.isDirectory && entry.name.endsWith(".srt", true))
                        files.add(entry.name.substringAfterLast('/').substringAfterLast('\\') to output.toByteArray())
                }
            }
            val exact = files.filter { it.first.equals(expectedName.substringAfterLast('/'), true) }
            when { exact.size == 1 -> exact[0].second; files.size == 1 -> files[0].second; else -> null }
        } ?: return null
        // Don't cache HTML, JSON errors or binary image subtitles with an .srt suffix.
        val text = selected.toString(Charsets.UTF_8)
        if (!Regex("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}\\s*-->\\s*\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{3}").containsMatchIn(text)) return null
        return selected
    }
}
