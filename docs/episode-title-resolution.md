# Title-first episode search

When a caption has a meaningful episode title, search the known episode names before subtitle buckets. The topic/channel supplies the show name(s), never the caption. Resolve exact normalized names across all shows in a mixed topic. A unique name selects the show and S/E even when caption numbers disagree. Punctuation, accents, case and sharp S are normalized, but partial/fuzzy token overlap is not enough. Short/generic names such as Pilot or Episode 12 do not override numbers.

Sources (live checked 2026-10-14):
- Existing keyless English metadata: https://v3-cinemeta.strem.io/meta/series/tt0903747.json
- German metadata: https://tmdb.elfhosted.com/de-DE/meta/series/tt0903747.json
- Mixed-topic German metadata: https://tmdb.elfhosted.com/de-DE/meta/series/tt3032476.json
- Hosted service description: https://tmdb.elfhosted.com/
- Source: https://github.com/elfhosted/tmdb-addon
- Stremio video schema (title/season/episode): https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/meta.md

The hosted TMDB addon supplies German episode names without a TelTV key or account. It uses its own upstream TMDB credentials. These are online lookups, not offline recognition. Only IMDb ids and language are sent, not the full caption or Telegram data. No scraped HTML, translation model, Groq, paid API or new setting is involved. No signing/key changes.

The example "3 3 ... und der Sack ist im Fluss" in "Breaking Bad / Better Call Saul" resolves BB S1E3, not S3E3 or BCS. Sources agree: https://de.wikipedia.org/wiki/Breaking_Bad/Episodenliste and https://www.wunschliste.de/serie/breaking-bad/episoden . Minimal episode-only response fixtures are checked in for DE+EN regression tests.

Metadata requests have a 20-second call limit, 5 MB response limit, up to four topic shows, up to 5000 episodes per payload, and an eight-entry in-memory cache (only payloads <=1 MB retained). Successful metadata expires after an hour; failed lookup cached for a minute. No persistent database or storage growth. Missing/malformed metadata fails independently. New DE lookup can add latency and depends on public service availability.

Ambiguous names preserve labelled choices and prevent automatic subtitle attachment. Unresolved meaningful names keep the number-based manual search with an explicit warning, but prevent auto-attachment. Title-only captions without a match return an explanation instead of guessing S1E1. Existing subtitle release identity/timing checks and absolute episode fallback remain. Catalog title resolution does not prove playback timing or subtitle coverage. TV testing remains necessary.
