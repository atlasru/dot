# Hysteria2 0.4.0 validation

**Current status (18 October 2026):** Hysteria2 is included in the published [v0.4.0 release](releases/0.4.0.md). The original 14 October APK and Windows ZIP were replaced following Hysteria2 Xray routing/Salamander fixes by verified artifacts from [Actions #39111580483](https://github.com/atlasru/dot/actions/runs/39111580483), source `9d7064c3`. The original Git tag is not the hotfix source. Xray-core/libXray remains pinned to **v26.7.28** on both platforms. Windows CI checks generated VLESS/HY2 TUN and URL-test configs against the real pinned `xray.exe run -test`; Android CI compiles and runs unit tests. Successful real-device usage was reported for the updated builds, but all the scenarios below still need representative per-provider validation. HY2 requires working UDP/QUIC.

Supported URI schemes: `hy2://` and `hysteria2://`; both map to Hysteria version 2. URI options: `sni`/`serverName`/`peer`, `insecure`/`allowInsecure`, comma-separated `alpn`, `congestion` (`bbr`, `reno`, `brutal`, `force-brutal`), `up`, `down`, `obfs=salamander`, `obfs-password`, `packetSize` (single integer or range up to 2048). Unknown options are ignored. Other finalmask types and Hysteria v1 are unsupported. Server inbound fields are never copied to the client.

Existing Android subscriptions are stored as raw URIs and re-parsed, so old VLESS entries remain readable. Desktop persisted VLESS nodes lacking `proxy_config` default to VLESS. A corrupt Android state is retained rather than overwritten by an empty state; Desktop reports deserialization errors without writing a replacement. Keep a backup of important state before upgrading; the Android APK remains a debug/development-signed distribution, even though v0.4.0 is the published GitHub release.

## Provider fixtures needed

- One standard TLS HY2 link and one Salamander link with actual auth, SNI, ALPN, congestion and packet size settings.
- Confirmation of certificate verification expectations and any port hopping or extra QUIC settings.
- Note every client-visible server setting tested; do not publish credentials in CI, issues or screenshots.

## Android device

- Import HY2-only and mixed VLESS/HY2 subscriptions; refresh an edited HY2 link. Confirm selected node, latency, sort and favorites survive.
- Connect on Wi-Fi and LTE/5G; browse and test UDP apps. Switch Wi-Fi to mobile data and back. Check capped reconnect and waiting-for-network.
- Test split tunneling, Quick Settings tile, foreground/background, URL test, AUTO NODE and direct switching between VLESS and HY2.
- Test standard HY2, Salamander, invalid auth, invalid SNI and a network that blocks UDP. Inspect diagnostics and ensure secrets stay hidden.

## Windows device

- Import text file, pasted links and remote mixed subscription. Connect over Ethernet and Wi-Fi.
- Disconnect, close to tray, reconnect, switch VLESS/HY2 live, run individual and group URL tests, verify traffic stats and process cleanup.
- Test standard HY2, Salamander, bad auth, bad SNI and blocked UDP. Confirm failed tests display FAIL and are not treated as successful nodes.
- Restart and confirm subscriptions, selection, favorites, latency, sorting and preferences persist. Confirm old 0.3.0 state loads first.

## Integrated HWID upgrade

- Update the existing 0.4.0 HY2 development install with the unified APK/portable ZIP from one CI run. Keep the existing data directory and Android application ID; do not reset or re-import subscriptions.
- Confirm existing VLESS/HY2 nodes, authentication, selection, settings and custom HWIDs survive. Missing HWIDs must be different per subscription and unchanged after restart.
- Give A and B distinct HWIDs; refresh A with B selected, then test Windows startup/scheduled refresh. Capture requests at the provider and confirm the requested subscription's identity.
- Paste a case-sensitive custom value, Copy it, regenerate A with confirmation and save, then restart. B must remain unchanged. Cancel regeneration/edit once and confirm the stored identity is retained.
- Confirm VLESS-only, HY2-only and mixed imports/refetches use the same saved identity. Verify all four identity headers are stripped on a different-origin redirect and preserved on same-origin redirects.
