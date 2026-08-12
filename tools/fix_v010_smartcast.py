from pathlib import Path
p = Path('app/src/main/java/dev/dotclient/android/ui/DotApp.kt')
s = p.read_text(encoding='utf-8')
old = '''        Spacer(Modifier.height(8.dp))
        Text(connectionLabel(state), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(3.dp))
        Text(
            text = when {
                state.vpnConnected -> state.runningNodeName ?: state.selectedProfile?.name ?: "connected"
                state.vpnBusy -> state.runningNodeName ?: state.selectedProfile?.name ?: "working…"
                state.selectedProfile != null -> "tap orb to connect · ${state.selectedProfile.name}"
                else -> "select a node below"
            },
'''
new = '''        Spacer(Modifier.height(8.dp))
        Text(connectionLabel(state), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(3.dp))
        val selectedProfile = state.selectedProfile
        Text(
            text = when {
                state.vpnConnected -> state.runningNodeName ?: selectedProfile?.name ?: "connected"
                state.vpnBusy -> state.runningNodeName ?: selectedProfile?.name ?: "working…"
                selectedProfile != null -> "tap orb to connect · ${selectedProfile.name}"
                else -> "select a node below"
            },
'''
if old not in s:
    raise SystemExit('smart-cast target block not found')
p.write_text(s.replace(old, new, 1), encoding='utf-8')
