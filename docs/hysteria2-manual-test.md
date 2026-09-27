# Hysteria2 0.4.0 validation

Development milestone only. Xray-core/libXray remains pinned to **v26.7.28** on both platforms. The Windows CI validates generated VLESS and HY2 TUN and URL-test configurations with the pinned `xray.exe run -test`. Android needs a device test with the pinned AAR. UDP/QUIC must be permitted by the current network.

Supported URI schemes: `hy2://` and `hysteria2://`; both map to Hysteria version 2. URI options: `sni`/`serverName`/`peer`, `insecure`/`allowInsecure`, comma-separated `alpn`, `congestion` (`bbr`, `reno`, `brutal`, `force-brutal`), `up`, `down`, `obfs=salamander`, `obfs-password`, `packetSize` (single integer or range up to 2048). Unknown options are ignored. Other finalmask types and Hysteria v1 are unsupported. Server inbound fields are never copied to the client.

Existing Android subscriptions are stored as raw URIs and re-parsed, so old VLESS entries remain readable. Desktop persisted VLESS nodes lacking `proxy_config` default to VLESS. A corrupt Android state is retained rather than overwritten by an empty state; Desktop reports deserialization errors without writing a replacement. Keep a backup of production state before installing a development build.

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
