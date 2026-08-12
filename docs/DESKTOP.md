# dot. Desktop

Desktop development is intentionally staged behind CI and is not released until the VPN path is complete and regression-tested.

## Current branch milestone: M1 core

The Windows client uses Tauri 2 + React for the UI and a Rust backend. Xray-core v26.7.28 is pinned as a runtime dependency and is downloaded by CI rather than committed to the repository.

The M1 data path is real, not simulated:

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

## Not yet considered release-ready

M1 deliberately does not advertise unfinished controls. Tray integration, traffic accounting, crash recovery/job-object ownership, network-change recovery, updater signing and the final installer belong to the later milestones and will only appear in the UI once implemented.
