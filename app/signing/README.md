# Development signing

`dot-dev.jks.b64` is an intentionally public **development/debug** signing key.

It exists so GitHub Actions debug APKs keep the same signature and can be installed over earlier development builds without deleting app data.

Do not use this key for a production release, Play Store build or any build that is expected to provide publisher authenticity. A future release channel must use a separate private signing key stored outside the repository, for example in GitHub Actions secrets.

The published [v0.5.0 Android APK](https://github.com/atlasru/dot/releases/download/v0.5.0/dot-android-0.5.0-dev-debug.apk) uses this public key. Its package ID is `dev.dotclient.android.debug`, installed version `0.5.0-debug`, and versionCode **500**. It can update an older build only when Android sees the **same package name and signing certificate**; raising the versionCode alone cannot resolve a signing mismatch. This is a development/debug distribution, not publisher authentication.
