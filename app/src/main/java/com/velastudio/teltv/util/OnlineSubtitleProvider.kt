package com.velastudio.teltv.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class OnlineSubtitle(
    val id: String,
    val lang: String,
    val langDisplay: String,
    val url: String,
    val fileName: String,
    val isForced: Boolean = false,
    val releaseName: String = ""
)

data class SubtitleMediaIdentity(
    val title: String,
    val isSeries: Boolean,
    val season: Int?,
    val episode: Int?,
    val year: Int?
) {
    fun displayLabel(): String = when {
        isSeries && season != null && episode != null ->
            "Detected series: $title - S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
        isSeries && episode != null -> "Detected series: $title - Episode $episode"
        isSeries -> "Detected series: $title (episode not detected)"
        year != null -> "Detected movie: $title ($year)"
        else -> "Detected movie: $title"
    }
}

object OnlineSubtitleProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val SEASON_EPISODE_REGEX = Regex("(?i)s(\\d{1,2})e(\\d{1,2})|(\\d{1,2})x(\\d{1,2})")
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val EPISODE_LABEL_REGEX = Regex("(?i)\\b(?:ep|episode|folge|part|teil|chapter|kapitel)[.\\s_-]*\\d{1,4}\\b")
    private val TRAILING_EPISODE_NUMBER_REGEX = Regex("(?i)(?:\\s*[-#]\\s*|\\s+)\\d{1,4}\\s*$")

    fun identifyMedia(rawTitle: String): SubtitleMediaIdentity {
        val cleanTitle = MediaTitleCleaner.clean(rawTitle)
        val signature = HybridEpisodeMatcher.parseSignature(rawTitle)
        val seasonMatch = SEASON_EPISODE_REGEX.find(cleanTitle)
        val year = YEAR_REGEX.find(cleanTitle)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = signature != null || seasonMatch != null

        val season = signature?.season ?: seasonMatch?.let {
            it.groups[1]?.value?.toIntOrNull() ?: it.groups[3]?.value?.toIntOrNull()
        }
        val episode = signature?.episode ?: seasonMatch?.let {
            it.groups[2]?.value?.toIntOrNull() ?: it.groups[4]?.value?.toIntOrNull()
        }

        val title = when {
            seasonMatch != null -> cleanTitle.substring(0, seasonMatch.range.first)
            signature != null -> {
                val episodeLabel = EPISODE_LABEL_REGEX.find(cleanTitle)
                when {
                    episodeLabel != null -> cleanTitle.substring(0, episodeLabel.range.first)
                    else -> TRAILING_EPISODE_NUMBER_REGEX.find(cleanTitle)?.let {
                        cleanTitle.substring(0, it.range.first)
                    } ?: cleanTitle
                }
            }
            year != null -> cleanTitle.substringBefore(year.toString())
            else -> cleanTitle
        }.trim().trim('-', '_', ':', '.').ifBlank { cleanTitle }
            .replace(Regex("\\s+"), " ").trim()
        return SubtitleMediaIdentity(title, isSeries, season, episode, year)
    }

    private val LANG_MAP = mapOf(
        "eng" to "English", "spa" to "Spanish", "fre" to "French", "fra" to "French",
        "ger" to "German", "deu" to "German", "ita" to "Italian", "por" to "Portuguese",
        "pob" to "Portuguese (Brazil)", "rus" to "Russian", "ara" to "Arabic",
        "jpn" to "Japanese", "chi" to "Chinese", "zho" to "Chinese", "kor" to "Korean",
        "hin" to "Hindi", "tur" to "Turkish", "pol" to "Polish", "dut" to "Dutch",
        "nld" to "Dutch", "swe" to "Swedish", "nor" to "Norwegian", "dan" to "Danish",
        "fin" to "Finnish", "gre" to "Greek", "ell" to "Greek", "heb" to "Hebrew",
        "ind" to "Indonesian", "vie" to "Vietnamese", "tha" to "Thai", "tgl" to "Tagalog",
        "ukr" to "Ukrainian", "ces" to "Czech", "cze" to "Czech", "hun" to "Hungarian",
        "ron" to "Romanian", "rum" to "Romanian", "bul" to "Bulgarian", "hrv" to "Croatian"
    )
    private val ISO3_TO_ISO2 by lazy {
        Locale.getISOLanguages().mapNotNull { language ->
            runCatching { Locale(language).isO3Language.lowercase() to language.lowercase() }.getOrNull()
        }.toMap()
    }

    private fun normalizedLanguageCode(code: String): String {
        val language = code.trim().lowercase().substringBefore('-')
        return when (language) {
            "pob" -> "pt"
            "fre" -> "fr"
            "ger" -> "de"
            "dut" -> "nl"
            "gre" -> "el"
            "cze" -> "cs"
            "rum" -> "ro"
            else -> if (language.length == 3) ISO3_TO_ISO2[language] ?: language else language
        }
    }

    fun isPreferredLanguage(languageCode: String?, preferredLanguageTags: List<String>): Boolean {
        if (languageCode.isNullOrBlank()) return false
        val language = normalizedLanguageCode(languageCode)
        return preferredLanguageTags.any { normalizedLanguageCode(it) == language }
    }

    fun prioritizePreferredLanguages(
        subtitles: List<OnlineSubtitle>,
        preferredLanguageTags: List<String>
    ): List<OnlineSubtitle> {
        val preferredCodes = preferredLanguageTags.map(::normalizedLanguageCode)
        return subtitles.withIndex()
            .sortedWith(
                compareBy<IndexedValue<OnlineSubtitle>> {
                    preferredCodes.indexOf(normalizedLanguageCode(it.value.lang))
                        .takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                }.thenBy { if (it.value.isForced) 0 else 1 }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    fun firstPreferredLanguageMatch(
        subtitles: List<OnlineSubtitle>,
        preferredLanguageTags: List<String>
    ): OnlineSubtitle? {
        val preferredCodes = preferredLanguageTags.map(::normalizedLanguageCode)
        return subtitles.firstOrNull {
            normalizedLanguageCode(it.lang) in preferredCodes
        }
    }

    private fun getLanguageDisplay(code: String): String {
        val lower = code.lowercase().trim()
        return LANG_MAP[lower] ?: try {
            Locale(lower).displayLanguage.takeIf { it.isNotBlank() } ?: code.uppercase()
        } catch (_: Exception) {
            code.uppercase()
        }
    }

    suspend fun searchSubtitles(rawTitle: String): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        try {
            val identity = identifyMedia(rawTitle)
            val searchTitle = identity.title
            val seasonNum = identity.season ?: 1
            val episodeNum = identity.episode ?: 1

            // Step 1: Query Cinemeta for IMDb ID
            val type = if (identity.isSeries) "series" else "movie"
            val encodedQuery = URLEncoder.encode(searchTitle, "UTF-8")
            val cinemetaUrl = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encodedQuery.json"

            val cinemetaReq = Request.Builder()
                .url(cinemetaUrl)
                .header("User-Agent", "TelTV-AndroidTV")
                .build()

            val cinemetaResp = client.newCall(cinemetaReq).execute()
            if (!cinemetaResp.isSuccessful) return@withContext emptyList()

            val cinemetaBody = cinemetaResp.body?.string() ?: return@withContext emptyList()
            val metas = JSONObject(cinemetaBody).optJSONArray("metas") ?: return@withContext emptyList()
            if (metas.length() == 0) return@withContext emptyList()

            val imdbId = metas.getJSONObject(0).optString("imdb_id").takeIf { it.isNotBlank() }
                ?: metas.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                ?: return@withContext emptyList()

            // Step 2: Query OpenSubtitles v3 for subtitles list
            val subUrl = if (identity.isSeries) {
                "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$seasonNum:$episodeNum.json"
            } else {
                "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
            }

            val subReq = Request.Builder()
                .url(subUrl)
                .header("User-Agent", "TelTV-AndroidTV")
                .build()

            val subResp = client.newCall(subReq).execute()
            if (!subResp.isSuccessful) return@withContext emptyList()

            val subBody = subResp.body?.string() ?: return@withContext emptyList()
            val subArray = JSONObject(subBody).optJSONArray("subtitles") ?: return@withContext emptyList()

            val results = mutableListOf<OnlineSubtitle>()
            for (i in 0 until subArray.length()) {
                val item = subArray.getJSONObject(i)
                val id = item.optString("id", "")
                val url = item.optString("url", "")
                val lang = item.optString("lang", "").lowercase()
                val fileName = item.optString(
                    "subtitleFileName",
                    item.optString(
                        "fileName",
                        item.optString("filename", item.optString("name", item.optString("title", "$lang.srt")))
                    )
                )
                val isForced = item.optBoolean("forced") ||
                    item.optBoolean("isForced") ||
                    item.optBoolean("foreign_parts_only") ||
                    item.optBoolean("foreignPartsOnly") ||
                    item.optString("type").equals("forced", ignoreCase = true) ||
                    Regex("""(?i)\bforced\b|\bforeign[ ._-]*parts?[ ._-]*only\b""")
                        .containsMatchIn("$fileName $id")
                val releaseName = item.optString("movieReleaseName", "").ifBlank { fileName }
                if (url.isNotBlank()) {
                    results.add(
                        OnlineSubtitle(
                            id = id.ifBlank { i.toString() },
                            lang = lang,
                            langDisplay = getLanguageDisplay(lang),
                            url = url,
                            fileName = fileName,
                            isForced = isForced,
                            releaseName = releaseName
                        )
                    )
                }
            }

            val targetKeywords = listOf("web-dl", "webrip", "web", "bluray", "bdrip", "hdtv", "1080p", "720p", "hevc", "x265", "x264", "nf", "netflix")
            val lowerTitle = rawTitle.lowercase()
            val matchedKeywords = targetKeywords.filter { lowerTitle.contains(it) }

            results.sortedWith(
                compareByDescending<OnlineSubtitle> { sub ->
                    when (sub.lang.lowercase()) {
                        "ger", "deu", "de" -> 100
                        "eng", "en" -> 50
                        else -> 0
                    }
                }.thenByDescending { sub ->
                    val subText = "${sub.releaseName} ${sub.fileName}".lowercase()
                    matchedKeywords.count { subText.contains(it) }
                }
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch online subtitles for %s", rawTitle)
            emptyList()
        }
    }

    fun formatReleaseBadge(release: String): String {
        val upper = release.uppercase()
        return when {
            upper.contains("WEB-DL") || upper.contains("WEBDL") -> "WEB-DL"
            upper.contains("WEBRIP") -> "WEBRip"
            upper.contains("BLURAY") || upper.contains("BD-RIP") || upper.contains("BDRIP") -> "BluRay"
            upper.contains("HDTV") -> "HDTV"
            upper.contains("NF") || upper.contains("NETFLIX") -> "Netflix"
            upper.contains("CR") || upper.contains("CRUNCHYROLL") -> "Crunchyroll"
            else -> ""
        }
    }

    suspend fun downloadSubtitle(context: Context, subtitle: OnlineSubtitle): File? = withContext(Dispatchers.IO) {
        try {
            val subDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
            val safeId = subtitle.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val safeLanguage = subtitle.lang.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val targetFile = File(subDir, "${safeLanguage}_$safeId.srt")
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext targetFile
            }
            val temporaryFile = File(subDir, "${targetFile.name}.part")
            temporaryFile.delete()

            try {
                val req = Request.Builder()
                    .url(subtitle.url)
                    .header("User-Agent", "TelTV-AndroidTV")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    body.byteStream().use { input ->
                        FileOutputStream(temporaryFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                if (temporaryFile.length() > 0 && temporaryFile.renameTo(targetFile)) targetFile else null
            } finally {
                temporaryFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download subtitle file: %s", subtitle.url)
            null
        }
    }

    private val TIME_REGEX = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    private fun parseSrtTime(timeStr: String): Long {
        val match = TIME_REGEX.find(timeStr) ?: return 0L
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val millis = match.groupValues[4].toLong()
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
    }

    private fun formatSrtTime(totalMs: Long): String {
        val ms = (totalMs % 1000).coerceAtLeast(0)
        val totalSeconds = (totalMs / 1000).coerceAtLeast(0)
        val s = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val m = totalMinutes % 60
        val h = totalMinutes / 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms)
    }

    fun shiftSubtitle(file: File, offsetMs: Long): File {
        if (offsetMs == 0L || !file.exists()) return file
        val shiftedFile = File(file.parentFile, "${file.nameWithoutExtension}_shift_${offsetMs}.srt")
        val lines = file.readLines()
        val shiftedLines = lines.map { line ->
            if (line.contains("-->")) {
                val parts = line.split("-->")
                if (parts.size == 2) {
                    val start = (parseSrtTime(parts[0].trim()) + offsetMs).coerceAtLeast(0L)
                    val end = (parseSrtTime(parts[1].trim()) + offsetMs).coerceAtLeast(0L)
                    "${formatSrtTime(start)} --> ${formatSrtTime(end)}"
                } else line
            } else line
        }
        shiftedFile.bufferedWriter().use { writer ->
            for (line in shiftedLines) {
                writer.write(line)
                writer.newLine()
            }
        }
        return shiftedFile
    }
}
