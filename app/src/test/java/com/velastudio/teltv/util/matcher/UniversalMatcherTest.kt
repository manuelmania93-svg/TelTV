package com.velastudio.teltv.util.matcher

import com.velastudio.teltv.util.matcher.UniversalMatcher.Entry
import org.junit.Assert.*
import org.junit.Test

class UniversalMatcherTest {
    private fun lib(vararg t: String) = t.mapIndexed { i, s -> Entry("id$i", s) }

    @Test fun parsesDateNamedWrestling() {
        val id = MediaIdentityParser.parse("WCW Thunder (21.03.2001).mp4")
        assertEquals(MediaIdentity.Kind.DATED, id.kind)
        assertEquals("wcwthunder", id.stem)
        assertEquals(20010321, id.dateKey)
    }

    @Test fun parsesIsoAndShortDates() {
        assertEquals(20010321, MediaIdentityParser.parse("WWF Raw 2001-03-21.mkv").dateKey)
        assertEquals(20010321, MediaIdentityParser.parse("WWF Raw 21.03.01.mkv").dateKey)
        assertNull(MediaIdentityParser.parse("Show 45.13.2001").dateKey)
    }

    @Test fun datedNextAndPreviousAreChronologicalNotUploadOrder() {
        val l = lib(
            "WCW Thunder (28.03.2001).mp4", "WCW Thunder (14.03.2001).mp4",
            "WCW Thunder (21.03.2001).mp4", "WWF Raw (22.03.2001).mp4"
        )
        val cur = l[2]
        assertEquals("id0", UniversalMatcher.findNext(cur, l)?.id)
        assertEquals("id1", UniversalMatcher.findPrevious(cur, l)?.id)
        assertNull(UniversalMatcher.findNext(l[0], l))
    }

    @Test fun animeAbsoluteNumberingWithOrphanSeason() {
        val l = lib("Death Note S02E18.mkv", "Death Note S02E19.mkv", "Death Note S02E17.mkv")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l)?.id)
        assertEquals("id2", UniversalMatcher.findPrevious(l[0], l)?.id)
    }

    @Test fun seasonCountHintForcesAbsolute() {
        val l = lib("Death Note S01E25.mkv", "Death Note S02E26.mkv")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l, seasonCountHint = 1)?.id)
        assertNull(UniversalMatcher.findNext(l[0], l))
    }

    @Test fun normalSeasonsStillCrossSeasonBoundary() {
        val l = lib("Show S01E10.mkv", "Show S02E01.mkv", "Show S01E09.mkv")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l)?.id)
        assertEquals("id0", UniversalMatcher.findPrevious(l[1], l)?.id)
    }

    @Test fun bareThreeDigitNumberDecodesToSeasonEpisode() {
        val l = lib("Malcolm Mittendrin S07E21.mp4", "Malcolm Mittendrin 722.mp4", "Malcolm Mittendrin S07E23.mp4")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l)?.id)
        assertEquals("id0", UniversalMatcher.findPrevious(l[1], l)?.id)
        assertEquals("id2", UniversalMatcher.findNext(l[1], l)?.id)
    }

    @Test fun bareNumbersWithoutSeasonsAreAbsolute() {
        val l = lib("One Piece 1050.mkv", "One Piece 1051.mkv")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l)?.id)
    }

    @Test fun bigBareNumberIsNotDecodedAsSeasonInSeasonedGroup() {
        val l = lib("Show S01E01.mkv", "Show 1050.mkv", "Show 1051.mkv")
        assertEquals("id2", UniversalMatcher.findNext(l[1], l)?.id)
    }

    @Test fun fuzzyStemMatchesTypos() {
        assertTrue(UniversalMatcher.sameShow("malcolmmittendrin", "malcolmmitendrin"))
        assertFalse(UniversalMatcher.sameShow("naruto", "bleach"))
        val l = lib("Attack on Titan - 05.mkv", "Attack on Titn - 06.mkv")
        assertEquals("id1", UniversalMatcher.findNext(l[0], l)?.id)
    }

    @Test fun junkAndMoviesNeverMatch() {
        assertEquals(MediaIdentity.Kind.JUNK, MediaIdentityParser.parse("12345.mp4").kind)
        assertEquals(MediaIdentity.Kind.MOVIE, MediaIdentityParser.parse("The Matrix (1999).mkv").kind)
        val l = lib("The Matrix (1999).mkv", "The Matrix Reloaded (2003).mkv")
        assertNull(UniversalMatcher.findNext(l[0], l))
    }

    @Test fun differentShowsDoNotMix() {
        val l = lib("Naruto - 050.mkv", "Bleach - 051.mkv")
        assertNull(UniversalMatcher.findNext(l[0], l))
    }

    @Test fun fingerprintIsStableAndSmallRead() {
        val data = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
        var bytesRead = 0L
        val read = { off: Long, buf: ByteArray, len: Int ->
            System.arraycopy(data, off.toInt(), buf, 0, len); bytesRead += len; len
        }
        val a = ContentFingerprint.compute(data.size.toLong(), read)
        assertEquals(3L * ContentFingerprint.SAMPLE_BYTES, bytesRead)
        val b = ContentFingerprint.compute(data.size.toLong(), read)
        assertEquals(a, b)
        val changed = data.copyOf().also { it[500_000] = 1 }
        val c = ContentFingerprint.compute(changed.size.toLong()) { off, buf, len ->
            System.arraycopy(changed, off.toInt(), buf, 0, len); len
        }
        assertNotEquals(a, c)
    }

    @Test fun duplicatesGroupedByFingerprint() {
        val d = ContentFingerprint.duplicates(mapOf("a" to "x", "b" to "x", "c" to "y"))
        assertEquals(1, d.size)
        assertEquals(setOf("a", "b"), d[0].toSet())
    }
}
