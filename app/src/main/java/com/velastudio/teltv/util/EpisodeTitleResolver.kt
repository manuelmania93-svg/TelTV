package com.velastudio.teltv.util

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Episode names select an identity before any subtitle bucket is requested. No show guessing. */
object EpisodeTitleResolver {
    data class Episode(val showId: String, val season: Int, val episode: Int, val titles: List<String>)
    data class Resolution(val matches: List<Episode>) {
        val unique: Episode? get() = matches.singleOrNull()
        val ambiguous: Boolean get() = matches.size > 1
    }

    private val technical = Regex("""(?i)\b(?:\d{3,4}p|WEB[ ._-]*DL|WEBRip|HDTV|BluRay|BDRip|AAC|H[ ._-]?26[45]|x26[45]|HEVC|GER|ENG|SRT)\b.*""")
    fun normalize(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace("ß", "ss")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    fun titleHint(raw: String, showName: String?): String {
        val hint = CaptionEpisodeParser.parse(raw, showName)?.titleHint
            ?: CaptionEpisodeParser.stripExtension(raw)
        var cleaned = hint.replace(technical, "")
        showName?.let { ShowNameMatcher.splitMixed(it).forEach { show ->
            cleaned = cleaned.replace(Regex(Regex.escape(show), RegexOption.IGNORE_CASE), " ")
        } }
        return cleaned.trim(' ', '-', '_', '.', ':', '\u2026')
    }

    /** Accept exact normalized titles only. Common short/generic titles cannot override numbers. */
    fun resolve(hint: String, episodes: List<Episode>): Resolution {
        val target = normalize(hint)
        if (target.length < 7 || target.split(' ').size < 2 ||
            Regex("(?i)^(?:episode|folge|part|teil|chapter|kapitel) \\d+$").matches(target)) {
            return Resolution(emptyList())
        }
        return Resolution(episodes.filter { entry -> entry.titles.any { normalize(it) == target } }
            .distinctBy { Triple(it.showId, it.season, it.episode) })
    }

    /** Both the documented Stremio `title` and Cinemeta's `name` are seen in real responses. */
    fun parse(showId: String, metaJson: String?): List<Episode> = runCatching {
        val videos = metaJson?.let { JSONObject(it).optJSONObject("meta")?.optJSONArray("videos") }
            ?: return@runCatching emptyList()
        (0 until minOf(videos.length(), 5000)).mapNotNull { i ->
            val v = videos.optJSONObject(i) ?: return@mapNotNull null
            val s = v.optInt("season", 0)
            val e = v.optInt("episode", v.optInt("number", 0))
            val titles = listOf(v.optString("name"), v.optString("title"))
                .filter { it.isNotBlank() && it != "null" }.distinct()
            if (s < 1 || e < 1 || titles.isEmpty()) null else Episode(showId, s, e, titles)
        }
    }.getOrDefault(emptyList())

    fun merge(episodes: List<Episode>): List<Episode> = episodes
        .groupBy { Triple(it.showId, it.season, it.episode) }.values.map { group ->
            group.first().copy(titles = group.flatMap { it.titles }.distinct())
        }
}
