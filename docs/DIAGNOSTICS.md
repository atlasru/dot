# Diagnostics and settings (0.5.0 candidate)

The default is **Via Proxy GET**, `https://cp.cloudflare.com/generate_204`, **5 seconds per attempt**, **3 attempts**, **median**, **4 concurrent nodes**. Startup testing and repeat testing are off. Each HTTP attempt opens a fresh client connection. Timing uses `System.nanoTime()` on Android and `Instant` on Windows. Config conversion, core startup, client construction and process startup are outside the timer. GET includes response body transfer (maximum 1 MiB); HEAD ends at response headers. Only HTTP 2xx responses pass; redirects are not followed.

Android diagnostic URLs are HTTPS-only under the existing app-wide network security policy; Windows also accepts plain HTTP. Android validation rejects HTTP URLs with an explicit explanation rather than accepting a setting that its HTTP client cannot use. The app's cleartext policy is not weakened for diagnostics.

Different methods measure different operations. A TCP or ICMP value can be much lower than an HTTP request involving proxy negotiation and TLS. Results must not be adjusted to match another client's number.

| Method | Measurement | Confirms proxy operation? |
| --- | --- | --- |
| Via Proxy GET | Full HTTP(S) request through the node | Yes, for the tested URL |
| Via Proxy HEAD | HTTP(S) response headers through the node | Yes; 405 reports unsupported HEAD, with no failed-node badge |
| TCP Ping | Connect to a resolved server IP/port; no Xray or HTTP | No; rejected for HY2 |
| ICMP Ping | Actual system ICMP round-trip time | No; can be blocked or unavailable |

Android ICMP uses the system ping/ping6 executable on the selected network interface. Windows calls the native IPv4/IPv6 ICMP APIs and reads their round-trip value. The application reports unavailable ICMP support explicitly. TCP/ICMP share a per-attempt deadline with endpoint DNS resolution, and the reported duration measures TCP connect or the ICMP echo. HTTP timeouts include proxy negotiation, TLS and body transfer.

Attempts: 1/2/3/5. Timeout: 3/5/10/15 seconds. Aggregation: minimum, average, median (mean of the middle two for an even count), last. Concurrency: 1/2/4/8. All attempts must succeed for a successful aggregate; partial samples and an error remain inspectable, but partial results never verify a proxy. This conservative policy avoids confirming an intermittently failing proxy.

Every result stores method, optional latency, wall-clock timestamp, success, sanitized error, applicable URL, individual samples, settings snapshot and active-tunnel context. The monotonic timer measures duration; wall-clock time labels history. A settings mismatch hides incompatible cached results from the list, sorting and AUTO. Old 0.4.0 latency values do not have enough metadata and are not reused as 0.5.0 measurements.

On Android, **OK** requires a valid latency. **FAIL** requires evidence of rejected proxy credentials or a configuration or protocol error. **UNAVAILABLE** covers DNS, timeouts, other network failures, and ambiguous errors from the core. Cancelled and untested nodes are separate from these outcomes. Delay Sort orders successful nodes by latency, followed by unavailable, failed, and untested nodes in their original order within each group. The **Show detailed test errors** switch under Ping & Diagnostics is off by default. It reveals the sanitized reason under each affected node.

## Isolation, routing and cancellation

Android diagnostics use a private `:diagnostics` service process. It owns **one temporary libXray core at a time**, with at most the selected number of outbounds/HTTP requests. The process-wide service mutex covers cancellation and service recreation. Diagnostic sockets and endpoint DNS use an underlying network. The separate process isolates libXray's process-global dialer and outbound manager from the VPN core. See the [pinned upstream isolation warning](https://github.com/XTLS/libXray/tree/v26.7.28#api).

Windows uses at most the configured number of isolated Xray processes. Job Objects terminate their process trees and temporary configs are removed when the processes stop. Windows selects an underlying route for the server endpoint outside dot's TUN and binds outbound connections to that interface. Both platforms use a loopback HTTP proxy as the entry point to the chosen node's Xray outbound, with a blocked default route if the rule does not match.

Node diagnostics run independently of connection commands, including when a VPN connection starts during Test All. Third-party VPNs and administrator routing policies can affect access to the underlying network.

On Android, **Test Connection** requires a current VPN session and uses a temporary proxy with that session's Xray outbound, underlying network, and bootstrap DNS. The check takes the same proxy path when app split tunneling includes or excludes dot. It uses the HTTPS **Test URL** setting, including when node diagnostics is set to TCP or ICMP. The result measures the active node's proxy path. Device-level TUN routing needs separate verification.

