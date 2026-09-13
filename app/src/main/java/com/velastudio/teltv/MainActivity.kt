package com.velastudio.teltv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.velastudio.teltv.data.local.WatchStateEntity
import com.velastudio.teltv.data.local.PlaylistEntity
import com.velastudio.teltv.data.local.PlaylistItemEntity
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.browse.BrowseScreen
import com.velastudio.teltv.ui.home.ContinueWatchingEntry
import com.velastudio.teltv.ui.home.HomeEntry
import com.velastudio.teltv.ui.home.HomeRow
import com.velastudio.teltv.ui.home.HomeScreen
import com.velastudio.teltv.ui.login.LoginScreen
import com.velastudio.teltv.ui.player.PlayerScreen
import com.velastudio.teltv.ui.search.SearchScreen
import com.velastudio.teltv.ui.settings.AppUpdateSection
import com.velastudio.teltv.ui.settings.CacheSettingsSection
import com.velastudio.teltv.ui.settings.AppInfoSection
import com.velastudio.teltv.ui.settings.PerformanceSettingsSection
import com.velastudio.teltv.ui.settings.PlaybackSettingsSection
import com.velastudio.teltv.ui.settings.PlaylistSettingsSection
import com.velastudio.teltv.ui.player.PlaybackPrefs
import com.velastudio.teltv.ui.theme.TelTvTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Single-activity Compose app. Screens:
 *   login    -> QR code (default) or phone number / code / 2FA, via TdApi.AuthorizationState
 *               (see LoginScreen). This is the start destination: it's also what actually starts
 *               the TDLib client (TelegramClient.authorizationFlow()), so it always runs,
 *               whether or not there's already a saved session -- a returning user just sees it
 *               flash by briefly.
 *   home     -> continue watching + pinned channels + folder rows + other sources
 *   browse   -> paged video grid for a chosen channel (see BrowseScreen)
 *   player   -> ExoPlayer full-screen playback via TdLibDataSource
 *   search   -> instant local + debounced cross-channel search
 *   settings -> cache management (see CacheSettingsSection) + playback + account
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TelTvApp

        setContent {
            TelTvTheme {
                if (app.startupError != null) {
                    StartupErrorScreen(app.startupError!!)
                    return@TelTvTheme
                }

                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                
                var crashReport by remember { mutableStateOf(com.velastudio.teltv.util.CrashLogger.lastCrashReport(app)) }
                if (crashReport != null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.9f)),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        androidx.tv.material3.Card(onClick = {}) {
                            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp)) {
                                androidx.tv.material3.Text("Previous Crash Detected", style = androidx.tv.material3.MaterialTheme.typography.titleLarge)
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                                androidx.tv.material3.Text(
                                    text = crashReport!!.take(400),
                                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall
                                )
                                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(16.dp))
                                androidx.tv.material3.Button(onClick = {
                                    com.velastudio.teltv.util.CrashLogger.clear(app)
                                    crashReport = null
                                }) {
                                    androidx.tv.material3.Text("Dismiss")
                                }
                            }
                        }
                    }
                    return@TelTvTheme
                }

                val thumbnailLoader = remember { ThumbnailLoader(app.telegramClient, scope, app.deviceProfile) }

                // Shared across Home/Search for this session so Search doesn't need to re-fetch
                // the pinned-channel list from Telegram just to know what to search across.
                var pinnedChatIds by remember { mutableStateOf<List<Long>>(emptyList()) }

                NavHost(navController = navController, startDestination = "login") {
                    composable("login") {
                        LoginScreen(
                            telegramClient = app.telegramClient,
                            onReady = {
                                navController.navigate("home") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("home") {
                        var continueWatching by remember { mutableStateOf<List<ContinueWatchingEntry>>(emptyList()) }
                        var recentlyWatched by remember { mutableStateOf<List<ContinueWatchingEntry>>(emptyList()) }
                        var watchLater by remember { mutableStateOf<List<ContinueWatchingEntry>>(emptyList()) }
                        var pinnedRow by remember { mutableStateOf(HomeRow("Pinned Channels", emptyList())) }
                        var allChannelsRow by remember { mutableStateOf(HomeRow("Channels", emptyList())) }
                        var folderRowsState by remember { mutableStateOf<List<HomeRow>>(emptyList()) }
                        var isLoadingChannels by remember { mutableStateOf(true) }
                        var cacheClearedSignal by remember { mutableStateOf(0) }

                        LaunchedEffect(Unit) {
                            runCatching {
                                com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute)
                                    .maybeEmergencyClear(app.filesDir.usableSpace)
                            }.onFailure { Timber.w(it, "Foreground cache trim skipped") }

                            runCatching {
                                val watchStates = app.database.watchStateDao().continueWatching()
                                continueWatching = watchStates.map {
                                    ContinueWatchingEntry(
                                        mediaId = it.mediaId,
                                        title = it.title,
                                        progressFraction = if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f,
                                        thumbnailFileId = it.thumbnailFileId
                                    )
                                }
                                recentlyWatched = app.database.watchStateDao().recentlyWatched(limit = 20).map {
                                    ContinueWatchingEntry(
                                        mediaId = it.mediaId,
                                        title = it.title,
                                        progressFraction = if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f,
                                        thumbnailFileId = it.thumbnailFileId
                                    )
                                }
                                watchLater = app.database.watchlistDao().getAll().mapNotNull { saved ->
                                    app.database.videoIndexDao().getByMediaId(saved.mediaId)?.let { video ->
                                        ContinueWatchingEntry(
                                            mediaId = video.mediaId,
                                            title = video.title,
                                            progressFraction = 0f,
                                            thumbnailFileId = video.thumbnailFileId
                                        )
                                    }
                                }
                            }.onFailure { Timber.e(it, "Failed to load local home shelves") }

                            // Pins and folders are independent TDLib requests; load them together
                            // so Home is not blocked by two sequential chat-list walks.
                            val discovery = kotlinx.coroutines.coroutineScope {
                                val pinnedDeferred = async {
                                    runCatching { app.telegramClient.getPinnedChannels() }
                                        .getOrDefault(emptyList())
                                }
                                val foldersDeferred = async {
                                    runCatching { app.telegramClient.getChannelsInFolders() }
                                        .getOrDefault(emptyMap())
                                }
                                pinnedDeferred.await() to foldersDeferred.await()
                            }
                            val pinned = discovery.first
                            if (pinned.isNotEmpty()) {
                                pinnedRow = HomeRow(
                                    "Pinned Channels",
                                    pinned.map { chat ->
                                        HomeEntry(
                                            id = chat.id.toString(),
                                            name = chat.title,
                                            thumbnailFileId = chat.photo?.small?.id
                                        )
                                    }
                                )
                            }

                            // Chat folders are the organized media libraries from Telegram.
                            val folderMap = discovery.second
                            val dynamicFolderRows = folderMap.mapNotNull { (folderInfo, chatList) ->
                                if (chatList.isEmpty()) null
                                else HomeRow(
                                    (folderInfo.name?.text?.text ?: "Folder"),
                                    chatList.map { chat ->
                                        HomeEntry(
                                            id = chat.id.toString(),
                                            name = chat.title,
                                            thumbnailFileId = chat.photo?.small?.id
                                        )
                                    }
                                )
                            }

                            folderRowsState = dynamicFolderRows
                            pinnedChatIds = (pinned.map { it.id } + folderMap.values.flatten().map { it.id }).distinct()
                            isLoadingChannels = false
                        }

                        LaunchedEffect(cacheClearedSignal) {
                            if (cacheClearedSignal == 0) return@LaunchedEffect
                            val remaining = runCatching {
                                com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute).getCurrentSizeBytes()
                            }.onFailure { Timber.w(it, "Could not read cache size after quick clear") }
                                .getOrDefault(-1L)
                            Timber.i("Quick cache clear done; remaining bytes=%d", remaining)
                        }

                        HomeScreen(
                            thumbnailLoader = thumbnailLoader,
                            continueWatching = continueWatching,
                            recentlyWatched = recentlyWatched,
                            watchLater = watchLater,
                            pinned = pinnedRow,
                            allChannels = allChannelsRow,
                            folderRows = folderRowsState,
                            otherSources = HomeRow("Other sources", emptyList()),
                            isLoading = isLoadingChannels,
                            onOpenEntry = { entry ->
                                scope.launch {
                                    val chatId = entry.id.toLongOrNull() ?: 0L
                                    val isForum = if (chatId != 0L) runCatching { app.telegramClient.isForumChat(chatId) }.getOrDefault(false) else false
                                    val encodedTitle = URLEncoder.encode(entry.name, "UTF-8")
                                    if (isForum) {
                                        navController.navigate("topics/${entry.id}/$encodedTitle")
                                    } else {
                                        navController.navigate("browse/${entry.id}/$encodedTitle")
                                    }
                                }
                            },
                            onResumeWatching = { mediaId ->
                                navController.navigate("player/${URLEncoder.encode(mediaId, "UTF-8")}")
                            },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSettings = { navController.navigate("settings") },
                            onQuickClearCache = {
                                scope.launch {
                                    runCatching {
                                        com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute).clearAllNow()
                                    }.onFailure { Timber.e(it, "Quick cache clear failed") }
                                        .onSuccess { cacheClearedSignal++ }
                                }
                            }
                        )
                    }

                                        composable(
                        "topics/{chatId}/{title}",
                        arguments = listOf(
                            navArgument("chatId") { type = NavType.LongType },
                            navArgument("title") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getLong("chatId") ?: return@composable
                        val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")
                        var topics by remember { mutableStateOf<List<com.velastudio.teltv.telegram.TelegramClient.ForumTopicDetail>>(emptyList()) }
                        var isLoading by remember { mutableStateOf(true) }

                        LaunchedEffect(chatId) {
                            topics = app.telegramClient.getForumTopics(chatId)
                            isLoading = false
                        }

                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 40.dp, vertical = 28.dp)
                        ) {
                            androidx.tv.material3.Text(title, style = androidx.tv.material3.MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color.White)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                            androidx.tv.material3.Text("Choose a Series / Topic", style = androidx.tv.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.ui.graphics.Color.Gray)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))

                            if (isLoading) {
                                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.compose.material3.CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFFFFC107))
                                }
                            } else if (topics.isEmpty()) {
                                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    androidx.tv.material3.Text("No topics found in this group.", color = androidx.compose.ui.graphics.Color.LightGray)
                                }
                            } else {
                                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 220.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(20.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(topics.size) { idx ->
                                        val topic = topics[idx]
                                        androidx.tv.material3.Card(
                                            onClick = {
                                                val raw = kotlin.math.abs(chatId)
                                                val virtualId = -(raw * 100_000L + topic.info.forumTopicId)
                                                val encodedTopicTitle = URLEncoder.encode(topic.info.name, "UTF-8")
                                                navController.navigate("browse/$virtualId/$encodedTopicTitle")
                                            },
                                            shape = androidx.tv.material3.CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                                            colors = androidx.tv.material3.CardDefaults.colors(
                                                containerColor = androidx.compose.ui.graphics.Color(0xFF151515),
                                                focusedContainerColor = androidx.compose.ui.graphics.Color(0xFF292929)
                                            ),
                                            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.05f),
                                            modifier = Modifier
                                                .height(130.dp)
                                                .fillMaxWidth()
                                        ) {
                                            androidx.compose.foundation.layout.Box(
                                                modifier = Modifier.fillMaxSize().padding(16.dp),
                                                contentAlignment = androidx.compose.ui.Alignment.CenterStart
                                            ) {
                                                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                                    androidx.tv.material3.Icon(
                                                        imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                                                        contentDescription = null,
                                                        tint = androidx.compose.ui.graphics.Color(0xFFFFC107),
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                    androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
                                                    androidx.compose.foundation.layout.Column {
                                                        androidx.tv.material3.Text(
                                                            text = topic.info.name,
                                                            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                                                            color = androidx.compose.ui.graphics.Color.White,
                                                            maxLines = 2
                                                        )
                                                        androidx.tv.material3.Text(
                                                            text = if (topic.videoCount > 0) "🎬 ${topic.videoCount} Videos" else "Topic #${topic.info.forumTopicId}",
                                                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                                                            color = androidx.compose.ui.graphics.Color(0xFFFFC107)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    composable(
                        "browse/{chatId}/{title}",
                        arguments = listOf(
                            navArgument("chatId") { type = NavType.LongType },
                            navArgument("title") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getLong("chatId") ?: return@composable
                        val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")

                        var resumeFractions by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
                        var pinnedVideo by remember { mutableStateOf<MediaItem?>(null) }
                        LaunchedEffect(chatId) {
                            // refreshNewest runs first: corrects stale titles on already-cached rows
                            // (e.g. after the caption-title fix) and prepends any new videos posted
                            // since the last visit. ensureNextPage then fills the first page if the
                            // cache was empty. Both are no-ops if nothing has changed.
                            runCatching {
                                app.channelVideoRepository.refreshNewest(chatId)
                                app.channelVideoRepository.ensureNextPage(chatId)
                            }.onFailure { Timber.w(it, "Failed to load channel videos for chatId=%d", chatId) }

                            // Silently pre-load up to thousands of remaining videos into Room in the background
                            scope.launch {
                                runCatching { app.channelVideoRepository.preloadRemaining(chatId) }
                            }
                            pinnedVideo = runCatching { app.telegramClient.getPinnedVideo(chatId) }
                                .onFailure { Timber.w(it, "Failed to load pinned video for chat %d", chatId) }
                                .getOrNull()
                            resumeFractions = app.database.watchStateDao().getForChat(chatId)
                                .associate { state ->
                                    val fraction = when {
                                        state.finished -> 1.0f
                                        state.durationMs > 0 -> (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                    state.mediaId to fraction
                                }
                        }

                        var isAscending by remember { mutableStateOf(true) } // Default to Newest First
                        val marathonName = "$title Marathon"
                        var activeMarathon by remember { mutableStateOf<com.velastudio.teltv.data.local.PlaylistEntity?>(null) }

                        LaunchedEffect(chatId) {
                            activeMarathon = app.database.playlistDao().getByName(marathonName)
                        }

                        BrowseScreen(
                            channelTitle = title,
                            pinnedVideo = pinnedVideo,
                            isAscending = isAscending,
                            onToggleSort = { isAscending = !isAscending },
                            pagingFlow = remember(chatId, isAscending) { app.channelVideoRepository.videoPager(chatId, isAscending) },
                            activeMarathonName = activeMarathon?.name,
                            onResumeMarathon = {
                                activeMarathon?.let { playlist ->
                                    scope.launch {
                                        val items = app.database.playlistDao().getItems(playlist.id)
                                        // Resume from first unwatched episode, or first episode
                                        val resumeItem = items.firstOrNull { item ->
                                            val state = app.database.watchStateDao().get(item.mediaId)
                                            state == null || !state.finished
                                        } ?: items.firstOrNull()

                                        if (resumeItem != null) {
                                            navController.navigate("player/${URLEncoder.encode(resumeItem.mediaId, "UTF-8")}?playlistId=${playlist.id}")
                                        }
                                    }
                                }
                            },
                            onDeleteMarathon = {
                                activeMarathon?.let { playlist ->
                                    scope.launch {
                                        app.database.playlistDao().clearItems(playlist.id)
                                        app.database.playlistDao().delete(playlist.id)
                                        activeMarathon = null
                                    }
                                }
                            },
                            onStartMarathonFrom = { startMedia ->
                                scope.launch {
                                    val dao = app.database.playlistDao()
                                    // Remove any existing marathon for this title
                                    val existing = dao.getByName(marathonName)
                                    if (existing != null) {
                                        dao.clearItems(existing.id)
                                        dao.delete(existing.id)
                                    }

                                    val playlist = com.velastudio.teltv.data.local.PlaylistEntity(
                                        name = marathonName,
                                        createdEpochSec = System.currentTimeMillis() / 1000
                                    ).let { it.copy(id = dao.insert(it)) }

                                    // Sort all videos in chronological order (oldest to newest)
                                    val allVideos = app.database.videoIndexDao().getAllForChat(chatId).sortedBy { it.messageId }
                                    val startIndex = allVideos.indexOfFirst { it.mediaId == startMedia.id }.coerceAtLeast(0)
                                    val marathonVideos = allVideos.drop(startIndex)

                                    marathonVideos.forEachIndexed { index, video ->
                                        dao.addItem(
                                            com.velastudio.teltv.data.local.PlaylistItemEntity(
                                                playlistId = playlist.id,
                                                mediaId = video.mediaId,
                                                position = index,
                                                title = video.title,
                                                addedEpochSec = System.currentTimeMillis() / 1000
                                            )
                                        )
                                    }
                                    activeMarathon = playlist
                                    navController.navigate("player/${URLEncoder.encode(startMedia.id, "UTF-8")}?playlistId=${playlist.id}")
                                }
                            },
                            thumbnailLoader = thumbnailLoader,
                            deviceProfile = app.deviceProfile,
                            resumeFractionFor = { mediaId -> resumeFractions[mediaId] },
                            onLoadMore = { scope.launch { app.channelVideoRepository.ensureNextPage(chatId) } },
                            onOpenItem = { media ->
                                navController.navigate("player/${URLEncoder.encode(media.id, "UTF-8")}")
                            },
                            onPinVideo = { media ->
                                scope.launch {
                                    val messageId = media.id.substringAfterLast(':').toLongOrNull() ?: return@launch
                                    runCatching { app.telegramClient.pinVideo(chatId, messageId) }
                                        .onSuccess { pinnedVideo = media }
                                        .onFailure { Timber.e(it, "Failed to pin video %s", media.id) }
                                }
                            },
                            onUnpinVideo = { media ->
                                scope.launch {
                                    val messageId = media.id.substringAfterLast(':').toLongOrNull() ?: return@launch
                                    runCatching { app.telegramClient.unpinVideo(chatId, messageId) }
                                        .onSuccess { pinnedVideo = null }
                                        .onFailure { Timber.e(it, "Failed to unpin video %s", media.id) }
                                }
                            },
                            onAddToPlaylist = { media ->
                                scope.launch {
                                    val dao = app.database.playlistDao()
                                    val playlist = dao.getAll().firstOrNull()
                                        ?: PlaylistEntity(name = "My Marathon", createdEpochSec = System.currentTimeMillis() / 1000)
                                            .let { it.copy(id = dao.insert(it)) }
                                    dao.addItem(
                                        PlaylistItemEntity(
                                            playlistId = playlist.id,
                                            mediaId = media.id,
                                            position = dao.nextPosition(playlist.id),
                                            title = media.title,
                                            addedEpochSec = System.currentTimeMillis() / 1000
                                        )
                                    )
                                }
                            },
                            onAddToWatchLater = { media ->
                                scope.launch {
                                    app.database.watchlistDao().add(
                                        com.velastudio.teltv.data.local.WatchlistEntity(
                                            mediaId = media.id,
                                            addedEpochSec = System.currentTimeMillis() / 1000
                                        )
                                    )
                                }
                            },
                            onCreateMarathon = {
                                scope.launch {
                                    val dao = app.database.playlistDao()
                                    val existing = dao.getByName(marathonName)
                                    if (existing != null) {
                                        dao.clearItems(existing.id)
                                        dao.delete(existing.id)
                                    }

                                    val playlist = com.velastudio.teltv.data.local.PlaylistEntity(
                                        name = marathonName,
                                        createdEpochSec = System.currentTimeMillis() / 1000
                                    ).let { it.copy(id = dao.insert(it)) }

                                    val allVideos = app.database.videoIndexDao().getAllForChat(chatId).sortedBy { it.messageId }
                                    allVideos.forEachIndexed { index, video ->
                                        dao.addItem(
                                            com.velastudio.teltv.data.local.PlaylistItemEntity(
                                                playlistId = playlist.id,
                                                mediaId = video.mediaId,
                                                position = index,
                                                title = video.title,
                                                addedEpochSec = System.currentTimeMillis() / 1000
                                            )
                                        )
                                    }
                                    activeMarathon = playlist
                                    allVideos.firstOrNull()?.let { first ->
                                        navController.navigate("player/${URLEncoder.encode(first.mediaId, "UTF-8")}?playlistId=${playlist.id}")
                                    }
                                }
                            }
                        )
                    }

                    composable("search") {
                        var recentQueries by remember { mutableStateOf<List<String>>(emptyList()) }
                        var localResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                        var remoteResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                        var isSearchingRemote by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            recentQueries = app.database.searchHistoryDao().recent().map { it.query }
                        }

                        SearchScreen(
                            deviceProfile = app.deviceProfile,
                            thumbnailLoader = thumbnailLoader,
                            recentQueries = recentQueries,
                            localResults = localResults,
                            remoteResults = remoteResults,
                            isSearchingRemote = isSearchingRemote,
                            onLocalQueryChanged = { q ->
                                scope.launch {
                                    localResults = if (q.isBlank()) emptyList()
                                    else app.database.videoIndexDao().searchLocal(q).map {
                                        MediaItem(
                                            id = it.mediaId,
                                            sourceType = com.velastudio.teltv.data.model.SourceType.TELEGRAM,
                                            title = it.title,
                                            subtitle = it.subtitle,
                                            thumbnailUrl = it.thumbnailFileId?.let { fileId -> "tdlib://thumb/$fileId" },
                                            streamUrl = it.streamUrl,
                                            addedAtEpochSec = it.addedAtEpochSec
                                        )
                                    }
                                }
                            },
                            onRemoteQueryChanged = { q ->
                                if (q.isBlank() || pinnedChatIds.isEmpty()) return@SearchScreen
                                scope.launch {
                                    isSearchingRemote = true
                                    remoteResults = runCatching {
                                        app.telegramClient.searchAcrossChannels(pinnedChatIds, q)
                                    }.onFailure { Timber.w(it, "Remote search failed for query=%s", q) }
                                        .getOrDefault(emptyList())
                                    isSearchingRemote = false
                                    app.database.searchHistoryDao().upsert(
                                        com.velastudio.teltv.data.local.SearchHistoryEntity(
                                            query = q, lastUsedEpochSec = System.currentTimeMillis() / 1000
                                        )
                                    )
                                }
                            },
                            onRecentQueryPicked = { },
                            onOpenItem = { media ->
                                navController.navigate("player/${URLEncoder.encode(media.id, "UTF-8")}")
                            }
                        )
                    }

                    composable(
                        "player/{mediaId}?playlistId={playlistId}",
                        arguments = listOf(
                            navArgument("mediaId") { type = NavType.StringType },
                            navArgument("playlistId") { type = NavType.StringType; nullable = true; defaultValue = null }
                        )
                    ) { backStackEntry ->
                        val mediaId = URLDecoder.decode(backStackEntry.arguments?.getString("mediaId") ?: "", "UTF-8")
                        val playlistId = backStackEntry.arguments
                            ?.takeIf { it.containsKey("playlistId") }
                            ?.getString("playlistId")?.toLongOrNull()
                        var resolved by remember { mutableStateOf<WatchStateEntity?>(null) }
                        var fileId by remember { mutableStateOf<Int?>(null) }
                        var title by remember { mutableStateOf(mediaId) }
                        var resumeMs by remember { mutableStateOf(0L) }

                        LaunchedEffect(mediaId) {
                            val existingState = app.database.watchStateDao().get(mediaId)
                            resumeMs = existingState?.positionMs ?: 0L
                            val cachedEntity = app.database.videoIndexDao().getByMediaId(mediaId)
                            title = cachedEntity?.title ?: existingState?.title?.ifBlank { null } ?: mediaId

                            // Parse chatId and msgId to get the LIVE session fileId from Telegram
                            val parts = mediaId.removePrefix("tg:").split(":")
                            val cId = parts.getOrNull(0)?.toLongOrNull()
                            val mId = parts.getOrNull(1)?.toLongOrNull()

                            if (cId != null && mId != null) {
                                val fresh = app.telegramClient.getFreshFileId(cId, mId)
                                fileId = fresh ?: cachedEntity?.streamUrl?.removePrefix("tdlib://file/")?.toIntOrNull()
                            } else {
                                fileId = cachedEntity?.streamUrl?.removePrefix("tdlib://file/")?.toIntOrNull()
                            }
                        }

                        var nextEntity by remember { mutableStateOf<com.velastudio.teltv.data.local.VideoIndexEntity?>(null) }
                        var prevEntity by remember { mutableStateOf<com.velastudio.teltv.data.local.VideoIndexEntity?>(null) }
                        LaunchedEffect(mediaId, playlistId) {
                            val cur = app.database.videoIndexDao().getByMediaId(mediaId)
                            val playlistItems = if (playlistId != null) app.database.playlistDao().getItems(playlistId) else emptyList()
                            val curPos = playlistItems.firstOrNull { it.mediaId == mediaId }?.position ?: -1
                            nextEntity = if (playlistId != null) {
                                app.database.playlistDao().nextItem(playlistId, curPos)?.let { app.database.videoIndexDao().getByMediaId(it.mediaId) }
                            } else if (cur != null) {
                                com.velastudio.teltv.util.HybridEpisodeMatcher.findNext(cur, app.database.videoIndexDao())
                            } else {
                                null
                            }
                            prevEntity = if (playlistId != null) {
                                app.database.playlistDao().previousItem(playlistId, curPos)?.let { app.database.videoIndexDao().getByMediaId(it.mediaId) }
                            } else if (cur != null) {
                                com.velastudio.teltv.util.HybridEpisodeMatcher.findPrevious(cur, app.database.videoIndexDao())
                            } else {
                                null
                            }
                        }

                        PlayerScreen(
                            fileId = fileId,
                            directUri = null,
                            title = title,
                            resumePositionMs = resumeMs,
                            nextTitle = nextEntity?.title,
                            autoPlayByDefault = false,
                            onPlayPrevious = prevEntity?.let { prev ->
                                {
                                    val prevRoute = "player/${URLEncoder.encode(prev.mediaId, "UTF-8")}" +
                                        (playlistId?.let { "?playlistId=$it" } ?: "")
                                    navController.navigate(prevRoute) {
                                        popUpTo("player/{mediaId}") { inclusive = true }
                                    }
                                }
                            },
                            onPlayNext = nextEntity?.let { next ->
                                {
                                    val nextRoute = "player/${URLEncoder.encode(next.mediaId, "UTF-8")}" +
                                        (playlistId?.let { "?playlistId=$it" } ?: "")
                                    navController.navigate(nextRoute) {
                                        popUpTo("player/{mediaId}") { inclusive = true }
                                    }
                                }
                            },
                            onPositionUpdate = { positionMs, durationMs ->
                                scope.launch {
                                    app.database.watchStateDao().upsert(
                                        WatchStateEntity(
                                            mediaId = mediaId,
                                            positionMs = positionMs,
                                            durationMs = durationMs,
                                            lastWatchedEpochSec = System.currentTimeMillis() / 1000,
                                            title = title,
                                            finished = durationMs > 0 && positionMs >= durationMs * 0.95
                                        )
                                    )
                                }
                            },
                            onPlaybackEnded = { navController.popBackStack() },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("settings") {
                        var cacheSize by remember { mutableStateOf(0L) }
                        var freeStorage by remember { mutableStateOf(app.filesDir.usableSpace) }
                        var totalStorage by remember { mutableStateOf(app.filesDir.totalSpace) }
                        var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
                        val cachePrefs = remember { com.velastudio.teltv.worker.CachePrefs(app) }
                        // Both now read from (and, via the callbacks below, write to) the same
                        // DataStore that CacheTrimWorker reads in the background -- previously
                        // this was local `remember` state that the worker never saw.
                        val autoClearEnabled by cachePrefs.autoClearEnabled.collectAsState(initial = true)
                        val playbackPrefs = remember { PlaybackPrefs(app) }
                        val skipMs by playbackPrefs.skipIncrementMs.collectAsState(initial = PlaybackPrefs.DEFAULT_SKIP_MS)
                        val fastModeEnabled by playbackPrefs.fastModeEnabled.collectAsState(initial = false)

                        LaunchedEffect(Unit) {
                            cacheSize = runCatching {
                                com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute).getCurrentSizeBytes()
                            }.onFailure { Timber.e(it, "Failed to read cache size") }
                                .getOrDefault(0L)
                            freeStorage = app.filesDir.usableSpace
                            totalStorage = app.filesDir.totalSpace
                            playlists = app.database.playlistDao().getAll()
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            item {
                                AppUpdateSection()
                            }
                            item {
                            CacheSettingsSection(
                                currentSizeBytes = cacheSize,
                                freeStorageBytes = freeStorage,
                                totalStorageBytes = totalStorage,
                                autoClearEnabled = autoClearEnabled,
                                onToggleAutoClear = { scope.launch { cachePrefs.setAutoClearEnabled(it) } },
                                onClearNow = {
                                    scope.launch {
                                        com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute).clearAllNow()
                                        cacheSize = runCatching {
                                            com.velastudio.teltv.telegram.CacheManager(app.telegramClient::execute).getCurrentSizeBytes()
                                        }.getOrDefault(0L)
                                        freeStorage = app.filesDir.usableSpace
                                        totalStorage = app.filesDir.totalSpace
                                    }
                                }
                            )
                            }
                            item {
                            PlaylistSettingsSection(
                                playlists = playlists,
                                onCreate = { name ->
                                    scope.launch {
                                        val dao = app.database.playlistDao()
                                        dao.insert(PlaylistEntity(name = name, createdEpochSec = System.currentTimeMillis() / 1000))
                                        playlists = dao.getAll()
                                    }
                                },
                                onPlay = { playlist ->
                                    scope.launch {
                                        val first = app.database.playlistDao().getItems(playlist.id).firstOrNull()
                                        if (first != null) {
                                            navController.navigate("player/${URLEncoder.encode(first.mediaId, "UTF-8")}?playlistId=${playlist.id}")
                                        }
                                    }
                                },
                                onDelete = { playlist ->
                                    scope.launch {
                                        app.database.playlistDao().delete(playlist.id)
                                        playlists = app.database.playlistDao().getAll()
                                    }
                                }
                            )
                            }
                            item {
                            PlaybackSettingsSection(
                                skipIncrementMs = skipMs,
                                onSkipIncrementChanged = { scope.launch { playbackPrefs.setSkipIncrementMs(it) } }
                            )
                            }
                            item {
                            PerformanceSettingsSection(
                                fastModeEnabled = fastModeEnabled,
                                onToggleFastMode = { enabled -> scope.launch { playbackPrefs.setFastModeEnabled(enabled) } }
                            )
                            }
                            item {
                            AppInfoSection()
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StartupErrorScreen(report: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFF090B10))
            .padding(40.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color(0xFF1E1E1E), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            androidx.tv.material3.Text(
                text = "TelTV Startup Error",
                style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
                color = androidx.compose.ui.graphics.Color(0xFFFFC107)
            )
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
            androidx.tv.material3.Text(
                text = report.take(3000),
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White
            )
        }
    }
}
