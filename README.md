# dot.

Minimal VLESS client for Android with an AMOLED-first interface.

> Current milestone: **0.0.1 / foundation**. Subscription fetching and VLESS URI parsing are implemented. libXray/TUN startup is intentionally not enabled yet, so this milestone cannot establish a working VPN tunnel.

## Design

- pure black AMOLED background
- Courier-like system monospace typography
- minimal `home / nodes / settings` navigation
- no analytics, ads, accounts, or telemetry

## Implemented

- Kotlin + Jetpack Compose
- VLESS URI parser
- REALITY / TLS metadata parsing
- TCP, WS, gRPC, XHTTP, HTTPUpgrade transport metadata
- HTTPS subscription fetching with `Accept: */*`
- plaintext VLESS subscription decoding
- lenient Base64 subscription decoding
- subscription URL redaction
- node selection UI
- `TunnelEngine` abstraction
- Android `VpnService` boundary
- parser unit tests
- GitHub Actions debug APK build

## Next milestone

1. Build/pin libXray AAR.
2. Implement `XrayTunnelEngine` using libXray `Invoke` API.
3. Generate Xray JSON from `VlessProfile`.
4. Establish Android TUN via `VpnService.Builder` only immediately before starting Xray.
5. Put the TUN fd in Xray config root `env` as `xray.tun.fd`.
6. Implement socket protection and DNS handling.
7. Replace the stub CONNECT action with real VPN permission + service lifecycle.
8. Add foreground notification and live traffic stats.

## Subscription URL privacy

Treat `/sub/user/<id>` as a credential. Never commit a real subscription URL, UUID, or raw debug log. `SecretRedactor` is the first redaction layer; production diagnostics should redact again at the logging boundary.

## Build

Requires JDK 17, Android SDK 37 and Gradle 9.5.0.

```bash
gradle assembleDebug
```

The CI workflow builds `app-debug.apk` automatically.

## Package

Temporary application ID: `dev.dotclient.android`. Change it before the first public release if desired.
