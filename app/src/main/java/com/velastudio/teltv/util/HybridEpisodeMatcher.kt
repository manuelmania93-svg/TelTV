package com.velastudio.teltv.util

import com.velastudio.teltv.data.local.VideoIndexDao
import com.velastudio.teltv.data.local.VideoIndexEntity
import com.velastudio.teltv.util.matcher.UniversalMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val explicitlyMarked = match.value.trimStart().let { it.startsWith("-") || it.startsWith("#") }
            val looksLikeYear = episode in 1900..2099 && !explicitlyMarked
            if (episode != null && !looksLikeYear) {
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

    /** Parses each (mediaId, title) at most once per cache instance. Not thread safe: use one per call. */
    class SignatureCache {
        private val map = HashMap<String, EpisodeSignature?>()
        fun of(e: VideoIndexEntity): EpisodeSignature? {
            val key = e.mediaId + "\u0000" + e.title
            if (map.containsKey(key)) return map[key]
            return parseSignature(e.title).also { map[key] = it }
        }
    }

    /** Next and previous episode for one item, from a single channel snapshot. */
    data class Adjacent(val next: VideoIndexEntity?, val previous: VideoIndexEntity?)

    /**
     * Pure steps 1, 2 and 2b (series numbering, season rollover, universal matcher) over an
     * in-memory snapshot. Returns null when only the timeline fallback is left.
     */
    fun nextFromSnapshot(
        current: VideoIndexEntity,
        all: List<VideoIndexEntity>,
        cache: SignatureCache = SignatureCache()
    ): VideoIndexEntity? {
        val currentSig = cache.of(current)
        if (currentSig != null) {
            all.firstOrNull { c ->
                val s = cache.of(c)
                s != null && s.stem == currentSig.stem && s.season == currentSig.season &&
                    s.episode == currentSig.episode + 1
            }?.let { return it }
            if (currentSig.season != null) {
                all.firstOrNull { c ->
                    val s = cache.of(c)
                    s != null && s.stem == currentSig.stem && s.season == currentSig.season + 1 &&
                        (s.episode == 1 || s.episode == 0)
                }?.let { return it }
            }
        }
        return universal(current, all) { cur, lib -> UniversalMatcher.findNext(cur, lib) }
    }

    fun previousFromSnapshot(
        current: VideoIndexEntity,
        all: List<VideoIndexEntity>,
        cache: SignatureCache = SignatureCache()
    ): VideoIndexEntity? {
        val currentSig = cache.of(current)
        if (currentSig != null) {
            if (currentSig.episode > 1) {
                all.firstOrNull { c ->
                    val s = cache.of(c)
                    s != null && s.stem == currentSig.stem && s.season == currentSig.season &&
                        s.episode == currentSig.episode - 1
                }?.let { return it }
            }
            if (currentSig.season != null && currentSig.season > 1) {
                all.filter { c ->
                    val s = cache.of(c)
                    s != null && s.stem == currentSig.stem && s.season == currentSig.season - 1
                }.maxByOrNull { cache.of(it)?.episode ?: 0 }?.let { return it }
            }
        }
        return universal(current, all) { cur, lib -> UniversalMatcher.findPrevious(cur, lib) }
    }

    /** One channel read and one parse per title for both directions. Safe to call off the main thread. */
    suspend fun findAdjacent(current: VideoIndexEntity, dao: VideoIndexDao): Adjacent =
        withContext(Dispatchers.Default) {
            val all = dao.getAllForChat(current.chatId)
            val cache = SignatureCache()
            Adjacent(
                next = nextFromSnapshot(current, all, cache) ?: dao.getNextInChannel(current.chatId, current.messageId),
                previous = previousFromSnapshot(current, all, cache)
                    ?: dao.getPreviousInChannel(current.chatId, current.messageId)
            )
        }

    suspend fun findNext(current: VideoIndexEntity, dao: VideoIndexDao): VideoIndexEntity? =
        withContext(Dispatchers.Default) {
            nextFromSnapshot(current, dao.getAllForChat(current.chatId))
                ?: dao.getNextInChannel(current.chatId, current.messageId)
        }

    suspend fun findPrevious(current: VideoIndexEntity, dao: VideoIndexDao): VideoIndexEntity? =
        withContext(Dispatchers.Default) {
            previousFromSnapshot(current, dao.getAllForChat(current.chatId))
                ?: dao.getPreviousInChannel(current.chatId, current.messageId)
        }

    private fun universal(
        current: VideoIndexEntity,
        all: List<VideoIndexEntity>,
        step: (UniversalMatcher.Entry, List<UniversalMatcher.Entry>) -> UniversalMatcher.Entry?
    ): VideoIndexEntity? {
        val hit = step(
            UniversalMatcher.Entry(current.mediaId, current.title),
            all.map { UniversalMatcher.Entry(it.mediaId, it.title) }
        ) ?: return null
        return all.firstOrNull { it.mediaId == hit.id }
    }
}
