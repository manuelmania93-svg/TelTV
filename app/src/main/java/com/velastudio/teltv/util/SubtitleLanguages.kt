package com.velastudio.teltv.util

import java.text.Normalizer
import java.util.Locale

/** One language the subtitle picker can offer. [code] is the ISO 639-1 code. */
data class SubtitleLanguage(
    val code: String,
    val english: String,
    val native: String,
    val aliases: List<String> = emptyList()
) {
    /** "German (Deutsch)" when the native name differs, otherwise just the English name. */
    fun label(): String = if (native.equals(english, ignoreCase = true)) english else "$english ($native)"
}

/**
 * Pure helpers for the subtitle language search: a small fixed catalog (no network, no storage),
 * code normalisation for the three-letter codes OpenSubtitles returns, search and ranking.
 * German is the default language.
 */
object SubtitleLanguages {
    const val DEFAULT_CODE = "de"
    private const val ENGLISH = "en"

    val catalog: List<SubtitleLanguage> = listOf(
        SubtitleLanguage("de", "German", "Deutsch", listOf("deutsch", "ger", "deu", "german")),
        SubtitleLanguage("en", "English", "English", listOf("eng", "englisch")),
        SubtitleLanguage("fr", "French", "Francais", listOf("fre", "fra", "franzoesisch")),
        SubtitleLanguage("es", "Spanish", "Espanol", listOf("spa", "castellano", "spanisch")),
        SubtitleLanguage("it", "Italian", "Italiano", listOf("ita", "italienisch")),
        SubtitleLanguage("pt", "Portuguese", "Portugues", listOf("por", "pob", "pt-br", "brazilian", "portugiesisch")),
        SubtitleLanguage("nl", "Dutch", "Nederlands", listOf("dut", "nld", "niederlaendisch")),
        SubtitleLanguage("pl", "Polish", "Polski", listOf("pol", "polnisch")),
        SubtitleLanguage("cs", "Czech", "Cestina", listOf("cze", "ces", "tschechisch")),
        SubtitleLanguage("sk", "Slovak", "Slovencina", listOf("slo", "slk", "slowakisch")),
        SubtitleLanguage("hu", "Hungarian", "Magyar", listOf("hun", "ungarisch")),
        SubtitleLanguage("ro", "Romanian", "Romana", listOf("rum", "ron", "rumaenisch")),
        SubtitleLanguage("bg", "Bulgarian", "Balgarski", listOf("bul", "bulgarisch")),
        SubtitleLanguage("hr", "Croatian", "Hrvatski", listOf("hrv", "kroatisch")),
        SubtitleLanguage("sr", "Serbian", "Srpski", listOf("srp", "scc", "serbisch")),
        SubtitleLanguage("sl", "Slovenian", "Slovenscina", listOf("slv", "slowenisch")),
        SubtitleLanguage("bs", "Bosnian", "Bosanski", listOf("bos", "bosnisch")),
        SubtitleLanguage("sq", "Albanian", "Shqip", listOf("alb", "sqi", "albanisch")),
        SubtitleLanguage("el", "Greek", "Ellinika", listOf("gre", "ell", "griechisch")),
        SubtitleLanguage("tr", "Turkish", "Turkce", listOf("tur", "tuerkisch")),
        SubtitleLanguage("ru", "Russian", "Russkij", listOf("rus", "russisch")),
        SubtitleLanguage("uk", "Ukrainian", "Ukrainska", listOf("ukr", "ukrainisch")),
        SubtitleLanguage("sv", "Swedish", "Svenska", listOf("swe", "schwedisch")),
        SubtitleLanguage("no", "Norwegian", "Norsk", listOf("nor", "nob", "nb", "norwegisch")),
        SubtitleLanguage("da", "Danish", "Dansk", listOf("dan", "daenisch")),
        SubtitleLanguage("fi", "Finnish", "Suomi", listOf("fin", "finnisch")),
        SubtitleLanguage("is", "Icelandic", "Islenska", listOf("ice", "isl", "islaendisch")),
        SubtitleLanguage("et", "Estonian", "Eesti", listOf("est", "estnisch")),
        SubtitleLanguage("lv", "Latvian", "Latviesu", listOf("lav", "lettisch")),
        SubtitleLanguage("lt", "Lithuanian", "Lietuviu", listOf("lit", "litauisch")),
        SubtitleLanguage("ca", "Catalan", "Catala", listOf("cat", "katalanisch")),
        SubtitleLanguage("eu", "Basque", "Euskara", listOf("baq", "eus", "baskisch")),
        SubtitleLanguage("ar", "Arabic", "Arabiyya", listOf("ara", "arabisch")),
        SubtitleLanguage("he", "Hebrew", "Ivrit", listOf("heb", "hebraeisch")),
        SubtitleLanguage("fa", "Persian", "Farsi", listOf("per", "fas", "persisch")),
        SubtitleLanguage("hi", "Hindi", "Hindi", listOf("hin")),
        SubtitleLanguage("bn", "Bengali", "Bangla", listOf("ben", "bengalisch")),
        SubtitleLanguage("ta", "Tamil", "Tamil", listOf("tam")),
        SubtitleLanguage("te", "Telugu", "Telugu", listOf("tel")),
        SubtitleLanguage("ur", "Urdu", "Urdu", listOf("urd")),
        SubtitleLanguage("zh", "Chinese", "Zhongwen", listOf("chi", "zho", "zht", "zhc", "mandarin", "chinesisch")),
        SubtitleLanguage("ja", "Japanese", "Nihongo", listOf("jpn", "japanisch")),
        SubtitleLanguage("ko", "Korean", "Hangugeo", listOf("kor", "koreanisch")),
        SubtitleLanguage("th", "Thai", "Thai", listOf("tha")),
        SubtitleLanguage("vi", "Vietnamese", "Tieng Viet", listOf("vie", "vietnamesisch")),
        SubtitleLanguage("id", "Indonesian", "Bahasa Indonesia", listOf("ind", "indonesisch")),
        SubtitleLanguage("ms", "Malay", "Bahasa Melayu", listOf("may", "msa", "malaiisch")),
        SubtitleLanguage("tl", "Filipino", "Tagalog", listOf("tgl", "fil", "filipino")),
        SubtitleLanguage("af", "Afrikaans", "Afrikaans", listOf("afr")),
        SubtitleLanguage("sw", "Swahili", "Kiswahili", listOf("swa"))
    )

