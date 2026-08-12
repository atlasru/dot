# VPN engine

`dot.` uses Android `VpnService` for the system TUN interface and the official XTLS `libXray` Android artifact for Xray-core.

## Core version

CI currently pins `libXray` **v26.7.28** and downloads the Android AAR before Gradle compilation.

## Startup

1. Android grants VPN permission through `VpnService.prepare()`.
2. `DotVpnService` creates the TUN interface.
3. The TUN file descriptor is injected into Xray config root `env` as `xray.tun.fd`.
4. libXray converts the selected `vless://` share link to Xray JSON.
5. `dot.` normalizes fields that are incompatible with current Xray behavior.
6. Xray is started through libXray `runXrayFromJson`.
7. `VpnRuntime` publishes the real connection state to the UI and Quick Settings tile.

## Socket protection

The service registers libXray dialer/listener controllers and forwards file descriptors to `VpnService.protect()`. This prevents Xray's own outbound sockets from being routed back into its VPN TUN.

A protected DNS resolver is also configured before the core starts.

## libXray normalization

The pinned libXray release has a few serialization behaviors that `dot.` corrects at runtime:

- removes outbound `sendThrough` when it contains the display name instead of a local IP
- removes server-only REALITY fields from client outbounds
- restores client REALITY values such as SNI, fingerprint, public key/password and short ID directly from the original VLESS URI when needed

These fixes are intentionally kept close to the libXray integration layer so the rest of the application can continue using normalized `VlessProfile` models.

## Traffic

While connected, the service samples Android UID traffic counters once per second. The calculated upload/download rates are exposed to both the Home UI and foreground VPN notification.

## Shutdown

Disconnect stops the traffic meter, invokes libXray `stopXray`, resets DNS, closes the TUN descriptor, clears runtime state and removes the foreground notification.
