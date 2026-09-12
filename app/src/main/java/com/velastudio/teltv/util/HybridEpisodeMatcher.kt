package com.velastudio.teltv.util

import com.velastudio.teltv.data.local.VideoIndexDao
import com.velastudio.teltv.data.local.VideoIndexEntity
import java.util.Locale

object HybridEpisodeMatcher {

    data class EpisodeSignature(
        val stem: String,
        val season: Int?,
        val episode: Int
    )

    // S01E02, s1e2, 1x02
    private val S_E_REGEX = Regex("(?i)\\b(?:s|season\\s*)(\\d{1,2})[ex](\\d{1,4})\\b")
    private val ALT_S_E_REGEX = Regex("(?i)\\b(\\d{1,2})x(\\d{1,4})\\b")

    // Episode 12, Ep. 12, Folge 12, Part 2, Teil 2
    private val EP_WORD_REGEX = Regex("(?i)\\b(?:ep|episode|folge|part|teil|chapter|kapitel)[.\\s_-]*(\\d{1,4})\\b")

    // Standalone Anime numbers: "Naruto - 050", "Naruto Shippuden 145", "One Piece #1050"
    private val ANIME_NUM_REGEX = Regex("(?i)(?:-\\s*|#\\s*|\\s+)(\\d{1,4})(?:\\s*\\[|\\s*\\(|\\s*$)")

    fun parseSignature(rawTitle: String): EpisodeSignature? {
        val clean = MediaTitleCleaner.clean(rawTitle).trim()

        // 1. Season + Episode (e.g. S01E05)
        S_E_REGEX.find(clean)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null) {
                val stem = clean.substring(0, match.range.first).trim().trim('-', '_', ':')
                return EpisodeSignature(normalizeStem(stem), season, episode)
            }
        }

        ALT_S_E_REGEX.find(clean)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null) {
                val stem = clean.substring(0, match.range.first).trim().trim('-', '_', ':')
                return EpisodeSignature(normalizeStem(stem), season, episode)
            }
        }

        // 2. Word + Number (e.g. Episode 5, Part 2)
        EP_WORD_REGEX.find(clean)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull()
            if (episode != null) {
                val stem = clean.substring(0, match.range.first).trim().trim('-', '_', ':')
                return EpisodeSignature(normalizeStem(stem), null, episode)
            }
        }

        // 3. Anime standalone numbers (e.g. Naruto - 050)
        ANIME_NUM_REGEX.find(clean)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull()
            if (episode != null) {
                val stem = clean.substring(0, match.range.first).trim().trim('-', '_', ':')
                if (stem.isNotBlank()) {
                    return EpisodeSignature(normalizeStem(stem), null, episode)
                }
            }
        }

        return null
    }

    private fun normalizeStem(stem: String): String {
        return stem.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
    }

    suspend fun findNext(
        current: VideoIndexEntity,
        dao: VideoIndexDao
    ): VideoIndexEntity? {
        val currentSig = parseSignature(current.title)

        if (currentSig != null) {
            val candidates = dao.getAllForChat(current.chatId)

            // Step 1: Look for same show stem + next episode (e.g. Naruto 50 -> 51)
            val nextInSeries = candidates.firstOrNull { candidate ->
                val candSig = parseSignature(candidate.title)
                candSig != null &&
                candSig.stem == currentSig.stem &&
                candSig.season == currentSig.season &&
                candSig.episode == currentSig.episode + 1
            }
            if (nextInSeries != null) return nextInSeries

            // Step 2: Next Season Premiere (e.g. S01E10 -> S02E01)
            if (currentSig.season != null) {
                val nextSeasonPremiere = candidates.firstOrNull { candidate ->
                    val candSig = parseSignature(candidate.title)
                    candSig != null &&
                    candSig.stem == currentSig.stem &&
                    candSig.season == currentSig.season + 1 &&
                    (candSig.episode == 1 || candSig.episode == 0)
                }
                if (nextSeasonPremiere != null) return nextSeasonPremiere
            }
        }

        // Step 3: Timeline Fallback (The Bridge)
        // - Naruto 220 -> Naruto Shippuden 001
        // - Wrestling Part 1 -> Part 2
        // - Custom dates / event titles
        return dao.getNextInChannel(current.chatId, current.messageId)
    }
}
