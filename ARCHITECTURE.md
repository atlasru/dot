# dot. architecture

## Data path

```text
subscription URL
  -> SubscriptionClient (OkHttp)
  -> SubscriptionDecoder (plaintext/base64 detection)
  -> VlessUriParser
  -> VlessProfile[]
  -> selected profile
  -> XrayConfigBuilder [next milestone]
  -> TunnelEngine
  -> DotVpnService
  -> Android TUN fd
  -> libXray/Xray-core
```

## Invariants

- UI never parses Xray JSON.
- Subscription response format is detected, not assumed.
- VLESS URLs are normalized into our own model before core configuration.
- Credentials are redacted before diagnostics.
- `VpnService.Builder.establish()` must not run unless the packet engine is ready.
- libXray is pinned; upgrades are explicit due to API instability.
- A temporary ping/test Xray instance must not overlap a running instance in the same process; current libXray documents process-wide state that can be affected by overlapping instances.