    private val byCode: Map<String, SubtitleLanguage> = catalog.associateBy { it.code }
    private val aliasToCode: Map<String, String> = buildMap {
        for (lang in catalog) {
            put(lang.code, lang.code)
            lang.aliases.forEach { put(fold(it), lang.code) }
        }
    }

    /** Lowercase, strip accents, drop everything except letters, digits and '-'. */
    internal fun fold(text: String): String =
        Normalizer.normalize(
            text.trim().lowercase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss"),
            Normalizer.Form.NFD
        ).replace(Regex("\\p{M}+"), "")

    /**
     * Maps "ger", "deu", "de-AT", "pt-BR", "pob", "chi" ... to the catalog's two-letter code.
     * Unknown codes come back lowercased (primary subtag only) so they still group together.
     */
    fun normalize(code: String?): String {
        val raw = fold(code.orEmpty())
        if (raw.isEmpty()) return ""
        aliasToCode[raw]?.let { return it }
        val primary = raw.substringBefore('-').substringBefore('_')
        return aliasToCode[primary] ?: primary
    }

    fun find(code: String?): SubtitleLanguage? = byCode[normalize(code)]

    /** Display name for any code, including ones outside the catalog. */
    fun displayName(code: String?): String {
        find(code)?.let { return it.label() }
        val normalized = normalize(code)
        if (normalized.isEmpty()) return "Unknown"
        val fromLocale = runCatching { Locale(normalized).getDisplayLanguage(Locale.ENGLISH) }.getOrDefault("")
        return fromLocale.takeIf { it.isNotBlank() && !it.equals(normalized, ignoreCase = true) }
            ?: normalized.uppercase(Locale.ROOT)
    }

    /**
     * Search by English name, native name, ISO code or alias. Prefix hits rank before contains hits.
     * A blank query returns the whole catalog with [selected] first, then German, then English.
     */
    fun search(query: String, selected: String = DEFAULT_CODE): List<SubtitleLanguage> {
        val q = fold(query)
        val pinned = listOf(normalize(selected), DEFAULT_CODE, ENGLISH)
        if (q.isEmpty()) {
            return catalog.withIndex().sortedBy { (index, lang) ->
                val pin = pinned.indexOf(lang.code)
                if (pin >= 0) pin - 1000 else index
            }.map { it.value }
        }
        fun score(lang: SubtitleLanguage): Int {
            val names = listOf(lang.english, lang.native, lang.code) + lang.aliases
            val folded = names.map(::fold)
            return when {
                folded.any { it == q } -> 0
                folded.any { it.startsWith(q) } -> 1
                folded.any { it.contains(q) } -> 2
                else -> Int.MAX_VALUE
            }
        }
        return catalog.withIndex()
            .map { Triple(it.index, it.value, score(it.value)) }
            .filter { it.third != Int.MAX_VALUE }
            .sortedWith(compareBy({ it.third }, { it.first }))
            .map { it.second }
    }

    /** How many results each language has, for the picker's "(3)" hint. Keys are normalized codes. */
    fun countByLanguage(subtitles: List<OnlineSubtitle>): Map<String, Int> =
        subtitles.groupingBy { normalize(it.lang) }.eachCount()

    /**
     * Orders results: chosen language first (forced dialogue tracks before full ones), English next
     * when it is not the chosen language, then everything else; ties keep the provider order.
     */
    fun orderForSelection(subtitles: List<OnlineSubtitle>, selected: String): List<OnlineSubtitle> {
        val chosen = normalize(selected).ifEmpty { DEFAULT_CODE }
        fun rank(sub: OnlineSubtitle): Int = when (normalize(sub.lang)) {
            chosen -> 0
            ENGLISH -> 1
            else -> 2
        }
        return subtitles.withIndex()
            .sortedWith(
                compareBy<IndexedValue<OnlineSubtitle>> { rank(it.value) }
                    .thenBy { if (rank(it.value) == 0 && it.value.isForced) 0 else 1 }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    /**
     * Provider-side ranking after the search: chosen language, then English, then release keywords
     * (web-dl, bluray ...) matching the file name.
     */
    fun rankResults(
        results: List<OnlineSubtitle>,
        selected: String,
        matchedKeywords: List<String>
    ): List<OnlineSubtitle> {
        val chosen = normalize(selected).ifEmpty { DEFAULT_CODE }
        return results.sortedWith(
            compareByDescending<OnlineSubtitle> { sub ->
                when (normalize(sub.lang)) {
                    chosen -> 100
                    ENGLISH -> 50
                    else -> 0
                }
            }.thenByDescending { sub ->
                val subText = "${sub.releaseName} ${sub.fileName}".lowercase(Locale.ROOT)
                matchedKeywords.count { subText.contains(it) }
            }
        )
    }
}
