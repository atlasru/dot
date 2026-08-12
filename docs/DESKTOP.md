# dot. Desktop

Desktop development is intentionally staged behind CI and is not released until the VPN path is complete and regression-tested.

## Current milestone: M2 lifecycle

The Windows client uses Tauri 2 + React for the UI and a Rust backend. Xray-core v26.7.28 is pinned as a runtime dependency and is downloaded by CI rather than committed to the repository.

The data path is real, not simulated:

```text
subscription URL
  -> HTTP fetch
  -> plaintext/Base64 detection
  -> VLESS parser
  -> persisted subscription group
  -> selected node
  -> current Xray JSON generator
  -> xray run -test
  -> Xray Windows TUN / Wintun
  -> automatic Windows routes + DNS
  -> HTTP connectivity probe
  -> connected
```

`CONNECTED` is returned only after Xray remains alive through TUN startup and at least one external HTTP connectivity probe succeeds.

## M2 lifecycle guarantees

M2 makes the Xray process a strict child of the dot. lifetime instead of a best-effort subprocess:

- every running Xray instance is assigned to a Windows Job Object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`;
- if dot. is closed normally, the job is closed and the entire Xray process tree is terminated;
- if dot. crashes or is killed through Task Manager, Windows closes the process-owned job handle and terminates the Xray process tree;
- a session journal records the active Xray PID, executable path and selected node and is cleared on clean shutdown;
- a stale journal is consumed on the next startup rather than being treated as a live connection;
- a backend watchdog checks the Xray process even when the UI is idle;
- VPN state is stored independently from the engine mutex, so status polling remains responsive while Xray/TUN is starting;
- `DISCONNECT` is available during startup and sends a cancellation signal without waiting for the startup mutex.

The Job Object is the cleanup authority. The session journal is bookkeeping/recovery metadata and never kills an arbitrary process by a stale PID.

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

Windows CI must pass all of the following before Desktop changes are merged:

1. subscription/VLESS/Rust unit tests;
2. Windows Job Object kill-on-close test;
3. generated config validation using the pinned real `xray.exe run -test`;
4. React/Vite frontend build;
5. Tauri Windows executable compilation.

## Not yet considered release-ready

M2 still deliberately does not advertise unfinished controls. Tray integration, traffic accounting, network-change auto-recovery, updater signing and the final installer belong to later milestones and will only appear in the UI once implemented.
