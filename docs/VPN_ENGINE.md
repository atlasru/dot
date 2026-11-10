# VPN engine

dot. v0.4.0 uses pinned **Xray/libXray v26.7.28**. Android uses `VpnService`, a system TUN interface and the upstream libXray AAR. Windows uses an independently configured Xray-core executable with Wintun. Both platforms support VLESS with REALITY/TLS and Hysteria2 over UDP/QUIC.

## Android startup

1. Request VPN permission with `VpnService.prepare()`.
2. Create a TUN interface through `DotVpnService` and apply the saved split-tunnel policy.
3. Select the best matching non-VPN network. Protect Xray's outbound and bootstrap DNS sockets with `VpnService.protect()` and bind them to that network. Android app DNS is configured on the IPv4 TUN interface.
4. Construct a client outbound for the selected URI:
   - **VLESS:** libXray share-link conversion followed by normalization of invalid `sendThrough` and server-only REALITY data, preserving the client's SNI/fingerprint/public key/short ID.
   - **Hysteria2:** `Hysteria2UriParser` + `Hysteria2XrayConfig` generate a native Hysteria v2 outbound (TLS/SNI/ALPN; optional congestion, Salamander and packet-size parameters).
5. Insert the root `env` key `xray.tun.fd` and the `dot-tun` inbound into the JSON.
6. Invoke libXray `runXrayFromJson` and publish `VpnRuntime` state to the app, notification and Quick Settings tile.

## Reliability and network handoff

Android observes non-VPN network changes even when the VPN itself is the app's default network. On a Wi-Fi/mobile handoff the service establishes the replacement TUN, closes the old core and opens the new core on the replacement network. If no underlay is available, network recovery keeps the established TUN in place and waits for a network. Turning off network recovery disconnects the VPN after a network outage.

Startup retry delays use the **1, 2, 5, 10, 30** backoff multipliers with the configured base interval and maximum. A newer connection or disconnect invalidates pending retries. A permanent configuration error ends the session.

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

### Android TUN verification on a device

1. Use a real VLESS profile and a real HY2 profile with UDP connectivity. Record baseline public egress and DNS behavior from an Android app on the device before connecting. Use an endpoint and resolver you control where possible.
2. Connect and repeat the external request from an app included in the tunnel. Verify the endpoint receives traffic from the node's egress and verify a fresh DNS query reaches the configured resolver. Use an app that uses Android system DNS for the DNS check.
3. Repeat with one included and one excluded app. Compare external egress for both apps and check that IPv6 is blocked for included apps on an IPv6-capable network.
4. Move between Wi-Fi and mobile data in both directions with the VPN connected. After each transition, use a fresh request and a fresh DNS name to confirm the node's egress and resolution. Repeat after losing both networks, restoring one, switching nodes and manually disconnecting during a retry.
5. Use `adb shell dumpsys connectivity` to inspect the VPN's routes, DNS and underlay. Use `adb logcat -s DotVpnService` for lifecycle transitions. Record the device, build, protocol and network for each scenario. Keep network dumps and profile credentials private.

The app's Connected state reports TUN and core startup. Device verification requires the external traffic and DNS observations above.

See [architecture](../ARCHITECTURE.md) and [Hysteria2 manual tests](hysteria2-manual-test.md).
