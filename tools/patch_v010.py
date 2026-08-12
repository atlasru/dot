from pathlib import Path

root = Path(__file__).resolve().parents[1]

def replace(path: str, old: str, new: str):
    p = root / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")

# version
replace(
    "app/build.gradle.kts",
    'versionCode = 16\n        versionName = "0.0.16"',
    'versionCode = 100\n        versionName = "0.1.0"',
)

# UI state: central latency/runtime-node state for the unified main screen.
p = root / "app/src/main/java/dev/dotclient/android/ui/DotUiState.kt"
text = p.read_text(encoding="utf-8")
text = text.replace(
    '    val sessionUploadBytes: Long = 0L,\n',
    '    val sessionUploadBytes: Long = 0L,\n    val runningNodeName: String? = null,\n    val nodeLatenciesMs: Map<String, Long> = emptyMap(),\n    val testingNodeIds: Set<String> = emptySet(),\n',
)
p.write_text(text, encoding="utf-8")

# ViewModel: expose running node, central URL tests, test-all, and live node switching.
p = root / "app/src/main/java/dev/dotclient/android/ui/MainViewModel.kt"
text = p.read_text(encoding="utf-8")
text = text.replace(
    '                        sessionUploadBytes = runtime.sessionUploadBytes,\n',
    '                        sessionUploadBytes = runtime.sessionUploadBytes,\n                        runningNodeName = runtime.nodeName,\n',
)
text = text.replace(
    '    suspend fun testNode(rawUri: String): Result<Long> = nodeLatencyTester.test(rawUri)\n\n    fun redactedSubscriptionUrl',
    '''    fun testNode(profile: dev.dotclient.android.core.model.VlessProfile) {\n        if (state.value.testingNodeIds.contains(profile.id)) return\n        viewModelScope.launch {\n            mutableState.update { it.copy(testingNodeIds = it.testingNodeIds + profile.id) }\n            nodeLatencyTester.test(profile.rawUri)\n                .onSuccess { latency ->\n                    mutableState.update { current ->\n                        current.copy(\n                            nodeLatenciesMs = current.nodeLatenciesMs + (profile.id to latency),\n                            testingNodeIds = current.testingNodeIds - profile.id,\n                        )\n                    }\n                }\n                .onFailure { error ->\n                    mutableState.update { current ->\n                        current.copy(\n                            testingNodeIds = current.testingNodeIds - profile.id,\n                            message = error.message ?: \"url test failed\",\n                        )\n                    }\n                }\n        }\n    }\n\n    fun testAllNodes() {\n        val profiles = state.value.profiles\n        if (profiles.isEmpty()) return\n        if (state.value.vpnState != VpnConnectionState.DISCONNECTED && state.value.vpnState != VpnConnectionState.ERROR) {\n            mutableState.update { it.copy(message = \"disconnect VPN before url test\") }\n            return\n        }\n        viewModelScope.launch {\n            mutableState.update { it.copy(testingNodeIds = it.testingNodeIds + profiles.map { p -> p.id }) }\n            for (profile in profiles) {\n                nodeLatencyTester.test(profile.rawUri)\n                    .onSuccess { latency ->\n                        mutableState.update { current ->\n                            current.copy(\n                                nodeLatenciesMs = current.nodeLatenciesMs + (profile.id to latency),\n                                testingNodeIds = current.testingNodeIds - profile.id,\n                            )\n                        }\n                    }\n                    .onFailure {\n                        mutableState.update { current -> current.copy(testingNodeIds = current.testingNodeIds - profile.id) }\n                    }\n            }\n            mutableState.update { it.copy(message = \"URL test complete · cp.cloudflare.com\") }\n        }\n    }\n\n    fun switchProfile(id: String) {\n        val group = state.value.selectedSubscription ?: return\n        val profile = group.profiles.firstOrNull { it.id == id } ?: return\n        selectProfile(id)\n        if (!state.value.vpnConnected) return\n\n        mutableState.update {\n            it.copy(\n                vpnState = VpnConnectionState.CONNECTING,\n                message = \"switching to ${profile.name}…\",\n            )\n        }\n        val application = getApplication<Application>()\n        val intent = Intent(application, DotVpnService::class.java)\n            .setAction(DotVpnService.ACTION_CONNECT)\n            .putExtra(DotVpnService.EXTRA_VLESS_URI, profile.rawUri)\n            .putExtra(DotVpnService.EXTRA_NODE_NAME, profile.name)\n        ContextCompat.startForegroundService(application, intent)\n    }\n\n    fun redactedSubscriptionUrl''',
)
p.write_text(text, encoding="utf-8")

