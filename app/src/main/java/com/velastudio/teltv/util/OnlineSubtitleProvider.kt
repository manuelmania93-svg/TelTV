package com.velastudio.teltv.util

import android.content.Context
import kotlinx.coroutines.ensureActive
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
    val releaseName: String = "",
    val episodeEvidence: EpisodeEvidence? = null
)

/** Search outcome. [reason] explains an empty result so the UI can say why nothing was found. */
data class SubtitleSearchResult(
    val subtitles: List<OnlineSubtitle>,
    val reason: String? = null,
    /** True when several shows or episode readings are mixed in [subtitles]: never auto-attach. */
    val ambiguous: Boolean = false
)

data class SubtitleMediaIdentity(
    val title: String,
    val isSeries: Boolean,
    val season: Int?,
    val episode: Int?,
    val year: Int?,
    /** Bare 3-4 digit caption number ("101"): could be S1E01 or absolute episode 101. */
    val bareDigits: Int? = null,
    /** Date-named caption (wrestling, talk shows) without season/episode inside a topic. */
    val dated: Boolean = false
) {
    fun displayLabel(): String = when {
        dated -> "Date-named episode in: $title (no season/episode)"
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

    private val SEASON_EPISODE_REGEX = Regex("(?i)s(\\d{1,2})e(\\d{1,3})|\\b(\\d{1,2})x(\\d{1,3})\\b")
    private val YEAR_REGEX = Regex("\\b(19\\d{2}|20\\d{2})\\b")
    private val EPISODE_LABEL_REGEX = Regex("(?i)\\b(?:ep|episode|folge|part|teil|chapter|kapitel)[.\\s_-]*\\d{1,4}\\b")
    private val TRAILING_EPISODE_NUMBER_REGEX = Regex("(?i)(?:\\s*[-#]\\s*|\\s+)\\d{1,4}\\s*$")

    // Release/language tags that are not part of a show name (the shared cleaner leaves these alone).
    private val LANGUAGE_TAG_REGEX = Regex(
        "(?i)\\b(?:ger(?:man)?[ -]?(?:sub|dub)?|deutsch|eng(?:lish)?[ -]?(?:sub|dub)|multi|dual|aniworld)\\b"
    )
    private val SEASON_WORD_SUFFIX = Regex("(?i)[\\s:,.-]*(?:staffel|season)\\s*\\d{1,2}\\s*$")
    private val SHOW_SUFFIX_REGEX = Regex("(?i)\\s+(?:marathon|shuffle)\\s*$")
    private val SHOW_JUNK_REGEX = Regex("[^\\p{L}\\p{N}\\s'&:!.-]")

    /** Series name taken from a channel/folder/playlist name: no tags, emoji or "Marathon"/"Shuffle". */
    fun cleanShowName(raw: String): String {
        var t = MediaTitleCleaner.clean(raw)
        t = t.replace(SHOW_JUNK_REGEX, " ")
        t = t.replace(LANGUAGE_TAG_REGEX, " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        t = t.replace(SHOW_SUFFIX_REGEX, "")
        return t.trim().trim('-', '_', ':', '.', '|').trim()
    }

    /** Title lookups to try in order: full name, name without ": subtitle", then shorter prefixes. */
    fun titleCandidates(title: String): List<String> {
        val base = title.trim()
        if (base.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(base)
        val noSubtitle = base.split(":", " - ", "|").first().trim()
        if (noSubtitle.isNotBlank()) out.add(noSubtitle)
        var words = noSubtitle.split(" ").filter { it.isNotBlank() }
        while (words.size > 2) {
            words = words.dropLast(1)
            out.add(words.joinToString(" "))
        }
        return out.take(4)
    }

    private fun normalizeName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Index of the catalog hit whose name equals the query, else the first hit. */
    fun bestMetaIndex(names: List<String>, query: String): Int {
        val q = normalizeName(query)
        val exact = names.indexOfFirst { normalizeName(it) == q }
        return if (exact >= 0) exact else 0
    }

    /** Absolute episode number for a season/episode pair, counting regular seasons only. */
    fun absoluteEpisodeNumber(seasonCounts: Map<Int, Int>, season: Int, episode: Int): Int? {
        if (season < 1 || episode < 1) return null
        val before = seasonCounts.filterKeys { it in 1 until season }.values.sum()
        return before + episode
    }

    /** Inverse of [absoluteEpisodeNumber]; null when the number is beyond the known seasons. */
    fun seasonEpisodeFromAbsolute(seasonCounts: Map<Int, Int>, absolute: Int): Pair<Int, Int>? {
        if (absolute < 1) return null
        var remaining = absolute
        for (season in seasonCounts.keys.filter { it >= 1 }.sorted()) {
            val count = seasonCounts[season] ?: 0
            if (remaining <= count) return season to remaining
            remaining -= count
        }
        return null
    }

    fun parseSeasonCounts(metaJson: String): Map<Int, Int> {
        val videos = JSONObject(metaJson).optJSONObject("meta")?.optJSONArray("videos") ?: return emptyMap()
        val counts = sortedMapOf<Int, Int>()
        for (i in 0 until videos.length()) {
            val season = videos.optJSONObject(i)?.optInt("season", 0) ?: 0
            if (season >= 1) counts[season] = (counts[season] ?: 0) + 1
        }
        return counts
    }

    /**
     * [showName] (channel/topic name) is the only source for the series title when given;
     * the caption supplies just the season/episode numbers.
     */
    fun identifyMedia(rawTitle: String, showName: String? = null): SubtitleMediaIdentity {
        val cleanTitle = MediaTitleCleaner.clean(rawTitle)
        val signature = HybridEpisodeMatcher.parseSignature(rawTitle)
        val seasonMatch = SEASON_EPISODE_REGEX.find(cleanTitle)
        val year = YEAR_REGEX.find(cleanTitle)?.groupValues?.get(1)?.toIntOrNull()
        val isSeries = signature != null || seasonMatch != null

        val season = seasonMatch?.let {
            it.groups[1]?.value?.toIntOrNull() ?: it.groups[3]?.value?.toIntOrNull()
        } ?: signature?.season
        val episode = seasonMatch?.let {
            it.groups[2]?.value?.toIntOrNull() ?: it.groups[4]?.value?.toIntOrNull()
        } ?: signature?.episode

        val cleanedShow = showName?.let { cleanShowName(it) }?.takeIf { it.isNotBlank() }
        if (cleanedShow != null) {
            // Topic captions differ per topic: "4 4 Title", "101", "Staffel 4 Folge 4" ...
            var s2 = season
            var e2 = episode
            var series = isSeries
            var bare: Int? = null
            if (s2 == null) {
                val mine = CaptionEpisodeParser.parse(rawTitle, showName)
                if (mine != null) {
                    if (mine.season != null) {
                        s2 = mine.season; e2 = mine.episode; series = true
                    } else if (e2 == null) {
                        e2 = mine.episode; series = true; bare = mine.bareDigits
                    } else if (mine.bareDigits == e2) {
                        bare = mine.bareDigits
                    }
                }
            }
            val dated = !series && CaptionEpisodeParser.hasDate(rawTitle)
            return SubtitleMediaIdentity(cleanedShow, series, s2, e2, year, bare, dated)
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
        // "Show Staffel 02 Episode 03" without a topic name: take the season from the long form.
        val longForm = if (season == null) CaptionEpisodeParser.parse(rawTitle, null)?.takeIf { it.season != null } else null
        val cleanedTitle = title.replace(LANGUAGE_TAG_REGEX, " ").replace(Regex("\\s+"), " ").trim()
            .let { t -> if (longForm != null) t.replace(SEASON_WORD_SUFFIX, "").trim() else t }
            .ifBlank { title }
        if (longForm != null) {
            return SubtitleMediaIdentity(cleanedTitle, true, longForm.season, longForm.episode, year)
        }
        return SubtitleMediaIdentity(cleanedTitle, isSeries, season, episode, year)
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

    suspend fun searchSubtitles(
        rawTitle: String,
        preferredLanguage: String = SubtitleLanguages.DEFAULT_CODE,
        showName: String? = null
    ): List<OnlineSubtitle> = searchSubtitlesDetailed(rawTitle, preferredLanguage, showName).subtitles

    private fun httpGet(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "TelTV-AndroidTV").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private data class ShowHit(val imdbId: String, val name: String)

    private fun searchMetas(type: String, query: String): List<ShowHit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = httpGet("https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json") ?: return emptyList()
        val metas = runCatching { JSONObject(body).optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return (0 until metas.length()).mapNotNull { i ->
            val meta = metas.getJSONObject(i)
            val id = meta.optString("imdb_id").takeIf { it.isNotBlank() }
                ?: meta.optString("id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ShowHit(id, meta.optString("name", query))
        }
    }

    private fun findImdbId(type: String, query: String): Pair<String, String>? {
        val hits = searchMetas(type, query)
        if (hits.isEmpty()) return null
        val hit = hits[bestMetaIndex(hits.map { it.name }, query)]
        return hit.imdbId to hit.name
    }

    private fun resolveSingleShow(type: String, name: String): ShowHit? {
        for (candidate in titleCandidates(name)) {
            findImdbId(type, candidate)?.let { return ShowHit(it.first, it.second) }
        }
        return null
    }

    /** Shows for a topic name. Several shows ("Breaking Bad & Better Call Saul") give several hits. */
    private fun resolveShows(type: String, title: String): List<ShowHit> {
        val parts = ShowNameMatcher.splitMixed(title)
        if (parts.size >= 2) {
            // "Law & Order" is one show: only split when the whole name is not a real title.
            val whole = searchMetas(type, title).firstOrNull { ShowNameMatcher.isExact(it.name, title) }
            if (whole != null) return listOf(whole)
            val hits = parts.mapNotNull { resolveSingleShow(type, it) }.distinctBy { it.imdbId }
            if (hits.isNotEmpty()) return hits
        }
        return listOfNotNull(resolveSingleShow(type, title))
    }

    /** Names that look like [title] with small typos. Only suggested to the user, never auto-picked. */
    private fun suggestShows(type: String, title: String): List<String> {
        val out = LinkedHashMap<String, Double>()
        for (word in ShowNameMatcher.distinctiveWords(title)) {
            for (hit in searchMetas(type, word).take(8)) {
                val score = ShowNameMatcher.bestWordSimilarity(word, hit.name)
                if (score >= 0.8) out[hit.name] = maxOf(out[hit.name] ?: 0.0, score)
            }
        }
        return out.entries.sortedByDescending { it.value }.map { it.key }.take(4)
    }

    private val addonClient = client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    private suspend fun fetchSubtitleList(
        type: String, videoId: String, addons: List<SubtitleAddon>
    ): List<OnlineSubtitle> = SubtitleAddons.fetch(addons, type, videoId) { url ->
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("User-Agent", "TelTV-AndroidTV").build()
            addonClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                // Don't let a broken public addon exhaust the 2 GB TV's memory.
                if (body.contentLength() > SubtitleAddons.MAX_RESPONSE_BYTES) return@withContext null
                body.byteStream().use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() + count > SubtitleAddons.MAX_RESPONSE_BYTES) return@withContext null
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toString("UTF-8")
                }
            }
        }
    }

    private data class Reading(val season: Int, val episode: Int, val label: String?)

    /** S/E readings of the caption for one show. A bare "101" can be S1E01 or absolute episode 101. */
    private fun readingsFor(identity: SubtitleMediaIdentity, counts: Map<Int, Int>): List<Reading> {
        val bare = identity.bareDigits
        if (identity.season == null && bare != null && counts.isNotEmpty()) {
            val se = CaptionEpisodeParser.splitBareDigits(bare)
                ?.takeIf { (counts[it.first] ?: 0) >= it.second }
            val abs = seasonEpisodeFromAbsolute(counts, bare)
            val both = listOfNotNull(se, abs).distinct()
            if (both.size == 1) return listOf(Reading(both[0].first, both[0].second, null))
            if (both.size == 2) return both.map {
                Reading(it.first, it.second, "S%02dE%02d".format(it.first, it.second))
            }
        }
        return listOf(Reading(identity.season ?: 1, identity.episode ?: 1, null))
    }

    private fun relabel(sub: OnlineSubtitle, prefix: String): OnlineSubtitle =
        if (prefix.isBlank()) sub else sub.copy(
            fileName = prefix + sub.fileName,
            releaseName = prefix + sub.releaseName
        )

    /** Subtitles for one show and one episode reading, with the absolute-number fallback. */
    private suspend fun fetchEpisodeSubtitles(
        hit: ShowHit,
        metaBody: String?,
        identity: SubtitleMediaIdentity,
        rawTitle: String,
        reading: Reading,
        preferredLanguage: String,
        allowNeighbours: Boolean,
        addons: List<SubtitleAddon>
    ): Pair<List<OnlineSubtitle>, String?> {
        val seasonNum = reading.season
        val episodeNum = reading.episode
        val expectedTitles = EpisodeMatchPolicy.expectedTitles(rawTitle, metaBody, seasonNum, episodeNum)
        var triedAbsolute: Int? = null
        var list = fetchSubtitleList("series", "${hit.imdbId}:$seasonNum:$episodeNum", addons)

        // Fallback: some providers index long series under absolute numbers (Shippuden S10E20 = S1E216).
        if (list.isEmpty() && identity.episode != null && reading.label == null) {
            val counts = metaBody?.let { runCatching { parseSeasonCounts(it) }.getOrNull() }.orEmpty()
            if (counts.isNotEmpty()) {
                if (identity.season != null && identity.season > 1) {
                    val absolute = absoluteEpisodeNumber(counts, identity.season, identity.episode)
                    if (absolute != null) {
                        triedAbsolute = absolute
                        list = fetchSubtitleList("series", "${hit.imdbId}:1:$absolute", addons)
                    }
                } else if (identity.season == null) {
                    // A bare absolute number ("Naruto - 120") was searched as S1E120; try the real season/episode.
                    val mapped = seasonEpisodeFromAbsolute(counts, identity.episode)
                    if (mapped != null && mapped.first > 1) {
                        list = fetchSubtitleList(
                            "series", "${hit.imdbId}:${mapped.first}:${mapped.second}", addons
                        )
                    }
                }
            }
        }
        if (list.isEmpty()) {
            val what = "S%02dE%02d".format(seasonNum, episodeNum) +
                (triedAbsolute?.let { " (also tried absolute episode $it)" } ?: "")
            return emptyList<OnlineSubtitle>() to "Found \"${hit.name}\" but the subtitle provider has no subtitles for $what."
        }

        // Provider numbering is evidence, not identity. Search a bounded neighbourhood only
        // when the requested bucket has no strong release-title match in the chosen language.
        fun decorate(sub: OnlineSubtitle) = sub.copy(episodeEvidence = EpisodeMatchPolicy.assess(
            sub, expectedTitles, seasonNum, episodeNum
        ))
        list = list.map(::decorate)
        val preferred = list.filter { SubtitleLanguages.normalize(it.lang) == SubtitleLanguages.normalize(preferredLanguage) }
        if (allowNeighbours && preferred.none { it.episodeEvidence?.titleMatch == true } && expectedTitles.isNotEmpty()) {
            for (neighbour in EpisodeMatchPolicy.neighbours(episodeNum)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                list = SubtitleAddons.merge(list + fetchSubtitleList(
                    "series", "${hit.imdbId}:$seasonNum:$neighbour", addons
                ).map(::decorate))
            }
        }
        return list to null
    }

    suspend fun searchSubtitlesDetailed(
        rawTitle: String,
        preferredLanguage: String = SubtitleLanguages.DEFAULT_CODE,
        showName: String? = null,
        addons: List<SubtitleAddon> = SubtitleAddons.defaults(preferredLanguage)
    ): SubtitleSearchResult = withContext(Dispatchers.IO) {
        try {
            val identity = identifyMedia(rawTitle, showName)
            if (identity.dated) {
                // Date-named files (wrestling, talk shows) have no season/episode. Never guess a movie
                // from the topic name; say so and let the user search by hand.
                return@withContext SubtitleSearchResult(
                    emptyList(),
                    "Date-named episode in \"${identity.title}\": there is no season/episode to match, " +
                        "so no automatic subtitle search. Type a show name in the search box to try one."
                )
            }
            if (!identity.isSeries) return@withContext searchMovie(rawTitle, identity, preferredLanguage, addons)

            // Step 1: IMDb ids from Cinemeta. A topic with several shows gives several hits.
            val hits = resolveShows("series", identity.title)
            if (hits.isEmpty()) {
                val suggestions = suggestShows("series", identity.title)
                return@withContext SubtitleSearchResult(
                    emptyList(),
                    "No series named \"${identity.title}\" found in the subtitle database." +
                        if (suggestions.isNotEmpty()) " Did you mean: ${suggestions.joinToString(", ")}? Type the name in the search box." else ""
                )
            }

            // Step 2: merged addon subtitles for every show and episode reading.
            val multiShow = hits.size > 1
            data class Bucket(val hit: ShowHit, val reading: Reading, val subs: List<OnlineSubtitle>)
            val buckets = mutableListOf<Bucket>()
            val notes = mutableListOf<String>()
            for (hit in hits) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val metaBody = httpGet("https://v3-cinemeta.strem.io/meta/series/${hit.imdbId}.json")
                val counts = metaBody?.let { runCatching { parseSeasonCounts(it) }.getOrNull() }.orEmpty()
                val readings = readingsFor(identity, counts)
                for (reading in readings) {
                    val (subs, note) = fetchEpisodeSubtitles(
                        hit, metaBody, identity, rawTitle, reading, preferredLanguage,
                        allowNeighbours = !multiShow && readings.size == 1, addons = addons
                    )
                    if (subs.isEmpty()) note?.let(notes::add) else buckets.add(Bucket(hit, reading, subs))
                }
            }
            if (buckets.isEmpty()) {
                return@withContext SubtitleSearchResult(emptyList(), notes.firstOrNull() ?: "No subtitles found.")
            }

            // Several shows: if exactly one has a release-title match in the chosen language, use it.
            var kept = buckets.toList()
            if (multiShow) {
                val withTitle = buckets.filter { b ->
                    b.subs.any {
                        SubtitleLanguages.normalize(it.lang) == SubtitleLanguages.normalize(preferredLanguage) &&
                            it.episodeEvidence?.titleMatch == true
                    }
                }.map { it.hit.imdbId }.distinct()
                if (withTitle.size == 1) {
                    kept = buckets.filter { it.hit.imdbId == withTitle[0] }
                }
            }
            val shownShows = kept.map { it.hit.imdbId }.distinct().size
            val labelShows = shownShows > 1
            val labelReadings = kept.map { it.reading }.distinct().size > 1
            val list = kept.flatMap { b ->
                val prefix = buildString {
                    if (labelShows) append("[${b.hit.name}] ")
                    if (labelReadings && b.reading.label != null) append("[${b.reading.label}] ")
                }
                b.subs.map { relabel(it, prefix) }
            }
            val ambiguous = labelShows || labelReadings

            val targetKeywords = listOf("web-dl", "webrip", "web", "bluray", "bdrip", "hdtv", "1080p", "720p", "hevc", "x265", "x264", "nf", "netflix")
            val lowerTitle = rawTitle.lowercase()
            val matchedKeywords = targetKeywords.filter { lowerTitle.contains(it) }
            val reason = when {
                labelShows -> "This topic holds several shows (${kept.map { it.hit.name }.distinct().joinToString(", ")}). " +
                    "Subtitles from each are listed with the show name in front. Pick the right one."
                labelReadings -> "\"${identity.bareDigits}\" can mean ${kept.mapNotNull { it.reading.label }.distinct().joinToString(" or ")}. " +
                    "Both are listed, labelled. Pick the right one."
                list.none { it.episodeEvidence?.titleMatch == true } ->
                    "Episode identity is not verified. You can search or choose a different subtitle."
                else -> null
            }
            SubtitleSearchResult(SubtitleLanguages.rankResults(list, preferredLanguage, matchedKeywords)
                .sortedByDescending { it.episodeEvidence?.rank ?: 0 }, reason, ambiguous)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            Timber.w(e, "Failed to fetch online subtitles for %s", rawTitle)
            SubtitleSearchResult(emptyList(), "Could not reach the subtitle service. Check your connection.")
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch online subtitles for %s", rawTitle)
            SubtitleSearchResult(emptyList(), "Subtitle search failed unexpectedly.")
        }
    }

    private suspend fun searchMovie(
        rawTitle: String,
        identity: SubtitleMediaIdentity,
        preferredLanguage: String,
        addons: List<SubtitleAddon>
    ): SubtitleSearchResult {
        var imdbId: String? = null
        for (candidate in titleCandidates(identity.title)) {
            val hit = findImdbId("movie", candidate)
            if (hit != null) {
                imdbId = hit.first
                break
            }
        }
        if (imdbId == null) {
            return SubtitleSearchResult(emptyList(), "No movie named \"${identity.title}\" found in the subtitle database.")
        }
        val list = fetchSubtitleList("movie", imdbId, addons)
        if (list.isEmpty()) {
            return SubtitleSearchResult(emptyList(), "Found \"${identity.title}\" but the subtitle provider has no subtitles for this movie.")
        }
        val targetKeywords = listOf("web-dl", "webrip", "web", "bluray", "bdrip", "hdtv", "1080p", "720p", "hevc", "x265", "x264", "nf", "netflix")
        val lowerTitle = rawTitle.lowercase()
        val matchedKeywords = targetKeywords.filter { lowerTitle.contains(it) }
        return SubtitleSearchResult(SubtitleLanguages.rankResults(list, preferredLanguage, matchedKeywords)
            .sortedByDescending { it.episodeEvidence?.rank ?: 0 }, null)
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
            if (targetFile.exists() && targetFile.length() in 1L..(2L * 1024 * 1024)) {
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
                            val buffer = ByteArray(8192)
                            var total = 0
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 2 * 1024 * 1024) throw java.io.IOException("Subtitle exceeds 2 MB limit")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                if (temporaryFile.length() > 0 && temporaryFile.renameTo(targetFile)) targetFile else null
            } finally {
                temporaryFile.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
