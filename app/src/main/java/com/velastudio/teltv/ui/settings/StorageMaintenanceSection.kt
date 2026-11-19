package com.velastudio.teltv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.velastudio.teltv.telegram.AppStorageUsage
import com.velastudio.teltv.worker.IdleCleanupPolicy

private fun storageAmount(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) java.lang.String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    else java.lang.String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))

@Composable
fun StorageUsageSection(usage: AppStorageUsage?, error: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF171E28)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App data breakdown", style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (usage == null) Text(if (error) "Storage scan unavailable. No files were changed." else "Reading app storage...", color = Color.White)
        else {
            listOf(
                "Telegram downloaded media / thumbnails" to usage.telegramFiles,
                "Telegram database / support files / logs" to usage.telegramDatabase,
                "TelTV library / favorites / history database" to usage.libraryDatabase,
                "Other app files / settings" to usage.otherData,
                "Android cache (includes images and subtitles)" to usage.androidCache,
                "Subtitles kept within Android cache" to usage.subtitles
            ).forEach { (name, bytes) -> Text("$name: ${storageAmount(bytes)}", color = Color.White) }
        }
        Text("Approximate file-content sizes, not Android's app/code/allocated-space total. Subtitles are part of cache, not an extra total.", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRefresh) { Text("Refresh storage breakdown") }
        Text("Clear local Telegram database", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Text("Not enabled in this safe test build: preserving Telegram login and saved file references after a database rebuild has not been verified. Database files are never removed by this cleaner.", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun IdleCleanupSection(hours: Long, retentionSeconds: Long,
    onHours: (Long) -> Unit, onRetention: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF171E28)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Automatic reusable-file cleanup", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text("Sweep interval (uses the automatic-cleanup switch above)", color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IdleCleanupPolicy.intervals.forEach { value ->
                SkipChoiceButton("$value hours", hours == value) { onHours(value) }
            }
        }
        Text("Remove media and thumbnails not used for", color = Color.White)
        IdleCleanupPolicy.retentions.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { value -> SkipChoiceButton(IdleCleanupPolicy.retentionLabel(value), retentionSeconds == value) { onRetention(value) } }
            }
        }
        Text("Default: check every 4 hours, keep recently used files for 4 hours. The existing size cap also applies. Android may delay background checks. Playback (also paused/buffering), open media readers and downloads defer the sweep.", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall)
        Text("Reusable pictures/videos download again when needed. Login, databases, favorites, history, playlists and downloaded subtitles stay. This does not shrink the Telegram database.", color = Color(0xFFB0BEC5), style = MaterialTheme.typography.bodySmall)
    }
}
