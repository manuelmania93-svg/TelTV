# Subtitle addon sources

TelTV queries a bounded list of Stremio subtitle addon base URLs in parallel for
movies, episode buckets, absolute-number fallbacks and neighbouring episodes.
Each request is `/subtitles/{type}/{videoId}.json`. An addon may have a configuration
path before `/subtitles`. No new settings screen is added in this change.

Defaults:

- OpenSubtitles v3: https://opensubtitles-v3.strem.io/manifest.json
- SubSense: https://subsense.nepiraw.com . TelTV builds the documented URL-encoded
  JSON configuration path from the selected language plus English, with 20 results
  per language. No user id or API keys are sent. The app's selected-language filter
  remains in charge, with no silent switch to English.

## Verification on 2026-10-14

SubSense's homepage and GitHub link to the public hosted service:
https://github.com/NepiRaw/Stremio-SubSense . Its `src/routes/stremio.js` accepts
URL-encoded JSON configuration, and `src/config.js` accepts two-letter language
codes. The unconfigured `/subtitles/...` route returns 404 and must not be used.

The configured German/English route returned valid subtitle JSON without a key:
Malcolm in the Middle S1E1 returned 20 English files and no German file; The Matrix
(1999) returned 29 files, including 9 German. A sampled English episode download
and German movie download returned SRT text (HTTP 200). These are point-in-time
checks, not a promise of coverage or uptime. Most sampled results still came from
OpenSubtitles. The hosted service may use its own upstream credentials; TelTV
neither supplies keys nor directly integrates any key-based provider.

SubSense can aggregate other sources server-side. TelTV adds no HTML scrapers.
No guaranteed improvement for German, anime, date-named wrestling, or timing is
claimed. The legacy https://opensubtitles.strem.io endpoint returned HTML even at
its JSON URLs, so it was excluded.

## Safety and limits

- At most four addons, 12-second total call timeout per addon, 512 KiB response
  limit, and 400 retained results. Existing neighbouring-episode limit remains.
- Malformed entries, failed HTTP calls and malformed addon responses are isolated.
  Cancellation propagates. Failed sources may therefore appear as fewer results.
- Merge by normalized language, forced status and exact download URL. Known
  OpenSubtitles file-id URLs (including the hosted Wyzie download proxy) collapse
  to the same file. Similar filenames alone never prove two releases are the same.
- Existing v3 ids retain saved timing compatibility. Other sources receive
  namespaced URL hashes, preventing recycled provider indexes from reusing the
  wrong downloaded file or per-subtitle timing correction.
- Release titles and filename evidence reach the unchanged wrong-episode policy.
  Multi-show ambiguity and date-name handling remain unchanged.
- Public services have no verified SLA or rate-limit promise. The extra addon can
  increase search latency up to its call deadline. No background polling is added.
- IMDb id, episode and selected language are sent to enabled public sources; video
  files, Telegram credentials, channel names and local filenames are not sent.

Protocol: https://stremio.github.io/stremio-addon-sdk/protocol.html

Device playback, TV network accessibility and actual subtitle timing require TV
verification. CI smoke checks startup/lifecycle, not these external services.
