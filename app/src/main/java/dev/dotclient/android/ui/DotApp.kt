package dev.dotclient.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotclient.android.BuildConfig
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.VlessProfile
import dev.dotclient.android.core.splittunnel.SplitTunnelConfig
import dev.dotclient.android.core.splittunnel.SplitTunnelMode
import dev.dotclient.android.core.splittunnel.SplitTunnelStore
import dev.dotclient.android.ui.theme.DotThemeMode
import dev.dotclient.android.vpn.VpnConnectionState
import kotlin.math.max

private enum class Screen { HOME, NODES, SETTINGS, SPLIT_TUNNEL, ABOUT }
private enum class NodeViewMode { LIST, MAP }
private val DotRed = Color(0xFFFF2D2D)

@Composable
fun DotApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val splitTunnelStore = remember(context.applicationContext) { SplitTunnelStore(context.applicationContext) }
    var splitTunnelConfig by remember { mutableStateOf(splitTunnelStore.load()) }
    var screen by remember { mutableStateOf(Screen.HOME) }

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
            VpnConnectionState.WAITING_FOR_NETWORK,
            VpnConnectionState.RECONNECTING,
            VpnConnectionState.DISCONNECTING -> viewModel.disconnect()

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    beginVpnPermissionFlow()
                }
            }
        }
    }

    val updateSplitTunnel: (SplitTunnelConfig) -> Unit = { updated ->
        splitTunnelConfig = updated
        splitTunnelStore.save(updated)
    }

    val topLevel = screen == Screen.HOME || screen == Screen.NODES || screen == Screen.SETTINGS

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (topLevel) {
                BottomNavigation(screen) { screen = it }
            }
        },
    ) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                splitTunnelConfig = splitTunnelConfig,
                onToggleVpn = toggleVpn,
                onOpenNodes = { screen = Screen.NODES },
                onOpenSettings = { screen = Screen.SETTINGS },
                onTestConnection = viewModel::testConnection,
                modifier = Modifier.padding(padding),
            )

            Screen.NODES -> NodesScreen(
                state = state,
                viewModel = viewModel,
                onToggleVpn = toggleVpn,
                onOpenSettings = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )

            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                splitTunnelConfig = splitTunnelConfig,
                onSplitTunnel = { screen = Screen.SPLIT_TUNNEL },
                onAbout = { screen = Screen.ABOUT },
                modifier = Modifier.padding(padding),
            )

            Screen.SPLIT_TUNNEL -> SplitTunnelScreen(
                config = splitTunnelConfig,
                vpnConnected = state.vpnConnected,
                onChange = updateSplitTunnel,
                onReconnect = viewModel::reconnectCurrentProfile,
                onBack = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )

            Screen.ABOUT -> AboutScreen(
                onBack = { screen = Screen.SETTINGS },
                modifier = Modifier.padding(padding),
            )
        }
    }

    state.subscriptionUpdateResult?.let { result ->
        SubscriptionUpdateResultDialog(result, viewModel::dismissSubscriptionUpdateResult)
    }
}

