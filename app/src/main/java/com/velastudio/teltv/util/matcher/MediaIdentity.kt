package com.velastudio.teltv.util.matcher

import com.velastudio.teltv.util.HybridEpisodeMatcher
import com.velastudio.teltv.util.MediaTitleCleaner
import java.util.Locale

/** What a messy file name says about the item. Pure data, a few bytes per item. */
data class MediaIdentity(
    val kind: Kind,
    /** Lowercase letters/digits only. Empty for junk names. */
    val stem: String,
    val season: Int?,
    val episode: Int?,
    /** yyyymmdd for date-named files (wrestling, talk shows), else null. */
    val dateKey: Int?,
    val year: Int?,
    /** True when [episode] came from a bare number like "722" (could be S7E22 or absolute). */
    val bareNumber: Boolean
) {
    enum class Kind { EPISODE, DATED, MOVIE, JUNK }
}

object MediaIdentityParser {
    private val DMY = Regex("(?<!\\d)(\\d{1,2})[.\\-/_ ](\\d{1,2})[.\\-/_ ](\\d{4})(?!\\d)")
    private val YMD = Regex("(?<!\\d)(\\d{4})[.\\-/_ ](\\d{1,2})[.\\-/_ ](\\d{1,2})(?!\\d)")
    private val DMY2 = Regex("(?<!\\d)(\\d{2})\\.(\\d{2})\\.(\\d{2})(?!\\d)")
    private val YEAR = Regex("(?<!\\d)(19[5-9]\\d|20\\d{2})(?!\\d)")
    private val BARE_TRAILING = Regex("^(.*?\\D)\\s*[-#]?\\s*(\\d{1,4})$")

    private fun validDate(y: Int, m: Int, d: Int): Int? =
        if (y in 1950..2099 && m in 1..12 && d in 1..31) y * 10000 + m * 100 + d else null

    fun parse(rawTitle: String): MediaIdentity {
        // Dates first: MediaTitleCleaner strips "(21.03.2001)" together with its brackets.
        var work = rawTitle.replace(Regex("(?i)\\.(mkv|mp4|avi|mov|wmv|flv|webm|ts)$"), "")
        var dateKey: Int? = null
        for ((re, order) in listOf(DMY to "dmy", YMD to "ymd", DMY2 to "dmy2")) {
            val m = re.find(work) ?: continue
            val g = m.groupValues.drop(1).map { it.toInt() }
            val key = when (order) {
                "dmy" -> validDate(g[2], g[1], g[0])
                "ymd" -> validDate(g[0], g[1], g[2])
                else -> validDate(if (g[2] >= 50) 1900 + g[2] else 2000 + g[2], g[1], g[0])
            }
            if (key != null) {
                dateKey = key
                work = work.removeRange(m.range)
                break
            }
        }
        val cleaned = MediaTitleCleaner.clean(work).replace(Regex("[()\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ").trim()

        if (dateKey != null) {
            val stem = normalize(cleaned.trim('-', '_', ':', ' '))
            return MediaIdentity(
                if (stem.isEmpty()) MediaIdentity.Kind.JUNK else MediaIdentity.Kind.DATED,
                stem, null, null, dateKey, dateKey / 10000, false
            )
        }

        val sig = HybridEpisodeMatcher.parseSignature(work)
        if (sig != null && sig.stem.isNotEmpty()) {
            val bare = sig.season == null && BARE_TRAILING.matches(cleaned) &&
                !Regex("(?i)\\b(ep|episode|folge|part|teil|chapter|kapitel)[.\\s_-]*\\d").containsMatchIn(cleaned)
            return MediaIdentity(MediaIdentity.Kind.EPISODE, sig.stem, sig.season, sig.episode, null, null, bare)
        }

        val year = YEAR.find(cleaned)?.groupValues?.get(1)?.toIntOrNull()
        val stem = normalize(if (year != null) cleaned.substringBefore(year.toString()) else cleaned)
        return if (stem.length < 2 || stem.none { it.isLetter() }) {
            MediaIdentity(MediaIdentity.Kind.JUNK, "", null, null, null, year, false)
        } else {
            MediaIdentity(MediaIdentity.Kind.MOVIE, stem, null, null, null, year, false)
        }
    }

    internal fun normalize(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
}
