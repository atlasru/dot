# dot. architecture

Android and Windows share support for **VLESS** and **Hysteria2** (`hy2://`, `hysteria2://`), mixed subscriptions and per-subscription HTTP identity. The platforms have separate native VPN runtimes. The current published version is [v0.4.0](docs/releases/0.4.0.md); pinned Xray/libXray version: **v26.7.28**.

## Android data path

```text
HTTPS subscription URL (+ x-hwid / OS metadata headers)
  -> SubscriptionClient (OkHttp)
  -> SubscriptionDecoder (plaintext / Base64)
  -> VlessUriParser or Hysteria2UriParser
  -> ProxyNode[] (VLESS or HY2)
  -> selection + persistent subscriptions
  -> MainViewModel -> DotVpnService
  -> Android VpnService.Builder (split-tunnel policy + TUN fd)
  -> protocol-specific Xray JSON:
       VLESS: libXray share-link conversion + REALITY / field normalization
       HY2: Hysteria2XrayConfig builds native Xray Hysteria v2 outbound
  -> root env "xray.tun.fd" + TUN inbound
  -> libXray runXrayFromJson -> Xray-core
```

The service owns tunnel lifecycle, socket protection (`VpnService.protect`), protected DNS, connection recovery and traffic sampling. `VpnRuntime` is the source of truth for Home, foreground notification and Quick Settings tile. Android handles Wi-Fi/mobile-network transitions with waiting/reconnecting states and capped retry delays.

## Windows data path

```text
HTTPS subscription URL (+ x-hwid / OS metadata headers)
  -> Rust reqwest subscription client (plaintext / Base64)
  -> VLESS or HY2 parser -> persisted subscription groups
  -> Tauri commands + React UI (nodes, tests, map, settings)
  -> protocol-aware Rust Xray config (TUN or isolated URL-test proxy)
  -> pinned xray.exe + wintun.dll
  -> Windows TUN / routing -> Xray-core
```

The Windows backend validates generated configurations with `xray.exe run -test`, coordinates node switching and URL tests, manages the Xray process tree using a Windows Job Object, and supports optional startup/interval subscription refresh. Android-specific network-aware automatic reconnection is **not** implemented on Windows.

## Subscription identity

Every subscription stores its own random or user-provided identifier. Only requests for that subscription are sent with `x-hwid`, `x-device-os`, `x-ver-os` and `x-device-model`. Redirects to a different origin drop these identity headers. HWID does **not** identify a physical device and is not injected into VPN traffic, node tests or GeoIP lookups. See [subscription identity](docs/SUBSCRIPTION_IDENTITY.md).

## VPN lifecycle

```text
disconnected -> permission / connect -> establish TUN -> start Xray
    -> connected -> disconnect -> stop Xray + close TUN -> disconnected
```

Android also exposes waiting-for-network and reconnecting states; configuration failures are classified separately from transient connectivity failures.

## Invariants

- Parse supported link protocols explicitly; do not treat HY2 links as VLESS.
- Do not log or display subscription URLs, UUIDs, HY2 auth, Salamander passwords or private identity values.
- A failed refresh must not discard the last working subscription nodes or credentials.
- Keep each subscription's identity separate during import, retry and scheduled/manual refresh.
- The native VPN service/backend owns the tunnel and process lifecycle, not the UI.
- Validate pinned Xray configuration format before changing version or outbound generation.
- Read connection state from the runtime, not an optimistic frontend boolean.

See [VPN engine](docs/VPN_ENGINE.md), [Desktop](docs/DESKTOP.md) and [Hysteria2 validation](docs/hysteria2-manual-test.md).

## 0.5.0 candidate additions

Android `core/diagnostics` owns typed diagnostic settings/results, an asynchronous PingEngine and a private-process PingService. One core per service batch avoids libXray global-state conflicts; MainViewModel delegates operation/resource ownership to NodeDiagnostics. `core/settings/SettingsRepository` stores validated new preferences and results without replacing the subscription/theme stores. `RuntimeSettings` applies DNS/LAN/log controls at the next connection. UI categories live in AdvancedSettingsPanel.

Windows `settings.rs` and `diagnostics.rs` define the same methods/defaults and verified-proxy policy. `url_test.rs` owns temporary-process/HTTP resources; Store persists versioned preferences and diagnostic metadata transactionally. A bounded scheduler uses the same connection-operation gate as commands/tray. React AdvancedSettings.tsx is independent of the main view. See [full semantics and limitations](docs/DIAGNOSTICS.md).
