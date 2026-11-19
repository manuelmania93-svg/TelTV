package com.velastudio.teltv.telegram

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StorageMaintenanceTest {
    @Test fun pausedOrBufferingPlayerDefersCleanup() {
        val gate = StorageUseGate()
        gate.playerOpened()
        assertFalse(gate.tryBeginCleanup())
        gate.playerClosed()
        assertTrue(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun activeDownloadAndPendingCallbackBothDefer() {
        val gate = StorageUseGate()
        var sent = false
        gate.submitDownload(7) { sent = true }
        assertTrue(sent)
        gate.fileUpdate(7, false)
        assertFalse(gate.tryBeginCleanup()) // request callback still pending
        gate.downloadReply(7, true)
        assertFalse(gate.tryBeginCleanup())
        gate.fileUpdate(7, false)
        assertTrue(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun downloadArrivingDuringCleanupIsQueuedUntilEnd() {
        val gate = StorageUseGate()
        assertTrue(gate.tryBeginCleanup())
        var sends = 0
        gate.submitDownload(9) { sends++ }
        assertEquals(0, sends)
        gate.endCleanup()
        assertEquals(1, sends)
        assertFalse(gate.tryBeginCleanup())
        gate.downloadReply(9, false)
        assertTrue(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun openReaderKeepsAlreadyCachedMediaSafe() {
        val gate = StorageUseGate()
        gate.acquireReader()
        assertFalse(gate.tryBeginCleanup())
        gate.releaseReader()
        assertTrue(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun newReaderWaitsForCleanupAndThenBlocksNextSweep() {
        val gate = StorageUseGate()
        assertTrue(gate.tryBeginCleanup())
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val thread = Thread {
            gate.acquireReader()
            acquired.countDown()
            release.await(5, TimeUnit.SECONDS)
            gate.releaseReader()
        }
        thread.start()
        assertFalse(acquired.await(100, TimeUnit.MILLISECONDS))
        gate.endCleanup()
        assertTrue(acquired.await(5, TimeUnit.SECONDS))
        assertFalse(gate.tryBeginCleanup())
        release.countDown()
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertTrue(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun secondCleanupCannotOverlap() {
        val gate = StorageUseGate()
        assertTrue(gate.tryBeginCleanup())
        assertFalse(gate.tryBeginCleanup())
        gate.endCleanup()
    }
    @Test fun usageSeparatesDataAndCacheWithoutChangingAnyFiles() {
        val root = Files.createTempDirectory("storage-test").toFile()
        fun write(path: String, n: Int) = File(root, path).apply { parentFile.mkdirs(); writeBytes(ByteArray(n)) }
        try {
            val media = write("files/tdlib-files/videos/a", 101)
            val db = write("files/tdlib/db.sqlite", 202)
            val login = write("files/tdlib/td.binlog", 23)
            val history = write("databases/teltv.db", 34)
            write("files/datastore/cache.preferences_pb", 45)
            write("shared_prefs/preferences.xml", 6)
            val subtitles = write("cache/subtitles/s.srt", 7)
            write("cache/image_cache/p", 8)
            val usage = AppStorageUsage.measure(root, File(root, "cache"))
            assertEquals(101L, usage.telegramFiles)
            assertEquals(225L, usage.telegramDatabase)
            assertEquals(34L, usage.libraryDatabase)
            assertEquals(51L, usage.otherData)
            assertEquals(15L, usage.androidCache)
            assertEquals(7L, usage.subtitles)
            listOf(media, db, login, history, subtitles).forEach { assertTrue(it.exists()) }
            assertEquals(202, db.readBytes().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun missingStorageIsZeroAndSymlinksAreNotFollowed() {
        val root = Files.createTempDirectory("storage-test").toFile()
        val outside = Files.createTempDirectory("outside-storage").toFile()
        try {
            File(outside, "secret").writeBytes(ByteArray(200))
            File(root, "files").mkdirs()
            Files.createSymbolicLink(File(root, "files/linked").toPath(), outside.toPath())
            val usage = AppStorageUsage.measure(root, File(root, "cache"))
            assertEquals(AppStorageUsage(0, 0, 0, 0, 0, 0), usage)
            assertTrue(File(outside, "secret").exists())
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
}
