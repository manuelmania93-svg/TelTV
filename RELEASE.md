# TelTV release checklist

## GitHub Actions signing secrets

The release workflow expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_ALIAS`: signing key alias
- `ANDROID_KEY_PASSWORD`: signing key password

The keystore must be created once and stored securely outside Git. Never commit the
`.jks` file or any password. Losing the keystore means existing Android installs
cannot be upgraded with the same signing identity.

Create the base64 value locally with:

```bash
base64 -w 0 teltv-release.jks > teltv-release.jks.b64
```

Copy the contents of `teltv-release.jks.b64` into the GitHub secret
`ANDROID_KEYSTORE_BASE64`, then run the `Android Release` workflow manually.

## What the workflow publishes

Each run builds a signed, minified APK and publishes:

- `app-release.apk`
- `app-release.apk.sha256`

Verify a downloaded APK locally with:

```bash
sha256sum -c app-release.apk.sha256
```

The repository contains TDLib native libraries because the app needs them to build
and run. They are official-build outputs and should be replaced only with binaries
built from Telegram's official TDLib source. Do not download random prebuilt TDLib
packages.
