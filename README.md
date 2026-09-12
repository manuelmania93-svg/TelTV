# TelTV — Android Smart TV app for your Telegram library

A from-scratch Android TV app in the spirit of VelaTV, connected directly to **your own Telegram
account** (not a bot, not a specific channel) — with better pinned-chat/folder discovery, ExoPlayer
streaming that seeks instantly instead of downloading whole files, and automatic cache management.

## What's actually in this scaffold vs. what you still need to do

I've written this as a real, compilable-shaped Android Studio project with correct architecture
and working logic in the pieces that don't depend on native binaries. Two things I could **not**
do inside this sandbox, on purpose:

1. **Compile TDLib's native library.** TDLib (Telegram's own client library, needed to log into a
   *real user account* rather than a bot) is C++ and must be built with the Android NDK against
   Telegram's official source. My sandbox has no NDK and no access to Google's Maven/NDK
   repositories. I also deliberately did **not** wire in any of the unofficial "prebuilt TDLib
   AAR" packages floating around GitHub/JitPack — several I found were single-purpose accounts
   making suspiciously turnkey claims, which is a classic shape for a malicious dependency, and
   this is a library that gets full access to your real Telegram session. Build it yourself from
   the source Telegram publishes; see step 2 below.
2. **Produce a compiled `.apk`.** Same root cause — no Android SDK/NDK toolchain here. You'll open
   this in Android Studio and build it there (or I can keep helping you fix build errors as you go).

Everything else — the data models, SMB/WebDAV clients, Room database, TDLib coroutine wrapper,
the custom ExoPlayer streaming data source, cache manager, and Compose UI — is real code, not
pseudocode. It'll need the usual amount of glue/debugging once it's in Android Studio, but the
architecture and the tricky parts (streaming playback from TDLib, folder/pinned discovery, cache
sizing) are actually solved here, not hand-waved.

## Setup

### APK mit einem Klick herunterladen

Die aktuellste signierte APK liegt nach einem erfolgreichen Build hier:

[**TelTV APK herunterladen**](https://github.com/manuelmania93-svg/TelTV/releases/download/rolling-release/app-release.apk)

Auf der Android-TV-Box den Link im Browser öffnen, den Download bestätigen und die APK
anschließend aus dem Download-Ordner installieren. Dafür muss die Installation aus dieser
Quelle in den Android-TV-Sicherheitseinstellungen erlaubt sein.

Der Link wird durch den Workflow `Build TelTV APK` aktualisiert, sobald nach `main` gepusht
wird. Den Build kann man auch in GitHub unter **Actions** manuell über **Run workflow** starten.

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

## How the "better than VelaTV" pieces work

- **Pinned channels + folders** (`TelegramClient.getPinnedChannels()` /
  `getChannelsInFolders()`): reads directly from your account's real chat list and chat-folder
  data via TDLib, instead of you re-adding/re-organizing sources inside the app. The exact TDLib
  call names for folder enumeration differ slightly between TDLib versions (`GetChatFolders` /
  chat-list-by-folder-id) — the file has a clear note on which call to wire up once you see what
  your built `TdApi.java` exposes; the calling pattern in the rest of the app won't need to change.
- **Instant-seek streaming** (`TdLibDataSource.kt`): ExoPlayer asks for a byte range, we tell
  TDLib to prioritize downloading exactly that range (`DownloadFile` with offset/limit), then read
  straight off TDLib's local file once it's ready. This is the same technique Telegram's own apps
  use — no "wait for the whole video to download" delay.
- **Cache management** (`CacheManager.kt`): rather than reinventing an LRU cache, this calls
  TDLib's own `OptimizeStorage`, which already knows every file it has downloaded, its size, and
  last-access time. "Clear cache now" = optimize down to 0 bytes. Automatic cleanup is emergency
  only: it clears the cache when the TV has less than 512 MB of usable storage remaining.
- **NAS/WebDAV/local** (`SmbSourceClient.kt`, `WebDavSourceClient.kt`): same fields as VelaTV's
  "Add a source" flow (host/port/share/folder for SMB; server URL/user/pass for WebDAV), kept as
  a secondary source type alongside your Telegram account.

## What's new: performance + the features a real Telegram-TV player needs

This pass focused on the two things you asked about directly, plus everything else that a
"browse a Telegram channel on a TV and actually enjoy it" app needs. All of it is real code (same
caveat as above: can't compile it in this sandbox), wired into the nav graph in `MainActivity.kt`.

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

### 3. Continue Watching / last-played
`WatchStateEntity` now carries title/subtitle/thumbnail alongside the resume position, so the new
"Continue Watching" row on Home (`ui/home/HomeScreen.kt`) renders instantly from Room -- no
Telegram round-trip just to redraw a row of cards. `PlayerScreen` now saves the resume position
every 5 seconds while playing, not only on a clean exit, since cheap TV boxes get killed by the
system far more eagerly than a phone and a kill mid-episode used to silently lose progress.

### 4. Search
`ui/search/SearchScreen.kt`: typing shows instant on-device title matches
(`VideoIndexDao.searchLocal`, no network) immediately, and after a device-tuned debounce, also
fires `TelegramClient.searchAcrossChannels()` -- a bounded-concurrency search across every pinned
channel at once, for videos that were never paged into the local cache. Recent searches are saved
and shown as one-tap chips, since typing on a remote is the slowest input method there is.

### 5. Smooth remote/D-pad navigation
`util/RemoteInput.kt`:
- `rememberKeyRepeatThrottle` coalesces a held D-pad key's auto-repeat into a steady, throttled
  rate instead of firing an action every single repeat event.
- `rememberDebounced` backs the search box's remote-search trigger.
- `LazyGridState.isNearEnd()` / `LazyListState.isNearEnd()` is the shared "close enough to the end
  to start loading the next page" check, used by `BrowseScreen` so grid scrolling and pagination
  are governed by the same threshold instead of every screen inventing its own.
- Every `LazyRow`/`LazyColumn`/`LazyVerticalGrid` added in this pass uses stable `key = { it.id }`
  so scrolling and focus don't get scrambled by unrelated recompositions.

### 6. Background cache upkeep
`worker/CacheTrimWorker.kt`: a `WorkManager` periodic job (every 6h, battery-aware) that calls the
existing `CacheManager.maybeAutoClear()` even when Settings was never opened -- previously that
only ran when someone happened to open the cache screen.

### Ideas brainstormed, not yet built (clear extension points, deliberately left as such)
- **Autoplay next episode**: `PlayerScreen.onPlaybackEnded` already fires on `STATE_ENDED`; wire
  it to look up the next video by `position` in `VideoIndexDao` and navigate straight into it.
- **Skeleton "recently added" row per folder**, refreshed on a schedule, not just on open.
- **Subtitle track discovery** for channels that post `.srt`/`.vtt` alongside the video message.
- **Picture-in-picture** while browsing back to Home (Media3 supports this on API 26+).
- **Voice search** via the Assistant button many TV remotes have (`RecognizerIntent`).
- **A dedicated low-priority background sync** that walks folders overnight so cold-start Home
  is always fresh without the person waiting on it.
- **Real Room `Migration`s** before this leaves the "pre-1.0, no real users" stage --
  `fallbackToDestructiveMigration()` is fine for now, not for a release build.

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
  MainActivity.kt  Navigation host (screens are stubbed with TODOs pointing at the pieces above)
```
