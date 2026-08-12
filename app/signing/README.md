# Development signing

`dot-dev.jks.b64` is an intentionally public **development/debug** signing key.

It exists so GitHub Actions debug APKs keep the same signature and can be installed over earlier development builds without deleting app data.

Do not use this key for a production release, Play Store build or any build that is expected to provide publisher authenticity. A future release channel must use a separate private signing key stored outside the repository, for example in GitHub Actions secrets.
