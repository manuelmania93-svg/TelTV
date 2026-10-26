package com.velastudio.teltv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ViewHeadline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.tv.material3.*
import com.velastudio.teltv.R
import com.velastudio.teltv.telegram.ThumbnailLoader
import com.velastudio.teltv.ui.common.PosterCard
import com.velastudio.teltv.ui.settings.ClearCacheConfirmDialog
import com.velastudio.teltv.ui.theme.TelTvBlack
import com.velastudio.teltv.ui.theme.TelTvMuted
import com.velastudio.teltv.ui.theme.TelTvPanel
import com.velastudio.teltv.ui.theme.TelTvPanelFocused
import com.velastudio.teltv.ui.theme.TelTvWhite
import com.velastudio.teltv.ui.theme.TelTvYellow
import kotlinx.coroutines.launch

data class HomeRow(val title: String, val entries: List<HomeEntry>)
data class HomeEntry(val id: String, val name: String, val subtitle: String? = null, val thumbnailFileId: Int? = null)
data class ContinueWatchingEntry(
    val mediaId: String,
    val title: String,
    val progressFraction: Float,
    val thumbnailFileId: Int? = null,
    val showName: String? = null
)

enum class HomeFilterMode { ALL, PINNED_ONLY, FOLDERS_ONLY }

/** A pending remove confirmation: which entry, which button label, which delete action. */
private data class RemoveRequest(
    val entry: ContinueWatchingEntry,
    val actionLabel: String,
    val onRemove: (mediaId: String) -> Unit,
    val restoreFocus: (removed: Boolean) -> Unit
)

/**
 * Returns TV-remote focus to the row after the remove overlay closes: tries each requester
 * (Cancel/Back: the long-pressed card; Remove: its neighbors, since the deleted card's
 * requester detaches and fails) with retries, because the deletion recomposition and the
 * window's focus readiness are both asynchronous. Without this the overlay dismissal dropped
 * focus to the screen default and the user lost their place in the row.
 */
