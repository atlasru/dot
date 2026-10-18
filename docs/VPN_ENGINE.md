# VPN engine

dot. v0.4.0 uses pinned **Xray/libXray v26.7.28**. Android uses `VpnService`, a system TUN interface and the upstream libXray AAR. Windows uses an independently configured Xray-core executable with Wintun. Both platforms support VLESS with REALITY/TLS and Hysteria2 over UDP/QUIC.

## Android startup

1. Request VPN permission with `VpnService.prepare()`.
2. Create a TUN interface through `DotVpnService` and apply the saved split-tunnel policy.
3. Configure protected outbound sockets with `VpnService.protect()` and a protected DNS resolver.
4. Construct a client outbound for the selected URI:
   - **VLESS:** libXray share-link conversion followed by normalization of invalid `sendThrough` and server-only REALITY data, preserving the client's SNI/fingerprint/public key/short ID.
   - **Hysteria2:** `Hysteria2UriParser` + `Hysteria2XrayConfig` generate a native Hysteria v2 outbound (TLS/SNI/ALPN; optional congestion, Salamander and packet-size parameters).
5. Insert the root `env` key `xray.tun.fd` and the `dot-tun` inbound into the JSON.
6. Invoke libXray `runXrayFromJson` and publish `VpnRuntime` state to the app, notification and Quick Settings tile.

## Reliability and network handoff

Android observes default-network changes. A lost Wi-Fi/mobile connection becomes waiting-for-network rather than an immediate permanent error. Reconnect attempts use a capped **1, 2, 5, 10, 30-second** backoff (30 seconds thereafter); disconnect cancels retries. The selected VLESS/HY2 URI and split-tunnel policy are retained. Configuration errors can stop reconnecting rather than retrying an invalid config.

## Windows backend

The Rust backend builds protocol-specific TUN and URL-test Xray configurations. CI invokes the **real pinned `xray.exe run -test`** against representative VLESS and Hysteria2 configurations. Portable Windows ZIPs include `dot-desktop.exe`, `runtime/xray.exe`, `runtime/wintun.dll` and `BUILD.json`. Windows requires WebView2 Runtime and administrator privileges for routing/TUN. Automatic reconnect on network handoff, Android-style AUTO NODE and split tunneling are not currently Windows features.

## Network and identity boundaries

Subscription HTTP(S) fetches may send `x-hwid`, `x-device-os`, `x-ver-os` and `x-device-model` to the subscription origin. They are **not** included in tunneled VPN traffic, node URL tests or geolocation requests. Redirects crossing origins remove all four identity headers. See [subscription identity](SUBSCRIPTION_IDENTITY.md).

## Traffic and shutdown

Android samples UID traffic counters for Home and its foreground notification. Disconnect stops traffic reporting, calls libXray `stopXray`, resets DNS, closes the TUN descriptor and clears the runtime notification/state. Windows handles Xray child processes and routing through the native backend/Job Object.

## Compatibility / testing

- Only Hysteria **version 2** is supported; not Hysteria v1.
- For HY2, QUIC/UDP must be allowed by the network and server.
- CI validates configuration and compilation; real-world provider auth, routing, QUIC behavior and network handoff still need representative manual testing.
- Download **the patched [v0.4.0 release assets](releases/0.4.0.md)**, not old assets cached from 14 October 2026.

See [architecture](../ARCHITECTURE.md) and [Hysteria2 manual tests](hysteria2-manual-test.md).
