package com.velastudio.teltv.worker

/**
 * Builds the "last cleanup freed X" line shown in Settings so people can see TelTV recovering
 * space on its own. Pure Kotlin (no Android imports) so the wording is unit-testable on the JVM.
 */
object CleanupNotice {

    fun format(freedBytes: Long, freedAtMs: Long, nowMs: Long): String {
        val elapsedMs = nowMs - freedAtMs
        val whenText = if (elapsedMs < 60_000) "just now" else "${formatElapsed(elapsedMs)} ago"
        return "Last cleanup freed ${formatSize(freedBytes)} ($whenText)"
    }

    internal fun formatSize(bytes: Long): String {
        val gb = 1024.0 * 1024 * 1024
        return if (bytes >= gb) "%.1f GB".format(bytes / gb)
        else "${(bytes / (1024 * 1024)).coerceAtLeast(1)} MB"
    }

    internal fun formatElapsed(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${minutes / 60} h"
            else -> "${minutes / (60 * 24)} d"
        }
    }
}
