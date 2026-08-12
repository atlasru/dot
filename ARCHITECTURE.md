# dot. architecture

## Runtime data path

```text
subscription URL
  -> SubscriptionClient (OkHttp)
  -> SubscriptionDecoder (plaintext/base64 detection)
  -> VlessUriParser
  -> VlessProfile[]
  -> selected profile
  -> DotVpnService
  -> Android VpnService.Builder
  -> TUN file descriptor
  -> libXray share-link conversion
  -> dot. config normalization
  -> runXrayFromJson
  -> Xray-core
```

## Application layers

```text
Compose UI
  -> MainViewModel / persisted app state
  -> subscription + node models
  -> VPN runtime state
  -> DotVpnService
  -> libXray / Xray-core
```

The UI does not own the tunnel. `DotVpnService` owns the Android VPN lifecycle and updates `VpnRuntime`, which is observed by the Home screen, notification and Quick Settings tile.

## VPN lifecycle

```text
offline
  -> VpnService.prepare()
  -> connecting
  -> establish TUN
  -> protect core sockets
  -> start libXray
  -> connected
  -> disconnecting
  -> stop Xray + close TUN
  -> offline
```

## Configuration notes

`dot.` currently uses libXray v26.7.28. Share links are converted by libXray and then normalized before being passed to Xray-core. This normalization removes values that the pinned libXray release serializes incorrectly for current Xray, including the outbound `sendThrough` display-name field and server-only REALITY fields.

The Android-provided TUN fd is injected through root `env` as `xray.tun.fd`.

## Invariants

- subscription response format is detected rather than assumed
- credentials must not be included in user-facing diagnostics
- the VPN service owns TUN and Xray lifecycle
- Xray outbound sockets are protected with `VpnService.protect()`
- libXray is pinned and upgraded deliberately because its invocation API and generated configuration may change
- UI connection state comes from the real VPN runtime, not a separate local boolean
