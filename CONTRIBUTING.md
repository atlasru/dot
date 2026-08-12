# Contributing to dot.

`dot.` is still pre-1.0, so small focused changes are preferred over large rewrites.

## Before opening a pull request

- keep the UI minimal and consistent with the existing AMOLED/monospace direction
- do not commit real subscription URLs, UUIDs, access tokens or raw private logs
- keep libXray changes isolated to the VPN integration layer
- preserve compatibility with the pinned Android and Gradle toolchain unless the upgrade is part of the change
- run unit tests before submitting

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

## Pull requests

A useful PR description should explain:

- what changed
- why it changed
- how it was tested
- whether VPN/runtime behavior changed
- whether screenshots are useful for the UI change

## Issues

For VPN connection problems, include the app version, Android version, transport/security type and a redacted error message. Never include a full private subscription URL or VLESS credential.
