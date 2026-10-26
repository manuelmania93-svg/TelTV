package com.velastudio.teltv.util

import com.velastudio.teltv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class TmdbMetadata(
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Float?,
    val year: String?,
    val overview: String?
)

/** One search hit from Cinemeta or TMDB, normalized for scoring. */
internal data class MetaCandidate(
    val name: String,
    val year: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Float?,
    val overview: String?,
    val isSeries: Boolean
)

object TmdbMetadataProvider {
    private val client = OkHttpClient.Builder().build()
    private val EMPTY_META = TmdbMetadata(null, null, null, null, null)
    private val cache = ConcurrentHashMap<String, TmdbMetadata>()
    private val throttle = Semaphore(2)
    // Read from local.properties (TMDB_API_KEY) at build time; see README.
    private val API_KEY: String get() = BuildConfig.TMDB_API_KEY
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"
    private const val MAX_CANDIDATES_PER_SOURCE = 5
    internal const val MIN_MATCH_SCORE = 0.55

    private val EPISODE_REGEX = Regex("(?i)\\s*-\\s*s\\d{1,2}e\\d{1,2}.*|\\s+s\\d{1,2}e\\d{1,2}.*|\\s+season\\s+\\d+.*|\\s+episode\\s+\\d+.*")
    // Leading episode markers ("S06E03: ...", "3x05 ...") sit at the start of many captions and
    // used to become part of the search query, which made unrelated first hits ("Die Sendung mit
    // der Maus" for an X-Factor episode title) win the poster slot.
    private val LEADING_EPISODE_REGEX = Regex("(?i)^s\\d{1,2}e\\d{1,2}\\s*[:\\-.]?\\s*|^\\d{1,2}x\\d{1,2}\\s*[:\\-.]?\\s*")
    private val QUERY_YEAR_REGEX = Regex("\\b(?:19|20)\\d{2}\\b")
    private val YEAR_NUMBER_REGEX = Regex("\\d{4}")

    fun extractQuery(rawTitle: String): String {
        val clean = MediaTitleCleaner.clean(rawTitle)
        val noLeading = clean.replace(LEADING_EPISODE_REGEX, "")
        val stripped = noLeading.replace(EPISODE_REGEX, "").trim()
        return if (stripped.isNotBlank()) stripped else noLeading.trim().ifBlank { clean }
    }

    /**
     * True when the query's 4-digit number agrees with the candidate's year info, false when it
     * clearly disagrees, null when either side has no usable year. Candidate years come as
     * "2023", "1971–" or "2015–2019"; a range matches when the query year lies inside it.
     */
    internal fun yearAgreement(query: String, candidateYear: String?): Boolean? {
        val qYear = QUERY_YEAR_REGEX.find(query)?.value?.toIntOrNull() ?: return null
        if (candidateYear.isNullOrBlank()) return null
        val years = YEAR_NUMBER_REGEX.findAll(candidateYear).mapNotNull { it.value.toIntOrNull() }.toList()
        if (years.isEmpty()) return null
        val start = years.first()
        val end = years.getOrNull(1)
        return if (end != null) qYear in start..end else if (candidateYear.contains('–') || candidateYear.contains('-')) qYear >= start else qYear == start
    }

    /**
     * Score how well a search hit matches the query: name similarity/containment dominates,
     * a matching year adds confidence, a conflicting year subtracts it. Pure logic.
     */
    internal fun scoreCandidate(query: String, candidate: MetaCandidate): Double {
        val nq = ShowNameMatcher.normalize(query)
        val nc = ShowNameMatcher.normalize(candidate.name)
        if (nq.isEmpty() || nc.isEmpty()) return 0.0
        var score = ShowNameMatcher.similarity(candidate.name, query)
        if (nq.contains(nc) || nc.contains(nq)) {
            // Containment, scaled by how much of the longer name is covered, so a short generic
            // hit ("X Factor") scores below a near-complete one ("X-Factor: Das Unfassbare kehrt zurück").
            val coverage = minOf(nq.length, nc.length).toDouble() / maxOf(nq.length, nc.length)
            score = maxOf(score, 0.6 + 0.3 * coverage)
        }
        when (yearAgreement(query, candidate.year)) {
            true -> score += 0.15
            false -> score -= 0.3
            null -> Unit
        }
        return score
    }

