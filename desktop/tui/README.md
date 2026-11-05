# dot. TUI

Native Windows terminal frontend. Uses the exact Rust storage, subscription, VLESS/HY2 parsers, Xray config builder and real VPN engine from desktop/src-tauri/src. No browser, WebView2, React, Tauri or WebGL.

## Build

In PowerShell:

    cd desktop\tui
    cargo build --release

Place xray.exe and wintun.dll next to the generated executable in a runtime\ directory:

    desktop\tui\target\release\runtime\xray.exe
    desktop\tui\target\release\runtime\wintun.dll

Alternatively set DOT_RUNTIME to the directory containing both files. Run an **elevated Windows Terminal**:

    .\target\release\dot-tui.exe

By default, imports and selections share the desktop app's state at %APPDATA%\dev.dotclient.desktop\state.json. Stop the desktop GUI before running TUI: both write this file. Set DOT_DATA_DIR to use a different profile. Both apps must not use the same VPN simultaneously.

## Keys

- Up/Down or j/k: move between nodes
- Tab / Shift+Tab: next/previous subscription group
- Enter: connect or switch to selected node (real Xray/TUN)
- d: disconnect
- a: add remote subscription; type URL and press Enter
- r: refresh selected remote subscription
- t: test selected node latency (while offline)
- s: cycle node order (origin/name/delay)
- q: quit and stop VPN
- Esc: cancel URL input

Subscription URLs and proxy credentials are never displayed in the interface. The state file still contains secrets, just as in desktop dot. Native VPN needs administrator privileges and working Xray/Wintun runtime.

This version is Windows-only because the existing dot. desktop engine and its TUN implementation are Windows-specific.

Note: Build and real Windows VPN behavior have not yet been verified in CI.
