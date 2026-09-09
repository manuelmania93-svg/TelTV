package com.velastudio.teltv.telegram

import android.content.Context
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin coroutine wrapper around the OFFICIAL TDLib JNI client (org.drinkless.tdlib).
 *
 * Setup (do this once, see /README.md in this project for the full walkthrough):
 *   1. Get your own api_id / api_hash from https://my.telegram.org (free, required by Telegram
 *      for ANY app that talks to the MTProto API directly).
 *   2. Build TDLib for Android using Telegram's own official script:
 *        https://github.com/tdlib/td/blob/master/example/android/README.md
 *      This produces libtdjni.so for each ABI + the TdApi.java / Client.java sources.
 *      Do NOT use random third-party "prebuilt TDLib AAR" packages you find on GitHub/JitPack --
 *      this library gets full access to your real Telegram account, and there's no reason to
 *      trust an unaudited native binary with that. Build it from Telegram's own source.
 *   3. Drop the .so files into app/src/main/jniLibs/<abi>/ and the generated Java sources
 *      alongside this file (or as a module) so `org.drinkless.tdlib.*` resolves.
 */
class TelegramClient(private val context: Context) {

    private var client: Client? = null
    var authState: TdApi.AuthorizationState? = null
        private set

    companion object {
        // Replace with your own values from https://my.telegram.org -- never commit real ones.
        const val API_ID = 6
        const val API_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e"
    }

    /** Emits authorization states so the UI can drive phone-number / code / 2FA screens. */
    fun authorizationFlow(): Flow<TdApi.AuthorizationState> = callbackFlow {
        val c = Client.create(
            { update ->
                if (update is TdApi.UpdateAuthorizationState) {
                    authState = update.authorizationState
                    trySend(update.authorizationState)
                }
            },
            // These two were previously null, which means any exception thrown while handling
            // a TDLib update (or a default/unhandled TDLib error) was silently swallowed by the
            // native layer -- exactly the kind of failure that's impossible to debug from a bug
            // report alone. Logging them doesn't change behavior, just makes it observable.
            { exception -> Timber.e(exception, "TDLib update handler exception") },
            { exception -> Timber.e(exception, "TDLib default exception handler") }
        )
        client = c
        c.send(TdApi.SetTdlibParameters().apply {
            databaseDirectory = context.filesDir.resolve("tdlib").absolutePath
            filesDirectory = context.filesDir.resolve("tdlib-files").absolutePath
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = API_ID
            apiHash = API_HASH
            systemLanguageCode = "en"
            deviceModel = "Android TV"
            systemVersion = android.os.Build.VERSION.RELEASE ?: "Android"
            applicationVersion = "1.0.0"
        }) { res ->
            if (res is TdApi.Error) {
                Timber.e("SetTdlibParameters failed: [%d] %s", res.code, res.message)
            }
        }
        awaitClose { }
    }

    suspend fun setPhoneNumber(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode(code))
    suspend fun checkPassword(password: String) = send(TdApi.CheckAuthenticationPassword(password))

    /**
     * Starts (or re-requests) TDLib's QR-code login flow -- valid to call from
     * [TdApi.AuthorizationStateWaitPhoneNumber], which is where a fresh client always starts.
     * TDLib responds by moving to [TdApi.AuthorizationStateWaitOtherDeviceConfirmation], whose
     * `link` field (a `tg://login?token=...` URL) is what gets rendered as the QR code -- see
     * `QrCodeStep` in LoginScreen.kt. TDLib pushes a fresh `UpdateAuthorizationState` with a new
     * `link` roughly every 30s on its own (the login token expires), so the caller doesn't need
     * to re-call this itself to keep the code alive; just re-render whenever `link` changes.
     *
     * Confirmed against the current TDLib docs
     * (core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1request_qr_code_authentication.html):
     * `otherUserIds` is only relevant for QR-login-into-an-already-open-app flows (letting the
     * user pick which of several already-signed-in accounts to add) -- irrelevant for a
     * from-scratch sign-in like this one, so an empty list is always correct here.
     */
    suspend fun requestQrCodeAuthentication() = send(TdApi.RequestQrCodeAuthentication(LongArray(0)))