internal fun restoreFocusWithRetry(scope: CoroutineScope, targets: List<FocusRequester>) {
    scope.launch {
        var attempts = 0
        while (attempts < 40) {
            for (target in targets) {
                // requestFocus() returns Unit in this Compose version and a non-throwing call
                // does NOT prove the grant landed - the window may not be focus-ready yet and
                // the request silently no-ops. So keep re-requesting the first attached
                // target for the whole window; re-requesting one target is idempotent. A
                // detached requester (removed card) throws and the walk moves on.
                if (runCatching { target.requestFocus() }.isSuccess) break
            }
            attempts++
            delay(50)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    thumbnailLoader: ThumbnailLoader,
    continueWatching: List<ContinueWatchingEntry>,
    recentlyWatched: List<ContinueWatchingEntry>,
    favorites: List<ContinueWatchingEntry>,
    pinned: HomeRow,
    allChannels: HomeRow,
    folderRows: List<HomeRow>,
    otherSources: HomeRow,
    isLoading: Boolean = false,
    onOpenEntry: (HomeEntry) -> Unit,
    onResumeWatching: (mediaId: String) -> Unit,
    onRemoveFromHistory: (mediaId: String) -> Unit,
    onRemoveFavorite: (mediaId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuickClearCache: (() -> Unit)? = null
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.velastudio.teltv.util.UpdateInfo?>(null) }
    var dismissedVersion by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var filterMode by remember { mutableStateOf(HomeFilterMode.ALL) }
    var removeRequest by remember { mutableStateOf<RemoveRequest?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    androidx.activity.compose.BackHandler {
        (context as? android.app.Activity)?.finishAffinity()
    }

    LaunchedEffect(Unit) {
        val info = com.velastudio.teltv.util.AppUpdater.checkForUpdate()
        if (info != null && info.versionName != dismissedVersion) {
            updateInfo = info
        }
    }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // While the remove confirmation overlay is open, the shelf content cannot hold
            // or gain focus: remote keys pressed in the focus-handoff window (before the
            // overlay root is granted focus) hit nothing instead of the card behind.
            .focusProperties { canFocus = removeRequest == null }
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // App Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher),
                        contentDescription = "TelTV logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(84.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "TelTV",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = TelTvYellow
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Manuel Mania",
                        style = MaterialTheme.typography.labelSmall,
                        color = TelTvMuted
                    )
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(TelTvPanel)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Telegram TV",
                            style = MaterialTheme.typography.labelSmall,
                            color = TelTvMuted
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onOpenSearch,
                        colors = ButtonDefaults.colors(
                            containerColor = TelTvPanel,
                            focusedContainerColor = TelTvYellow
                        )
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = TelTvWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Search", color = TelTvWhite)
                    }

                    Button(
                        onClick = onOpenSettings,
                        colors = ButtonDefaults.colors(
                            containerColor = TelTvPanel,
                            focusedContainerColor = TelTvYellow
                        )
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TelTvWhite)
                        Spacer(Modifier.width(8.dp))
                        Text("Settings", color = TelTvWhite)
                    }

                    Button(
                        onClick = { (context as? android.app.Activity)?.finishAffinity() },
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFF1E2638),
                            focusedContainerColor = Color(0xFFE53935)
                        )
                    ) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = "Exit App", tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Exit", color = Color.White)
                    }

                    if (onQuickClearCache != null) {
                        Button(
                            onClick = { showClearConfirm = true },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0xFF1E2638),
                                focusedContainerColor = Color(0xFFEF5350)
                            )
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear cache", tint = Color.White)
                        }
                    }
                }
            }
        }

        // View Filter Tabs: All / Pinned Only / Folders Only
        item {
            Row(
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterTabButton(
                    label = "All",
                    icon = Icons.Filled.ViewHeadline,
                    selected = filterMode == HomeFilterMode.ALL,
                    onClick = { filterMode = HomeFilterMode.ALL }
                )
                FilterTabButton(
                    label = "Pinned Only",
                    icon = Icons.Filled.PushPin,
                    selected = filterMode == HomeFilterMode.PINNED_ONLY,
                    onClick = { filterMode = HomeFilterMode.PINNED_ONLY }
                )
                FilterTabButton(
                    label = "Folders Only",
                    icon = Icons.Filled.Folder,
                    selected = filterMode == HomeFilterMode.FOLDERS_ONLY,
                    onClick = { filterMode = HomeFilterMode.FOLDERS_ONLY }
                )
            }
        }

        // Continue Watching Row (hidden if in FOLDERS_ONLY)
        if (filterMode != HomeFilterMode.FOLDERS_ONLY && continueWatching.isNotEmpty()) {
            item {
                ContinueWatchingRow(
                    entries = continueWatching,
                    thumbnailLoader = thumbnailLoader,
                    onResumeWatching = onResumeWatching,
                    onRequestRemove = { entry, label, restore ->
                        removeRequest = RemoveRequest(entry, label, onRemoveFromHistory, restore)
                    },
                    removeActionLabel = "Remove from Continue Watching",
                    contentFocusEnabled = removeRequest == null
                )
            }
        }

        if (filterMode != HomeFilterMode.FOLDERS_ONLY && recentlyWatched.isNotEmpty()) {
            item {
                ContinueWatchingRow(
                    entries = recentlyWatched,
                    thumbnailLoader = thumbnailLoader,
                    onResumeWatching = onResumeWatching,
                    title = "Watch History",
                    onRequestRemove = { entry, label, restore ->
                        removeRequest = RemoveRequest(entry, label, onRemoveFromHistory, restore)
                    },
                    removeActionLabel = "Remove from history",
                    contentFocusEnabled = removeRequest == null
                )
            }
        }

        if (filterMode != HomeFilterMode.FOLDERS_ONLY && favorites.isNotEmpty()) {
            item {
                ContinueWatchingRow(
                    entries = favorites,
                    thumbnailLoader = thumbnailLoader,
                    onResumeWatching = onResumeWatching,
                    title = "Favorites",
                    onRequestRemove = { entry, label, restore ->
                        removeRequest = RemoveRequest(entry, label, onRemoveFavorite, restore)
                    },
                    removeActionLabel = "Remove favorite",
                    contentFocusEnabled = removeRequest == null
                )
            }
        }

        // Pinned Channels Row (hidden if in FOLDERS_ONLY)
        if (filterMode != HomeFilterMode.FOLDERS_ONLY && pinned.entries.isNotEmpty()) {
            item {
                HomeRowView(pinned, thumbnailLoader, onOpenEntry)
            }
        }

        // Chat Folders Shelves (hidden if in PINNED_ONLY)
        if (filterMode != HomeFilterMode.PINNED_ONLY) {
            items(folderRows, key = { it.title }) { row ->
                if (row.entries.isNotEmpty()) {
                    HomeRowView(row, thumbnailLoader, onOpenEntry)
                }
            }
        }

        // All Channels & Groups (only shown in ALL mode)
        if (filterMode == HomeFilterMode.ALL && allChannels.entries.isNotEmpty()) {
            item {
                HomeRowView(allChannels, thumbnailLoader, onOpenEntry)
            }
        }

        // Other Sources Row
        if (filterMode == HomeFilterMode.ALL && otherSources.entries.isNotEmpty()) {
            item {
                HomeRowView(otherSources, thumbnailLoader, onOpenEntry)
            }
        }

        // Feedback / Empty state
        val hasContent = (filterMode == HomeFilterMode.ALL && (pinned.entries.isNotEmpty() || allChannels.entries.isNotEmpty() || folderRows.isNotEmpty())) ||
                         (filterMode == HomeFilterMode.PINNED_ONLY && pinned.entries.isNotEmpty()) ||
                         (filterMode == HomeFilterMode.FOLDERS_ONLY && folderRows.isNotEmpty())

        if (isLoading && !hasContent) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = Color(0xFFFFC107),
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Loading channels and folders...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }
        } else if (!hasContent) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF161D27))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Tv,
                            contentDescription = null,
                            tint = Color(0xFF29B6F6),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = when (filterMode) {
                                HomeFilterMode.PINNED_ONLY -> "No Pinned Channels Found"
                                HomeFilterMode.FOLDERS_ONLY -> "No Chat Folders Found"
                                else -> "No Channels Found"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (filterMode) {
                                HomeFilterMode.PINNED_ONLY -> "Pin your favorite movie or media channels in Telegram to see them here!"
                                HomeFilterMode.FOLDERS_ONLY -> "Create folders (e.g. Movies, Series) in Telegram Settings > Folders."
                                else -> "Join channels with videos to start streaming!"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFB0BEC5)
                        )
                    }
                }
            }
        }
    }

        // Remove confirmation, rendered INSIDE the home window's own composition (last = on
        // top): a separate Dialog window never received D-pad/OK keys on the real TCL TV -
        // focus visibly stayed on the card behind it and only Back (window-level) worked.
        removeRequest?.let { request ->
            RemoveEntryDialog(
                title = request.entry.title,
                actionLabel = request.actionLabel,
                onRemove = {
                    request.onRemove(request.entry.mediaId)
                    removeRequest = null
                    request.restoreFocus(true)
                },
                onDismiss = {
                    removeRequest = null
                    request.restoreFocus(false)
                }
            )
        }
    }

    if (updateInfo != null) {
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            isDownloading = isDownloading,
            downloadProgress = downloadProgress,
            onDownload = {
                coroutineScope.launch {
                    isDownloading = true
                    val installResult = com.velastudio.teltv.util.AppUpdater.downloadAndInstall(context, updateInfo!!.downloadUrl) { p ->
                        downloadProgress = p
                    }
                    isDownloading = false
                    val message = when (installResult) {
                        com.velastudio.teltv.util.InstallResult.INSTALLER_OPENED -> "Opening package installer..."
                        com.velastudio.teltv.util.InstallResult.PERMISSION_REQUIRED -> "Grant install permission, return here, then try again."
                        com.velastudio.teltv.util.InstallResult.FAILED -> "Download or installer failed. Please retry."
                    }
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = {
                dismissedVersion = updateInfo?.versionName
                updateInfo = null
            }
        )
    }

    if (showClearConfirm && onQuickClearCache != null) {
        ClearCacheConfirmDialog(
            onDismiss = { showClearConfirm = false },
            onConfirm = onQuickClearCache
        )
    }
}

