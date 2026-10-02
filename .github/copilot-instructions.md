# TelTV - Architecture & Automation Rules for GitHub Copilot

## 1. CI/CD & Keystore Invariants (STRICT - DO NOT TOUCH)
- **DO NOT delete or relocate `app/release.keystore`**. This keystore signs APKs installed on physical Android TV hardware. Changing or removing it breaks application updates (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
- **DO NOT modify `.github/workflows/build.yml`** to disable release generation. Every push to `main` MUST compile `assembleRelease`, sign with `app/release.keystore`, and publish to the `rolling-release` tag using `softprops/action-gh-release@v2`.
- **DO NOT replace keystore logic with unconfigured repository secrets**.

## 2. Versioning Protocol (MANDATORY)
- Every single functional change or commit pushed to `main` **MUST increment `versionCode` and update `versionName`** in `app/build.gradle.kts`.
- The Android TV updater (`AppUpdater.kt`) relies on strictly increasing `versionCode` against GitHub Releases.

## 3. TV UX & Hardware Gate
- **D-Pad First**: All screens, dialogs, and rows must be navigable via TV remote (directional keys + OK/Center button).
- **Stale-While-Revalidate**: Never block UI rendering on remote Telegram RPCs. Always display cached memory/Room items first (0ms initial paint), then refresh silently in background coroutines.
- **Low-RAM Architecture**: Target devices are 1GB–2GB RAM Android TV sticks. Maintain paced SQLite writes (>=120ms) and bounded pagination (`prefetchDistance = 20`).
