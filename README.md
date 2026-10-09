# TelTV — Android TV media player for Telegram libraries

TelTV is an Android TV app for browsing and streaming videos from your own Telegram account.
It is branded **Manuel Mania** and is designed for remote-first navigation on Android TV boxes.

## Features

- Browse pinned chats, Telegram folders, groups, and forum topics.
- Stream and seek Telegram videos with ExoPlayer; browse large libraries using paged Room caching.
- Shuffle all indexed episodes in a channel or topic and continue playback from the shuffled queue.
- Use **Continue Watching** for unfinished videos, **Watch History** for recently played videos, and
  **Favorites** as a separate saved list on Home. Long-press a history or favorite card to remove it.
- Search embedded subtitle tracks or look up online subtitles by title. The online lookup uses
  Cinemeta and OpenSubtitles metadata; manually adjust the search title/episode when filenames
  are inconsistent. Subtitle timing, size, and color can be adjusted in the player.
- Browse SMB and WebDAV sources alongside Telegram media.
- Manage playback preferences, playlists, cache usage, and automatic cache cleanup.
- Navigate screens and actions with an Android TV D-pad and OK/Center button.

**Attribution:** The app's visible brand is Manuel Mania. Copyright attribution to Manuel Durnig
is retained in the app's About screen.

## Setup

### TMDB API key

Poster and rating lookups use a TMDB v3 API key that is not stored in the repo. Add this line to
`local.properties` in the project root (the file is git-ignored):

```
TMDB_API_KEY=<your TMDB v3 key>
```

Without a key the app still builds; the TMDB fallback lookup is simply skipped. CI builds can set
the `TMDB_API_KEY` environment variable (for example from a repository secret) instead.

### Download the latest APK

The latest signed APK is published here after a successful release workflow:

[**TelTV APK herunterladen**](https://github.com/manuelmania93-svg/TelTV/releases/download/rolling-release/app-release.apk)

Open the link in a browser on the Android TV device, download the APK, then install it from the
Downloads folder. Android may require allowing installs from that browser.

The link is updated by the GitHub Actions release workflow after a push to `main`.

### 0. Use a supported JDK

Use JDK 17 for Android Studio and Gradle. The project targets Java 17, and the
current Android Gradle Plugin/Gradle combination is not guaranteed to run on
newer JDK releases.

### 1. Get your own Telegram API credentials
Go to https://my.telegram.org → API development tools → create an app. You'll get an `api_id`
and `api_hash`. Put them in `TelegramClient.kt` (`API_ID`, `API_HASH`). Never commit real values
to a public repo.

### 2. Build TDLib for Android (official source only)
Follow Telegram's own guide: https://github.com/tdlib/td/blob/master/example/android/README.md
It walks through `check-environment.sh` → `fetch-sdk.sh` → `build-openssl.sh` → `build-tdlib.sh`.
Output goes in `tdlib/libs` (native `.so` per ABI) and `tdlib/java` (the `org.drinkless.tdlib.*`
Java sources — `Client.java`, `TdApi.java`).

Copy them into this project:
```
app/src/main/jniLibs/arm64-v8a/libtdjni.so
app/src/main/jniLibs/armeabi-v7a/libtdjni.so
app/src/main/jniLibs/x86_64/libtdjni.so          # useful for emulator / x86 TV boxes
app/src/main/java/org/drinkless/tdlib/Client.java
app/src/main/java/org/drinkless/tdlib/TdApi.java
```

### 3. Open in Android Studio, sync Gradle, run on a TV device/emulator
Android TV emulator: Android Studio → Device Manager → Create Device → TV category.
Real device: enable Developer Options + ADB debugging on your TV box, `adb connect <ip>`.

## How TelTV works

- **Pinned channels + folders** (`TelegramClient.getPinnedChannels()` /
  `getChannelsInFolders()`): reads pinned chats and Telegram folder data through TDLib, so you
  can browse the organization already present in your account.
- **Instant-seek streaming** (`TdLibDataSource.kt`): ExoPlayer asks for a byte range, we tell
  TDLib to prioritize downloading exactly that range (`DownloadFile` with offset/limit), then read
  straight off TDLib's local file once it's ready. This is the same technique Telegram's own apps
  use — no "wait for the whole video to download" delay.
- **Cache management** (`CacheManager.kt`): rather than reinventing an LRU cache, this calls
  TDLib's own `OptimizeStorage`, which already knows every file it has downloaded, its size, and
  last-access time. "Clear cache now" = optimize down to 0 bytes. Automatic cleanup is emergency
  only: it clears the cache when the TV has less than 512 MB of usable storage remaining.
- **NAS/WebDAV/local** (`SmbSourceClient.kt`, `WebDavSourceClient.kt`): supports host/port/share/
  folder for SMB and server URL/user/pass for WebDAV, kept as secondary source types alongside
  your Telegram account.

## Features and architecture

These features are available in the app and wired into the navigation graph in `MainActivity.kt`.

### 1. Faster pinned-chat/folder discovery
`TelegramClient.getPinnedChannels()` used to call `GetChat` on every chat in your list one at a
time. It now fires up to 8 of those calls concurrently (`kotlinx.coroutines.async` + a
`Semaphore(8)`), so an account with a few hundred chats resolves in a fraction of the time --
directly shortens the "Home spins before showing Pinned" wait.

### 2. The 2GB-RAM / thousands-of-videos problem
This is the one that actually needed new architecture, not just a tweak:

- **`VideoIndexEntity` + `VideoIndexDao.pagingSource()`** (`data/local/LocalDb.kt`): every video a
  channel returns gets cached locally, keyed by a stable `position`. Room generates a
  `PagingSource` for it directly.
- **`ChannelVideoRepository`** (`data/repository/`): the source of truth is now Room, not "however
  many `MediaItem`s TDLib handed back." `ensureNextPage()` fetches one more page from Telegram and
  inserts it; `videoPager()` exposes a `Pager`/`PagingData` stream. Reopening a channel you've
  browsed before paints instantly from the cache while new pages load quietly in the background --
  no more staring at a spinner for a channel you've already seen.
- **`BrowseScreen`** (`ui/browse/`): a `LazyVerticalGrid` fed by `collectAsLazyPagingItems()`.
  Only the visible window (+ a small prefetch margin) is ever inflated into Composables or has its
  thumbnail downloaded -- scrolling a 3,000-video channel costs the same memory as a 30-video one.
  Off-screen placeholder cells render immediately instead of leaving a blank gap.
- **`DeviceCapabilities`** (`util/`): reads `ActivityManager.isLowRamDevice` / `memoryClass` /
  total RAM once at startup and picks a page size, grid column count, thumbnail memory-cache size,
  and search debounce that's appropriately conservative on a cheap box and roomier on a capable
  one, instead of one hard-coded profile for every device.
- **`ThumbnailLoader`** (`telegram/`): downloads thumbnails at TDLib's *low* priority (so they
  never compete with an in-progress video download) and cancels the download the instant its card
  scrolls off-screen (`DisposableEffect` + `TdApi.CancelDownloadFile`), with a small bounded
  LRU of resolved paths. This is the other half of "scrolls fine for a second then stalls" --
  it was every off-screen row quietly continuing to download.

