package com.velastudio.teltv.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.velastudio.teltv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val changelog: String
)

object AppUpdater {
    private val client = OkHttpClient.Builder().build()
    private const val GITHUB_REPO = "manuelmania93-svg/TelTV"

    fun cleanVersion(raw: String): String {
        return raw.replace(Regex("(?i)teltv"), "")
            .trim()
            .trimStart('v', 'V', ' ')
            .trim()
    }

    private fun isNewer(remoteVer: String, currentVer: String): Boolean {
        val rParts = remoteVer.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = currentVer.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    suspend fun checkForUpdate(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val releaseUrls = listOf(
                "https://api.github.com/repos/$GITHUB_REPO/releases/latest",
                "https://api.github.com/repos/$GITHUB_REPO/releases/tags/rolling-release"
            )

            val currentVersion = cleanVersion(BuildConfig.VERSION_NAME)

            for (url in releaseUrls) {
                val json = fetchRelease(url) ?: continue
                val rawTagName = json.optString("tag_name", "")
                val rawReleaseName = json.optString("name", "")
                val tagName = cleanVersion(rawTagName)
                val versionInRelease = cleanVersion(rawReleaseName)

                val remoteVersion = if (versionInRelease.isNotBlank()) versionInRelease else tagName

                val assets = json.optJSONArray("assets") ?: continue
                var downloadUrl: String? = null
                val targetName = if (BuildConfig.DEBUG) "debug" else "release"

                // First pass: find exact matching APK (e.g. TelTV-release.apk)
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "").lowercase()
                    if (name.endsWith(".apk") && targetName in name) {
                        downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                        break
                    }
                }
                // Fallback pass: any .apk if no specific build-type found
                if (downloadUrl == null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                            break
                        }
                    }
                }

                if (downloadUrl != null) {
                    if (!force) {
                        if (remoteVersion.isBlank() || !isNewer(remoteVersion, currentVersion)) {
                            return@withContext null
                        }
                    }
                    return@withContext UpdateInfo(
                        remoteVersion,
                        downloadUrl,
                        json.optString("body", "Continuous release update with latest fixes.")
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchRelease(url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TelTV-AndroidTV-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.let(::JSONObject)
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "TelTV-AndroidTV-Updater")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val body = response.body ?: return@withContext false
                val contentLength = body.contentLength()
                val apkFile = File(context.cacheDir, "TelTV_update.apk").apply {
                    if (exists()) delete()
                }

                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (contentLength > 0) {
                                onProgress(totalRead.toFloat() / contentLength)
                            }
                        }
                        output.flush()
                    }
                }

                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
