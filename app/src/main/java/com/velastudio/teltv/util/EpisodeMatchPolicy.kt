package com.velastudio.teltv.util

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Title evidence is independent of the provider bucket number. A number alone is never proof. */
data class EpisodeEvidence(val titleMatch: Boolean, val numberConflict: Boolean, val rank: Int, val explanation: String)

object EpisodeMatchPolicy {
    private val signature = Regex("""(?i)s(\d{1,2})[ ._-]*e(\d{1,3})|\b(\d{1,2})x(\d{1,3})\b""")
    private val technical = Regex("""(?i)\b(?:\d{3,4}p|WEB[ ._-]*DL|WEBRip|HDTV|BluRay|BDRip|DSNP|DDP|AAC|H[ ._-]?26[45]|x26[45]|HEVC|NF|GER|ENG|SRT)\b.*""")
    private fun words(s: String): List<String> = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\p{L}]+"), " ").trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    fun neighbours(episode: Int): List<Int> = listOf(episode - 1, episode + 1, episode - 2, episode + 2).filter { it > 0 }

    fun expectedTitles(rawTitle: String, metaJson: String?, season: Int, episode: Int): List<String> {
        val out = mutableListOf<String>()
        signature.find(rawTitle)?.let { hit ->
            val suffix = rawTitle.substring(hit.range.last + 1).replace(technical, "").trim(' ', '-', '_', '.', ':')
            if (words(suffix).size >= 2) out.add(suffix)
        }
        runCatching {
            val videos = metaJson?.let { JSONObject(it).optJSONObject("meta")?.optJSONArray("videos") }
            if (videos != null) for (i in 0 until videos.length()) {
                val v = videos.getJSONObject(i)
                if (v.optInt("season") == season && v.optInt("episode", v.optInt("number")) == episode) {
                    v.optString("name").takeIf { words(it).size >= 2 }?.let(out::add)
                }
            }
        }
        return out.distinct()
    }

    fun assess(sub: OnlineSubtitle, titles: List<String>, season: Int, episode: Int): EpisodeEvidence {
        val release = "${sub.releaseName} ${sub.fileName}"
        val normalized = words(release).joinToString(" ")
        // Exact normalized title phrase, not generic token overlap such as 'The Simpsons'.
        val titleMatch = titles.any { title ->
            val phrase = words(title).joinToString(" ")
            phrase.length >= 7 && " $normalized ".contains(" $phrase ")
        }
        val hit = signature.find(release)
        val actualSeason = hit?.let { (it.groups[1] ?: it.groups[3])?.value?.toIntOrNull() }
        val actualEpisode = hit?.let { (it.groups[2] ?: it.groups[4])?.value?.toIntOrNull() }
        val conflict = hit != null && (actualSeason != season || actualEpisode != episode)
        return EpisodeEvidence(titleMatch, conflict,
            if (titleMatch) 100 else if (conflict) -100 else 0,
            when {
                titleMatch && conflict -> "Episode title matches; release numbering differs"
                titleMatch -> "Episode title matches release"
                conflict -> "Release episode number conflicts"
                else -> "Episode identity not verified"
            })
    }
}
