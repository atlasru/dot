# Unified releases

1. Finalize the versions on a branch. Android `versionName` and Desktop `package.json` must match a stable `X.Y.Z`. Keep Android's intended versionCode; v0.3.0 uses 300. Run `node desktop/scripts/sync-version.mjs` after changing Desktop's version.
2. Add `docs/releases/X.Y.Z.md`, update the changelog, and merge only after the unified-release validation jobs pass. These jobs run Android unit tests/APK verification, Windows Rust/Xray/Job Object/interface tests, strict TypeScript, Vite, release-mode Tauri compilation, ZIP validation and checksum generation. The `unified-release-candidate` artifact includes both binaries, notes and SHA256SUMS.
3. For an approved automatic release, include `.github/release-request.json` with `{"tag":"vX.Y.Z"}` in the reviewed PR. After both platform builds and asset checks pass on the final main commit, the promotion job verifies it is still main HEAD, creates the absent tag and explicitly dispatches the tag build. A consumed request never moves an existing tag. Alternatively, omit the request and manually create/push the version tag at the validated main commit. Never move an existing release tag.
4. A tag push runs the same two platform builds from the tag's single source SHA. The publication job checks that the tag matches the versions and belongs to main. It stages a draft, downloads and verifies the exact uploaded files, then publishes a stable release and verifies it again. Existing releases are rejected, never overwritten.
5. Reopen the release and check assets, hashes and download detection on the website. `workflow_dispatch` can retry an unpublished existing version tag selected in the UI; branch dispatches fail before building/publishing. If a failed publication left a draft, inspect it before taking any recovery action; automatic retries do not overwrite it.

Android currently distributes the established debug variant, signed with the repository's public development key. The asset is explicitly named `dot-android-X.Y.Z-dev-debug.apk`. The installed version includes `-debug`; this is not a production signing key. Release builds have no signing configuration, and this workflow does not invent one.

Windows distributes `dot-desktop-X.Y.Z-windows-x64.zip`: release-mode unsigned `dot-desktop.exe`, `runtime/xray.exe`, `runtime/wintun.dll` and source metadata `BUILD.json`. WebView2 Runtime and administrator privileges are required. NSIS exists in Tauri configuration but is not shipped without installer validation.

Pinned runtime: libXray / Xray-core v26.7.28. Neither platform imports binaries from an older dot. release. The normal tag-publish job owns release publication permissions. Android CI cannot publish a separate release; the exceptional one-time v0.4.0 asset-recovery workflow has been removed.

## Published v0.4.0 binary recovery (historical exception)

The first published `v0.4.0` files (14 October 2026) had broken Hysteria2 behavior. On **18 October 2026** their asset filenames were kept but APK, Windows ZIP and `SHA256SUMS` were explicitly replaced **once** with the validated [Actions run #39111580483](https://github.com/atlasru/dot/actions/runs/39111580483) outputs. That source is `9d7064c3fd83f340f787e6656c2d6f0c915cfcce`; the `v0.4.0` tag still points to `52a7dd01b989b26047fa3d36ef08ff3cab795945`. Release notes and checksums were updated, and the one-time recovery workflow was removed afterward.

The recovered Android build is `0.4.0-debug` with **versionCode 401** (previously 400); its package name and dev signing certificate are unchanged. The verified binaries are [listed here](releases/0.4.0.md). Do **not** describe the patched release assets as a reproducible build of the old tag.

**Future releases:** prefer a new version/tag and immutable artifact names over replacing public release bytes. Existing release assets still remain protected from accidental overwrite by the standard unified workflow; the historical v0.4.0 recovery was a deliberate manual exception. Remove a completed `.github/release-request.json` so an old release request cannot be interpreted as a new release action.

## Version / publication sanity checks

- Cross-check `desktop/package.json`, `desktop/src-tauri/Cargo.toml`, `desktop/src-tauri/tauri.conf.json` and Android `versionName` (all currently **0.4.0**).
- Cross-check the Android `versionCode` (currently **401**), installed package `dev.dotclient.android.debug` and the dev signing certificate.
- Check the current [GitHub release](https://github.com/atlasru/dot/releases/latest) and [website](https://atlasru.github.io/dot/). The website queries `releases/latest`, with pinned v0.4.0 fallbacks and matching hashes when the API is unavailable.
- Check full asset SHA-256 digests against published `SHA256SUMS`, not just file names.
- Never assume the `v0.4.0` tag equals its replaced downloadable binaries.
