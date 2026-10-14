package com.velastudio.teltv.util

/**
 * Reads season/episode out of a caption when the topic (show) is already known.
 * Pure string logic, no Android classes. Handles the formats seen in the user's library:
 * S04E04, 4x04, "Staffel 4 Folge 4", "4 4 Die Diagnose", "Episode 12" and bare "101"/"1001".
 */
object CaptionEpisodeParser {
    data class Parsed(
        val season: Int?,
        val episode: Int,
        /** Text after the episode numbers (often the episode title), cleaned. May be empty. */
        val titleHint: String,
        /** Set for a bare 3-4 digit number such as "101": S1E01 or absolute episode 101. */
        val bareDigits: Int? = null
    )

    private val SYMBOLS = Regex("[\\p{So}\\p{Cs}\\p{Sk}\\uFE0F\\u200D]")
    private val DATE_LIKE = Regex("(?<!\\d)\\d{1,2}[./-]\\d{1,2}[./-](?:\\d{4}|\\d{2})(?!\\d)|(?<!\\d)\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}(?!\\d)")
    private val SE = Regex("(?i)(?<![\\p{L}\\d])(?:s|season|staffel)\\s*(\\d{1,2})\\s*[ex]\\s*(\\d{1,4})(?!\\d)")
    private val NxM = Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{1,4})(?!\\d)")
    private val WORDS = Regex("(?i)(?<![\\p{L}\\d])(?:season|staffel|s)\\s*(\\d{1,2})[\\s,.:-]+(?:episode|folge|ep|e)\\s*\\.?\\s*(\\d{1,4})(?!\\d)")
    // "4 4 Die Diagnose": season and episode as two small numbers, not part of a longer number run.
    private val PAIR = Regex("(?<![\\d][ ._/-])(?<!\\d)(\\d{1,2})[ ._-]+(\\d{1,3})(?=[ ._-]+\\p{L}|\\s*$)")
    // "08 - Die Teufelsfrucht": episode number, dash or colon, title (no season).
    private val LEADING_EP = Regex("^(\\d{1,4})\\s*[-:.\u2013]\\s+\\p{L}")
    private val EP_WORD = Regex("(?i)(?<![\\p{L}\\d])(?:episode|folge|ep|teil|part)\\s*\\.?\\s*(\\d{1,4})(?!\\d)")
    // Not after "-" or "#": "Naruto - 120" is explicit absolute numbering, not S1E20.
    private val BARE = Regex("(?<!\\d)(?<!-)(?<!-\\s)(?<!#)(?<!#\\s)(\\d{3,4})(?=[ ._-]+\\p{L}|\\s*$)")

    /** Trailing container extension, also when a stray digit follows it ("mp41"). */
    private val EXT_STRICT = Regex("(?i)\\.(mkv|mp4|m4v|avi|mov|wmv|flv|webm|ts)$")
    private val EXT_LENIENT = Regex("(?i)[ ._](mkv|mp4|m4v|avi|mov|wmv|flv|webm)\\d{0,2}$")

    fun stripExtension(raw: String): String {
        val t = raw.trim()
        return t.replace(EXT_STRICT, "").replace(EXT_LENIENT, "").trim()
    }

    fun hasDate(raw: String): Boolean = DATE_LIKE.containsMatchIn(raw)

    private fun prepare(raw: String, showName: String?): String {
        var t = stripExtension(raw).replace(SYMBOLS, " ")
        if (!showName.isNullOrBlank()) {
            t = t.replace(Regex(Regex.escape(showName.trim()), RegexOption.IGNORE_CASE), " ")
        }
        return t.replace(Regex("[\\[\\](){}|]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun hint(t: String, end: Int): String =
        t.substring(end).replace(Regex("^[\\s:._-]+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * [showName] is the topic/channel name. Number-pair and bare-number readings are only used
     * when a show name is known (a series topic), and never for date-named captions.
     */
    fun parse(raw: String, showName: String? = null): Parsed? {
        val t = prepare(raw, showName)
        if (t.isBlank()) return null
        for (re in listOf(SE, WORDS, NxM)) {
            re.find(t)?.let { m ->
                val e = m.groupValues[2].toIntOrNull() ?: return@let
                return Parsed(m.groupValues[1].toIntOrNull(), e, hint(t, m.range.last + 1))
            }
        }
        if (hasDate(raw)) return null
        if (!showName.isNullOrBlank()) {
            PAIR.find(t)?.let { m ->
                val s = m.groupValues[1].toIntOrNull()
                val e = m.groupValues[2].toIntOrNull()
                if (s != null && e != null && s >= 1 && e >= 1) return Parsed(s, e, hint(t, m.range.last + 1))
            }
        }
        if (!showName.isNullOrBlank()) {
            LEADING_EP.find(t)?.let { m ->
                val e = m.groupValues[1].toIntOrNull()
                if (e != null && e >= 1) {
                    val numEnd = m.groups[1]!!.range.last + 1
                    return Parsed(null, e, hint(t, numEnd))
                }
            }
        }
        EP_WORD.find(t)?.let { m ->
            val e = m.groupValues[1].toIntOrNull() ?: return@let
            return Parsed(null, e, hint(t, m.range.last + 1))
        }
        if (!showName.isNullOrBlank()) {
            BARE.findAll(t).lastOrNull()?.let { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@let
                val looksLikeYear = m.groupValues[1].length == 4 && n in 1900..2099
                if (!looksLikeYear) return Parsed(null, n, hint(t, m.range.last + 1), bareDigits = n)
            }
        }
        return null
    }

    /** "101" as season 1 episode 1, "1001" as season 10 episode 1. Null when it cannot be one. */
    fun splitBareDigits(n: Int): Pair<Int, Int>? {
        if (n < 101) return null
        val season = n / 100
        val episode = n % 100
        return if (season >= 1 && episode >= 1) season to episode else null
    }
}
