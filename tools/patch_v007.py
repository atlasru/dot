from pathlib import Path

p = Path('app/src/main/java/dev/dotclient/android/ui/DotApp.kt')
s = p.read_text()

s = s.replace(
'''import dev.dotclient.android.core.model.VlessProfile\nimport kotlin.math.max''',
'''import dev.dotclient.android.core.model.VlessProfile\nimport dev.dotclient.android.vpn.VpnConnectionState\nimport kotlin.math.max''')

s = s.replace(
'''    val requestConnect = {\n        if (viewModel.requestVpnPermission()) {\n            val permissionIntent = VpnService.prepare(context)\n            if (permissionIntent != null) {\n                vpnPermissionLauncher.launch(permissionIntent)\n            } else {\n                viewModel.onVpnPermissionGranted()\n            }\n        }\n    }''',
'''    val requestConnect = {\n        when (state.vpnState) {\n            VpnConnectionState.CONNECTED,\n            VpnConnectionState.CONNECTING,\n            VpnConnectionState.DISCONNECTING -> viewModel.disconnect()\n\n            VpnConnectionState.DISCONNECTED,\n            VpnConnectionState.ERROR -> {\n                if (viewModel.requestVpnPermission()) {\n                    val permissionIntent = VpnService.prepare(context)\n                    if (permissionIntent != null) {\n                        vpnPermissionLauncher.launch(permissionIntent)\n                    } else {\n                        viewModel.onVpnPermissionGranted()\n                    }\n                }\n            }\n        }\n    }''')

s = s.replace(
'''            if (state.requestingVpnPermission) {''',
'''            if (state.requestingVpnPermission || state.vpnBusy) {''', 1)

s = s.replace(
'''                        if (state.vpnPermissionGranted) Color(0xFF7A7A7A) else Color.White,''',
'''                        if (state.vpnConnected) Color.White else Color(0xFF7A7A7A),''', 1)

s = s.replace(
'''            text = if (state.requestingVpnPermission) "permission" else "offline",''',
'''            text = when {\n                state.requestingVpnPermission -> "permission"\n                state.vpnState == VpnConnectionState.CONNECTING -> "connecting"\n                state.vpnState == VpnConnectionState.CONNECTED -> "connected"\n                state.vpnState == VpnConnectionState.DISCONNECTING -> "disconnecting"\n                state.vpnState == VpnConnectionState.ERROR -> "error"\n                else -> "offline"\n            },''', 1)

s = s.replace(
'''            enabled = !state.loading && !state.requestingVpnPermission,''',
'''            enabled = !state.loading && !state.requestingVpnPermission && !state.vpnBusy,''', 1)

s = s.replace(
'''            Text("CONNECT", style = MaterialTheme.typography.labelLarge)''',
'''            Text(\n                if (state.vpnConnected) "DISCONNECT" else "CONNECT",\n                style = MaterialTheme.typography.labelLarge,\n            )''', 1)

p.write_text(s)

b = Path('app/build.gradle.kts')
g = b.read_text().replace('versionCode = 6', 'versionCode = 7').replace('versionName = "0.0.6"', 'versionName = "0.0.7"')
b.write_text(g)
