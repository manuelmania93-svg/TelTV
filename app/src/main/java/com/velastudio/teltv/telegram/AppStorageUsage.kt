package com.velastudio.teltv.telegram

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Read-only file-content counts, not Android's allocated-space/app-code accounting. */
data class AppStorageUsage(
    val telegramFiles: Long,
    val telegramDatabase: Long,
    val libraryDatabase: Long,
    val otherData: Long,
    val androidCache: Long,
    val subtitles: Long
) {
    companion object {
        fun measure(dataDir: File, cacheDir: File): AppStorageUsage {
            val files = File(dataDir, "files")
            val media = size(File(files, "tdlib-files"))
            val database = size(File(files, "tdlib"))
            val library = size(File(dataDir, "databases"))
            // App-owned files/settings only. Exclude code, cache and the measured buckets.
            val otherFiles = files.listFiles()?.filter { it.name !in setOf("tdlib", "tdlib-files") }
                ?.sumOf { size(it) } ?: if (files.exists()) throw IOException("Cannot list app files") else 0L
            val other = otherFiles + size(File(dataDir, "shared_prefs")) + size(File(dataDir, "no_backup"))
            return AppStorageUsage(media, database, library, other, size(cacheDir), size(File(cacheDir, "subtitles")))
        }
        internal fun size(root: File): Long {
            if (Files.isSymbolicLink(root.toPath()) || !root.exists()) return 0L
            var entries = 0
            fun visit(file: File, depth: Int): Long {
                if (++entries > 100_000 || depth > 64) throw IOException("Storage scan limit reached")
                if (Files.isSymbolicLink(file.toPath())) return 0L
                if (!file.canRead()) throw IOException("Storage category unavailable")
                if (file.isFile) return file.length()
                if (!file.isDirectory) return 0L
                val children = file.listFiles() ?: throw IOException("Cannot list storage category")
                return children.fold(0L) { total, child -> Math.addExact(total, visit(child, depth + 1)) }
            }
            return visit(root, 0)
        }
    }
}
