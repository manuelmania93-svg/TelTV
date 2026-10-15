package com.velastudio.teltv.util

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Per-search state: a failed optional provider never replaces the keyless results. */
class SubDlSource(private val apiKey: String) {
    var notice: String? = null
        private set
    private var stopped = false
    private val cache = mutableMapOf<String, List<OnlineSubtitle>>()

    suspend fun fetch(type: String, videoId: String, get: suspend (HttpUrl) -> Pair<Int, String?>): List<OnlineSubtitle> {
        if (apiKey.isBlank() || stopped) return emptyList()
        return cache.getOrPutSuspend("$type:$videoId") {
            try {
                val (status, body) = get(requestUrl(apiKey, type, videoId))
                val error = errorNotice(status, body)
                if (error != null) { notice = error; stopped = true; emptyList() }
                else parse(body.orEmpty(), type, videoId)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // Never log an exception containing a request URL or the API key.
                notice = "SubDL is unavailable. Other subtitle sources are still active."
                stopped = true
                emptyList()
            }
        }
    }

    private suspend fun <K, V> MutableMap<K, V>.getOrPutSuspend(key: K, block: suspend () -> V): V {
        return this[key] ?: block().also { this[key] = it }
    }

    companion object {
        fun requestUrl(key: String, type: String, videoId: String): HttpUrl {
            require(type == "movie" || type == "series")
            require(Regex("tt[0-9]+(?::[0-9]+:[0-9]+)?").matches(videoId))
            val parts = videoId.split(':')
            require(type != "series" || parts.size == 3)
            return "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("imdb_id", parts[0])
                .addQueryParameter("type", if (type == "series") "tv" else "movie")
                .addQueryParameter("languages", "DE")
                .addQueryParameter("subs_per_page", "30")
                .addQueryParameter("unpack", "1")
                .addQueryParameter("releases", "1")
                .addQueryParameter("comment", "1")
                .addQueryParameter("client", "custom_integration")
                .apply { if (type == "series") {
                    addQueryParameter("season_number", parts[1]); addQueryParameter("episode_number", parts[2])
                } }.build()
        }

        fun errorNotice(status: Int, body: String?): String? {
            val json = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
            val error = json?.optString("error").orEmpty().lowercase(Locale.ROOT)
            return when {
                status == 429 || error.contains("limit") || error.contains("quota") ->
                    "SubDL request/download limit reached. Try later; other subtitle sources are still active."
                status == 401 || status == 403 || error.contains("key") || error.contains("auth") ->
                    "SubDL key was rejected. Check it in Settings; other subtitle sources are still active."
                status !in 200..299 -> "SubDL is unavailable. Other subtitle sources are still active."
                json == null -> "SubDL returned an invalid response. Other subtitle sources are still active."
                else -> null // status:false with no match is an empty result, not a fatal error.
            }
        }

        /** Only accept German SRT files; packs require a specific unpacked episode file. */
        fun parse(body: String, type: String, videoId: String): List<OnlineSubtitle> {
            if (body.length > SubtitleAddons.MAX_RESPONSE_BYTES) return emptyList()
            val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            if (!json.optBoolean("status")) return emptyList()
            val items = json.optJSONArray("subtitles") ?: return emptyList()
            val parts = videoId.split(':')
            val season = parts.getOrNull(1)?.toIntOrNull()
            val episode = parts.getOrNull(2)?.toIntOrNull()
            val out = mutableListOf<OnlineSubtitle>()
            for (i in 0 until minOf(items.length(), 30)) {
                val item = items.optJSONObject(i) ?: continue
                val unpack = item.optJSONArray("unpack_files")
                val entries = if (unpack != null && unpack.length() > 0)
                    (0 until minOf(unpack.length(), 100)).mapNotNull { unpack.optJSONObject(it) }
                else listOf(item)
                for (file in entries) {
                    val language = file.optString("language").ifBlank { item.optString("language") }
                    if (SubtitleLanguages.normalize(language) != "de") continue
                    if (type == "series") {
                        if (file.optInt("season", item.optInt("season", -1)) != season ||
                            file.optInt("episode", item.optInt("episode", -1)) != episode) continue
                        if (file === item && (item.optBoolean("full_season") ||
                            item.optInt("episode_end", episode ?: -1) != episode ||
                            item.optInt("episode_from", episode ?: -1) != episode)) continue
                    }
                    val name = file.optString("name")
                    val isZip = file === item && file.optString("url").endsWith(".zip")
                    if (!isZip && !name.endsWith(".srt", true) && file.optString("format") != "srt") continue
                    val url = downloadUrl(file.optString("url")) ?: continue
                    val releases = item.optJSONArray("releases")
                    val release = file.optString("release_name").ifBlank {
                        item.optString("release_name").ifBlank {
                            releases?.let { a -> (0 until minOf(a.length(), 20)).joinToString(" ") { a.optString(it) } }.orEmpty()
                        }
                    }
                    val forced = item.optBoolean("forced") || file.optBoolean("forced") ||
                        Regex("(?i)\\bforced\\b|\\bforeign[ ._-]*parts?[ ._-]*only\\b")
                            .containsMatchIn("$name $release ${item.optString("comment")}")
                    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                        .take(16).joinToString("") { "%02x".format(it) }
                    out.add(OnlineSubtitle("subdl-$digest", "de", SubtitleLanguages.displayName("de"),
                        url, name.ifBlank { "de.srt" }, forced, release))
                }
            }
            return SubtitleAddons.merge(out)
        }

        fun downloadUrl(value: String): String? {
            val url = runCatching { "https://dl.subdl.com".toHttpUrl().resolve(value) }.getOrNull() ?: return null
            // No credentials or arbitrary hosts/redirect destinations supplied by the API.
            if (url.scheme != "https" || url.host != "dl.subdl.com" ||
                url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null ||
                url.fragment != null || !url.encodedPath.startsWith("/subtitle/")) return null
            return url.toString()
        }
    }
}