@Composable
private fun FilterTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0xFF004D73) else Color(0xFF1E2638),
            focusedContainerColor = Color(0xFF29B6F6)
        )
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun ContinueWatchingRow(
    entries: List<ContinueWatchingEntry>,
    thumbnailLoader: ThumbnailLoader,
    onResumeWatching: (mediaId: String) -> Unit,
    title: String = "Continue Watching",
    onRequestRemove: ((entry: ContinueWatchingEntry, actionLabel: String, restoreFocus: (removed: Boolean) -> Unit) -> Unit)? = null,
    removeActionLabel: String = "Remove",
    contentFocusEnabled: Boolean = true
) {
    // One FocusRequester per card so the remove overlay can hand remote focus back to the
    // exact card on Cancel/Back, or to the nearest remaining card after a removal.
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val rowScope = rememberCoroutineScope()

    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        LazyRow( horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(entries, key = { it.mediaId }) { entry ->
                PosterCard(
                    title = entry.title,
                    thumbnailFileId = entry.thumbnailFileId,
                    thumbnailLoader = thumbnailLoader,
                    resumeFraction = entry.progressFraction,
                    enableTmdb = true,
                    fallbackTitle = entry.showName,
                    onClick = { onResumeWatching(entry.mediaId) },
                    onLongClick = if (onRequestRemove != null) ({
                        val index = entries.indexOfFirst { it.mediaId == entry.mediaId }
                        // Cancel/Back -> the same card; after a removal its requester detaches
                        // and the retry loop walks on to the next/previous remaining card.
                        val focusChain = listOfNotNull(
                            cardFocusRequesters[entry.mediaId],
                            entries.getOrNull(index + 1)?.let { cardFocusRequesters[it.mediaId] },
                            entries.getOrNull(index - 1)?.let { cardFocusRequesters[it.mediaId] }
                        )
                        onRequestRemove(entry, removeActionLabel) { removed ->
                            // After a removal the card's own requester only detaches once the
                            // deletion recomposes - skip it, or focus could land on a card
                            // that is about to vanish and be lost again.
                            restoreFocusWithRetry(rowScope, if (removed) focusChain.drop(1) else focusChain)
                        }
                    }) else null,
                    // Gate on the card's own modifier chain too (single-target pattern): the
                    // long-pressed card is always the currently focused node when the overlay
                    // opens, so its own focus target must be the thing that deactivates.
                    modifier = Modifier
                        .focusProperties { canFocus = contentFocusEnabled }
                        .focusRequester(
                            cardFocusRequesters.getOrPut(entry.mediaId) { FocusRequester() }
                        )
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RemoveEntryDialog(
    title: String,
    actionLabel: String,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    // In-composition overlay, NOT a Dialog window: on the real TCL TV the separate dialog
    // window never received D-pad/OK keys (focus visibly stayed on the card behind it, only
    // Back worked). Rendered inside the home screen's own window, input takes the exact path
    // the working shelf rows use. Selection is a visible yellow border on the option itself;
    // the overlay root is the single focus target, so every remote key lands in onKeyEvent
    // below regardless of where the window first puts focus. Cancel is the default: the
    // destructive action stays one deliberate D-pad press away.
    val removeSelected = remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }

    // Back must dismiss even before the root is granted focus: while nothing in the window
    // holds focus, a Back key would otherwise reach the activity and could finish it with
    // the overlay still on screen.
    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .onFocusChanged { rootHasFocus = it.isFocused }
            .testTag("remove-entry-overlay-root")
            // Expose the real focus state so integration tests can wait until the overlay
            // observably holds window focus before injecting remote keys.
            .semantics { focused = rootHasFocus }
            // Swallow touches so nothing behind the scrim reacts on touch-capable devices.
            .pointerInput(Unit) { detectTapGestures { } }
            .focusRequester(rootFocus)
            .onKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) removeSelected.value = !removeSelected.value
                        true
                    }
                    // Trap focus: no vertical targets inside the overlay, and the rows behind
                    // must not receive navigation while the confirmation is open.
                    Key.DirectionUp, Key.DirectionDown -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyDown) {
                            if (removeSelected.value) onRemove() else onDismiss()
                        }
                        true
                    }
                    Key.Back, Key.Escape -> {
                        if (event.type == KeyEventType.KeyDown) onDismiss()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.width(440.dp).padding(24.dp)) {
                Text("Remove video?", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(title, maxLines = 2, color = TelTvMuted)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The buttons are deliberately NOT focus targets: the overlay root owns
                    // focus and key handling, the border + selected semantics show the state.
                    Button(
                        onClick = onRemove,
                        modifier = Modifier
                            .focusProperties { canFocus = false }
                            .then(
                                if (removeSelected.value) {
                                    Modifier.border(3.dp, TelTvYellow, RoundedCornerShape(50))
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        // Selection marker sits on the text so it merges into the button's
                        // semantics node (tests assert selected state there).
                        Text(actionLabel, modifier = Modifier.semantics { selected = removeSelected.value })
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusProperties { canFocus = false }
                            .then(
                                if (!removeSelected.value) {
                                    Modifier.border(3.dp, TelTvYellow, RoundedCornerShape(50))
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Text("Cancel", modifier = Modifier.semantics { selected = !removeSelected.value })
                    }
                }
            }
        }
    }

    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) {
        // Deterministic handoff: drop whatever the shelf held (the gate stops it from coming
        // back), then request the root until onFocusChanged observably grants it. The first
        // requests can land before the window is ready, so retry fast at first, then back
        // off - never capped, because giving up would soft-lock the TV on a keyless overlay,
        // and never hot: the loop always sleeps between attempts and is cancelled with the
        // overlay. Back stays safe throughout via the BackHandler above.
        focusManager.clearFocus()
        var attempts = 0
        while (!rootHasFocus) {
            runCatching { rootFocus.requestFocus() }
            delay(if (attempts < 40) 50 else 250)
            attempts++
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HomeRowView(row: HomeRow, thumbnailLoader: ThumbnailLoader, onOpenEntry: (HomeEntry) -> Unit) {
    Column {
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(12.dp))
        LazyRow( horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.entries, key = { it.id }) { entry ->
                PosterCard(
                    title = entry.name,
                    subtitle = entry.subtitle,
                    thumbnailFileId = entry.thumbnailFileId,
                    thumbnailLoader = thumbnailLoader,
                    enableTmdb = false,
                    onClick = { onOpenEntry(entry) }
                )
            }
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    updateInfo: com.velastudio.teltv.util.UpdateInfo,
    isDownloading: Boolean,
    downloadProgress: Float,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.activity.compose.BackHandler(onBack = onDismiss)
        Card(onClick = {}) {
            Column(Modifier.padding(24.dp)) {
                Text("Update Available (v${updateInfo.versionName})", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(updateInfo.changelog)
                Spacer(Modifier.height(16.dp))
                if (isDownloading) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onDismiss) { Text("Later") }
                        Button(onClick = onDownload) { Text("Update Now") }
                    }
                }
            }
        }
    }
}
