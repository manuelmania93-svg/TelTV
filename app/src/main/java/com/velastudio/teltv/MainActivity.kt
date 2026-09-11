package com.velastudio.teltv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.velastudio.teltv.ui.settings.CacheSettingsSection
import com.velastudio.teltv.ui.settings.AppInfoSection
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
                val navController = rememberNavController()
                val scope = rememberCoroutineScope()
                
                var crashReport by remember { mutableStateOf(com.velastudio.teltv.util.CrashLogger.lastCrashReport(app)) }
                if (crashReport != null) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { 
                        java.io.File(app.filesDir, "last_crash.txt").delete()
                        crashReport = null 
                    }) {
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
                                    java.io.File(app.filesDir, "last_crash.txt").delete()
                                    crashReport = null
                                }) {
                                    androidx.tv.material3.Text("Dismiss")
                                }
                            }
                        }
                    }
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

                            // 3. Fallback: only if both Pinned and Folders are completely empty
                            if (pinned.isEmpty() && dynamicFolderRows.isEmpty()) {
                                val topChats = runCatching { app.telegramClient.getAllChannels(limit = 15) }.getOrDefault(emptyList())
                                allChannelsRow = HomeRow(
                                    "Channels",
                                    topChats.map { chat ->
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
                                val encodedTitle = URLEncoder.encode(entry.name, "UTF-8")
                                navController.navigate("browse/${entry.id}/$encodedTitle")
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
                        "browse/{chatId}/{title}",
                        arguments = listOf(
                            navArgument("chatId") { type = NavType.LongType },
                            navArgument("title") { type = NavType.StringType }
                        )
                    ) { backStackEntry ->
                        val chatId = backStackEntry.arguments?.getLong("chatId") ?: return@composable
                        val title = URLDecoder.decode(backStackEntry.arguments?.getString("title") ?: "", "UTF-8")

                        var resumeFractions by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
                        LaunchedEffect(chatId) {
                            // refreshNewest runs first: corrects stale titles on already-cached rows
                            // (e.g. after the caption-title fix) and prepends any new videos posted
                            // since the last visit. ensureNextPage then fills the first page if the
                            // cache was empty. Both are no-ops if nothing has changed.
                            app.channelVideoRepository.refreshNewest(chatId)
                            app.channelVideoRepository.ensureNextPage(chatId)
                            resumeFractions = app.database.watchStateDao().recentlyWatched(limit = 200)
                                .filter { it.mediaId.startsWith("tg:$chatId:") }
                                .associate { it.mediaId to (if (it.durationMs > 0) it.positionMs.toFloat() / it.durationMs else 0f) }
                        }

                        var isAscending by remember { mutableStateOf(true) }
                        BrowseScreen(
                            channelTitle = title,
                            isAscending = isAscending,
                            onToggleSort = { isAscending = !isAscending },
                            pagingFlow = remember(chatId, isAscending) { app.channelVideoRepository.videoPager(chatId, isAscending) },
                            thumbnailLoader = thumbnailLoader,
                            deviceProfile = app.deviceProfile,
                            resumeFractionFor = { mediaId -> resumeFractions[mediaId] },
                            onLoadMore = { scope.launch { app.channelVideoRepository.ensureNextPage(chatId) } },
                            onOpenItem = { media ->
                                navController.navigate("player/${URLEncoder.encode(media.id, "UTF-8")}")
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
                                    val playlist = PlaylistEntity(
                                        name = "$title Marathon",
                                        createdEpochSec = System.currentTimeMillis() / 1000
                                    ).let { it.copy(id = dao.insert(it)) }
                                    app.database.videoIndexDao().getAllForChat(chatId).forEachIndexed { index, video ->
                                        dao.addItem(
                                            PlaylistItemEntity(
                                                playlistId = playlist.id,
                                                mediaId = video.mediaId,
                                                position = index,
                                                title = video.title,
                                                addedEpochSec = System.currentTimeMillis() / 1000
                                            )
                                        )
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
                            navArgument("playlistId") { type = NavType.LongType; nullable = true; defaultValue = null }
                        )
                    ) { backStackEntry ->
                        val mediaId = URLDecoder.decode(backStackEntry.arguments?.getString("mediaId") ?: "", "UTF-8")
                        val playlistId = backStackEntry.arguments
                            ?.takeIf { it.containsKey("playlistId") }
                            ?.getLong("playlistId")
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
                        LaunchedEffect(mediaId, playlistId) {
                            val cur = app.database.videoIndexDao().getByMediaId(mediaId)
                            if (cur != null) {
                                nextEntity = if (playlistId != null) {
                                    app.database.playlistDao().nextItem(
                                        playlistId,
                                        app.database.playlistDao().getItems(playlistId)
                                            .firstOrNull { it.mediaId == mediaId }?.position ?: -1
                                    )?.let { app.database.videoIndexDao().getByMediaId(it.mediaId) }
                                } else {
                                    app.database.videoIndexDao().getNextInChannel(cur.chatId, cur.position)
                                }
                            }
                        }

                        PlayerScreen(
                            fileId = fileId,
                            directUri = null,
                            title = title,
                            resumePositionMs = resumeMs,
                            nextTitle = nextEntity?.title,
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
                            AppInfoSection()
                            }
                        }
                    }
                }
            }
        }
    }
}
