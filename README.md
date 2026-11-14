# dot.

Minimal VLESS and Hysteria2 client for Android and Windows with an AMOLED-first, Nothing-inspired interface.

**Latest stable release [v0.5.0](https://github.com/atlasru/dot/releases/tag/v0.5.0)**, published 14 November 2026. Android ships a development-signed debug APK and Windows ships an unsigned x64 portable ZIP. The maintainer tested both published builds manually. Provider and network compatibility varies. [Website](https://atlasru.github.io/dot/) · [Changelog](CHANGELOG.md) · [Release notes](docs/releases/0.5.0.md).

## Features

- VLESS with REALITY / TLS
- Hysteria2 over TLS and UDP/QUIC (`hy2://` and `hysteria2://`); optional Salamander finalmask and packet size range
- HTTPS subscription import
- plaintext and Base64 subscription decoding
- multiple subscription groups
- per-subscription device identity (X-HWID), with edit, copy and manual regeneration
- subscription refresh reports with added, edited and deleted node counts/details
- classified subscription update errors with redacted raw-error viewing while keeping existing nodes intact
- node selection and persistence
- AUTO selection using recent successful proxy tests, with node exclusions and a latency limit
- per-subscription node sorting by provider order, delay or natural name order
- delay sorting queues checks for nodes without compatible measurements
- LIST / MAP views for nodes, with LIST as the default
- interactive world map with real country geometry, borders, Mercator projection, pinch zoom and pan
- DNS + IP geolocation for subscription nodes with local caching
- fallback country detection from node flags, ISO codes and country names when IP geolocation fails
- country-level node grouping so multiple nodes do not overlap on the map
- GET/HEAD proxy checks, direct TCP and system ICMP, with cancellable Test All and per-node progress
- Android Motion for the connection orb, tabs, node reordering, settings and dialogs
- advanced settings for diagnostics, DNS/LAN, subscription requests and Android reconnect policy
- direct node selection and switching while connected
- per-app split tunneling with all-apps, exclude-selected and only-selected modes
- network-aware reconnect after Wi-Fi/mobile-data changes
- configurable reconnect backoff and retry cap
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

- **Home** is connection-first: the pixel orb, connection state, active node, traffic and connection test stay in one place
- **Nodes** contains the AUTO toggle, subscription switching, refresh/test actions, sorting, LIST/MAP views and per-node controls
- **Settings** contains subscriptions, split tunneling, connection state, appearance and app information

The primary destinations use a floating pill navigation bar above the Android system navigation/gesture area. The selected destination is highlighted as an inner pill with the dot. red accent, while navigation automatically respects the system navigation inset.

Android Motion animates the VPN orb, tab transitions, node reordering, diagnostic results, settings and dialogs.

## AUTO NODE

AUTO is an opt-in setting on Android and Windows. It selects the lowest-latency node in the selected subscription with a compatible successful Via Proxy GET/HEAD result from the past 15 minutes. Exclusions and the optional maximum latency apply. Manual selection disables AUTO. If no node qualifies, run GET/HEAD diagnostics or choose a node manually.

## Connection reliability

The Android VPN service observes default-network changes through `ConnectivityManager`.

If Wi-Fi or mobile data disappears while a tunnel is active, dot. moves to **waiting for network** instead of treating the interruption as a permanent failure. When connectivity returns, the same selected VLESS or Hysteria2 node and split-tunnel configuration are re-established automatically.

Reconnect delays use 1, 2, 5, 10 and 30 times the configured base interval, up to the configured maximum. A manual disconnect cancels the reconnect loop.

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

Each subscription has its own locally generated HWID for subscription-service compatibility. It uses 128 random bits (`dot-` followed by 32 lowercase hexadecimal characters), survives restarts, and is never derived from hardware identifiers. Existing subscriptions receive an HWID once during migration.

Change, paste, copy or regenerate it in **Settings → Subscriptions → Add/Edit** on Android and **Settings → Subscriptions → EDIT** on Windows. Saving a regenerated value changes the identity presented by that subscription. Values are preserved exactly; both clients accept 10–64 ASCII letters, digits, `=` or `-`. There is no automatic rotation.

Subscription downloads send `x-hwid`, `x-device-os`, `x-ver-os` and `x-device-model` alongside the existing User-Agent. Identity headers are scoped to the subscription origin and removed on redirects to a different origin. They are never added to VPN traffic, node tests, GeoIP or other requests. See [device identity details and validation](docs/SUBSCRIPTION_IDENTITY.md).

Refreshing a subscription compares the newly parsed VLESS and Hysteria2 nodes with the currently stored nodes before replacing them. `dot.` reports added, edited and deleted nodes, preserves the selected node when the same logical endpoint was edited, and carries matching latency results across the refresh.

If downloading or parsing fails, the previous working node list is retained. The error dialog presents a readable explanation and an optional raw error view; subscription credentials, VLESS user IDs, Hysteria2 authentication and obfuscation passwords are redacted before raw errors reach the UI.

## Node sorting

Sorting is stored independently for each subscription:

- **ORIGIN** keeps the exact order received from the provider
- **DELAY** orders successful URL-test results from lowest to highest latency, then unavailable, failed and untested nodes on Android
- **NAME** uses natural numeric ordering, so `France #2` comes before `France #10`

Choosing **DELAY** can queue tests for nodes without compatible measurements. Test All streams results and keeps completed measurements when cancelled. On Android, **OK** reports valid latency, **FAIL** indicates a confirmed credential, configuration or protocol error, and **UNAVAILABLE** covers timeouts and other network failures. See [Diagnostics](docs/DIAGNOSTICS.md).

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

Download from the [v0.5.0 release](https://github.com/atlasru/dot/releases/tag/v0.5.0) or the [dot. website](https://atlasru.github.io/dot/).

| Platform | Download | Details |
| --- | --- | --- |
| Android 8.0+ (API 26) | [dot-android-0.5.0-dev-debug.apk](https://github.com/atlasru/dot/releases/download/v0.5.0/dot-android-0.5.0-dev-debug.apk) | Development-signed debug APK. Installed version: `0.5.0-debug`, code **500**, package `dev.dotclient.android.debug`. |
| Windows x64 | [dot-desktop-0.5.0-windows-x64.zip](https://github.com/atlasru/dot/releases/download/v0.5.0/dot-desktop-0.5.0-windows-x64.zip) | Unsigned release-mode portable ZIP. |

On Android, open the APK and allow installs from your download app if prompted. A compatible `0.4.0-debug` hotfix install (code 401) can upgrade in place when its package ID and signing certificate match. Then open dot. and import a compatible subscription if needed.

On Windows, extract the full ZIP and keep `runtime/` next to `dot-desktop.exe`. Run the executable as administrator for VPN routing. Microsoft Edge WebView2 Runtime is required. To update, close dot. before replacing the portable files and keep your existing settings directory.

Verify the downloads against [SHA256SUMS](https://github.com/atlasru/dot/releases/download/v0.5.0/SHA256SUMS).

```text
f641f4b4f255cc8fd6a1a2deabcc75dfb3ccbd11d25326436df2970ff9ce47c8  dot-android-0.5.0-dev-debug.apk
fa43d74019e037f9aacb2606ba6602b95d9ab92283ab2e762cdaa73f7bf04682  dot-desktop-0.5.0-windows-x64.zip
```

Both published files come from [CI #63](https://github.com/atlasru/dot/actions/runs/43451993488), built from `57de8207348f9eaded4e9e92954008b90d328532`.

See [Desktop](docs/DESKTOP.md), [Hysteria2 validation](docs/hysteria2-manual-test.md) and [Release process](docs/RELEASING.md) for limitations, tests and provenance.

## Build from source

Requirements:

- JDK 17
- Android SDK 36 / Build Tools 36.0.0
- Gradle 9.5.0

CI downloads the pinned libXray Android AAR (v26.7.28) into `app/libs/libXray.aar` before compilation. For local Android builds, provide that AAR first using the same pinned upstream release.

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Windows development

The Windows client uses Tauri 2, Rust, React and TypeScript. From `desktop/`, run `npm ci`, `npm test`, and `npm run build`. Building or running the Tauri VPN backend requires a Windows environment with Rust and the pinned `xray.exe` / `wintun.dll` placed in `desktop/src-tauri/runtime/`. See [Desktop](docs/DESKTOP.md).

## Privacy

Treat subscription URLs, VLESS UUIDs, Hysteria2 authentication/obfuscation passwords and subscription access tokens as credentials. Do not publish private links or unredacted runtime logs.

Split tunneling stores only Android package names selected by the user. dot. does not need broad installed-package visibility and does not send the app selection anywhere.

AUTO NODE selection and reconnect diagnostics remain local to the device. dot. does not upload latency history, network state or runtime VPN errors.

## Stack

Kotlin · Jetpack Compose · Android VpnService · OkHttp · libXray / Xray-core · Rust · Tauri 2 · React · TypeScript · GitHub Actions

## Documentation

- [Architecture](ARCHITECTURE.md)
- [VPN engine](docs/VPN_ENGINE.md)
- [Changelog](CHANGELOG.md)
- [Desktop](docs/DESKTOP.md)
- [Diagnostics and advanced settings](docs/DIAGNOSTICS.md)
- [Subscription identity / HWID](docs/SUBSCRIPTION_IDENTITY.md)
- [Release and checksums](docs/releases/0.5.0.md)
- [Contributing](CONTRIBUTING.md)

## License

**Copyright (C) 2026 atlasru and contributors.** Original dot. source code
is licensed under the **[GNU General Public License v3.0 or later](LICENSE)**
(`GPL-3.0-or-later`).

You may use, study, modify and redistribute dot., including for commercial
purposes. When redistributing a GPL-covered modified version, you must comply
with the GPL's corresponding-source and license obligations: distributing a
proprietary/closed-source derivative is not permitted under the GPL.

The Xray/libXray/Wintun runtimes and other third-party packages retain
**their own copyright and license terms**. See
[third-party notices](THIRD_PARTY_NOTICES.md) and original license texts in
[`third_party_licenses/`](third_party_licenses/).

**Important release distinction:** This licensing change applies to the
repository from the commit that introduced `LICENSE`. The already published
v0.4.0 binaries were built from an **earlier** source revision; their bytes and
checksums have not been modified or retrospectively rebuilt with the new
notices. New GPL-distributed binaries should carry the GPL and third-party
notices and point to their **exact corresponding source**.
