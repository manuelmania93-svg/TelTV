# Optional TMDB library metadata

TelTV uses TMDB for movie/show posters, season posters, episode names, ratings and years.
The original Telegram title and local thumbnail stay available when metadata is missing,
ambiguous, offline, or disabled by Fast Mode. Playback does not need a TMDB key.

## Enable on your own build

1. Get a TMDB **v3 API key** from your own TMDB account's API settings. This is the API key,
   not the API Read Access Token. Follow TMDB's terms for your use.
2. In the repository root's `local.properties`, keep any existing `sdk.dir` and add:
   ```properties
   TMDB_API_KEY=your_v3_api_key_here
   ```
3. Sync Gradle and rebuild/install the APK. The value becomes `BuildConfig.TMDB_API_KEY`.
   `local.properties` is already ignored by Git. Do not commit your key.
4. Turn off Fast Mode to see metadata in the existing library cards.

Without a key the build still succeeds and cards use their local titles/thumbnails.
No account or key is created automatically. A key embedded in an APK can be extracted;
this is local configuration, not a secret storage guarantee. Distributed builds need
an appropriate key policy or a backend proxy. No signing or CI changes are included.

## Matching and limits

Movie/TV search is scoped by the filename's type and year where known. Titles are scored,
explicit year mismatches are rejected, and tied identities fall back to local media.
Explicit SxxExx/1xNN filenames fetch the relevant season once and choose the matching episode.
Absolute anime numbering without a season is not guessed into season 1.
Season/episode labels enrich existing paged cards; this does not reorganize the Room library
into a new series/season navigation screen. No schema migration is needed.
Metadata uses an in-memory, bounded one-hour cache and requests are coalesced while scrolling.
Language is currently English. Metadata refreshes on app restart or cache expiry.

Data/artwork are provided by TMDB. This product uses the TMDB API but is not endorsed or
certified by TMDB. Follow TMDB's attribution/branding requirements before distribution.
