# Changelog

## 0.3.0 — unified Android and Windows

### Windows

- Make the Home Orb the connection control: connect, disconnect and cancel startup.
- Remove the separate Connect/Disconnect button; keep keyboard access and state-aware disabling.
- Unify Desktop node management and interactive map.
- Add subscription editing/deletion, local VLESS imports, search and persistent favorites.
- Add opt-in startup/scheduled subscription refresh that remains active in the tray.
- Preserve data on failed fetches and disk writes; report removed selections.
- Identify the running node by ID and prevent overlapping connection/test operations.
- Synchronize Desktop version manifests and version the portable CI artifact.
- Promote the manually tested Desktop functionality to stable 0.3.0.

### Android


- add one-tap AUTO NODE at the top of the Nodes list to choose the fastest successful URL-tested node and connect immediately
- automatically run the existing group URL test first when AUTO NODE needs latency data and the VPN core is idle
- keep AUTO NODE as a one-shot action rather than a persistent routing mode, so later manual node choices are never overridden
- add network-aware VPN states for waiting-for-network and reconnecting
- reconnect the current VLESS profile after Wi-Fi/mobile-data changes with capped backoff at 1s, 2s, 5s, 10s and 30s
- keep the foreground notification and Quick Settings tile active while the tunnel is waiting or reconnecting
- classify VPN startup failures into DNS, timeout, connection-refused, TLS, REALITY, TUN, config, Xray and unknown categories
- expose reconnect attempt and the latest failure category in the Android UI
- add unit tests for AUTO selection and VPN failure classification

### Distribution

- Build both platforms from one validated source revision; publish only from a matching version tag.
- Publish the honestly named Android dev-signed debug APK and unsigned Windows release-mode portable ZIP with SHA-256 checksums.

## 0.2.1

- replace the full-width Android bottom bar with a raised floating pill navigation inspired by the Happ interaction pattern
- respect Android gesture/button navigation insets so the primary destinations sit above the system navigation area
- highlight the active destination with an animated inner pill and dot. red accent while keeping large tap targets
- remove the duplicate Settings gear from Home and Nodes now that Settings has a persistent primary navigation destination
- keep node/map and settings content comfortably clear of the raised navigation control

## 0.2.0

- add per-app Android split tunneling with `ALL APPS`, `EXCLUDE SELECTED` and `ONLY SELECTED APPS` routing modes
- persist split-tunnel rules independently from subscription groups and apply them at `VpnService.Builder` before TUN establishment
- enumerate launchable apps without requesting Android's broad `QUERY_ALL_PACKAGES` permission
- reject include-only tunnel startup when none of the selected packages are installed instead of silently falling back to all-app routing
- add a dedicated split-tunneling app selector with search, clear action and explicit reconnect flow for live VPN sessions
- redesign Android navigation into focused `HOME`, `NODES` and `SETTINGS` destinations without removing existing functionality
- keep Home centered on the pixel orb, connection state, active node, traffic and connection test
- move subscription switching, refresh, group tests, sorting and LIST/MAP controls into Nodes
- preserve subscription editing, themes, launcher icons, About, node map, URL tests and direct node switching

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
- fall back to country detection from node flags, ISO country codes and common country names when IP geolocation fails
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
