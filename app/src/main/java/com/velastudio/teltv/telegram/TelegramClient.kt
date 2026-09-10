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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TelegramClient(private val context: Context) {
    private val _cachedFolders = CopyOnWriteArrayList<TdApi.ChatFolderInfo>()

    private var client: Client? = null
    var authState: TdApi.AuthorizationState? = null
        private set

    companion object {
        const val API_ID = 6
        const val API_HASH = "eb06d4abfb49dc3eeb1aeb98ae0f581e"
    }

    /** Emits authorization states and unlocks local session with encryption key. */
    fun authorizationFlow(): Flow<TdApi.AuthorizationState> = callbackFlow {
        val c = Client.create(
            { update ->
                when (update) {
                    is TdApi.UpdateAuthorizationState -> {
                        authState = update.authorizationState
                        when (update.authorizationState) {
                            is TdApi.AuthorizationStateWaitTdlibParameters -> {
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
                                    if (res is TdApi.Error) Timber.e("SetTdlibParameters error: %s", res.message)
                                }
                            }
                            is TdApi.AuthorizationStateWaitEncryptionKey -> {
                                // REQUIRED by TDLib to unlock the database and restore saved login
                                c.send(TdApi.CheckDatabaseEncryptionKey()) { res ->
                                    if (res is TdApi.Error) Timber.e("CheckDatabaseEncryptionKey error: %s", res.message)
                                }
                            }
                            else -> {}
                        }
                        trySend(update.authorizationState)
                    }
                    is TdApi.UpdateChatFolders -> {
                        _cachedFolders.clear()
                        _cachedFolders.addAll(update.chatFolders)
                        Timber.i("TDLib received %d chat folders", update.chatFolders.size)
                    }
                    else -> {}
                }
            },
            { exception -> Timber.e(exception, "TDLib update handler exception") },
            { exception -> Timber.e(exception, "TDLib default exception handler") }
        )
        client = c
        awaitClose { }
    }

    suspend fun setPhoneNumber(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    suspend fun checkCode(code: String) = send(TdApi.CheckAuthenticationCode(code))
    suspend fun checkPassword(password: String) = send(TdApi.CheckAuthenticationPassword(password))

    suspend fun requestQrCodeAuthentication() = send(TdApi.RequestQrCodeAuthentication(emptyArray()))

    suspend fun getChatFolders(): List<TdApi.ChatFolderInfo> {
        if (_cachedFolders.isEmpty()) {
            kotlinx.coroutines.delay(800)
        }
        return _cachedFolders.toList()
    }

    suspend fun getAllChannels(limit: Int = 40): List<TdApi.Chat> = coroutineScope {
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) }
        val chatsResult = runCatching { send(TdApi.GetChats(TdApi.ChatListMain(), limit)) as TdApi.Chats }.getOrNull()
        val chats = chatsResult?.chatIds ?: longArrayOf()

        val concurrencyLimit = Semaphore(8)
        chats
            .map { id ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                    }
                }
            }
            .mapNotNull { it.await() }
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
        runCatching { send(TdApi.LoadChats(TdApi.ChatListMain(), 40)) }
        val chatsResult = runCatching { send(TdApi.GetChats(TdApi.ChatListMain(), 40)) as TdApi.Chats }.getOrNull()
        val chatIds = chatsResult?.chatIds ?: longArrayOf()

        val concurrencyLimit = Semaphore(8)
        chatIds
            .map { id ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                    }
                }
            }
            .mapNotNull { it.await() }
            .filter { chat ->
                val isPinned = chat.positions.any { it.isPinned }
                val isEligible = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true ||
                                chat.type is TdApi.ChatTypeSupergroup ||
                                chat.type is TdApi.ChatTypeBasicGroup
                isEligible && isPinned
            }
    }

    suspend fun getChannelsInFolders(): Map<TdApi.ChatFolderInfo, List<TdApi.Chat>> = coroutineScope {
        val folders = getChatFolders()
        if (folders.isEmpty()) return@coroutineScope emptyMap()

        val concurrencyLimit = Semaphore(8)
        folders.associateWith { folder ->
            runCatching { send(TdApi.LoadChats(TdApi.ChatListFolder(folder.id), 40)) }
            val chatsResult = runCatching { send(TdApi.GetChats(TdApi.ChatListFolder(folder.id), 40)) as TdApi.Chats }.getOrNull()
            val chatIds = chatsResult?.chatIds ?: longArrayOf()
            chatIds
                .map { id ->
                    async {
                        concurrencyLimit.withPermit {
                            runCatching { send(TdApi.GetChat(id)) as TdApi.Chat }.getOrNull()
                        }
                    }
                }
                .mapNotNull { it.await() }
                .filter { chat ->
                    (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true ||
                    chat.type is TdApi.ChatTypeSupergroup ||
                    chat.type is TdApi.ChatTypeBasicGroup
                }
        }
    }

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
            val thumbFileId = video.thumbnail?.file?.id
            val title = msgVideo.caption?.text.orEmpty()
                .ifBlank { video.fileName }
                .ifBlank { "Video ${msg.id}" }
            MediaItem(
                id = "tg:${chatId}:${msg.id}",
                sourceType = SourceType.TELEGRAM,
                title = title,
                subtitle = formatDuration(video.duration),
                thumbnailUrl = thumbFileId?.let { "tdlib://thumb/$it" },
                streamUrl = "tdlib://file/${video.video.id}",
                addedAtEpochSec = msg.date.toLong()
            )
        }
    }

    /** Downloads thumbnails synchronously so photos are fully ready on disk. */
    suspend fun downloadThumbnail(fileId: Int): String = suspendCancellableCoroutine { cont ->
        val c = client
        if (c == null) {
            if (cont.isActive) cont.resume("")
            return@suspendCancellableCoroutine
        }
        try {
            c.send(TdApi.DownloadFile(fileId, 1, 0, 0, true)) { result ->
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

    suspend fun cancelDownload(fileId: Int) {
        client?.send(TdApi.CancelDownloadFile(fileId, false)) { }
    }

    suspend fun execute(function: TdApi.Function<*>): TdApi.Object = send(function)

    suspend fun searchAcrossChannels(chatIds: List<Long>, query: String): List<MediaItem> = coroutineScope {
        val concurrencyLimit = Semaphore(4)
        chatIds
            .map { chatId ->
                async {
                    concurrencyLimit.withPermit {
                        runCatching {
                            val res = send(
                                TdApi.SearchChatMessages(
                                    chatId, null, query, null, 0, 0, 10,
                                    TdApi.SearchMessagesFilterVideo()
                                )
                            ) as TdApi.FoundChatMessages
                            res.messages.mapNotNull { msg ->
                                val v = (msg.content as? TdApi.MessageVideo)?.video ?: return@mapNotNull null
                                MediaItem(
                                    id = "tg:${chatId}:${msg.id}",
                                    sourceType = SourceType.TELEGRAM,
                                    title = (msg.content as? TdApi.MessageVideo)?.caption?.text.orEmpty().ifBlank { v.fileName },
                                    subtitle = formatDuration(v.duration),
                                    thumbnailUrl = v.thumbnail?.file?.id?.let { "tdlib://thumb/$it" },
                                    streamUrl = "tdlib://file/${v.video.id}",
                                    addedAtEpochSec = msg.date.toLong()
                                )
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }
            .flatMap { it.await() }
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): TdApi.Object =
        suspendCancellableCoroutine { cont ->
            val c = client ?: return@suspendCancellableCoroutine cont.resumeWithException(
                IllegalStateException("TDLib client not started")
            )
            c.send(function) { result ->
                if (!cont.isActive) return@send
                when (result) {
                    is TdApi.Error -> cont.resumeWithException(RuntimeException("[${result.code}] ${result.message}"))
                    else -> cont.resume(result)
                }
            }
        }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }
}
