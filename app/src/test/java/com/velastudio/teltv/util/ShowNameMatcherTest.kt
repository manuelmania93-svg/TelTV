package com.velastudio.teltv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowNameMatcherTest {
    @Test
    fun mixedTopicSplitsIntoShows() {
        assertEquals(listOf("Breaking Bad", "Better Call Saul"), ShowNameMatcher.splitMixed("Breaking Bad & Better Call Saul"))
        assertEquals(listOf("Breaking Bad", "Better Call Saul"), ShowNameMatcher.splitMixed("Breaking Bad und Better Call Saul"))
        assertEquals(listOf("A Show", "Other Show"), ShowNameMatcher.splitMixed("A Show, Other Show"))
    }

    @Test
    fun singleShowIsNotSplit() {
        assertEquals(listOf("Malcolm in the Middle"), ShowNameMatcher.splitMixed("Malcolm in the Middle"))
        assertEquals(listOf("Naruto Shippuden"), ShowNameMatcher.splitMixed("Naruto Shippuden"))
        // Too short to be a show: stays whole.
        assertEquals(listOf("A & B"), ShowNameMatcher.splitMixed("A & B"))
    }

    @Test
    fun typoSimilarity() {
        assertTrue(ShowNameMatcher.similarity("Malcom", "Malcolm") > 0.8)
        assertTrue(ShowNameMatcher.bestWordSimilarity("Malcom", "Malcolm in the Middle") > 0.8)
        assertTrue(ShowNameMatcher.similarity("Breaking Bad", "Better Call Saul") < 0.5)
        assertTrue(ShowNameMatcher.isExact("Law & Order", "law and order".replace("and", "&")))
    }

    @Test
    fun distinctiveWordsSkipShortOnes() {
        assertEquals(listOf("Mittendrin", "Malcom"), ShowNameMatcher.distinctiveWords("Malcom Mittendrin"))
    }
}