    /** Best-scoring candidate at or above [minScore], or null when nothing is a trustworthy match. */
    internal fun pickBestCandidate(query: String, candidates: List<MetaCandidate>, minScore: Double = MIN_MATCH_SCORE): MetaCandidate? {
        var best: MetaCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = scoreCandidate(query, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return if (best != null && bestScore >= minScore) best else null
    }

    private fun fetchCinemetaCandidates(query: String): List<MetaCandidate> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "https://v3-cinemeta.strem.io/catalog/series/top/search=" + encoded + ".json" to true,
            "https://v3-cinemeta.strem.io/catalog/movie/top/search=" + encoded + ".json" to false
        )
        val out = ArrayList<MetaCandidate>()
        for ((url, isSeries) in urls) {
            try {
                val res = client.newCall(Request.Builder().url(url).build()).execute()
                if (!res.isSuccessful) continue
                val body = res.body?.string() ?: continue
                val metas = JSONObject(body).optJSONArray("metas") ?: continue
                val limit = minOf(metas.length(), MAX_CANDIDATES_PER_SOURCE)
                for (i in 0 until limit) {
                    val meta = metas.getJSONObject(i)
                    val poster = meta.optString("poster").takeIf { it.isNotBlank() && it != "null" } ?: continue
                    out += MetaCandidate(
                        name = meta.optString("name").takeIf { it.isNotBlank() } ?: continue,
                        year = meta.optString("year").takeIf { it.isNotBlank() && it != "null" },
                        posterUrl = poster,
                        backdropUrl = poster,
                        rating = null,
                        overview = meta.optString("description").takeIf { it.isNotBlank() && it != "null" },
                        isSeries = isSeries
                    )
                }
            } catch (e: Exception) {
                // One failing catalog must not kill the other.
            }
        }
        return out
    }

    private fun fetchTmdbCandidates(query: String): List<MetaCandidate> {
        if (API_KEY.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$API_KEY&query=$encoded"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            val out = ArrayList<MetaCandidate>()
            val limit = minOf(results.length(), MAX_CANDIDATES_PER_SOURCE)
            for (i in 0 until limit) {
                val first = results.getJSONObject(i)
                val mediaType = first.optString("media_type")
                if (mediaType != "movie" && mediaType != "tv") continue
                val name = first.optString("title").ifBlank { first.optString("name") }
                if (name.isBlank()) continue
                val posterPath = first.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val backdropPath = first.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                val rating = first.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f }
                val releaseDate = first.optString("release_date").ifBlank { first.optString("first_air_date") }
                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                out += MetaCandidate(
                    name = name,
                    year = year,
                    posterUrl = "$IMAGE_BASE$posterPath",
                    backdropUrl = backdropPath?.let { "$IMAGE_BASE$it" },
                    rating = rating,
                    overview = first.optString("overview").takeIf { it.isNotBlank() },
                    isSeries = mediaType == "tv"
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getMetadata(rawTitle: String): TmdbMetadata? {
        val query = extractQuery(rawTitle)
        if (query.isBlank()) return null
        val cached = cache[query]
        if (cached != null) {
            return if (cached === EMPTY_META) null else cached
        }

        return withContext(Dispatchers.IO) {
        delay(120) // Debounce so fast scrolling skips network
        throttle.withPermit {
            // Cinemeta first (no API key needed), TMDB as fallback. Both are scored: the best
            // hit must actually resemble the query, otherwise we keep the Telegram thumbnail
            // instead of showing unrelated artwork.
            val cinemeta = pickBestCandidate(query, fetchCinemetaCandidates(query))
            val chosen = cinemeta ?: pickBestCandidate(query, fetchTmdbCandidates(query))
            val meta = chosen?.let {
                TmdbMetadata(
                    posterUrl = it.posterUrl,
                    backdropUrl = it.backdropUrl ?: it.posterUrl,
                    rating = it.rating,
                    year = it.year,
                    overview = it.overview
                )
            }
            cache[query] = meta ?: EMPTY_META
            meta
        }
        }
    }
}
