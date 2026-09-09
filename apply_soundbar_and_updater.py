import os

# 1. Create file_paths.xml for Android FileProvider
os.makedirs("app/src/main/res/xml", exist_ok=True)
with open("app/src/main/res/xml/file_paths.xml", "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="apk_cache" path="." />
    <external-cache-path name="ext_apk_cache" path="." />
</paths>
''')
print("✅ 1/6 file_paths.xml created!")

# 2. Update AndroidManifest.xml (Install permission + FileProvider)
with open("app/src/main/AndroidManifest.xml", "r", encoding="utf-8") as f:
    manifest = f.read()

if "REQUEST_INSTALL_PACKAGES" not in manifest:
    manifest = manifest.replace(
        '<uses-permission android:name="android.permission.INTERNET" />',
        '<uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />'
    )

if "androidx.core.content.FileProvider" not in manifest:
    provider_xml = '''
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>'''
    manifest = manifest.replace('</application>', provider_xml)

with open("app/src/main/AndroidManifest.xml", "w", encoding="utf-8") as f:
    f.write(manifest)
print("✅ 2/6 AndroidManifest.xml updated with FileProvider and install permission!")

# 3. Create AppUpdater.kt
with open("app/src/main/java/com/velastudio/teltv/util/AppUpdater.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.util

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

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").removePrefix("v").trim()
            val changelog = json.optString("body", "Bug fixes and performance enhancements.")

            val currentVersion = BuildConfig.VERSION_NAME
            if (tagName.isBlank() || tagName == currentVersion) {
                return@withContext null
            }

            val assets = json.optJSONArray("assets") ?: return@withContext null
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    downloadUrl = asset.optString("browser_download_url")
                    break
                }
            }

            if (downloadUrl != null) {
                UpdateInfo(tagName, downloadUrl, changelog)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            val apkFile = File(context.cacheDir, "TelTV_update.apk")
            if (apkFile.exists()) apkFile.delete()

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
            true
        } catch (e: Exception) {
            false
        }
    }
}
''')
print("✅ 3/6 AppUpdater.kt created!")

# 4. Update PlaybackService.kt with Audio Passthrough & Multi-channel Bitstreaming
with open("app/src/main/java/com/velastudio/teltv/player/PlaybackService.kt", "w", encoding="utf-8") as f:
    f.write('''package com.velastudio.teltv.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.velastudio.teltv.MainActivity
import com.velastudio.teltv.TelTvApp
import com.velastudio.teltv.telegram.TdLibAwareDataSourceFactory
import timber.log.Timber

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val app = application as TelTvApp
        val dataSourceFactory = TdLibAwareDataSourceFactory(app.telegramClient, this)

        // Low-RAM TV buffer tuning: 35s lookahead, strict 35MB memory ceiling
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 35_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500
            )
            .setTargetBufferBytes(35 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Audio Passthrough (Bitstream) for Soundbars & AV Receivers
        val audioCapabilities = AudioCapabilities.getCapabilities(this)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(audioCapabilities)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        // Track selector: Never downmix 5.1/7.1 audio to stereo on soundbars
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setConstrainAudioChannelCountToDeviceCapabilities(false)
                .build()
        }

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                    .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .build()

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Timber.e(error, "Player error (errorCode=%s)", error.errorCodeName)
            }
        })
        player = exoPlayer

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val currentPlayer = player
        if (currentPlayer == null || !currentPlayer.playWhenReady || currentPlayer.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        player = null
        super.onDestroy()
    }
}
''')
print("✅ 4/6 PlaybackService.kt updated with Dolby/DTS Bitstreaming for soundbars!")

# 5. Add In-App Update Dialog to HomeScreen.kt
with open("app/src/main/java/com/velastudio/teltv/ui/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    home_code = f.read()

if "UpdateAvailableDialog" not in home_code:
    dialog_composable = '''
@Composable
fun UpdateAvailableDialog(
    updateInfo: com.velastudio.teltv.util.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .width(460.dp)
                .background(androidx.compose.ui.graphics.Color(0xFF222222), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text("Update Available: v${updateInfo.versionName}", style = androidx.tv.material3.MaterialTheme.typography.titleLarge, color = androidx.compose.ui.graphics.Color.White)
            Spacer(Modifier.height(12.dp))
            Text("A new version of TelTV is ready to install.", style = androidx.tv.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.LightGray)
            Spacer(Modifier.height(20.dp))

            if (isDownloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = downloadProgress,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("Downloading... ${(downloadProgress * 100).toInt()}%", color = androidx.compose.ui.graphics.Color.White)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onDownload) { Text("Download & Install") }
                    androidx.tv.material3.OutlinedButton(onClick = onDismiss) { Text("Later") }
                }
            }
        }
    }
}
'''
    home_code += dialog_composable

    # Wire update state in HomeScreen
    old_state = "    var showClearConfirm by remember { mutableStateOf(false) }"
    new_state = '''    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.velastudio.teltv.util.UpdateInfo?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        updateInfo = com.velastudio.teltv.util.AppUpdater.checkForUpdate()
    }'''
    home_code = home_code.replace(old_state, new_state)

    # Wire update dialog rendering at bottom of HomeScreen
    old_render_end = "        if (otherSources.entries.isNotEmpty()) {\n            item { HomeRowView(otherSources, thumbnailLoader, onOpenEntry) }\n        }\n    }"
    new_render_end = old_render_end + '''

    if (updateInfo != null) {
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            onDownload = {
                coroutineScope.launch {
                    isDownloading = true
                    com.velastudio.teltv.util.AppUpdater.downloadAndInstall(context, updateInfo!!.downloadUrl) { p ->
                        downloadProgress = p
                    }
                    isDownloading = false
                }
            },
            onDismiss = { updateInfo = null }
        )
    }'''
    home_code = home_code.replace(old_render_end, new_render_end)

    with open("app/src/main/java/com/velastudio/teltv/ui/home/HomeScreen.kt", "w", encoding="utf-8") as f:
        f.write(home_code)
print("✅ 5/6 HomeScreen.kt updated with in-app update prompt!")

# 6. Update GitHub Actions to auto-publish releases on push
with open(".github/workflows/build.yml", "w", encoding="utf-8") as f:
    f.write('''name: Build TelTV APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3

      - name: Build APK with Gradle
        run: ./gradlew assembleDebug --stacktrace

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: TelTV-debug
          path: app/build/outputs/apk/debug/*.apk

      - name: Publish Rolling Release
        uses: softprops/action-gh-release@v2
        if: github.ref == 'refs/heads/main'
        with:
          tag_name: rolling-release
          name: TelTV Continuous Release
          body: |
            Automated continuous release from commit ${{ github.sha }}
            - Soundbar Audio Passthrough (AC3 / DTS Bitstream)
            - In-App Auto Updater
          files: app/build/outputs/apk/debug/*.apk
          draft: false
          prerelease: false
''')
print("✅ 6/6 .github/workflows/build.yml updated with automated releases!")