# Replace old Home + separate Nodes screen with one Happ-inspired dashboard.
p = root / "app/src/main/java/dev/dotclient/android/ui/DotApp.kt"
text = p.read_text(encoding="utf-8")
text = text.replace(
    'import androidx.compose.foundation.layout.Arrangement\n',
    'import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.PaddingValues\n',
)
text = text.replace(
    'import androidx.compose.foundation.layout.size\n',
    'import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.layout.width\n',
)
start = text.index('private enum class Screen')
end = text.index('@Composable\nprivate fun SettingsScreen')
new_block = r'''private enum class Screen { MAIN, SETTINGS, ABOUT }
private val DotRed = Color(0xFFFF2D2D)

@Composable
fun DotApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.MAIN) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onVpnPermissionGranted()
        else viewModel.onVpnPermissionDenied()
    }

    val beginVpnPermissionFlow = {
        if (viewModel.requestVpnPermission()) {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent != null) vpnPermissionLauncher.launch(permissionIntent)
            else viewModel.onVpnPermissionGranted()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { beginVpnPermissionFlow() }

    val toggleVpn = {
        when (state.vpnState) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.DISCONNECTING -> viewModel.disconnect()

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    beginVpnPermissionFlow()
                }
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        when (screen) {
            Screen.MAIN -> MainDashboard(
                state = state,
                viewModel = viewModel,
                onSettings = { screen = Screen.SETTINGS },
                onToggleVpn = toggleVpn,
                onAddSubscription = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )
            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                onBack = { screen = Screen.MAIN },
                onAbout = { screen = Screen.ABOUT },
                modifier = Modifier.padding(padding),
            )
            Screen.ABOUT -> AboutScreen(
                onBack = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun MainDashboard(
    state: DotUiState,
    viewModel: MainViewModel,
    onSettings: () -> Unit,
    onToggleVpn: () -> Unit,
    onAddSubscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotHeader(
            title = "dot.",
            trailing = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            onSettings = onSettings,
        )

        Spacer(Modifier.height(10.dp))
        PixelOrb(
            state = state,
            onClick = onToggleVpn,
        )

        Spacer(Modifier.height(8.dp))
        Text(connectionLabel(state), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(3.dp))
        Text(
            text = when {
                state.vpnConnected -> state.runningNodeName ?: state.selectedProfile?.name ?: "connected"
                state.vpnBusy -> state.runningNodeName ?: state.selectedProfile?.name ?: "working…"
                state.selectedProfile != null -> "tap orb to connect · ${state.selectedProfile.name}"
                else -> "select a node below"
            },
            color = Color(0xFF777777),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("DOWNLOAD", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
                Text("↓ ${formatRate(state.downloadBytesPerSecond)}", style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(state.sessionDownloadBytes), color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("UPLOAD", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
                Text("↑ ${formatRate(state.uploadBytesPerSecond)}", style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(state.sessionUploadBytes), color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202020)))
        Spacer(Modifier.height(10.dp))

        GroupToolbar(
            state = state,
            viewModel = viewModel,
            onAddSubscription = onAddSubscription,
        )

        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202020)))

        val group = state.selectedSubscription
        when {
            group == null -> {
                EmptyState(
                    title = "no subscriptions",
                    hint = "add a group to load nodes.",
                    action = "ADD SUBSCRIPTION",
                    onAction = onAddSubscription,
                )
            }
            group.profiles.isEmpty() -> {
                EmptyState(
                    title = "no nodes in ${group.name}",
                    hint = if (state.loadingSubscriptionId == group.id) "updating subscription…" else "refresh the group or edit its URL.",
                    action = "OPEN SETTINGS",
                    onAction = onAddSubscription,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(group.profiles, key = { it.id }) { profile ->
                        DashboardNodeRow(
                            profile = profile,
                            selected = profile.id == group.selectedProfileId,
                            running = state.vpnConnected && profile.name == state.runningNodeName,
                            latencyMs = state.nodeLatenciesMs[profile.id],
                            testing = profile.id in state.testingNodeIds,
                            onSelect = {
                                if (state.vpnConnected && profile.name != state.runningNodeName) {
                                    viewModel.switchProfile(profile.id)
                                } else {
                                    viewModel.selectProfile(profile.id)
                                }
                            },
                            onTest = { viewModel.testNode(profile) },
                            onConnect = {
                                if (state.vpnConnected) viewModel.switchProfile(profile.id)
                                else {
                                    viewModel.selectProfile(profile.id)
                                    onToggleVpn()
                                }
                            },
                        )
                    }
                }
            }
        }

        state.message?.takeIf { !it.equals("connected", ignoreCase = true) }?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = Color(0xFF666666),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PixelOrb(state: DotUiState, onClick: () -> Unit) {
    val active = state.vpnConnected
    val busy = state.requestingVpnPermission || state.vpnBusy
    val error = state.vpnState == VpnConnectionState.ERROR
    val pattern = listOf(
        "00011111000",
        "00110001100",
        "01100000110",
        "11001010011",
        "10010101001",
        "10001010001",
        "10010101001",
        "11001010011",
        "01100000110",
        "00110001100",
        "00011111000",
    )

    Box(
        modifier = Modifier
            .size(132.dp)
            .border(
                1.dp,
                if (active) Color(0xFF444444) else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable(enabled = !state.loading && !state.requestingVpnPermission, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            pattern.forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEachIndexed { colIndex, cell ->
                        val busyPixel = busy && (rowIndex + colIndex) % 4 == 0
                        val color = when {
                            error && (rowIndex == colIndex || rowIndex + colIndex == 10) -> DotRed
                            cell == '1' && active -> Color.White
                            cell == '1' && busyPixel -> Color(0xFFBDBDBD)
                            cell == '1' -> Color(0xFF555555)
                            else -> Color.Transparent
                        }
                        Box(Modifier.size(6.dp).background(color))
                    }
                }
            }
        }
        Box(
            Modifier
                .size(if (active) 12.dp else 8.dp)
                .background(
                    when {
                        error -> DotRed
                        active -> DotRed
                        busy -> Color(0xFF8A8A8A)
                        else -> Color(0xFF383838)
                    },
                    CircleShape,
                )
        )
    }
}

@Composable
private fun GroupToolbar(
    state: DotUiState,
    viewModel: MainViewModel,
    onAddSubscription: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val group = state.selectedSubscription
    val canTest = group != null && group.profiles.isNotEmpty() &&
        (state.vpnState == VpnConnectionState.DISCONNECTED || state.vpnState == VpnConnectionState.ERROR) &&
        state.testingNodeIds.isEmpty()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(group?.name ?: "subscriptions", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Text("⌄", color = Color(0xFF777777))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                state.subscriptions.forEach { subscription ->
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(subscription.name)
                                Text("${subscription.profiles.size}", color = Color(0xFF666666))
                            }
                        },
                        onClick = {
                            viewModel.selectSubscription(subscription.id)
                            menuOpen = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("+ add subscription") },
                    onClick = {
                        menuOpen = false
                        onAddSubscription()
                    },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loadingSubscriptionId == group?.id) "··" else "↻",
                modifier = Modifier
                    .clickable(enabled = group != null && state.loadingSubscriptionId == null) {
                        group?.let { viewModel.refreshSubscription(it.id) }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                color = Color(0xFFAAAAAA),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                if (state.testingNodeIds.isNotEmpty()) "TESTING" else "TEST",
                modifier = Modifier
                    .border(1.dp, if (canTest) Color(0xFF383838) else Color(0xFF202020), RoundedCornerShape(2.dp))
                    .clickable(enabled = canTest) { viewModel.testAllNodes() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                color = if (canTest) Color(0xFFBDBDBD) else Color(0xFF444444),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DashboardNodeRow(
    profile: VlessProfile,
    selected: Boolean,
    running: Boolean,
    latencyMs: Long?,
    testing: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onConnect: () -> Unit,
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF111111) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(36.dp)
                .background(if (selected) DotRed else Color(0xFF181818))
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (running) {
                    Spacer(Modifier.width(8.dp))
                    Text("LIVE", color = DotRed, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()} · ${profile.host}:${profile.port}",
                color = Color(0xFF5F5F5F),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                testing -> "··"
                latencyMs != null -> "${latencyMs} ms"
                else -> "--"
            },
            color = when {
                testing -> Color(0xFF777777)
                latencyMs != null && latencyMs > 300 -> Color(0xFF777777)
                latencyMs != null -> Color(0xFFB8B8B8)
                else -> Color(0xFF3F3F3F)
            },
            style = MaterialTheme.typography.labelMedium,
        )
        Box {
            Text(
                "⋮",
                modifier = Modifier.clickable { menuOpen = true }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                color = Color(0xFF777777),
                style = MaterialTheme.typography.titleLarge,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                DropdownMenuItem(
                    text = { Text(if (running) "connected" else "connect") },
                    onClick = {
                        menuOpen = false
                        if (!running) onConnect()
                    },
                    enabled = !running,
                )
                DropdownMenuItem(
                    text = { Text("url test · Cloudflare") },
                    onClick = {
                        menuOpen = false
                        onTest()
                    },
                )
                DropdownMenuItem(
                    text = { Text("select") },
                    onClick = {
                        menuOpen = false
                        onSelect()
                    },
                )
            }
        }
    }
}

private fun connectionLabel(state: DotUiState): String = when {
    state.requestingVpnPermission -> "permission"
    state.vpnState == VpnConnectionState.CONNECTING -> "connecting"
    state.vpnState == VpnConnectionState.CONNECTED -> "connected"
    state.vpnState == VpnConnectionState.DISCONNECTING -> "disconnecting"
    state.vpnState == VpnConnectionState.ERROR -> "error"
    else -> "offline"
}

private fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format("%.2f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.0f KB", value / 1024.0)
        else -> "${value.toLong()} B"
    }
}

'''
text = text[:start] + new_block + text[end:]
p.write_text(text, encoding="utf-8")

# changelog
p = root / "CHANGELOG.md"
text = p.read_text(encoding="utf-8")
entry = '''# Changelog\n\n## 0.1.0\n\n- replace separate Home/Nodes flow with a single Happ-inspired main dashboard\n- add tappable pixel orb as the primary VPN on/off control\n- keep the selected subscription node list always visible below the orb\n- add inline realtime/session traffic to the main dashboard\n- add group selector, refresh and group-wide Cloudflare URL test controls\n- centralize node latency state and show latency directly on every node row\n- allow tapping another node while connected to switch the active Xray profile\n- preserve dot. AMOLED/monospace/pixel identity and red-point accent\n\n'''
if text.startswith('# Changelog\n\n'):
    text = entry + text[len('# Changelog\n\n'):]
else:
    text = entry + text
p.write_text(text, encoding="utf-8")
