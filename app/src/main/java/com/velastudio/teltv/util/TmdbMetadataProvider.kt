package com.velastudio.teltv.util

import com.velastudio.teltv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class TmdbMetadata(
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Float?,
    val year: String?,
    val overview: String?,
    val title: String? = null,
    val episodeName: String? = null,
    val seasonName: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null
) {
    fun episodeLabel(): String? = episodeNumber?.let { episode ->
        seasonNumber?.let { "S${it.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}" }
            ?: "Episode $episode"
    }
}

/** Optional metadata only: playback and the Room library never depend on TMDB. */
object TmdbMetadataProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    // A lock also coalesces requests for the same show/season during fast scrolling.
    private val lock = Mutex()
    private data class Cached(val value: JSONObject?, val expiresAt: Long)
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > 200
    }
    val isConfigured: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

    fun extractQuery(rawTitle: String): String = OnlineSubtitleProvider.identifyMedia(rawTitle).title

    internal fun titleScore(expected: String, candidate: String): Int {
        fun words(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }.toSet()
        val left = words(expected)
        val right = words(candidate)
        if (left.isEmpty() || right.isEmpty()) return 0
        return 100 * left.intersect(right).size / left.union(right).size
    }

    private fun request(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
        val url = "https://api.themoviedb.org/3/$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", BuildConfig.TMDB_API_KEY)
            .addQueryParameter("language", "en-US")
        query.forEach { (name, value) -> url.addQueryParameter(name, value) }
        val key = path + query.toSortedMap().toString()
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAt > now }?.let { return it.value }
        // HTTP failures are not cached forever, and API keys/URLs are never logged.
        val json = client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.let(::JSONObject)
        }
        cache[key] = Cached(json, now + if (json == null) 60_000L else 3_600_000L)
        return json
    }

    internal fun selectResult(identity: SubtitleMediaIdentity, response: JSONObject): JSONObject? {
        val results = response.optJSONArray("results") ?: return null
        val ranked = (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            val score = maxOf(titleScore(identity.title, item.optString("title", item.optString("name"))),
                titleScore(identity.title, item.optString("original_title", item.optString("original_name"))))
            val year = item.optString("release_date", item.optString("first_air_date")).take(4).toIntOrNull()
            if (score < 70 || (identity.year != null && year != null && identity.year != year)) null
            else item to (score + if (identity.year != null && identity.year == year) 30 else 0)
        }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (ranked.drop(1).any { it.second == best.second && it.first.optInt("id") != best.first.optInt("id") }) return null
        return best.first
    }

    private fun text(json: JSONObject, key: String): String? = json.optString(key)
        .takeIf { it.isNotBlank() && it != "null" }
    private fun image(json: JSONObject, key: String): String? = text(json, key)
        ?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w500$it" }

    suspend fun getMetadata(rawTitle: String): TmdbMetadata? {
        if (!isConfigured) return null
        val parsed = OnlineSubtitleProvider.identifyMedia(rawTitle)
        // Preserve years that MediaTitleCleaner removes from parentheses.
        val identity = parsed.copy(year = Regex("\\b(19\\d{2}|20\\d{2})\\b")
            .find(rawTitle)?.value?.toIntOrNull() ?: parsed.year)
        if (identity.title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            delay(120)
            lock.withLock {
                try {
                    val type = if (identity.isSeries) "tv" else "movie"
                    val params = mutableMapOf("query" to identity.title, "include_adult" to "false")
                    identity.year?.let { params[if (identity.isSeries) "first_air_date_year" else "primary_release_year"] = it.toString() }
                    val result = request("search/$type", params)?.let { selectResult(identity, it) }
                        ?: return@withLock null
                    val id = result.optInt("id").takeIf { it > 0 } ?: return@withLock null
                    val seasonNumber = identity.season
                    val episodeNumber = identity.episode
                    val season = if (identity.isSeries && seasonNumber != null)
                        request("tv/$id/season/$seasonNumber") else null
                    val episodes = season?.optJSONArray("episodes")
                    val episode = episodes?.let { array -> (0 until array.length())
                        .mapNotNull { array.optJSONObject(it) }
                        .firstOrNull { it.optInt("episode_number", -1) == episodeNumber } }
                    TmdbMetadata(
                        posterUrl = season?.let { image(it, "poster_path") } ?: image(result, "poster_path"),
                        backdropUrl = episode?.let { image(it, "still_path") } ?: image(result, "backdrop_path"),
                        rating = result.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0 },
                        year = text(result, if (identity.isSeries) "first_air_date" else "release_date")?.take(4),
                        overview = episode?.let { text(it, "overview") } ?: text(result, "overview"),
                        title = text(result, if (identity.isSeries) "name" else "title"),
                        episodeName = episode?.let { text(it, "name") },
                        seasonName = season?.let { text(it, "name") },
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null // Keep local titles/thumbnails on offline, malformed, or unauthorized responses.
                }
            }
        }
    }
}