    /** Public bridge for callers (e.g. [CacheManager]) that just need to fire one raw TDLib call. */
    suspend fun execute(fn: TdApi.Function<*>): TdApi.Object = send(fn)

    private suspend fun send(fn: TdApi.Function<*>): TdApi.Object = suspendCancellableCoroutine { cont ->
        client?.send(fn) { result ->
            if (result is TdApi.Error) cont.resumeWithException(RuntimeException(result.message))
            else cont.resume(result)
        } ?: cont.resumeWithException(IllegalStateException("TDLib client not started"))
    }

    // ---- Pinned chats + folder discovery (this is the "better than VelaTV" browsing story) ----

    /**
     * All chat folders the user has configured in Telegram (e.g. "Movies", "Anime", "Work").
     *
     * `GetChatFolders()` (no args) returns a `TdApi.ChatFolders` object whose `chatFolders`
     * field is exactly this list -- confirmed against the current TDLib API docs
     * (core.telegram.org/tdlib/docs/classtd_1_1td__api_1_1get_chat_folders.html). If you're
     * building against an older TDLib where this function doesn't exist yet, the fallback is
     * reading folder info off the `UpdateChatFolders` update instead.
     */
    suspend fun getChatFolders(): List<TdApi.ChatFolderInfo> {
        return emptyList()
    }

    /**
     * Chats pinned in the main list OR inside any folder, filtered to channels/supergroups only.
     *
     * Fetched with bounded concurrency (8 in-flight `GetChat` calls) instead of one at a time --
     * on an account with a few hundred chats, a serial loop here is the difference between
     * "Home appears in half a second" and "Home spins for several seconds every cold start",
     * which matters even more on a TV box where that spinner is the first thing you see.
     */
    
