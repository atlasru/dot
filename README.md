# dot.

Minimal VLESS client for Android with an AMOLED-first, Nothing-inspired interface.

Current Android version: **0.1.8**

## Features

- VLESS with REALITY / TLS
- HTTPS subscription import
- plaintext and Base64 subscription decoding
- multiple subscription groups
- subscription refresh reports with added, edited and deleted node counts/details
- classified subscription update errors with redacted raw-error viewing while keeping existing nodes intact
- node selection and persistence
- per-subscription node sorting by provider order, delay or natural name order
- delay sorting runs URL tests first when current latency data is unavailable
- LIST / MAP views for nodes, with LIST as the default
- interactive world map with real country geometry, borders, Mercator projection, pinch zoom and pan
- DNS + IP geolocation for subscription nodes with local caching
- fallback country detection from node flags, ISO codes and country names when IP geolocation fails
- country-level node grouping so multiple nodes do not overlap on the map
- node latency / connection tests through libXray URL testing
- direct node selection and switching while connected
- Android VpnService + TUN integration
- libXray / Xray-core backend
- realtime upload/download traffic
- foreground VPN notification with active node and traffic
- Quick Settings connect/disconnect tile
- long-press Quick Settings tile opens dot.
- AMOLED, Graphite and Matrix themes
- selectable launcher icon variants
- no accounts, ads, analytics or telemetry

## Subscription updates

Refreshing a subscription now compares the newly parsed VLESS profiles with the currently stored profiles before replacing them. `dot.` reports added, edited and deleted nodes, preserves the selected node when the same logical endpoint was edited, and carries matching latency results across the refresh.

If downloading or parsing fails, the previous working node list is retained. The error dialog presents a readable explanation and an optional raw error view; subscription credentials and VLESS user IDs are redacted before raw errors reach the UI.

## Node sorting

Sorting is stored independently for each subscription:

- **ORIGIN** keeps the exact order received from the provider
- **DELAY** orders successful URL-test results from lowest to highest latency, then failed and untested nodes
- **NAME** uses natural numeric ordering, so `France #2` comes before `France #10`

Choosing **DELAY** without current test data starts the existing group URL test first and keeps the list stable until that batch completes. The selected sort mode is also applied to node lists opened from country markers on the map.

## Node map

The map is a lightweight custom Compose renderer rather than a full map SDK. It downloads low-resolution country GeoJSON, caches it locally, renders real borders with a dark ProtonVPN-inspired presentation and keeps node markers interactive while zooming and panning.

Node location resolution follows a fallback chain:

1. resolve the node hostname to a public IP
2. perform IP geolocation
3. if that fails, infer the country from the node name using an emoji flag, ISO code or common country name

Only the resolved node IP is sent to the geolocation provider; subscription URLs, UUIDs and VLESS credentials are not included.

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
