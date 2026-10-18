# Contributing to dot.

`dot.` is still pre-1.0, so small focused changes are preferred over large rewrites.

Current published version is **v0.4.0** (Android `0.4.0-debug` / versionCode **401**, Windows `0.4.0`), using Xray/libXray **v26.7.28**. The `v0.4.0` release assets were updated independently of the original tag; see [release provenance](docs/releases/0.4.0.md).

## Licensing contributions

The original dot. code is offered under **GPL-3.0-or-later**. By submitting
your own changes for inclusion, you must have the right to contribute them
under that license; do not copy incompatible or proprietary source into dot.
Preserve copyright notices and third-party license texts. A pull request does
**not** transfer your copyright to the maintainer.

See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md).
The pre-adoption v0.4.0 binary artifacts are a separate historical release
and have not been replaced by this license update.

## Before opening a pull request

- keep the UI minimal and consistent with the existing AMOLED/monospace direction
- do not commit real subscription URLs, UUIDs, access tokens or raw private logs
- keep Android libXray and Windows Xray config changes isolated to their native VPN integration layers; test VLESS and Hysteria2 independently
- preserve compatibility with the pinned Android and Gradle toolchain unless the upgrade is part of the change
- run unit tests before submitting

```bash
# Android (requires the pinned libXray AAR in app/libs/)
gradle testDebugUnitTest
gradle assembleDebug

# Windows/frontend (CI uses Node 24; project requires Node >=20.19 and a Rust toolchain)
cd desktop
npm ci
npm test
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
```

Run a real-device VPN test when touching TUN/routing, Xray configuration, reconnects, subscriptions or HWID. The Windows release pipeline additionally builds the Tauri executable and verifies the portable ZIP; follow [Desktop](docs/DESKTOP.md) and [Hysteria2 checklist](docs/hysteria2-manual-test.md).

## Pull requests

A useful PR description should explain:

- what changed
- why it changed
- how it was tested
- whether VPN/runtime behavior changed
- whether screenshots are useful for the UI change

## Issues

For VPN connection problems, include the platform (Android or Windows), app version, OS version, VLESS/Hysteria2 transport/security type and a redacted error message. Never include a full private subscription URL or VLESS credential.