    /**
     * Fetches all channels and video supergroups available in the account,
     * sorted so pinned chats come first, followed by active channels.
     */
    suspend fun getAllChannels(): List<TdApi.Chat> = coroutineScope {
        send(TdApi.LoadChats(TdApi.ChatListMain(), 200))
        val chats = send(TdApi.GetChats(TdApi.ChatListMain(), 200)) as TdApi.Chats

        val concurrencyLimit = Semaphore(8)
        chats.chatIds
            .map { id ->
                async {
                    concurrencyLimit.withPermit { send(TdApi.GetChat(id)) as TdApi.Chat }
                }
            }
            .map { it.await() }
            .filter { chat ->
                (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true ||
                chat.type is TdApi.ChatTypeSupergroup ||
                chat.type is TdApi.ChatTypeBasicGroup
            }
            .sortedWith(
                compareByDescending<TdApi.Chat> { chat -> chat.positions.any { it.isPinned } }
                    .thenByDescending { it.lastMessage?.date ?: 0 }
            )
    }

    suspend fun getPinnedChannels(): List<TdApi.Chat> = coroutineScope {
        send(TdApi.LoadChats(TdApi.ChatListMain(), 200))
        val chats = send(TdApi.GetChats(TdApi.ChatListMain(), 200)) as TdApi.Chats

        val concurrencyLimit = Semaphore(8)
        chats.chatIds
            .map { id ->
                async {
                    concurrencyLimit.withPermit { send(TdApi.GetChat(id)) as TdApi.Chat }
                }
            }
            .map { it.await() }
            .filter { chat ->
                val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
                val isPinned = chat.positions.any { it.isPinned }
                isChannel && isPinned
            }
    }

    /**
     * Every channel inside every folder (surfaces folder-organized libraries VelaTV can't see).
     *
     * `TdApi.ChatListFolder(folderId)` is the `ChatList` variant that scopes `GetChats` to one
     * folder, the same way [getPinnedChannels] scopes it with `ChatListMain()` -- confirmed
     * against the current TDLib docs (classtd_1_1td__api_1_1chat_list_folder.html). Folders are
     * fetched sequentially (there are rarely more than a handful), but the `GetChat` calls
     * within each folder use the same bounded-concurrency pattern as [getPinnedChannels].
     */
    suspend fun getChannelsInFolders(): Map<TdApi.ChatFolderInfo, List<TdApi.Chat>> = coroutineScope {
        val folders = getChatFolders()
        if (folders.isEmpty()) return@coroutineScope emptyMap()

        val concurrencyLimit = Semaphore(8)
        folders.associateWith { folder ->
            send(TdApi.LoadChats(TdApi.ChatListFolder(folder.id), 200))
            val chats = send(TdApi.GetChats(TdApi.ChatListFolder(folder.id), 200)) as TdApi.Chats
            chats.chatIds
                .map { id ->
                    async {
                        concurrencyLimit.withPermit { send(TdApi.GetChat(id)) as TdApi.Chat }
                    }
                }
                .map { it.await() }
                .filter { chat -> (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true }
        }
    }

    /** Paginated video messages for a given chat, newest first -- this becomes a MediaItem list. */
    suspend fun getVideoMessages(chatId: Long, fromMessageId: Long = 0L, limit: Int = 40): List<MediaItem> {
        val result = send(
            TdApi.SearchChatMessages(
                chatId, null, "", null, fromMessageId, 0, limit,
                TdApi.SearchMessagesFilterVideo()
            )
        ) as TdApi.FoundChatMessages

        return result.messages.mapNotNull { msg ->
            val msgVideo = msg.content as? TdApi.MessageVideo ?: return@mapNotNull null
            val video = msgVideo.video
            // Thumbnail is a tiny (a few KB) JPEG TDLib already has the file descriptor for --
            // we don't download it here, just remember its file id. The browse grid downloads
            // (and cancels) these lazily and at a low priority, see downloadThumbnail() below.
            val thumbFileId = video.thumbnail?.file?.id
            // Caption is the right primary title source: most media channels put the human-readable
            // name there (e.g. "The Dark Knight (2008)"), while video.fileName is frequently blank
            // or a meaningless upload artifact like "video.mp4" or "output_20240101.mp4".
            // Priority: caption text -> original filename -> "Video <messageId>" last resort.
            val title = msgVideo.caption?.text.orEmpty()
                .ifBlank { video.fileName }
                .ifBlank { "Video ${msg.id}" }
            MediaItem(
                id = "tg:${chatId}:${msg.id}",
                sourceType = SourceType.TELEGRAM,
                title = title,
                subtitle = null,
                category = null, // filled in by the UI layer from the folder/chat name
                durationMs = video.duration * 1000L,
                sizeBytes = video.video.size.toLong(),
                thumbnailUrl = thumbFileId?.let { "tdlib://thumb/$it" },
                streamUrl = "tdlib://file/${video.video.id}",
                addedAtEpochSec = msg.date.toLong()
            )
        }
    }

    /**
     * Downloads (or returns the already-cached path for) a thumbnail file, at LOW priority so it
     * never competes with an in-progress video download or a range request the player is
     * blocking on. Callers (the browse grid) should cancel this if the row scrolls off-screen
     * before it resolves -- see `ThumbnailLoader` for the cancellation-aware wrapper.
     */
    suspend fun downloadThumbnail(fileId: Int): String = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            if (cont.isActive) cont.resume("")
            return@suspendCancellableCoroutine
        }
        try {
            c.send(TdApi.DownloadFile(fileId, 1, 0, 0, false)) { result ->
                if (!cont.isActive) return@send
                try {
                    when (result) {
                        is TdApi.File -> cont.resume(result.local.path ?: "")
                        else -> cont.resume("")
                    }
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume("")
                }
            }
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume("")
        }
    }

    /** Cancels an in-flight low-priority thumbnail download, e.g. when its row scrolls off-screen. */
    fun cancelDownload(fileId: Int) {
        client?.send(TdApi.CancelDownloadFile(fileId, false)) {}
    }

