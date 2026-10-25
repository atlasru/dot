# Diagnostics and settings (0.5.0 candidate)

The default is **Via Proxy GET**, `https://cp.cloudflare.com/generate_204`, **5 seconds per attempt**, **3 attempts**, **median**, **4 concurrent nodes**. Startup testing and repeat testing are off. Each HTTP attempt opens a fresh client connection. Timing uses `System.nanoTime()` on Android and `Instant` on Windows. Config conversion, core startup, client construction and process startup are outside the timer. GET includes response body transfer (maximum 1 MiB); HEAD ends at response headers. Only HTTP 2xx responses pass; redirects are not followed.

Different methods measure different operations. A TCP or ICMP value can be much lower than an HTTP request involving proxy negotiation and TLS. Results must not be adjusted to match another client's number.

| Method | Measurement | Confirms proxy operation? |
| --- | --- | --- |
| Via Proxy GET | Full HTTP(S) request through the node | Yes, for the tested URL |
| Via Proxy HEAD | HTTP(S) response headers through the node | Yes; 405 reports unsupported HEAD, with no failed-node badge |
| TCP Ping | Connect to a resolved server IP/port; no Xray or HTTP | No; rejected for HY2 |
| ICMP Ping | Actual system ICMP round-trip time | No; can be blocked or unavailable |

Android ICMP uses the system ping/ping6 executable. Windows uses .NET Ping / Windows ICMP APIs in hidden PowerShell and reads the API round-trip value, excluding process startup. ICMP is never replaced by TCP. TCP/ICMP use the system resolver before measurement; resolver completion follows the OS resolver timeout. HTTP timeouts include DNS, proxy negotiation, TLS and body transfer.

Attempts: 1/2/3/5. Timeout: 3/5/10/15 seconds. Aggregation: minimum, average, median (mean of the middle two for an even count), last. Concurrency: 1/2/4/8. All attempts must succeed for a successful aggregate; partial samples and an error remain inspectable, but partial results never verify a proxy. This conservative policy avoids confirming an intermittently failing proxy.

Every result stores method, optional latency, wall-clock timestamp, success, sanitized error, applicable URL, individual samples, settings snapshot and active-tunnel context. The monotonic timer measures duration; wall-clock time labels history. A settings mismatch hides incompatible cached results from the list, sorting and AUTO. Old 0.4.0 latency values do not have enough metadata and are not reused as 0.5.0 measurements.

## Isolation, routing and cancellation

Android diagnostics use a private `:diagnostics` service process. It owns **one temporary libXray core at a time**, with at most the selected number of outbounds/HTTP requests. The process-wide service mutex covers cancellation and service recreation. It does not create a TUN. This avoids libXray's process-global dialer/outbound-manager collision between temporary instances and the VPN core. See the [pinned upstream isolation warning](https://github.com/XTLS/libXray/tree/v26.7.28#api).

Windows uses at most the configured number of isolated Xray processes. Job Objects terminate their process trees; temporary configs are removed on all normal error/cancellation paths. HTTP clients explicitly bypass environment/system proxy settings and use the test node's loopback proxy for both HTTP and HTTPS.

While dot VPN is connected, only GET/HEAD through the **currently active tunnel** are permitted. Other-node, TCP/ICMP and subscription-wide tests require disconnecting first. Android also checks whether dot itself is included in the applied app split tunnel. Testing does not launch a second TUN. Offline tests follow OS routing; third-party VPNs and administrator routing policies can affect that path.

Cancellation on Android cancels HTTP calls, closes sockets and stops the temporary core. Windows cancellation prevents further attempts/batches; an in-flight blocking HTTP/TCP request can finish up to the configured per-attempt timeout later. ICMP is polled and terminated on cancellation. Cancelled tests do not overwrite previous cached results. DNS lookup cancellation is limited by the system resolver.

AUTO selects the lowest successful GET/HEAD aggregate from the selected subscription, no older than 15 minutes, obeying node exclusions and optional maximum latency. It never falls back to an untested/failed/TCP/ICMP-only node. Run GET/HEAD diagnostics if AUTO has no eligible result. Manual selection disables AUTO; enabling AUTO is an explicit user action. No network-switch algorithm silently replaces a manual choice.

## Settings and platform support

Both platforms preserve existing themes, subscription identities, node sorting and selection. New settings are versioned and validated before saving/import. DNS/routing/core logging changes apply after reconnect; diagnostic and appearance changes apply immediately. Failed subscription updates retain the last saved nodes and preserve selection if its node remains present.

| Category | Both platforms | Platform-specific |
| --- | --- | --- |
| General | Connect on app launch after permission/setup | Windows close to tray; Android requires a foreground VPN notification |
| Connection | Existing engine lifecycle/connectivity behavior retained | Android transient reconnect toggle, retry base/cap and reconnect after network recovery |
| Ping & Diagnostics | Four methods, URL, timeout, attempts, aggregation, concurrency, startup/repeat, selected/subscription scope, cancel, reset | Android ICMP depends on executable/permissions; Windows on ICMP API/PowerShell |
| DNS & Routing | Numeric custom DNS resolver, LAN/private-address bypass | Existing Android include/exclude app split tunneling |
| Subscriptions | Startup/interval refresh, timeout, User-Agent, manual refresh all | Android schedules while its ViewModel is alive; Windows while the app is running, including tray |
| Nodes & AUTO | Lowest verified latency, failures excluded, node exclusions, threshold, manual priority | No automatic network handoff selection |
| Appearance | Existing themes, compact nodes, latency and protocol/transport visibility, saved sorting | Existing Android launcher icons |
| Advanced | Core log level (none/error/warning), redacted diagnostic metric export, advanced settings JSON import/export/reset, cache reset | Export uses clipboard on Android and download/file selection on Windows |

OS boot startup, arbitrary Xray JSON overrides, DoH/DoT presets, custom domain/IP exclusion editors, IPv4/IPv6 preference controls, Windows app split tunneling, Windows network-aware reconnection, kill-switch semantics and optional connection-timeout overrides are **not implemented or exposed as working controls**. Existing TUN/IPv6 behavior is retained. These require separate engine/platform validation. Advanced JSON export covers the new settings; existing theme, tray, launcher, group sorting and subscription-identity preferences continue in their original stores. It never exports subscription credentials. Raw core logs are not exported: the diagnostic report intentionally omits node URIs, addresses, URLs, User-Agent values and error text to avoid secrets.

## Upgrade and validation

Android keeps application ID `dev.dotclient.android.debug`, stable development signing, versionCode **500** (0.4.0 hotfix: 401), and re-parses saved subscription URIs. Node IDs are now deterministic so diagnostic cache and AUTO exclusions survive reloads. HY2's effective display transport is UDP, including legacy nodes carrying a TCP field; VLESS transport parsing is unchanged. Windows preserves its state.json and protocol config; HY2 views derive UDP from the protocol rather than the legacy raw transport field.

Unit/local HTTP tests and `xray run -test` validate parsing, config compatibility and diagnostic policy. They do not establish real provider VPN connections. Before publishing, manually verify VLESS TCP/TLS/REALITY and HY2 UDP/QUIC, Android permission/service lifecycle and split tunnel, Windows TUN routes, connect/disconnect/reconnect, Wi-Fi/mobile handoff, and an in-place 0.4.0 upgrade on actual devices. See [manual HY2 checks](hysteria2-manual-test.md).
