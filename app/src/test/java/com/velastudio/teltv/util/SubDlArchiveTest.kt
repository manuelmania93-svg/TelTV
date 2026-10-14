package com.velastudio.teltv.util

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubDlArchiveTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHallo\n".toByteArray()
    private fun zip(vararg names: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out -> names.forEach { out.putNextEntry(ZipEntry(it)); out.write(srt); out.closeEntry() } }
    }.toByteArray()
    @Test fun rawSrtAndSingleArchiveWork() {
        assertArrayEquals(srt, SubDlArchive.extract(srt, "a.srt"))
        assertArrayEquals(srt, SubDlArchive.extract(zip("dir/a.srt"), "archive.zip"))
    }
    @Test fun neverGuessBetweenMultipleFiles() {
        assertNull(SubDlArchive.extract(zip("a.srt", "b.srt"), "archive.zip"))
        assertArrayEquals(srt, SubDlArchive.extract(zip("a.srt", "b.srt"), "a.srt"))
    }
    @Test fun rejectsErrorsAndOversizedInput() {
        assertNull(SubDlArchive.extract("<html>quota</html>".toByteArray(), "a.srt"))
        assertNull(SubDlArchive.extract(ByteArray(SubDlArchive.MAX_DOWNLOAD + 1), "a.srt"))
    }
    @Test fun pathsAreNotWrittenToDisk() {
        assertArrayEquals(srt, SubDlArchive.extract(zip("../../a.srt"), "archive.zip"))
    }
}
