package com.velastudio.teltv.ui.player

import com.velastudio.teltv.util.OnlineSubtitle
import com.velastudio.teltv.util.SubtitleLanguages
import java.security.MessageDigest

/** Automatic choice is limited to the saved language; the manual picker remains unrestricted. */
internal object SubtitleSelectionPolicy {
    fun best(results: List<OnlineSubtitle>, language: String): OnlineSubtitle? =
        results.firstOrNull {
            SubtitleLanguages.normalize(it.lang) == SubtitleLanguages.normalize(language) &&
                (it.episodeEvidence?.numberConflict != true || it.episodeEvidence.titleMatch)
        }

    fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    // A length prefix prevents delimiter collisions; do not read legacy per-video corrections.
    fun syncStorageKey(videoKey: String, subtitleId: String): String =
        "subtitle_sync_v2_${videoKey.length}:$videoKey:$subtitleId"
}
