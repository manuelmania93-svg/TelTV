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
    val fileName: String
)

object OnlineSubtitleProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val SEASON_EPISODE_REGEX = Regex("(?i)s(\\d{1,2})e(\\d{1,2})|(\\d{1,2})x(\\d{1,2})")
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")

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
            val cleanTitle = MediaTitleCleaner.clean(rawTitle)
            val sig = HybridEpisodeMatcher.parseSignature(rawTitle)
            val seasonMatch = SEASON_EPISODE_REGEX.find(cleanTitle)

            val isSeries = sig != null || seasonMatch != null
            val seasonNum = sig?.season ?: seasonMatch?.let {
                it.groups[1]?.value?.toIntOrNull() ?: it.groups[3]?.value?.toIntOrNull()
            } ?: 1
            val episodeNum = sig?.episode ?: seasonMatch?.let {
                it.groups[2]?.value?.toIntOrNull() ?: it.groups[4]?.value?.toIntOrNull()
            } ?: 1

            var searchTitle = cleanTitle
            if (seasonMatch != null) {
                searchTitle = cleanTitle.substring(0, seasonMatch.range.first).trim()
            } else if (sig != null) {
                searchTitle = cleanTitle.replace(Regex("(?i)\\b(?:ep|episode|folge|part|teil|chapter)?\\s*[#-]?[\\s]*0*" + episodeNum + "\\b"), "").trim()
            } else {
                val yearMatch = YEAR_REGEX.find(cleanTitle)
                if (yearMatch != null) {
                    searchTitle = cleanTitle.substring(0, yearMatch.range.first).trim()
                }
            }
            searchTitle = searchTitle.replace(Regex("[\[\]()._\\-]+"), " ").trim()
            if (searchTitle.isBlank()) searchTitle = cleanTitle

            // Step 1: Query Cinemeta for IMDb ID
            val type = if (isSeries) "series" else "movie"
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
            var subUrl = if (isSeries) {
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
                val fileName = item.optString("subtitleFileName", "$lang.srt")
                if (url.isNotBlank()) {
                    results.add(
                        OnlineSubtitle(
                            id = id.ifBlank { i.toString() },
                            lang = lang,
                            langDisplay = getLanguageDisplay(lang),
                            url = url,
                            fileName = fileName
                        )
                    )
                }
            }

            results
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch online subtitles for %s", rawTitle)
            emptyList()
        }
    }

    suspend fun downloadSubtitle(context: Context, subtitle: OnlineSubtitle): File? = withContext(Dispatchers.IO) {
        try {
            val subDir = File(context.cacheDir, "subtitles").apply { mkdirs() }
            val targetFile = File(subDir, "${subtitle.lang}_${subtitle.id}.srt")
            if (targetFile.exists() && targetFile.length() > 0) {
                return@withContext targetFile
            }

            val req = Request.Builder()
                .url(subtitle.url)
                .header("User-Agent", "TelTV-AndroidTV")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            if (targetFile.exists() && targetFile.length() > 0) targetFile else null
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
