# dot. Desktop

The Windows client is built as a real VLESS/REALITY VPN product, not a UI prototype. UI controls are only exposed when their backend behavior exists.

## Current milestone: M3 product

Stack:

- Tauri 2 + React/TypeScript UI
- Rust backend
- Xray-core v26.7.28
- Wintun TUN on Windows

The connection path is real end-to-end:

```text
subscription URL
  -> HTTP fetch
  -> plaintext/Base64 VLESS parsing
  -> persisted groups and selected node
  -> Xray JSON generation
  -> xray run -test
  -> Xray + Wintun TUN
  -> automatic Windows routing and DNS
  -> external connectivity probe
  -> CONNECTED
```

`CONNECTED` is published only after Xray survives TUN startup and an external connectivity probe succeeds.

## Lifecycle and crash safety

- Xray is assigned to a Windows Job Object using `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`.
- Closing or crashing dot. terminates the owned Xray process tree.
- A watchdog detects unexpected Xray exits while the UI is idle.
- Startup can be cancelled with Disconnect.
- A session journal records active-process metadata and stale records are consumed safely on the next launch.
- VPN state is independent from the engine mutex so status remains responsive during startup/shutdown.

## M3 product features

All items below have real backend behavior:

- persistent subscription groups;
- persistent selected node;
- Home / Nodes / Settings UI;
- AMOLED, Graphite and Matrix themes;
- realtime download/upload rate and session totals from the `dot0` Windows TUN interface;
- connection duration;
- system tray with Open, Connect/Disconnect and Exit;
- left-click tray icon opens the main window;
- optional close-to-tray behavior;
- subscription refresh without replacing a working group when fetch/parse fails;
- About information tied to the pinned desktop/Xray versions.

There are intentionally no UI toggles for unimplemented DNS modes, split tunneling, kill switch, autostart or updater behavior.

## Runtime files

Windows requires `xray.exe` and `wintun.dll` from the pinned Xray release. CI downloads the official `Xray-windows-64.zip`; the binaries are not committed to the repository.

For local development place both files in:

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

The application requests administrator privileges because creating the Wintun interface and changing system routing/DNS require elevation.

## CI gates

Desktop changes must pass:

1. Rust unit tests;
2. Windows Job Object kill-on-close test;
3. generated configuration validation through the pinned real `xray.exe run -test`;
4. Windows interface API test used by traffic sampling;
5. React/Vite build;
6. Tauri Windows executable compilation.

A public desktop release is only cut after the product build passes manual Windows VPN regression testing in addition to CI.
