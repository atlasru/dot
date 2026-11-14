# dot. Desktop

Windows **VLESS + Hysteria2** client built with Tauri 2, React/TypeScript, Rust, Xray-core **v26.7.28** and Wintun. Latest published version **[v0.5.0](https://github.com/atlasru/dot/releases/tag/v0.5.0)**.

## Install or update

Download the [Windows x64 portable ZIP](https://github.com/atlasru/dot/releases/download/v0.5.0/dot-desktop-0.5.0-windows-x64.zip) and verify it against the [release checksums](releases/0.5.0.md#published-downloads). Extract the entire archive and keep the `runtime/` directory beside the executable. Install Microsoft Edge WebView2 Runtime if needed and run `dot-desktop.exe` as administrator. To upgrade an existing portable build, exit dot. before replacing the portable files and keep `state.json` in the application data directory.

## Current release — 0.5.0

- Test individual nodes with Via Proxy GET/HEAD, direct TCP or system ICMP. Test All runs with bounded concurrency, streams per-node results and preserves results completed before cancellation. Isolated proxy tests can run while the VPN is connected.
- AUTO uses recent successful GET/HEAD results, respecting per-node exclusions and an optional latency limit. Manual selection disables AUTO.
- Advanced settings cover DNS/LAN bypass, subscription request and refresh controls, appearance and a redacted diagnostics export.
- VLESS and Hysteria2 links, mixed subscriptions, per-subscription HWIDs and previously saved preferences remain supported. See [diagnostic semantics and platform limits](DIAGNOSTICS.md).

## 0.3.0 — original unified Desktop workspace (historical)

Stable Desktop release, published together with Android 0.3.0 from the same source revision.

### Home and connection

- Home Orb connects, disconnects and cancels startup; switching nodes uses a controlled restart.
- Validate generated configuration with the pinned `xray.exe run -test` before starting TUN.
- Publish Connected after startup and an external connectivity probe.
- Show interface download/upload rates, session totals and connection duration.
- Track the running node by ID rather than its display name.
- Serialize connection changes and individual/batch URL tests, including tray Connect actions.
- Windows Job Object owns the Xray process tree; watchdog detects unexpected exits.
- Open, Connect/Disconnect and Exit from the tray; optional close-to-tray.

### Nodes

- Original, delay and natural-name sorting.
- Individual URL tests and batches of at most six concurrent Xray processes. Batch tests require a disconnected VPN.
- Persistent test results and favorites; search name, host, port or protocol.
- List/map toggle, zoom, pan and country filtering. Search and favorites also filter map markers.
- Map lookup runs only after opening Map, with at most four frontend lookup workers. GeoIP uses ipapi.co; country-name fallback uses restcountries.com and is explicitly approximate. Country geometry is fetched from the existing GeoJSON source and cached locally. Unlocated nodes remain accessible in the list.

### Subscriptions and local imports

- Add HTTP(S) subscriptions; parse plaintext and Base64 VLESS lists.
- Rename, change source URL, or remove a group with confirmation. Disconnect before deleting groups.
- Validate a changed URL before atomically replacing its source and nodes. Empty URL converts a group to a local import.
- Import VLESS links by pasting into the import field or selecting a UTF-8 text file; identical links are deduplicated. Input is limited to 4 MB.
- Successful refresh reports added, edited and removed nodes and preserves selected logical nodes, favorites and test results.
- When the selected node disappears, report it explicitly and choose a valid next selection. An existing VPN session keeps its original configuration until disconnected; it is never silently restarted by subscription refresh.
- Failed download, parse, duplicate-source or disk-write operations preserve previous persisted/live data.

### Automatic refresh

Off by default. Settings offer refresh at application startup and intervals of 1, 6, 12 or 24 hours. The Rust scheduler remains active in the tray and skips local imports. It checks due groups every 30 seconds and throttles failed scheduled attempts to at most once every five minutes. It does not run after the application exits.

Last successful update time is persisted. The latest result/error per group is retained for the current application session. Import, edit, delete, manual and scheduled refreshes share a lock so stale fetches cannot overwrite a changed source or recreate a deleted group.

## Versions and builds

`desktop/package.json` is the version source. After changing it, run:

```sh
cd desktop
npm run sync-version
```

This synchronizes Cargo and Tauri manifests and package lock versions, including Cargo.lock when present. Build/CI rejects version drift. UI reads the running Tauri package version, and the CI portable artifact uses the same version.

```sh
cd desktop
npm ci
npm run build
npm run icons
npm run tauri -- dev
```

Windows development requires the official `xray.exe` and `wintun.dll` under `desktop/src-tauri/runtime/`. CI downloads them from the pinned Xray release. Administrator privileges are required for TUN and routing changes.

The [unified release workflow](RELEASING.md) builds both platforms at the same revision. Windows uses release mode and a verified portable ZIP containing the executable, Xray and Wintun. The application is unsigned; WebView2 Runtime must be installed. NSIS is configured but is not a validated release artifact.

## Validation

Automated gates: Rust unit tests, Windows Job Object tests, real pinned Xray configuration validation, Windows interface API test, version consistency, strict TypeScript checking, Vite build and Tauri executable compilation.

Manual Windows release checklist:

- [ ] Fresh install and migration of existing state.json
- [ ] Connect, switch nodes, cancel startup and disconnect
- [ ] Tray actions, window close, full exit and Xray crash
- [ ] Network loss/recovery and sleep/resume (automatic reconnect is not implemented)
- [ ] Refresh, source change and selected-node removal while connected
- [ ] Individual/batch URL tests and map/list filters
- [ ] Scheduled refresh while the window is closed to the tray
- [ ] DNS, IPv4/IPv6 behavior and routing cleanup after disconnect

The Desktop build provides numeric custom DNS and verified-proxy AUTO. Network-aware reconnect, per-app split tunneling, a kill switch, DoH/DoT controls, boot autostart and in-app updates are outside the current implementation.
