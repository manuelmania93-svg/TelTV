package com.velastudio.teltv.util

import java.text.Normalizer
import java.util.Locale

/** Show-name helpers for topics that hold several shows and for small typos. Pure logic. */
object ShowNameMatcher {
    private val SEPARATOR = Regex("(?i)\\s+(?:&|\\+|/|\\||and|und)\\s+|\\s*[,;]\\s+")

    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * "Breaking Bad & Better Call Saul" -> two names. Returns the single name when there is no
     * separator or a part is too short to be a show. The caller must still check whether the
     * whole name is a real show ("Law & Order") before using the parts.
     */
    fun splitMixed(name: String): List<String> {
        val parts = name.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2 && parts.all { normalize(it).length >= 3 }) parts else listOf(name.trim())
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 1.0 = same after normalizing, 0.0 = nothing in common. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        val longest = maxOf(x.length, y.length)
        return if (longest == 0) 0.0 else 1.0 - levenshtein(x, y).toDouble() / longest
    }

    /** Best similarity between [word] and any single word of [candidate]. */
    fun bestWordSimilarity(word: String, candidate: String): Double =
        candidate.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
            .maxOfOrNull { similarity(word, it) } ?: 0.0

    /** Words worth querying on their own when a full-name search finds nothing. Longest first. */
    fun distinctiveWords(name: String): List<String> =
        name.split(Regex("[^\\p{L}\\p{N}]+")).filter { normalize(it).length >= 5 }
            .sortedByDescending { it.length }.distinct().take(3)

    fun isExact(candidate: String, query: String): Boolean = normalize(candidate) == normalize(query)
}
