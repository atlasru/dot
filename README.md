# dot.

Minimal VLESS client for Android with an AMOLED-first, Nothing-inspired interface.

Current version: **0.0.14**

## Features

- VLESS with REALITY / TLS
- HTTPS subscription import
- plaintext and Base64 subscription decoding
- multiple subscription groups
- node selection and persistence
- Android VpnService + TUN integration
- libXray / Xray-core backend
- realtime upload/download traffic
- foreground VPN notification with node name and traffic
- Quick Settings connect/disconnect tile
- long-press Quick Settings tile opens dot.
- AMOLED, Graphite and Matrix themes
- adaptive launcher icon
- no accounts, ads, analytics or telemetry

## Design

`dot.` intentionally keeps the interface small: pure-black AMOLED surfaces, monospace/Courier-like typography, sharp geometry and pixel-art accents inspired by Nothing OS.

## Installation

Development builds are produced by GitHub Actions. Open the latest successful `android` workflow run and download the `dot-debug` artifact.

Debug builds use the same development signing key, so newer versions can be installed over previous builds without deleting app data.

## Build from source

Requirements:

- JDK 17
- Android SDK 36 / Build Tools 36.0.0
- Gradle 9.5.0

The CI downloads the pinned libXray Android AAR before compilation.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Privacy

Treat subscription URLs containing user IDs or tokens as credentials. Do not publish real subscription links, UUIDs or unredacted runtime logs.

## Stack

Kotlin · Jetpack Compose · Android VpnService · OkHttp · libXray / Xray-core · GitHub Actions

## Documentation

- [Architecture](ARCHITECTURE.md)
- [VPN engine](docs/VPN_ENGINE.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## License

A project license has not been selected yet.
