package com.velastudio.teltv.util

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

object TmdbMetadataProvider {
    private val client = OkHttpClient.Builder().build()
    private val EMPTY_META = TmdbMetadata(null, null, null, null, null)
    private val cache = ConcurrentHashMap<String, TmdbMetadata>()
    private val throttle = Semaphore(2)
    private const val API_KEY = "e6931fc8ba77a2818c3a9f931e088b3f"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    private val EPISODE_REGEX = Regex("(?i)\\s*-\\s*s\\d{1,2}e\\d{1,2}.*|\\s+s\\d{1,2}e\\d{1,2}.*|\\s+season\\s+\\d+.*|\\s+episode\\s+\\d+.*")

    fun extractQuery(rawTitle: String): String {
        val clean = MediaTitleCleaner.clean(rawTitle)
        val stripped = clean.replace(EPISODE_REGEX, "").trim()
        return if (stripped.isNotBlank()) stripped else clean
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
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://api.themoviedb.org/3/search/multi?api_key=$API_KEY&query=$encoded"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    cache[query] = EMPTY_META
                    return@withContext null
                }
                val body = response.body?.string() ?: run {
                    cache[query] = EMPTY_META
                    return@withContext null
                }
                val json = JSONObject(body)
                val results = json.optJSONArray("results") ?: run {
                    cache[query] = EMPTY_META
                    return@withContext null
                }
                if (results.length() == 0) {
                    cache[query] = EMPTY_META
                    return@withContext null
                }
                val first = results.getJSONObject(0)
                val posterPath = first.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                val backdropPath = first.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                val rating = first.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0f }
                val releaseDate = first.optString("release_date").ifBlank { first.optString("first_air_date") }
                val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                val overview = first.optString("overview").takeIf { it.isNotBlank() }

                val meta = TmdbMetadata(
                    posterUrl = posterPath?.let { "$IMAGE_BASE$it" },
                    backdropUrl = backdropPath?.let { "$IMAGE_BASE$it" },
                    rating = rating,
                    year = year,
                    overview = overview
                )
                cache[query] = meta
                meta
            } catch (e: Exception) {
                cache[query] = EMPTY_META
                null
            }
        }
        }
    }
}
