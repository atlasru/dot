# Changelog

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
- VLESS parsing and node selection
