# dot. Desktop

Desktop development is intentionally staged behind CI and is not released until the VPN path is complete and regression-tested.

## Current milestone: M3 product

The Windows client uses Tauri 2 + React for the UI and a Rust backend. Xray-core v26.7.28 is pinned as a runtime dependency and is downloaded by CI rather than committed to the repository.

The VPN data path remains the M1/M2 path and is real, not simulated:

```text
subscription URL
  -> HTTP fetch
  -> plaintext/Base64 detection
  -> VLESS parser
  -> persisted subscription group
  -> selected node
  -> Xray JSON generator
  -> xray run -test
  -> Xray Windows TUN / Wintun
  -> automatic Windows routes + DNS
  -> HTTP connectivity probe
  -> connected
```

`CONNECTED` is returned only after Xray remains alive through TUN startup and at least one external HTTP connectivity probe succeeds.

## Lifecycle guarantees

- every running Xray instance is assigned to a Windows Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`;
- normal exit and process crashes both close the process-owned job and terminate the Xray process tree;
- a session journal records the active Xray PID/path/node and is cleared on clean shutdown;
- a stale journal is consumed safely on the next startup and never kills an arbitrary process by PID;
- a backend watchdog detects unexpected Xray exits;
- VPN state is independent from the engine mutex, so status polling remains responsive during startup;
- `DISCONNECT` remains available while Xray/TUN/connectivity checks are still starting.

## M3 product layer

M3 adds only controls backed by real backend behavior:

- Home / Nodes / Settings flow in the AMOLED-first Courier/pixel style;
- persistent selected subscription and node;
- AMOLED, Graphite and Matrix themes;
- native Tauri system tray;
- left-clicking the tray icon opens/focuses dot.;
- tray menu provides Open, Connect/Disconnect and Exit;
- `close to tray` is a persisted setting; when enabled, closing the main window hides it without stopping the VPN;
- subscription add/refresh and node selection are backed by the same persisted state used by tray connect;
- realtime traffic is sampled from Windows interface counters for the `dot0` TUN, not from hardcoded values or total process traffic;
- Home displays realtime download/upload rate, per-session byte totals and connection duration;
- application state is committed through a temporary file followed by Windows `MoveFileExW` with replace + write-through semantics.

The traffic sampler uses Windows IP Helper interface counters (`InOctets` / `OutOctets`) and resets its session baseline whenever VPN state leaves `connected`.

## Runtime files

Windows requires `xray.exe` and `wintun.dll` from the same pinned Xray release. They must be adjacent at runtime. GitHub Actions downloads `Xray-windows-64.zip` and places both files in `src-tauri/runtime/` before compilation.

For local development, place the same two files in:

```text
desktop/src-tauri/runtime/
  xray.exe
  wintun.dll
```

Then run:

```powershell
cd desktop
npm install
npm run icons
npm run tauri -- dev
```

The Windows application manifest requests administrator privileges because creating/configuring a Wintun TUN interface and system routes requires elevation.

## CI gates

Desktop changes must pass:

1. subscription/VLESS/Rust tests;
2. Windows Job Object kill-on-close test;
3. Windows IP Helper interface-enumeration test;
4. generated config validation using the pinned real `xray.exe run -test`;
5. React/Vite frontend build;
6. Tauri Windows executable compilation.

## Not yet release-ready

M3 is still an internal alpha. M4 must produce and test the actual installer/runtime layout, pin dependency lockfiles, introduce private release/update signing, add the updater path and exercise install/update/uninstall and network-recovery scenarios before a public Desktop release is cut.
