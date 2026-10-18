# Third-party licensing notices

This document identifies **major third-party components** used by dot. and their
upstream licenses. It is not a substitute for the licenses of every transitive
dependency: the Gradle, npm and Cargo dependency graphs may contain other works
with their own copyright and distribution obligations.

**dot. original source code:** Copyright (C) 2026 atlasru and contributors.
Licensed under **GNU GPL v3 or (at your option) any later version**
(`SPDX-License-Identifier: GPL-3.0-or-later`); see [LICENSE](LICENSE).
This grant covers the original dot. code and does **not** change the licenses
or copyright of third-party components.

## Core / VPN runtime

| Component | Project | Applicable license | License copy |
| --- | --- | --- | --- |
| Xray-core / `xray.exe` | [XTLS/Xray-core](https://github.com/XTLS/Xray-core) | Mozilla Public License 2.0 (MPL-2.0) | [Original license](third_party_licenses/Xray-core-MPL-2.0.txt) |
| libXray Android wrapper / AAR | [XTLS/libXray](https://github.com/XTLS/libXray) | MIT | [Original license and XTLS copyright](third_party_licenses/libXray-MIT.txt) |
| Prebuilt `wintun.dll` | [WireGuard/Wintun](https://www.wintun.net/) | **Wintun Prebuilt Binaries License (PBL)**, *not* GPL | [Original binary license](third_party_licenses/Wintun-PBL.txt) |

The core / wrapper releases used by dot. v0.4.0 are pinned to `v26.7.28`.
The Wintun DLL is taken unmodified from the pinned upstream Xray Windows
archive and loaded as a separate runtime component. Wintun's *source code*
has GPLv2 terms, but the **official prebuilt DLL** is distributed under its
separate PBL. The dot. GPL license does not relicense the Wintun binary and
does not override the Wintun PBL's use and redistribution conditions.

## Application framework and supporting dependencies

- [Tauri](https://github.com/tauri-apps/tauri): MIT or Apache-2.0.
- [React](https://github.com/facebook/react) and React DOM: MIT.
- [OkHttp](https://github.com/square/okhttp): Apache-2.0.
- [AndroidX / Jetpack Compose](https://developer.android.com/jetpack): Android
  open-source components, primarily Apache-2.0.

The specific versions and other direct/transitive dependencies are declared
in `desktop/package-lock.json`, `desktop/src-tauri/Cargo.lock`, the
Android Gradle configuration and the pinned libXray / Xray upstream artifacts.
Their own license notices remain effective.

## Redistribution and source availability

- Do not remove copyright, disclaimer, patent or license notices from upstream
  components.
- The **Windows portable ZIP** produced after adoption of this policy must
  carry dot.'s `LICENSE`, this notice, the original Xray MPL license and the
  Wintun binary PBL license alongside the Xray/Wintun runtime.
- The **Android APK** produced after adoption of this policy carries dot.'s
  `LICENSE`, this notice and the Xray MPL/libXray MIT texts as application
  assets.
- Publishing the GPL-covered dot. program in compiled form involves the
  GPL's corresponding-source requirements and a copy of the GPL. Keep a
  matching, accessible source revision and build instructions for each new
  distributed build; hosting only a different source/tag is insufficient.
- Third-party source remains at its respective upstream project. Follow its
  own notice and source-distribution requirements when redistributing it.

The already published [v0.4.0 downloads](https://github.com/atlasru/dot/releases/tag/v0.4.0)
were built **before** GPL-3.0-or-later adoption. Their bytes, hashes, original
source revision and Git tag have not been changed to add these notices.
This licensing commit describes the repository from this revision forward;
it must not be represented as a rebuild or relicensing of an unchanged binary
asset from an older commit.

If a new packaged release includes or updates a runtime dependency, review
its actual license in the downloaded artifact and update these notices before
shipping it.