Cancellation stops new probes, cancels HTTP and TCP operations, and releases temporary cores. Android terminates the ping process when cancelled. A Windows native ICMP call or a blocking system resolver call can retain a bounded worker slot until it returns. Test All shows completed per-node measurements and keeps results already delivered when cancellation begins.

AUTO selects the lowest successful GET/HEAD aggregate from the selected subscription, no older than 15 minutes, obeying node exclusions and optional maximum latency. It never falls back to an untested/failed/TCP/ICMP-only node. Run GET/HEAD diagnostics if AUTO has no eligible result. Manual selection disables AUTO; enabling AUTO is an explicit user action. No network-switch algorithm silently replaces a manual choice.

## Settings and platform support

Both platforms preserve existing themes, subscription identities, node sorting and selection. New settings are versioned and validated before saving/import. DNS/routing/core logging changes apply after reconnect; diagnostic and appearance changes apply immediately. Failed subscription updates retain the last saved nodes and preserve selection if its node remains present.

| Category | Both platforms | Platform-specific |
| --- | --- | --- |
| General | Connect on app launch after permission/setup | Windows close to tray; Android requires a foreground VPN notification |
| Connection | Existing engine lifecycle/connectivity behavior retained | Android transient reconnect toggle, retry base/cap and reconnect after network recovery |
| Ping & Diagnostics | Four methods, URL, timeout, attempts, aggregation, concurrency, startup/repeat, selected/subscription scope, progress, cancel, reset | Android ICMP depends on executable/permissions; Windows uses native IPv4/IPv6 ICMP APIs |
| DNS & Routing | Numeric custom DNS resolver, LAN/private-address bypass | Android DNS is IPv4-only; Windows accepts IPv4/IPv6 DNS. Existing Android include/exclude app split tunneling |
| Subscriptions | Startup/interval refresh, timeout, User-Agent, manual refresh all | Android schedules while its ViewModel is alive; Windows while the app is running, including tray |
| Nodes & AUTO | Lowest verified latency, failures excluded, node exclusions, threshold, manual priority | No automatic network handoff selection |
| Appearance | Existing themes, compact nodes, latency and protocol/transport visibility, saved sorting | Existing Android launcher icons |
| Advanced | Core log level (none/error/warning), redacted diagnostic metric export, advanced settings JSON import/export/reset, cache reset | Export uses clipboard on Android and download/file selection on Windows |

OS boot startup, arbitrary Xray JSON overrides, DoH/DoT presets, custom domain/IP exclusion editors, IPv4/IPv6 preference controls, Windows app split tunneling, Windows network-aware reconnection, kill-switch semantics and optional connection-timeout overrides are **not implemented or exposed as working controls**. Existing TUN/IPv6 behavior is retained. These require separate engine/platform validation. Advanced JSON export covers the new settings; existing theme, tray, launcher, group sorting and subscription-identity preferences continue in their original stores. It never exports subscription credentials. Raw core logs are not exported: the diagnostic report intentionally omits node URIs, addresses, URLs, User-Agent values and error text to avoid secrets.

Android intentionally rejects IPv6 DNS while its tunnel only installs IPv4 routes. Adding a DNS server implicitly allows its IP family in VpnService; without matching IPv6 routes, that could permit traffic through the underlying network. See [Android Builder address-family behavior](https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int)). IPv6 server endpoints and ICMP reachability checks are separate from the tunnel's DNS resolver setting.

## Upgrade and validation

Android keeps application ID `dev.dotclient.android.debug`, stable development signing, versionCode **500** (0.4.0 hotfix: 401), and re-parses saved subscription URIs. Node IDs are now deterministic so diagnostic cache and AUTO exclusions survive reloads. HY2's effective display transport is UDP, including legacy nodes carrying a TCP field; VLESS transport parsing is unchanged. Windows preserves its state.json and protocol config; HY2 views derive UDP from the protocol rather than the legacy raw transport field.

Unit/local HTTP tests and `xray run -test` validate parsing, config compatibility and diagnostic policy. They do not establish real provider VPN connections. Before publishing, manually verify VLESS TCP/TLS/REALITY and HY2 UDP/QUIC, Android permission/service lifecycle and split tunnel, Windows TUN routes, connect/disconnect/reconnect, Wi-Fi/mobile handoff, and an in-place 0.4.0 upgrade on actual devices. See [manual HY2 checks](hysteria2-manual-test.md).