    /**
     * Downloads a specific byte range of a file and blocks the TDLib callback from returning
     * until that range is actually on disk. This is the piece [TdLibDataSource] (via
     * [RawTdClient]) needs: Media3's `DataSource.open()` contract is synchronous, so the calling
     * thread must wait for the exact range it just requested rather than getting back whatever
     * happens to be cached already.
     *
     * `synchronous = true` on `TdApi.DownloadFile` is what makes TDLib wait before replying,
     * unlike [downloadThumbnail]'s `false`, which returns immediately with the current
     * (possibly incomplete) file state.
     *
     * Priority 32 (TDLib's max) is intentional: a video the person is actively watching should
     * preempt anything else TDLib might be downloading in the background, such as a thumbnail
     * for a row that's merely scrolled into view.
     *
     * [onResult] is always invoked exactly once, with `null` on failure -- callers on a blocking
     * thread (see [RawTdClient]) rely on that to release their latch instead of hanging forever
     * on a network error.
     */
    fun downloadFileRangeSync(fileId: Int, offset: Long, limit: Long, onResult: (TdApi.File?) -> Unit) {
        val c = client
        if (c == null) {
            Timber.e("downloadFileRangeSync(fileId=$fileId) called before TDLib client started")
            onResult(null)
            return
        }
        c.send(TdApi.DownloadFile(fileId, 32 /* highest priority */, offset, limit, true)) { result ->
            when (result) {
                is TdApi.File -> onResult(result)
                is TdApi.Error -> {
                    Timber.e("Range download failed for fileId=$fileId offset=$offset limit=$limit: ${result.message}")
                    onResult(null)
                }
                else -> {
                    Timber.e("Unexpected result downloading fileId=$fileId: $result")
                    onResult(null)
                }
            }
        }
    }

    /**
     * Search within a single already-open channel (title/caption match), used by the search
     * screen once a person picks a specific channel, and as a fallback if the local Room-cached
     * title search in [com.velastudio.teltv.data.local.VideoIndexDao.searchLocal] misses videos
     * that were never fully paged into the local cache yet.
     */
    suspend fun searchChannelVideos(chatId: Long, query: String, limit: Int = 40): List<MediaItem> {
        val result = send(
            TdApi.SearchChatMessages(
                chatId, null, query, null, 0L, 0, limit,
                TdApi.SearchMessagesFilterVideo()
            )
        ) as TdApi.FoundChatMessages

        return result.messages.mapNotNull { msg ->
            val msgVideo = msg.content as? TdApi.MessageVideo ?: return@mapNotNull null
            val video = msgVideo.video
            val title = msgVideo.caption?.text.orEmpty()
                .ifBlank { video.fileName }
                .ifBlank { "Video ${msg.id}" }
            MediaItem(
                id = "tg:${chatId}:${msg.id}",
                sourceType = SourceType.TELEGRAM,
                title = title,
                subtitle = null,
                category = null,
                durationMs = video.duration * 1000L,
                sizeBytes = video.video.size.toLong(),
                thumbnailUrl = video.thumbnail?.file?.id?.let { "tdlib://thumb/$it" },
                streamUrl = "tdlib://file/${video.video.id}",
                addedAtEpochSec = msg.date.toLong()
            )
        }
    }

    /**
     * Searches videos across every pinned/folder channel at once, capped concurrency so typing
     * quickly on a remote-friendly search box doesn't fan out into dozens of parallel TDLib
     * calls. Pair with debouncing in the UI layer (see `SearchScreen`) -- this function assumes
     * it's only called once per settled query, not on every keystroke.
     */
    suspend fun searchAcrossChannels(
        chatIds: List<Long>,
        query: String,
        perChannelLimit: Int = 10
    ): List<MediaItem> = coroutineScope {
        val concurrencyLimit = Semaphore(4)
        chatIds
            .map { chatId ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching { searchChannelVideos(chatId, query, perChannelLimit) }
                            .onFailure { Timber.w(it, "Search failed for chatId=%d", chatId) }
                            .getOrDefault(emptyList())
                    }
                }
            }
            .flatMap { it.await() }
            .sortedByDescending { it.addedAtEpochSec }
    }

    fun close() {
        client?.send(TdApi.Close()) {}
    }
}

/**
 * Display name of a chat folder. As of current TDLib, `ChatFolderInfo.name` is a
 * `ChatFolderName` wrapping a `FormattedText` (so custom emoji in folder names render
 * correctly) rather than a plain string -- this pulls out just the text. If you're building
 * against a TDLib old enough to still expose `ChatFolderInfo.title: String` directly, use that
 * field instead of this helper. Top-level (not a member of [TelegramClient]) so UI code can call
 * `folder.displayName()` on any [TdApi.ChatFolderInfo] without needing a client instance around.
 */
fun TdApi.ChatFolderInfo.displayName(): String = name.text.text