### 3. Playback, history, and favorites
`Continue Watching` is the unfinished portion of viewing history and stores resume positions.
`Watch History` is the separate list of recently played videos. Long-press a card on Home and choose
**Remove from history** to remove an accidental play; this does not remove a favorite.
Favorites use the separate local watchlist, can be added from a video's long-press menu in Browse,
and appear in their own **Favorites** row on Home. `PlayerScreen` saves playback position every
five seconds and resumes from the saved position.

### 4. Shuffle and autoplay
Browse a Telegram channel or forum topic and choose **Shuffle Episodes**. TelTV waits for the
background index preload, creates a shuffled playlist from that channel/topic, and plays through
it. Autoplay can continue to the next playlist item.

### 5. Search
`ui/search/SearchScreen.kt`: typing shows instant on-device title matches
(`VideoIndexDao.searchLocal`, no network) immediately, and after a device-tuned debounce, also
fires `TelegramClient.searchAcrossChannels()` -- a bounded-concurrency search across every pinned
channel at once, for videos that were never paged into the local cache. Recent searches are saved
and shown as one-tap chips, since typing on a remote is the slowest input method there is.

### 6. Online and embedded subtitles
The player can select embedded text tracks or search online by title through Cinemeta and
OpenSubtitles metadata. The online query can be edited when source filenames use inconsistent
episode titles. Subtitle sync, size, and color controls are available in the player.

### 7. Smooth remote/D-pad navigation
`util/RemoteInput.kt`:
- `rememberKeyRepeatThrottle` coalesces a held D-pad key's auto-repeat into a steady, throttled
  rate instead of firing an action every single repeat event.
- `rememberDebounced` backs the search box's remote-search trigger.
- `LazyGridState.isNearEnd()` / `LazyListState.isNearEnd()` is the shared "close enough to the end
  to start loading the next page" check, used by `BrowseScreen` so grid scrolling and pagination
  are governed by the same threshold instead of every screen inventing its own.
- Every `LazyRow`/`LazyColumn`/`LazyVerticalGrid` added in this pass uses stable `key = { it.id }`
  so scrolling and focus don't get scrambled by unrelated recompositions.

### 8. Background cache upkeep
`worker/CacheTrimWorker.kt`: a `WorkManager` periodic job (every 6h, battery-aware) that calls the
existing `CacheManager.maybeAutoClear()` even when Settings was never opened -- previously that
only ran when someone happened to open the cache screen.

### Current limitations
- Online subtitle matches depend on metadata and subtitle availability. Unusual episode names may
  need a manually edited series/season/episode query.
- Automatic association of separate `.srt`/`.vtt` files posted alongside Telegram videos is not
  currently implemented.
- The app needs a Telegram login and a working network connection for Telegram-hosted media.

## Project layout
```
app/src/main/java/com/velastudio/teltv/
  telegram/        TDLib wrapper, streaming data source, cache manager
  data/model/      MediaItem, MediaSource, SourceType
  data/remote/     SMB + WebDAV clients
  data/local/      Room DB (sources, watch history, watchlist)
  ui/home/         Pinned/folder rows
  ui/player/       ExoPlayer screen
  ui/settings/     Cache controls
  MainActivity.kt  Navigation host for Home, topics, browse, player, and settings
```
