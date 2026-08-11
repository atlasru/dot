# VPN engine

`dot.` uses Android `VpnService` for the system TUN interface and the official XTLS `libXray` Android artifact for Xray-core.

The CI pins `libXray` v26.7.28 and downloads its Android AAR before Gradle compilation. At runtime the selected VLESS share link is converted by libXray itself, then a TUN inbound and the Android-provided `xray.tun.fd` are injected before `testXray` and `runXray`.

The service registers libXray's dialer/listener controller with `VpnService.protect()` and sets a protected DNS resolver before starting the core. This is required to avoid routing Xray's own outbound sockets back into the VPN.
