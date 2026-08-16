# Changelog

## 0.1.8

- add structured subscription refresh results with added, edited, deleted and no-change states
- add expandable refresh details while keeping sensitive VLESS credentials out of the normal result UI
- classify subscription download/HTTP/DNS/timeout/TLS/content errors and provide a redacted `VIEW RAW ERROR` path
- keep the previous working node list when a subscription refresh fails or returns no supported nodes
- add stable logical node matching so selected nodes and compatible latency results survive subscription refreshes
- add per-subscription `ORIGIN`, `DELAY` and natural `NAME` sorting without changing the provider's stored node order
- run the existing group URL test before activating `DELAY` sorting when latency data is unavailable
- keep the node list stable while the initial delay-sort test is running, then order successful nodes by latency with failed/untested nodes last
- apply the selected sort mode to node lists opened from map country markers and expose failed URL-test state in list/map rows

## 0.1.7

- replace the original rough continent sketch with real low-resolution country GeoJSON geometry
- render country borders and use a Mercator projection for a more realistic world map
- increase the default map zoom and center the initial view more closely on Europe
- keep pinch zoom, pan and double-tap reset while preserving stable marker size
- add a darker ProtonVPN-inspired map backdrop with subtle grid lines and varied country fills
- highlight countries containing subscription nodes and tint the active-node country red
- add country codes to map geometry so node countries can be styled independently
- keep the custom Compose map renderer lightweight and remove the unused MapLibre dependency

## 0.1.6

- add LIST / MAP switch for the selected Android subscription while keeping LIST as the default view
- add IP geolocation for subscription nodes with DNS resolution and a seven-day local cache
- fall back to country detection from emoji flags, ISO country codes and common country names when IP geolocation fails
- group every node in the same country into one map marker so duplicate-country nodes never overlap
- show node count, active-country state, city hints, latency and direct connect/switch actions from the country panel
- use a lightweight monochrome Compose world map that matches the existing dot. AMOLED/pixel visual language and avoids map SDK/tile dependencies

## 0.1.0

- replace separate Home/Nodes flow with a single Happ-inspired main dashboard
- add tappable pixel orb as the primary VPN on/off control
- keep the selected subscription node list always visible below the orb
- add inline realtime/session traffic to the main dashboard
- add group selector, refresh and group-wide Cloudflare URL test controls
- centralize node latency state and show latency directly on every node row
- allow tapping another node while connected to switch the active Xray profile
- preserve dot. AMOLED/monospace/pixel identity and red-point accent

## 0.0.17

- replace recreated alternate launcher vectors with bitmap artwork prepared directly from the supplied red-dot and `dot.` images
- preserve the original icon proportions while only removing the external white canvas for Android launcher masking

## 0.0.16

- launcher icon resource correction after the first alternate-icon implementation

## 0.0.15

- add per-node URL latency test through libXray using `http://cp.cloudflare.com/`
- add selectable Android launcher icons: shield, red pixel dot and `dot.` wordmark
- keep node URL tests disabled while the VPN core is active to avoid libXray process-state collisions

All notable changes to `dot.` are tracked here.

## 0.0.14

- Quick Settings tile long-press now opens `dot.` instead of Android App Info
- keeps tap behavior as VPN connect/disconnect

## 0.0.13

- replaced the temporary Android upload notification icon with a dedicated monochrome `dot.` shield icon

## 0.0.12

- added adaptive launcher icon based on the pixel shield artwork

## 0.0.11

- added Quick Settings tile for VPN connect/disconnect

## 0.0.10

- added About screen

## 0.0.9

- added realtime traffic metering
- added active-node and traffic foreground notification
- refined AMOLED styling
- removed non-functional connection settings from UI

## 0.0.8

- removed redundant bottom navigation
- added proper system Back handling
- added close action to node selector
- added AMOLED, Graphite and Matrix themes
- removed development-style UI copy

## 0.0.7

- connected Home UI to real VPN runtime state
- added working Disconnect action

## 0.0.6

- fixed REALITY client config normalization
- introduced stable development signing key for debug builds

## 0.0.5

- fixed invalid libXray `sendThrough` output

## 0.0.4

- fixed pinned libXray API version and switched to `runXrayFromJson`

## 0.0.3

- integrated libXray/Xray-core
- added real Android TUN pipeline
- added socket protection and protected DNS

## 0.0.2

- added subscription groups and node-group switching
- added real Android VPN permission flow
- moved subscription management into Settings

## 0.0.1

- initial Android/Compose foundation
- subscription fetching and decoding