@Composable
private fun BottomNavigation(selected: Screen, onSelect: (Screen) -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(30.dp))
                .border(1.dp, Color(0xFF292929), RoundedCornerShape(30.dp))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("HOME", selected == Screen.HOME, Modifier.weight(1f)) { onSelect(Screen.HOME) }
            NavItem("NODES", selected == Screen.NODES, Modifier.weight(1f)) { onSelect(Screen.NODES) }
            NavItem("SETTINGS", selected == Screen.SETTINGS, Modifier.weight(1f)) { onSelect(Screen.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (active) Color(0xFF202020) else Color.Transparent,
        label = "dot-nav-background",
    )
    val border by animateColorAsState(
        targetValue = if (active) Color(0xFF3B3B3B) else Color.Transparent,
        label = "dot-nav-border",
    )
    val textColor by animateColorAsState(
        targetValue = if (active) Color.White else Color(0xFF666666),
        label = "dot-nav-text",
    )

    Box(
        modifier.background(background, RoundedCornerShape(24.dp))
            .border(1.dp, border, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (active) {
                Box(Modifier.size(5.dp).background(DotRed, CircleShape))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, color = textColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HomeScreen(
    state: DotUiState,
    splitTunnelConfig: SplitTunnelConfig,
    onToggleVpn: () -> Unit,
    onOpenNodes: () -> Unit,
    onOpenSettings: () -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedProfile = state.selectedProfile

    Column(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotHeader("dot.", BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        Spacer(Modifier.height(24.dp))

        PixelOrb(state, onToggleVpn)
        Spacer(Modifier.height(12.dp))
        Text(connectionLabel(state), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                if (state.autoNodeEnabled) append("⚡ AUTO · ")
                append(
                    when {
                        state.vpnConnected -> state.runningNodeName ?: selectedProfile?.name ?: "connected"
                        state.vpnBusy -> state.runningNodeName ?: selectedProfile?.name ?: "working…"
                        selectedProfile != null -> selectedProfile.name
                        else -> "select a node"
                    },
                )
            },
            color = Color(0xFF777777),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (splitTunnelConfig.isActive) {
            Spacer(Modifier.height(7.dp))
            Text(
                splitTunnelSummary(splitTunnelConfig),
                Modifier.clickable(onClick = onOpenSettings).padding(vertical = 3.dp),
                color = Color(0xFF666666),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(22.dp))
        TrafficSummary(state)
        Spacer(Modifier.height(18.dp))

        ActiveNodeCard(
            profile = selectedProfile,
            runningName = state.runningNodeName,
            latencyMs = selectedProfile?.let { state.nodeLatenciesMs[it.id] },
            onClick = onOpenNodes,
        )

        if (state.vpnConnected) {
            Spacer(Modifier.height(12.dp))
            ConnectionTestButton(state, onTestConnection)
        }

        Spacer(Modifier.weight(1f))
        state.message?.takeIf { !it.equals("connected", true) }?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                color = if (state.vpnState == VpnConnectionState.ERROR) DotRed else Color(0xFF666666),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TrafficSummary(state: DotUiState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("DOWNLOAD", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
            Text("↓ ${formatRate(state.downloadBytesPerSecond)}", style = MaterialTheme.typography.bodyLarge)
            Text(formatBytes(state.sessionDownloadBytes), color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("UPLOAD", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
            Text("↑ ${formatRate(state.uploadBytesPerSecond)}", style = MaterialTheme.typography.bodyLarge)
            Text(formatBytes(state.sessionUploadBytes), color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ActiveNodeCard(
    profile: VlessProfile?,
    runningName: String?,
    latencyMs: Long?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFF101010), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF242424), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(38.dp).background(if (runningName != null) DotRed else Color(0xFF333333)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile?.name ?: "choose a node", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                profile?.let { "${it.security.name.lowercase()} · ${it.transport.name.lowercase()}" } ?: "open nodes to select a server",
                color = Color(0xFF606060),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Text(
            latencyMs?.let { "$it ms" } ?: "›",
            color = Color(0xFF777777),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NodesScreen(
    state: DotUiState,
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nodeViewMode by remember(state.selectedSubscriptionId) { mutableStateOf(NodeViewMode.LIST) }
    val group = state.selectedSubscription

    Column(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        DotHeader("nodes.", BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        Spacer(Modifier.height(14.dp))

        GroupToolbar(state, viewModel)
        Spacer(Modifier.height(8.dp))
        Divider()

        if (group != null && group.profiles.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            NodeViewSwitcher(
                state = state,
                selected = nodeViewMode,
                onViewSelect = { nodeViewMode = it },
                onSortSelect = viewModel::setNodeSortMode,
            )
        }

        when {
            group == null -> EmptyState(
                "no subscriptions",
                "add a subscription from settings.",
                "OPEN SETTINGS",
                onOpenSettings,
            )

            group.profiles.isEmpty() -> EmptyState(
                "no nodes in ${group.name}",
                if (state.loadingSubscriptionId == group.id) "updating subscription…" else "refresh the group or edit its URL.",
                "OPEN SETTINGS",
                onOpenSettings,
            )

            nodeViewMode == NodeViewMode.MAP -> NodeMapPanel(
                state = state,
                viewModel = viewModel,
                onToggleVpn = onToggleVpn,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp, bottom = 8.dp),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 7.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.sortedProfiles, key = { it.id }) { profile ->
                    NodeRow(
                        profile = profile,
                        selected = profile.id == group.selectedProfileId,
                        running = state.vpnConnected && profile.name == state.runningNodeName,
                        latencyMs = state.nodeLatenciesMs[profile.id],
                        testing = profile.id in state.testingNodeIds,
                        failed = profile.id in state.nodeLatencyFailedIds,
                        onSelect = {
                            if (state.vpnConnected && profile.name != state.runningNodeName) viewModel.switchProfile(profile.id)
                            else viewModel.selectProfile(profile.id)
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
}

@Composable
private fun GroupToolbar(state: DotUiState, viewModel: MainViewModel) {
    var groupMenuOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    val group = state.selectedSubscription
    val canTest = group != null && group.profiles.isNotEmpty() && state.testingNodeIds.isEmpty()
    val canRefresh = group != null && state.loadingSubscriptionId == null && state.testingNodeIds.isEmpty()

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier.clickable { groupMenuOpen = true }.padding(vertical = 8.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(group?.name ?: "subscriptions", style = MaterialTheme.typography.titleLarge)
                    group?.let {
                        Text("${it.profiles.size} nodes", color = Color(0xFF5F5F5F), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("⌄", color = Color(0xFF777777))
            }
            DropdownMenu(groupMenuOpen, { groupMenuOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
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
                            groupMenuOpen = false
                        },
                    )
                }
            }
        }

        Box {
            Text(
                "⋮",
                Modifier.clickable(enabled = group != null) { actionsOpen = true }.padding(horizontal = 12.dp, vertical = 5.dp),
                color = if (group != null) Color(0xFFAAAAAA) else Color(0xFF444444),
                style = MaterialTheme.typography.headlineLarge,
            )
            DropdownMenu(actionsOpen, { actionsOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(
                    text = { Text(if (state.loadingSubscriptionId == group?.id) "updating…" else "refresh") },
                    enabled = canRefresh,
                    onClick = {
                        actionsOpen = false
                        group?.let { viewModel.refreshSubscription(it.id) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (state.testingNodeIds.isNotEmpty()) "testing…" else "test all nodes") },
                    enabled = canTest,
                    onClick = {
                        actionsOpen = false
                        viewModel.testAllNodes()
                    },
                )
            }
        }
    }
}

@Composable
private fun NodeViewSwitcher(
    state: DotUiState,
    selected: NodeViewMode,
    onViewSelect: (NodeViewMode) -> Unit,
    onSortSelect: (NodeSortMode) -> Unit,
) {
    var sortMenuOpen by remember(state.selectedSubscriptionId) { mutableStateOf(false) }
    val delayTesting = state.pendingDelaySortSubscriptionId == state.selectedSubscriptionId

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Text(
                if (delayTesting) "SORT: TESTING" else "SORT: ${state.selectedSortMode.name}",
                Modifier.border(1.dp, Color(0xFF252525), RoundedCornerShape(2.dp))
                    .clickable(enabled = !delayTesting) { sortMenuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
            )
            DropdownMenu(sortMenuOpen, { sortMenuOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
                NodeSortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.name) },
                        onClick = {
                            sortMenuOpen = false
                            onSortSelect(mode)
                        },
                    )
                }
            }
        }

        Row {
            NodeViewMode.entries.forEach { mode ->
                val active = mode == selected
                Text(
                    mode.name,
                    Modifier.border(1.dp, if (active) Color(0xFF505050) else Color(0xFF252525), RoundedCornerShape(2.dp))
                        .background(if (active) Color(0xFF141414) else Color.Transparent)
                        .clickable { onViewSelect(mode) }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    color = if (active) Color(0xFFD0D0D0) else Color(0xFF626262),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (mode != NodeViewMode.entries.last()) Spacer(Modifier.width(5.dp))
            }
        }
    }
}

@Composable
private fun NodeRow(
    profile: VlessProfile,
    selected: Boolean,
    running: Boolean,
    latencyMs: Long?,
    testing: Boolean,
    failed: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onConnect: () -> Unit,
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF111111) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(36.dp).background(if (selected) DotRed else Color(0xFF181818)))
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
                "${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()}",
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
                latencyMs != null -> "$latencyMs ms"
                failed -> "FAIL"
                else -> "--"
            },
            color = when {
                failed -> DotRed
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
                Modifier.clickable { menuOpen = true }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                color = Color(0xFF777777),
                style = MaterialTheme.typography.titleLarge,
            )
            DropdownMenu(menuOpen, { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surface) {
                DropdownMenuItem(
                    text = { Text(if (running) "connected" else "connect") },
                    onClick = { menuOpen = false; if (!running) onConnect() },
                    enabled = !running,
                )
                DropdownMenuItem(
                    text = { Text("test latency") },
                    onClick = { menuOpen = false; onTest() },
                )
                DropdownMenuItem(
                    text = { Text("select") },
                    onClick = { menuOpen = false; onSelect() },
                )
                DropdownMenuItem(
                    text = { Text("${profile.host}:${profile.port}", color = Color(0xFF777777)) },
                    onClick = { menuOpen = false },
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: DotUiState,
    viewModel: MainViewModel,
    splitTunnelConfig: SplitTunnelConfig,
    onSplitTunnel: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var draftUrl by remember { mutableStateOf("") }

    fun openNewEditor() {
        editingId = null
        draftName = "vpn${state.subscriptions.size + 1}"
        draftUrl = ""
        editorOpen = true
    }

    fun openEditEditor(subscription: Subscription) {
        editingId = subscription.id
        draftName = subscription.name
        draftUrl = subscription.url
        editorOpen = true
    }

    BackHandler(enabled = editorOpen) { editorOpen = false }

    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Text("settings.", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(22.dp))
        }

        if (editorOpen) {
            item {
                Text(if (editingId == null) "add subscription" else "edit subscription", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                FieldLabel("name")
                DotTextField(draftName, { draftName = it }, "vpn1")
                Spacer(Modifier.height(14.dp))
                FieldLabel("url")
                DotTextField(draftUrl, { draftUrl = it }, "https://.../sub/user/...", KeyboardType.Uri)
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        viewModel.saveSubscription(editingId, draftName, draftUrl)
                        editorOpen = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("SAVE", style = MaterialTheme.typography.labelLarge) }
                TextButton(
                    onClick = { editorOpen = false },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("cancel", color = Color(0xFF777777)) }
            }
        } else {
            item {
                SettingsSectionTitle("VPN")
                SettingsToggle(
                    name = "auto node",
                    description = "pick the fastest tested node",
                    enabled = state.autoNodeEnabled,
                    onToggle = viewModel::setAutoNodeEnabled,
                )
                SettingsLink(
                    "split tunneling",
                    splitTunnelSummary(splitTunnelConfig),
                    onSplitTunnel,
                )
                SettingLine("status", connectionLabel(state))
                state.vpnFailureCategory?.let { SettingLine("last error", it.label) }
                if (state.reconnectAttempt > 0) SettingLine("reconnect", "attempt ${state.reconnectAttempt}")
                Spacer(Modifier.height(20.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SettingsSectionTitle("SUBSCRIPTIONS")
                    Text(
                        "+",
                        Modifier.clickable { openNewEditor() }.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.headlineLarge,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            if (state.subscriptions.isEmpty()) {
                item {
                    Text("no subscriptions yet", color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("tap + to add a subscription", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
                }
            } else {
                items(state.subscriptions, key = { it.id }) { subscription ->
                    SubscriptionRow(
                        subscription = subscription,
                        selected = subscription.id == state.selectedSubscriptionId,
                        loading = subscription.id == state.loadingSubscriptionId,
                        redactedUrl = viewModel.redactedSubscriptionUrl(subscription.url),
                        onSelect = { viewModel.selectSubscription(subscription.id) },
                        onRefresh = { viewModel.refreshSubscription(subscription.id) },
                        onEdit = { openEditEditor(subscription) },
                        onDelete = { viewModel.deleteSubscription(subscription.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                SettingsSectionTitle("APPEARANCE")
                Spacer(Modifier.height(8.dp))
                ThemePicker(state.themeMode, viewModel::setTheme)
                Spacer(Modifier.height(16.dp))
                LauncherIconPicker()

                Spacer(Modifier.height(22.dp))
                SettingsSectionTitle("APP")
                SettingsLink("about", "v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", onAbout)

                state.message?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, color = Color(0xFF5F5F5F), style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SettingsLink(name: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color(0xFFB8B8B8))
        Text("$value  ›", color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsToggle(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(!enabled) }.padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color(0xFFB8B8B8))
            Spacer(Modifier.height(3.dp))
            Text(description, color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            if (enabled) "ON" else "OFF",
            color = if (enabled) Color.White else Color(0xFF666666),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text("about.", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(42.dp))
        Text("dot.", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text("minimal VLESS client for Android", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(34.dp))
        AboutValue("version", BuildConfig.VERSION_NAME.removeSuffix("-debug"))
        AboutValue("core", "libXray v26.7.28")
        AboutValue("protocol", "VLESS / REALITY")
        AboutValue("android", "API 26+")
        Spacer(Modifier.height(28.dp))
        Text("project", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        AboutLink("github", "atlasru/dot") { openUrl("https://github.com/atlasru/dot") }
        Spacer(Modifier.weight(1f))
        Text("built around Xray-core · no accounts · no analytics", color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        Text("dot. / ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}", color = Color(0xFF444444), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun AboutValue(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = Color(0xFF8A8A8A))
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutLink(name: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Color(0xFF8A8A8A))
        Text("$value  ↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    selected: Boolean,
    loading: Boolean,
    redactedUrl: String,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(subscription.id) { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF171717) else Color(0xFF101010), RoundedCornerShape(8.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subscription.name, style = MaterialTheme.typography.bodyLarge)
                if (selected) {
                    Spacer(Modifier.size(8.dp))
                    Text("active", color = Color(0xFF777777), style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(redactedUrl, color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(subscription.profiles.size)
                    append(" nodes")
                    subscription.lastUpdatedEpochMs?.let {
                        append(" · ")
                        append(updatedAgo(it))
                    }
                },
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp, color = Color.White)
        } else {
            Text("↻", Modifier.clickable(onClick = onRefresh).padding(8.dp), color = Color(0xFFB0B0B0))
        }
        Box {
            Text(
                "⋮",
                Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFB0B0B0),
            )
            DropdownMenu(menuOpen, { menuOpen = false }, containerColor = Color(0xFF171717)) {
                DropdownMenuItem(text = { Text("edit") }, onClick = { menuOpen = false; onEdit() })
                DropdownMenuItem(text = { Text("update") }, onClick = { menuOpen = false; onRefresh() })
                DropdownMenuItem(
                    text = { Text("delete", color = Color(0xFFFF3B30)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ConnectionTestButton(state: DotUiState, onClick: () -> Unit) {
    val result = when {
        state.connectionTestRunning -> "TESTING…"
        state.connectionTestLatencyMs != null -> "${state.connectionTestLatencyMs} ms"
        state.connectionTestError != null -> "FAILED"
        else -> null
    }

    Row(
        Modifier.border(1.dp, Color(0xFF303030), RoundedCornerShape(2.dp))
            .clickable(enabled = !state.connectionTestRunning, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TEST CONNECTION", color = Color(0xFFBDBDBD), style = MaterialTheme.typography.labelMedium)
        result?.let {
            Spacer(Modifier.width(9.dp))
            Text(
                it,
                color = if (state.connectionTestError != null) DotRed else Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
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
        "00011111000", "00110001100", "01100000110", "11001010011", "10010101001",
        "10001010001", "10010101001", "11001010011", "01100000110", "00110001100", "00011111000",
    )

    Box(
        Modifier.size(142.dp)
            .border(1.dp, if (active) Color(0xFF444444) else MaterialTheme.colorScheme.outline, CircleShape)
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
            Modifier.size(if (active) 12.dp else 8.dp).background(
                when {
                    error || active -> DotRed
                    busy -> Color(0xFF8A8A8A)
                    else -> Color(0xFF383838)
                },
                CircleShape,
            ),
        )
    }
}

@Composable
private fun EmptyState(title: String, hint: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color(0xFF888888), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Color(0xFF555555), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text(
            action,
            Modifier.border(1.dp, Color(0xFF303030), RoundedCornerShape(2.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DotHeader(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        trailing?.let {
            Text("v$it", style = MaterialTheme.typography.labelMedium, color = Color(0xFF5E5E5E))
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Color(0xFF777777), modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun DotTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color(0xFF333333),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
        ),
        shape = RoundedCornerShape(2.dp),
    )
}

@Composable
private fun ThemePicker(selected: DotThemeMode, onSelect: (DotThemeMode) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DotThemeMode.entries.forEach { theme ->
            val active = theme == selected
            Text(
                theme.label,
                Modifier.weight(1f)
                    .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                    .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .clickable { onSelect(theme) }
                    .padding(vertical = 11.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LauncherIconPicker() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(LauncherIconManager.current(context)) }

    Column {
        Text("app icon", color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherIcon.entries.forEach { icon ->
                val active = icon == selected
                Text(
                    icon.label,
                    Modifier.weight(1f)
                        .border(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .background(if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                        .clickable {
                            LauncherIconManager.apply(context, icon)
                            selected = icon
                        }
                        .padding(vertical = 11.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("launcher may refresh the icon with a short delay", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingLine(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, color = Color(0xFFB0B0B0))
        Text(value, color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202020)))
}

private fun connectionLabel(state: DotUiState): String = when {
    state.requestingVpnPermission -> "permission"
    state.vpnState == VpnConnectionState.CONNECTING -> "connecting"
    state.vpnState == VpnConnectionState.CONNECTED -> "connected"
    state.vpnState == VpnConnectionState.WAITING_FOR_NETWORK -> "waiting for network"
    state.vpnState == VpnConnectionState.RECONNECTING -> "reconnecting"
    state.vpnState == VpnConnectionState.DISCONNECTING -> "disconnecting"
    state.vpnState == VpnConnectionState.ERROR -> "error"
    else -> "offline"
}

private fun splitTunnelSummary(config: SplitTunnelConfig): String = when (config.mode) {
    SplitTunnelMode.ALL_APPS -> "all apps"
    SplitTunnelMode.EXCLUDE_SELECTED -> "${config.normalizedPackages.size} apps bypass VPN"
    SplitTunnelMode.INCLUDE_ONLY_SELECTED -> "VPN for ${config.normalizedPackages.size} apps"
}

private fun updatedAgo(epochMs: Long): String {
    val deltaSeconds = max(0L, (System.currentTimeMillis() - epochMs) / 1000L)
    return when {
        deltaSeconds < 60L -> "updated now"
        deltaSeconds < 3600L -> "updated ${deltaSeconds / 60L}m ago"
        deltaSeconds < 86400L -> "updated ${deltaSeconds / 3600L}h ago"
        else -> "updated ${deltaSeconds / 86400L}d ago"
    }
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

private fun formatRate(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB/s", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.0f KB/s", value / 1024.0)
        else -> "${value.toLong()} B/s"
    }
}
