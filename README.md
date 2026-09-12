# dot.

Minimal VLESS client for Android with an AMOLED-first, Nothing-inspired interface.

Current Android version: **0.3.0**

## Features

- VLESS with REALITY / TLS
- HTTPS subscription import
- plaintext and Base64 subscription decoding
- multiple subscription groups
- subscription refresh reports with added, edited and deleted node counts/details
- classified subscription update errors with redacted raw-error viewing while keeping existing nodes intact
- node selection and persistence
- AUTO node mode that picks the fastest successful tested node
- per-subscription node sorting by provider order, delay or natural name order
- delay sorting runs URL tests first when current latency data is unavailable
- LIST / MAP views for nodes, with LIST as the default
- interactive world map with real country geometry, borders, Mercator projection, pinch zoom and pan
- DNS + IP geolocation for subscription nodes with local caching
- fallback country detection from node flags, ISO codes and country names when IP geolocation fails
- country-level node grouping so multiple nodes do not overlap on the map
- node latency / connection tests through libXray URL testing
- direct node selection and switching while connected
- per-app split tunneling with all-apps, exclude-selected and only-selected modes
- network-aware reconnect after Wi-Fi/mobile-data changes
- capped reconnect backoff: 1s, 2s, 5s, 10s, then 30s
- connection failure classification for DNS, timeout, refused, TLS, REALITY, TUN, config and Xray startup errors
- Android VpnService + TUN integration
- libXray / Xray-core backend
- realtime upload/download traffic
- foreground VPN notification with active node and traffic
- Quick Settings connect/disconnect tile, including reconnect/waiting states
- long-press Quick Settings tile opens dot.
- AMOLED, Graphite and Matrix themes
- selectable launcher icon variants
- no accounts, ads, analytics or telemetry

## Mobile interface

Android uses a small three-destination layout:

- **Home** is connection-first: the pixel orb, connection state, active node, AUTO status, traffic and connection test stay in one place
- **Nodes** contains subscription switching, refresh/test actions, sorting, LIST/MAP views and per-node controls
- **Settings** contains subscriptions, AUTO node, split tunneling, connection state, appearance and app information

The primary destinations use a floating pill navigation bar above the Android system navigation/gesture area. The selected destination is highlighted as an inner pill with the dot. red accent, while navigation automatically respects the system navigation inset.

The redesign changes information hierarchy without removing the existing node map, latency tests, sorting, subscription management, theme selection or launcher-icon controls.

## AUTO node

AUTO node keeps the manual node list intact but removes the need to pick a server for normal use.

When AUTO is enabled, dot. uses the current URL-test results for the selected subscription and chooses the successful node with the lowest measured latency. Failed nodes are excluded. If no fresh latency data exists, dot. runs the existing group URL test first and stores the selected AUTO node as the current profile so Quick Settings reconnects use the same choice.

Manually selecting a node disables AUTO, making the change explicit instead of silently fighting the user's selection.

## Connection reliability

The Android VPN service observes default-network changes through `ConnectivityManager`.

If Wi-Fi or mobile data disappears while a tunnel is active, dot. moves to **waiting for network** instead of treating the interruption as a permanent failure. When connectivity returns, the same VLESS profile and split-tunnel configuration are re-established automatically.

Failed reconnects use capped backoff intervals of 1, 2, 5, 10 and 30 seconds; further retries stay at 30 seconds. A manual disconnect cancels the reconnect loop immediately.

Startup failures are classified into readable categories such as DNS, timeout, connection refused, TLS, REALITY, TUN, invalid configuration and Xray startup failure. The latest category and reconnect attempt are exposed in Settings while the detailed runtime error stays local to the process.

## Split tunneling

Split tunneling is applied at the Android `VpnService.Builder` layer before the TUN interface is established. The Xray profile itself remains unchanged.

Three routing modes are available:

- **ALL APPS** routes the device through the VPN as before
- **EXCLUDE SELECTED** keeps the VPN as the default but lets selected apps use the normal network
- **ONLY SELECTED APPS** places only the selected apps inside the VPN

Only launchable applications are shown in the selector, so dot. does not request Android's broad `QUERY_ALL_PACKAGES` permission. Rules are stored by package name and survive app upgrades. Missing/uninstalled packages are ignored in exclude mode; include-only mode refuses to establish a tunnel if none of the selected packages are currently installed, avoiding an unsafe fallback to all-app routing.

Changing split-tunnel rules while connected does not mutate the live TUN interface. The UI offers an explicit reconnect action to apply the new app routing.

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

Split tunneling stores only Android package names selected by the user. dot. does not need broad installed-package visibility and does not send the app selection anywhere.

AUTO node selection and reconnect diagnostics remain local to the device. dot. does not upload latency history, network state or runtime VPN errors.

## Stack

Kotlin · Jetpack Compose · Android VpnService · OkHttp · libXray / Xray-core · GitHub Actions

## Documentation

- [Architecture](ARCHITECTURE.md)
- [VPN engine](docs/VPN_ENGINE.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## License

A project license has not been selected yet.
