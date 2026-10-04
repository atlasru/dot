# Subscription device identity

dot. sends a pseudonymous identifier in `x-hwid` for subscription-service compatibility. Every subscription owns a separate value. VPN transports and node credentials are unaffected.

## Values and controls

- Generated value: `dot-` plus 32 lowercase hex characters, 36 characters total, with 128 random bits.
- Android uses `SecureRandom`; Windows uses `getrandom` and the OS random generator. No hardware ID, MAC address, serial number, MachineGuid or additional permission is used.
- Custom values: 10–64 ASCII letters, digits, `=` or `-`. Case and all accepted characters are preserved exactly. Whitespace, controls and non-ASCII characters are rejected instead of trimmed.
- Compatibility reference: [Remnawave's documented Happ-style headers and HWID format](https://docs.rw/features/hwid-device-limit/).
- Android: Settings → Subscriptions → + / subscription menu → edit.
- Windows: Settings → Subscriptions → add form / EDIT.
- Both forms support paste, Copy and Generate new. Regeneration requires confirmation and takes effect when saved. Cancel retains the stored value. No periodic rotation or cycling exists.

The model metadata is a generic client label. dot. does not collect a physical device model for this feature.

## HTTP contract

| Header | Android | Windows |
| --- | --- | --- |
| `x-hwid` | Exact subscription value | Exact subscription value |
| `x-device-os` | `Android` | `Windows` |
| `x-ver-os` | Android release version | Actual Windows major.minor.build from `RtlGetVersion` |
| `x-device-model` | `dot Android` | `dot Windows` |
| `User-Agent` | Existing `dot/<version> (Android)` | Existing `dot-desktop/<version>` |
| `Accept` | Existing `*/*` | Existing `*/*` |

The subscription request builder receives the subscription or its URL/identity snapshot, never a global selected identity. Initial fetch, retry, manual refresh and changed-source validation use the same builder. Windows scheduled/startup refresh re-reads that subscription under the existing refresh gate. Android main currently has no scheduled subscription refresher; its download API requires an explicit subscription, including from a background coroutine.

Same-origin redirects retain the identity. Scheme, host or port changes strip all four identity headers; the destination can still supply the subscription body. A service requiring HWID on another origin must be configured using that destination's subscription URL. Unrelated headers and the existing User-Agent/Accept remain intact.

The identifier is never attached to GeoIP, map, update, node test or VPN traffic requests. Normal diagnostics do not print it; model debug output redacts it.

## Persistence and migration

Android stores `hwid` in each existing subscription JSON object in `dot.subscriptions`. A legacy load patches only missing/null HWID fields in the original JSON and synchronously commits the migration before exposing subscriptions. URL strings, profile URIs, credentials and other raw fields are retained. Save errors retain the previous in-memory subscription state and keep the editor open; an unreadable/unsaved migration blocks overwriting the original store.

Windows adds an optional `hwid` field for legacy deserialization in `state.json`. `Store::open` validates existing values, fills missing/null values, and commits once through the existing flushed temporary file and atomic replacement before the scheduler starts. It refuses to open if migration cannot be committed. Existing records, URLs, nodes, favorites, sort/selection and credentials remain intact. Subsequent launches reuse the saved value. New imports preserve the identity used before the record exists; failed fetches retain the form's draft identity for retry. URL and identity changes are saved together after a changed source downloads and parses successfully.

Both migrations are restart-safe: interruption leaves the previous or new complete state. Migrated values are persisted before any refresh can use them. Re-importing subscriptions is unnecessary.

## Automated coverage

`tests/fixtures/subscription-hwid.json` is consumed by Kotlin, Rust and frontend tests. It covers exact preservation, minimum/maximum length, forbidden whitespace/control/header injection and non-ASCII input.

Tests cover generation, persistence/reload, two independent subscriptions, custom edit/regeneration, legacy migration, failed writes, unchanged nodes/credentials/URLs, request headers, retries, initial import identity, non-subscription clients, redirect scoping and the actual Windows refresh scheduler with B selected while A refreshes.

```sh
gradle testDebugUnitTest assembleDebug lintDebug
cd desktop
npm ci
npm test
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri -- build --no-bundle
```

The GitHub Actions unified workflow builds Android and Windows from the same source revision, runs both unit suites and the frontend fixture test, and verifies packaged assets. Hysteria2 is not on main at the feature baseline; its separate draft PR is not merged by this change.

At this baseline, `lintDebug` reports the existing `StartActivityAndCollapseDeprecated` error in `DotQuickTileService.kt`. That file is unchanged by this feature. Android unit tests and APK compilation pass independently of that lint result.

## Remaining device checks

On a real Android device and Windows PC:

1. Upgrade a 0.3.0 install containing two subscriptions. Confirm URLs, nodes, credentials and selection remain usable.
2. Open both editors; confirm different HWIDs. Restart and confirm each value is unchanged.
3. Paste the same valid case-sensitive identifier on Android and Windows. Capture the provider's subscription requests and confirm the values are identical.
4. Refresh A while B is selected. On Windows, also enable startup/scheduled refresh and test from the tray. Confirm A/B request headers remain independent.
5. Regenerate A, cancel once, then confirm and save. B must keep its value. Test Copy/paste through each native clipboard.
6. Import an HWID-enabled subscription and refresh it; both requests must use its saved value. A failed fetch must preserve existing nodes and allow retry.
7. Confirm existing VLESS connections and node URL tests still work. Check Hysteria2 after its separate implementation is integrated.
