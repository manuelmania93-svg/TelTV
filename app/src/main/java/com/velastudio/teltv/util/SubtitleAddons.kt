package com.velastudio.teltv.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** A Stremio subtitle endpoint, including any non-secret configuration path. */
data class SubtitleAddon(val key: String, val baseUrl: String)

/** Bounded fan-out. No accounts, keys, HTML scraping or new service dependencies. */
object SubtitleAddons {
    const val MAX_ADDONS = 4
    const val MAX_RESULTS = 400
    const val MAX_RESPONSE_BYTES = 512 * 1024

    fun defaults(preferredLanguage: String): List<SubtitleAddon> {
        val languages = listOf(SubtitleLanguages.normalize(preferredLanguage), "en").distinct()
        val config = JSONObject().put("languages", org.json.JSONArray(languages))
            .put("maxSubtitles", 20).toString()
        return listOf(
            SubtitleAddon("opensubtitles-v3", "https://opensubtitles-v3.strem.io"),
            SubtitleAddon("subsense", "https://subsense.nepiraw.com/" +
                URLEncoder.encode(config, "UTF-8").replace("+", "%20"))
        )
    }

    fun requestUrl(addon: SubtitleAddon, type: String, videoId: String): String {
        require(type == "series" || type == "movie")
        require(Regex("tt[0-9]+(?::[0-9]+:[0-9]+)?").matches(videoId))
        val base = addon.baseUrl.trim().removeSuffix("/manifest.json").trimEnd('/')
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(Regex("[a-z0-9-]{1,40}").matches(addon.key))
        return "$base/subtitles/$type/$videoId.json"
    }

    /** One failed, malformed or empty addon does not discard any other addon's results. */
    suspend fun fetch(
        addons: List<SubtitleAddon>, type: String, videoId: String,
        get: suspend (String) -> String?
    ): List<OnlineSubtitle> = supervisorScope {
        val batches = addons.distinctBy { it.baseUrl }.take(MAX_ADDONS).map { addon ->
            async {
                try {
                    val body = get(requestUrl(addon, type, videoId))
                    if (body == null) emptyList() else parse(addon, body)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll()
        merge(batches.flatten())
    }

    fun parse(addon: SubtitleAddon, body: String): List<OnlineSubtitle> {
        if (body.length > MAX_RESPONSE_BYTES) return emptyList()
        val array = runCatching { JSONObject(body).optJSONArray("subtitles") }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<OnlineSubtitle>()
        for (i in 0 until minOf(array.length(), MAX_RESULTS)) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) continue
            val lang = item.optString("lang").lowercase(java.util.Locale.ROOT)
            if (lang.isBlank()) continue
            val id = item.optString("id").ifBlank { digest(url) }
            val fileName = listOf("subtitleFileName", "fileName", "filename", "name", "title")
                .firstNotNullOfOrNull { item.optString(it).takeIf(String::isNotBlank) } ?: "$lang.srt"
            val forced = item.optBoolean("forced") || item.optBoolean("isForced") ||
                item.optBoolean("foreign_parts_only") || item.optBoolean("foreignPartsOnly") ||
                item.optString("type").equals("forced", ignoreCase = true) ||
                Regex("""(?i)\bforced\b|\bforeign[ ._-]*parts?[ ._-]*only\b""")
                    .containsMatchIn("$fileName $id")
            // Keep v3 ids for saved timing compatibility. Other addon ids may be recycled indexes:
            // stable URL identity prevents them sharing cache files or offsets with different subs.
            val stableId = if (addon.key == "opensubtitles-v3") id else "${addon.key}-${digest(url)}"
            val release = item.optString("movieReleaseName").ifBlank {
                item.optString("releaseName").ifBlank { fileName }
            }
            out.add(OnlineSubtitle(stableId, lang, SubtitleLanguages.displayName(lang),
                url, fileName, forced, release))
        }
        return out
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    /** Only deduplicate proven download identity, never titles or colliding addon-local ids. */
    fun downloadIdentity(sub: OnlineSubtitle): String {
        val uri = runCatching { URI(sub.url) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val path = uri?.path.orEmpty()
        val file = when {
            host == "subs5.strem.io" || host == "dl.opensubtitles.org" ->
                Regex("/file/([0-9]+)").find(path)?.groupValues?.get(1)
            host == "sub.wyzie.io" -> Regex("/id/([0-9]+)").find(path)?.groupValues?.get(1)
            else -> null
        }
        val target = file?.let { "opensubtitles-file:$it" } ?: sub.url
        return "${SubtitleLanguages.normalize(sub.lang)}:${sub.isForced}:$target"
    }

    fun merge(subtitles: List<OnlineSubtitle>): List<OnlineSubtitle> =
        subtitles.distinctBy(::downloadIdentity).take(MAX_RESULTS)
}
