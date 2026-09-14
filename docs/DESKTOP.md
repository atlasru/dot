# dot. Desktop

Windows VLESS client built with Tauri 2, React/TypeScript, Rust, Xray-core v26.7.28 and Wintun.

## 0.3.0-alpha.1 — unified Desktop workspace

This development line combines the node-management work from PR #34 with the map components from Desktop 0.2.2-alpha.1. Android and the website remain on the current main baseline.

### Home and connection

- Connect, Disconnect and switch nodes with a controlled restart.
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

This synchronizes Cargo and Tauri manifests. Build/CI rejects version drift. UI reads the running Tauri package version, and the CI portable artifact uses the same version.

```sh
cd desktop
npm ci
npm run build
npm run icons
npm run tauri -- dev
```

Windows development requires the official `xray.exe` and `wintun.dll` under `desktop/src-tauri/runtime/`. CI downloads them from the pinned Xray release. Administrator privileges are required for TUN and routing changes.

The existing unified-release workflow describes historical Android 0.1.8 packaging; it is not the release path for this alpha. The Desktop PR workflow produces the versioned portable Windows build without publishing a release.

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

Keep this alpha PR in draft until manual Windows regression is completed. Split tunneling, kill switch, automatic reconnect/AUTO node selection, custom DNS modes, autostart and application updating remain future milestones.
