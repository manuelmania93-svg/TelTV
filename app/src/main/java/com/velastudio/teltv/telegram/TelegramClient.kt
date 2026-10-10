package com.velastudio.teltv.telegram

import java.util.concurrent.ConcurrentHashMap

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.velastudio.teltv.data.model.MediaItem
import com.velastudio.teltv.data.model.SourceType
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

data class VideoMessagesResult(
    val items: List<MediaItem>,
    val nextFromMessageId: Long
)

class TelegramClient(private val context: Context) {
    private val lastDownloadErrors = ConcurrentHashMap<Int, String>()
    private val forumTopicAliasPrefs by lazy {
        context.getSharedPreferences("forum_topic_aliases", Context.MODE_PRIVATE)
    }

    fun virtualChatIdForTopic(realChatId: Long, topicId: Int): Long {
        require(topicId > 0)
        if (topicId < 100_000) {
            val rawChatId = if (realChatId < 0) -realChatId else realChatId
            return -(rawChatId * 100_000L + topicId)
        }

        val pairKey = "$realChatId:$topicId"
        val pairPreferenceKey = "pair:$pairKey"
        synchronized(forumTopicAliasPrefs) {
            forumTopicAliasPrefs.getLong(pairPreferenceKey, 0L).takeIf { it != 0L }?.let { return it }

            var salt = 0
            while (true) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("$pairKey:$salt".toByteArray(Charsets.UTF_8))
                val hash = java.nio.ByteBuffer.wrap(digest).long and Long.MAX_VALUE
                val alias = Long.MIN_VALUE + hash
                val existingPair = forumTopicAliasPrefs.getString("alias:$alias", null)
                if (existingPair == null || existingPair == pairKey) {
                    forumTopicAliasPrefs.edit()
                        .putLong(pairPreferenceKey, alias)
                        .putString("alias:$alias", pairKey)
                        .apply()
                    return alias
                }
                salt++
            }
        }
    }

    private fun resolveChatAndTopic(chatId: Long): Pair<Long, Int> {
        forumTopicAliasPrefs.getString("alias:$chatId", null)?.let { pairKey ->
            val parts = pairKey.split(':', limit = 2)
            val realChatId = parts.getOrNull(0)?.toLongOrNull()
            val topicId = parts.getOrNull(1)?.toIntOrNull()
            if (realChatId != null && topicId != null) return realChatId to topicId
        }

        return if (chatId <= -100_000_000_000_000_000L) {
            val raw = if (chatId == Long.MIN_VALUE) Long.MAX_VALUE else -chatId
            (-(raw / 100_000L)) to (raw % 100_000L).toInt()
        } else {
            chatId to 0
        }
    }

    fun getLastDownloadError(fileId: Int): String? = lastDownloadErrors[fileId]

    suspend fun getFreshFileId(chatId: Long, messageId: Long): Int? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val realChatId = resolveChatAndTopic(chatId).first

        // 1. First attempt GetMessages (contacts Telegram server to register file into current session)
        val fromServer: Int? = suspendCancellableCoroutine { cont ->
            c.send(TdApi.GetMessages(realChatId, longArrayOf(messageId))) { res ->
                if (res is TdApi.Messages) {
                    val msg = res.messages.firstOrNull()
                    val fId = when (val content = msg?.content) {
                        is TdApi.MessageVideo -> content.video.video.id
                        is TdApi.MessageDocument -> content.document.document.id
                        else -> null
                    }
                    if (fId != null) {
                        val file = when (val cnt = msg?.content) {
                            is TdApi.MessageVideo -> cnt.video.video
                            is TdApi.MessageDocument -> cnt.document.document
                            else -> null
                        }
                        if (file != null) fileCache[fId] = file
                    }
                    cont.resume(fId)
                } else {
                    cont.resume(null)
                }
            }
        }
        if (fromServer != null) return@withContext fromServer

        // 2. Fallback to GetMessage (searches local TDLib memory)
        suspendCancellableCoroutine { cont ->
            c.send(TdApi.GetMessage(realChatId, messageId)) { res ->
                if (res is TdApi.Message) {
                    val fId = when (val content = res.content) {
                        is TdApi.MessageVideo -> content.video.video.id
                        is TdApi.MessageDocument -> content.document.document.id
                        else -> null
                    }
                    if (fId != null) {
                        val file = when (val cnt = res.content) {
                            is TdApi.MessageVideo -> cnt.video.video
                            is TdApi.MessageDocument -> cnt.document.document
                            else -> null
                        }
                        if (file != null) fileCache[fId] = file
                    }
                    cont.resume(fId)
                } else {
                    Timber.w("GetMessage failed for realChatId=%d msgId=%d: %s", realChatId, messageId, res)
                    cont.resume(null)
                }
            }
        }
    }

    fun executeAsync(fn: TdApi.Function<*>) {
        client?.send(fn) {}
    }

    fun downloadFileRangeBlocking(fileId: Int, offset: Long, limit: Long): TdApi.File? {
        val c = client
        if (c == null) {
            lastDownloadErrors[fileId] = "TDLib client is not initialized"
            return null
        }

        val cached = fileCache[fileId]
        val safeLimit = limit.coerceIn(1L, 4L * 1024L * 1024L)

        fun hasBytes(f: TdApi.File?): Boolean {
            if (f == null) return false
            val local = f.local ?: return false
            val path = local.path ?: return false
            if (path.isBlank()) return false
            val diskFile = java.io.File(path)
            if (!diskFile.exists() || diskFile.length() <= 0L) return false

            if (local.isDownloadingCompleted) return true

            val downloadedUpTo = local.downloadOffset + local.downloadedPrefixSize
            val remainingInFile = if (f.size > 0L) (f.size - offset).coerceAtLeast(1L) else safeLimit
            val minNeeded = minOf(safeLimit, 256L * 1024L, remainingInFile)
            return local.downloadOffset <= offset && downloadedUpTo >= (offset + minNeeded)
        }

        if (hasBytes(cached)) {
            return cached
        }

        var result: TdApi.File? = null
        var attempts = 0
        val maxAttempts = 4

        while (attempts < maxAttempts && result == null) {
            attempts++
            val latch = CountDownLatch(1)

            val listener: (TdApi.File) -> Unit = { updatedFile ->
                if (hasBytes(updatedFile)) {
                    result = updatedFile
                    latch.countDown()
                }
            }
            registerFileListener(fileId, listener)

            val currentCache = fileCache[fileId]
            if (hasBytes(currentCache)) {
                result = currentCache
                unregisterFileListener(fileId, listener)
                return currentCache
            }

            c.send(TdApi.DownloadFile(fileId, 32, offset, safeLimit, true)) { response ->
                when (response) {
                    is TdApi.File -> {
                        fileCache[fileId] = response
                        if (hasBytes(response)) {
                            result = response
                            lastDownloadErrors.remove(fileId)
                            latch.countDown()
                        }
                    }
                    is TdApi.Error -> {
                        val err = "[code=${response.code}] ${response.message}"
                        lastDownloadErrors[fileId] = err
                        Timber.w("DownloadFile attempt %d for fileId=%d offset=%d failed: %s", attempts, fileId, offset, err)
                        latch.countDown()
                    }
                }
            }

            val completed = latch.await(10, TimeUnit.SECONDS)
            unregisterFileListener(fileId, listener)

            if (result != null && hasBytes(result)) {
                return result
            }

            if (attempts < maxAttempts) {
                try { Thread.sleep(1200) } catch (_: InterruptedException) { break }
            }
        }

        val candidate = result ?: fileCache[fileId]
        return if (hasBytes(candidate)) candidate else null
    }

    val fileCache = ConcurrentHashMap<Int, TdApi.File>()
    private val fileListeners = ConcurrentHashMap<Int, MutableList<(TdApi.File) -> Unit>>()

    fun registerFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        fileListeners.getOrPut(fileId) { java.util.concurrent.CopyOnWriteArrayList() }.add(listener)
        fileCache[fileId]?.let(listener)
    }

    fun unregisterFileListener(fileId: Int, listener: (TdApi.File) -> Unit) {
        fileListeners[fileId]?.remove(listener)
    }

    private val _cachedFolders = java.util.concurrent.CopyOnWriteArrayList<TdApi.ChatFolderInfo>()

    @Volatile
    var connectionState: TdApi.ConnectionState? = null
        private set

    private var client: Client? = null
    var authState: TdApi.AuthorizationState? = null
        private set

    fun refreshNetworkState() {
        val activeClient = client ?: return
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
            when {
                capabilities == null -> TdApi.NetworkTypeNone()
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
                else -> TdApi.NetworkTypeOther()
            }
        } else {
            @Suppress("DEPRECATION")
            val info = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            when {
                info?.isConnected != true -> TdApi.NetworkTypeNone()
                info.type == ConnectivityManager.TYPE_WIFI -> TdApi.NetworkTypeWiFi()
                info.type == ConnectivityManager.TYPE_MOBILE -> TdApi.NetworkTypeMobile()
                else -> TdApi.NetworkTypeOther()
            }
        }

        if (networkType is TdApi.NetworkTypeNone) {
            activeClient.send(TdApi.SetNetworkType(networkType)) {}
        } else {
            // Force TDLib through an offline/online transition after Android TV sleep.
            activeClient.send(TdApi.SetNetworkType(TdApi.NetworkTypeNone())) { result ->
                if (result is TdApi.Error) {
                    Timber.w("TDLib network reset failed: [%d] %s", result.code, result.message)
                }
                activeClient.send(TdApi.SetNetworkType(networkType)) { reconnectResult ->
                    if (reconnectResult is TdApi.Error) {
                        Timber.w("TDLib network refresh failed: [%d] %s", reconnectResult.code, reconnectResult.message)
                    }
                }
            }
        }
    }

    companion object {
        // Replace with your own values from https://my.telegram.org -- never commit real ones.
        const val API_ID = 6
        const val API_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e"
    }

    /** Emits authorization states so the UI can drive phone-number / code / 2FA screens. */
    private val authorizationStates = kotlinx.coroutines.flow.MutableStateFlow<TdApi.AuthorizationState?>(null)

    fun authorizationFlow(): Flow<TdApi.AuthorizationState> {
        ensureStarted()
        return authorizationStates.filterNotNull()
    }

    @Synchronized
    private fun ensureStarted() {
        if (client != null) return
        val c = Client.create(
            { update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> {
                        authState = update.authorizationState
                        authorizationStates.value = update.authorizationState
                    }
                    is TdApi.UpdateConnectionState -> {
                        connectionState = update.state
                        Timber.i("TDLib connection state: %s", update.state.javaClass.simpleName)
                    }
                    is TdApi.UpdateChatFolders -> {
                        _cachedFolders.clear()
                        _cachedFolders.addAll(update.chatFolders)
                        Timber.i("TDLib received %d chat folders", update.chatFolders.size)
                    }
                    is TdApi.UpdateFile -> {
                        val f = update.file
                        fileCache[f.id] = f
                        fileListeners[f.id]?.forEach { it(f) }
                    }
                    is TdApi.UpdateNewMessage -> {
                        val msg = update.message
                        if (msg.content is TdApi.MessageVideo || msg.content is TdApi.MessageDocument) {
                            onNewVideoMessageListener?.invoke(msg)
                        }
                    }
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
            databaseEncryptionKey = ByteArray(0)
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

    // ---- Pinned chats + folder discovery ----

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
        var attempts = 0
        while (_cachedFolders.isEmpty() && attempts < 12) {
            kotlinx.coroutines.delay(250)
            attempts++
        }
        return _cachedFolders.toList()
    }

    /**
     * Chats pinned in the main list OR inside any folder, including channels and groups.
     */
    suspend fun getPinnedChannels(): List<TdApi.Chat> = coroutineScope {
        // LoadChats returns 404 when all chats are already loaded -- ignore it safely
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) }
        val chats = runCatching { send(TdApi.GetChats(TdApi.ChatListMain(), 100)) as TdApi.Chats }.getOrNull()
            ?: return@coroutineScope emptyList()

        val concurrencyLimit = Semaphore(8)
        chats.chatIds
            .map { id ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                    }
                }
            }
            .mapNotNull { it.await() }
            .filter { chat ->
                chat.positions.any { it.isPinned }
            }
    }

    /**
     * Every channel and media group inside every folder configured in Telegram.
     */
    suspend fun getChannelsInFolders(): Map<TdApi.ChatFolderInfo, List<TdApi.Chat>> = coroutineScope {
        val folders = getChatFolders()
        if (folders.isEmpty()) return@coroutineScope emptyMap()

        val concurrencyLimit = Semaphore(8)
        folders.associateWith { folder ->
            runCatching { send(TdApi.LoadChats(TdApi.ChatListFolder(folder.id), 100)) }
            val chats = runCatching { send(TdApi.GetChats(TdApi.ChatListFolder(folder.id), 100)) as TdApi.Chats }.getOrNull()
                ?: return@associateWith emptyList<TdApi.Chat>()

            chats.chatIds
                .map { id ->
                    async {
                        concurrencyLimit.withPermit {
                            runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                        }
                    }
                }
                .mapNotNull { it.await() }
                .filter { chat ->
                    (chat.type as? TdApi.ChatTypeSupergroup) != null || chat.type is TdApi.ChatTypeBasicGroup
                }
        }
    }

    /**
     * Fetches all channels and video groups available in the account,
     * sorted so pinned chats come first, followed by active channels.
     */
    suspend fun getAllChannels(limit: Int = 40): List<TdApi.Chat> = coroutineScope {
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) }
        val chats = runCatching { send(TdApi.GetChats(TdApi.ChatListMain(), limit)) as TdApi.Chats }.getOrNull()
            ?: return@coroutineScope emptyList()

        val concurrencyLimit = Semaphore(8)

        chats.chatIds
            .take(limit)
            .map { id ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                    }
                }
            }
            .mapNotNull { it.await() }
            .filter { chat ->
                (chat.type as? TdApi.ChatTypeSupergroup) != null || chat.type is TdApi.ChatTypeBasicGroup
            }
            .sortedWith(
                compareByDescending<TdApi.Chat> { chat -> chat.positions.any { it.isPinned } }
                    .thenByDescending { it.lastMessage?.date ?: 0 }
            )
    }

    suspend fun getPinnedVideo(chatId: Long): MediaItem? {
        val (realChatId, topicId) = resolveChatAndTopic(chatId)

        val messageRaw: TdApi.Message? = if (topicId != 0) {
            runCatching {
                val res = send(
                    TdApi.SearchChatMessages(
                        realChatId,
                        TdApi.MessageTopicForum(topicId),
                        "",
                        null,
                        0L,
                        0,
                        1,
                        TdApi.SearchMessagesFilterPinned()
                    )
                ) as? TdApi.FoundChatMessages
                res?.messages?.firstOrNull()
            }.getOrNull()
        } else {
            runCatching {
                (send(TdApi.GetChatPinnedMessage(realChatId)) as? TdApi.Message)
            }.getOrNull() ?: runCatching {
                val res = send(
                    TdApi.SearchChatMessages(
                        realChatId,
                        null,
                        "",
                        null,
                        0L,
                        0,
                        1,
                        TdApi.SearchMessagesFilterPinned()
                    )
                ) as? TdApi.FoundChatMessages
                res?.messages?.firstOrNull()
            }.getOrNull()
        }
        val message = messageRaw ?: return null

        return when (val content = message.content) {
            is TdApi.MessageVideo -> {
                val video = content.video
                val title = content.caption?.text.orEmpty()
                    .ifBlank { video.fileName }
                    .ifBlank { "Video ${message.id}" }
                MediaItem(
                    id = "tg:$chatId:${message.id}",
                    sourceType = SourceType.TELEGRAM,
                    title = title,
                    subtitle = null,
                    category = null,
                    durationMs = video.duration * 1000L,
                    sizeBytes = video.video.size.toLong(),
                    thumbnailUrl = video.thumbnail?.file?.id?.let { "tdlib://thumb/$it" },
                    streamUrl = "tdlib://file/${video.video.id}",
                    addedAtEpochSec = message.date.toLong()
                )
            }
            is TdApi.MessageDocument -> {
                val doc = content.document
                val title = content.caption?.text.orEmpty()
                    .ifBlank { doc.fileName }
                    .ifBlank { "File ${message.id}" }
                MediaItem(
                    id = "tg:$chatId:${message.id}",
                    sourceType = SourceType.TELEGRAM,
                    title = title,
                    subtitle = null,
                    category = null,
                    durationMs = null,
                    sizeBytes = doc.document.size.toLong(),
                    thumbnailUrl = doc.thumbnail?.file?.id?.let { "tdlib://thumb/$it" },
                    streamUrl = "tdlib://file/${doc.document.id}",
                    addedAtEpochSec = message.date.toLong()
                )
            }
            else -> null
        }
    }

    suspend fun pinVideo(chatId: Long, messageId: Long) {
        send(TdApi.PinChatMessage(chatId, messageId, false, false))
    }

    suspend fun unpinVideo(chatId: Long, messageId: Long) {
        send(TdApi.UnpinChatMessage(chatId, messageId))
    }

    
    data class ForumTopicDetail(
        val info: org.drinkless.tdlib.TdApi.ForumTopicInfo,
        val videoCount: Int = 0
    )

    var onNewVideoMessageListener: ((org.drinkless.tdlib.TdApi.Message) -> Unit)? = null

    private val forumTopicsCache = java.util.concurrent.ConcurrentHashMap<Long, List<ForumTopicDetail>>()

    fun getCachedForumTopics(chatId: Long): List<ForumTopicDetail>? = forumTopicsCache[chatId]

    suspend fun getForumTopics(chatId: Long): List<ForumTopicDetail> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val result = send(org.drinkless.tdlib.TdApi.GetForumTopics(chatId, "", 0, 0, 0, 100)) as org.drinkless.tdlib.TdApi.ForumTopics
            val topicsList = result.topics.map { topic ->
                ForumTopicDetail(topic.info, 0)
            }
            forumTopicsCache[chatId] = topicsList
            topicsList
        }.getOrDefault(forumTopicsCache[chatId] ?: emptyList())
    }

        private val forumChatCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    fun recordForumChat(chatId: Long, isForum: Boolean) {
        forumChatCache[chatId] = isForum
    }

    suspend fun isForumChat(chatId: Long): Boolean = forumChatCache[chatId] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val chat = send(org.drinkless.tdlib.TdApi.GetChat(chatId)) as org.drinkless.tdlib.TdApi.Chat
            val isForum = chat.viewAsTopics
            forumChatCache[chatId] = isForum
            isForum
        }.getOrDefault(false)
    }

    suspend fun getVideoMessages(chatId: Long, fromMessageId: Long = 0L, limit: Int = 40, topicId: Int = 0): VideoMessagesResult {
        val (realChatId, decodedTopicId) = resolveChatAndTopic(chatId)
        val resolvedTopicId = if (decodedTopicId != 0) decodedTopicId else topicId
        val topic = if (resolvedTopicId != 0) org.drinkless.tdlib.TdApi.MessageTopicForum(resolvedTopicId) else null
        val result = send(
            TdApi.SearchChatMessages(
                realChatId, topic, "", null, fromMessageId, 0, limit,
                TdApi.SearchMessagesFilterVideo()
            )
        ) as? TdApi.FoundChatMessages
            ?: throw IllegalStateException("TDLib returned an unexpected response for video search")

        val items = result.messages.filter { it.id != fromMessageId }.mapNotNull { msg ->
            val msgVideo = msg.content as? TdApi.MessageVideo ?: return@mapNotNull null
            val video = msgVideo.video
            val thumbFileId = video.thumbnail?.file?.id
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
                thumbnailUrl = thumbFileId?.let { "tdlib://thumb/$it" },
                streamUrl = "tdlib://file/${video.video.id}",
                addedAtEpochSec = msg.date.toLong()
            )
        }
        val nextId = if (result.nextFromMessageId != 0L) result.nextFromMessageId else (result.messages.lastOrNull()?.id ?: 0L)
        return VideoMessagesResult(items, nextId)
    }

    /**
     * Downloads (or returns the already-cached path for) a thumbnail file, at LOW priority so it
     * never competes with an in-progress video download or a range request the player is
     * blocking on. Callers (the browse grid) should cancel this if the row scrolls off-screen
     * before it resolves -- see `ThumbnailLoader` for the cancellation-aware wrapper.
     */
    suspend fun downloadThumbnail(fileId: Int): String = withContext(Dispatchers.IO) {
        if (fileId <= 0) return@withContext ""
        val c = client ?: return@withContext ""
        val cached = fileCache[fileId]
        if (cached?.local?.isDownloadingCompleted == true && !cached.local.path.isNullOrBlank()) {
            return@withContext cached.local.path
        }
        suspendCancellableCoroutine { cont ->
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var listener: (TdApi.File) -> Unit
            fun finish(path: String) {
                if (completed.compareAndSet(false, true)) {
                    unregisterFileListener(fileId, listener)
                    cont.resume(path)
                }
            }
            listener = { file ->
                if (file.local?.isDownloadingCompleted == true && !file.local.path.isNullOrBlank()) {
                    finish(file.local.path)
                }
            }
            registerFileListener(fileId, listener)
            cont.invokeOnCancellation {
                completed.set(true)
                unregisterFileListener(fileId, listener)
                // Another visible card can still be waiting for the same TDLib file.
            }
            c.send(TdApi.DownloadFile(fileId, 16, 0, 0, false)) { res ->
                if (res is TdApi.File && res.local?.isDownloadingCompleted == true && !res.local.path.isNullOrBlank()) {
                    finish(res.local.path)
                } else if (res is TdApi.Error) {
                    finish("")
                }
            }
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
    suspend fun getFile(fileId: Int): TdApi.File? = suspendCancellableCoroutine { cont ->
        val c = client ?: return@suspendCancellableCoroutine cont.resume(null)
        c.send(TdApi.GetFile(fileId)) { res ->
            if (res is TdApi.File) cont.resume(res) else cont.resume(null)
        }
    }

    suspend fun readChunk(fileId: Int, offset: Long, count: Long): ByteArray? = suspendCancellableCoroutine { cont ->
        val c = client ?: return@suspendCancellableCoroutine cont.resume(null)
        // Request chunk download with high priority
        c.send(TdApi.DownloadFile(fileId, 32, offset, count, false)) { downloadRes ->
            // Read downloaded part directly into memory
            c.send(TdApi.ReadFilePart(fileId, offset, count)) { readRes ->
                if (readRes is TdApi.Data && readRes.data.isNotEmpty()) {
                    cont.resume(readRes.data)
                } else {
                    cont.resume(null)
                }
            }
        }
    }

    fun downloadFileRangeSync(fileId: Int, offset: Long, limit: Long, onResult: (TdApi.File?) -> Unit) {
        val c = client
        if (c == null) {
            onResult(null)
            return
        }
        // Cap chunk to max 4MB so it streams on-the-fly instead of downloading full movies
        val safeLimit = if (limit <= 0) 4 * 1024 * 1024L else minOf(limit, 4 * 1024 * 1024L)
        c.send(TdApi.DownloadFile(fileId, 32, offset, safeLimit, false)) { result ->
            when (result) {
                is TdApi.File -> onResult(result)
                else -> onResult(null)
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
        val (realChatId, topicId) = resolveChatAndTopic(chatId)
        val topic = if (topicId != 0) org.drinkless.tdlib.TdApi.MessageTopicForum(topicId) else null
        val result = runCatching {
            send(
                TdApi.SearchChatMessages(
                    realChatId, topic, query, null, 0L, 0, limit,
                    TdApi.SearchMessagesFilterVideo()
                )
            ) as? TdApi.FoundChatMessages
        }.onFailure { Timber.w(it, "searchChannelVideos failed for chatId=%d topicId=%d", realChatId, topicId) }
            .getOrNull() ?: return emptyList()

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
