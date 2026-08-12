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
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotclient.android.BuildConfig
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.VlessProfile
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.ui.theme.DotThemeMode
import kotlinx.coroutines.launch
import kotlin.math.max

private enum class Screen { HOME, NODES, SETTINGS, ABOUT }

@Composable
fun DotApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }

    BackHandler(enabled = screen == Screen.NODES) { screen = Screen.HOME }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        } else {
            viewModel.onVpnPermissionDenied()
        }
    }

    val beginVpnPermissionFlow = {
        if (viewModel.requestVpnPermission()) {
            val permissionIntent = VpnService.prepare(context)
            if (permissionIntent != null) {
                vpnPermissionLauncher.launch(permissionIntent)
            } else {
                viewModel.onVpnPermissionGranted()
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        beginVpnPermissionFlow()
    }

    val requestConnect = {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                state = state,
                onSettings = { screen = Screen.SETTINGS },
                onOpenNodes = { screen = Screen.NODES },
                onConnect = requestConnect,
                modifier = Modifier.padding(padding),
            )

            Screen.NODES -> NodesScreen(
                state = state,
                viewModel = viewModel,
                onSettings = { screen = Screen.SETTINGS },
                onAddSubscription = { screen = Screen.SETTINGS },
                onClose = { screen = Screen.HOME },
                onConnect = {
                    screen = Screen.HOME
                    requestConnect()
                },
                modifier = Modifier.padding(padding),
            )

            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                onBack = { screen = Screen.HOME },
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
private fun HomeScreen(
    state: DotUiState,
    onSettings: () -> Unit,
    onOpenNodes: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DotHeader(
            title = "dot.",
            trailing = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            onSettings = onSettings,
        )

        Spacer(Modifier.weight(0.65f))

        if (state.themeMode == DotThemeMode.AMOLED) {
            PixelStatusMatrix(
                active = state.vpnConnected,
                busy = state.requestingVpnPermission || state.vpnBusy,
            )
        } else {
            Box(
                modifier = Modifier.size(96.dp).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (state.requestingVpnPermission || state.vpnBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(34.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Box(
                        Modifier.size(14.dp).background(
                            if (state.vpnConnected) MaterialTheme.colorScheme.primary else Color(0xFF7A7A7A),
                            CircleShape,
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            text = when {
                state.requestingVpnPermission -> "permission"
                state.vpnState == VpnConnectionState.CONNECTING -> "connecting"
                state.vpnState == VpnConnectionState.CONNECTED -> "connected"
                state.vpnState == VpnConnectionState.DISCONNECTING -> "disconnecting"
                state.vpnState == VpnConnectionState.ERROR -> "error"
                else -> "offline"
            },
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = state.selectedProfile?.name ?: "no node selected",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8A8A8A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(22.dp))
        SelectedNodeControl(
            groupName = state.selectedSubscription?.name ?: "nodes.",
            nodeName = state.selectedProfile?.name,
            onClick = onOpenNodes,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onConnect,
            enabled = !state.loading && !state.requestingVpnPermission && !state.vpnBusy,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = Color(0xFF2A2A2A),
                disabledContentColor = Color(0xFF777777),
            ),
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.vpnConnected) "DISCONNECT" else "CONNECT",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "↓ ${formatRate(state.downloadBytesPerSecond)}",
                color = Color(0xFF868686),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "↑ ${formatRate(state.uploadBytesPerSecond)}",
                color = Color(0xFF868686),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.weight(1f))

        state.message?.let {
            Text(
                text = it,
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PixelStatusMatrix(active: Boolean, busy: Boolean) {
    val pattern = listOf(
        "0011100",
        "0110110",
        "1100011",
        "1000001",
        "1100011",
        "0110110",
        "0011100",
    )
    val on = if (active) MaterialTheme.colorScheme.primary else Color(0xFF666666)
    val off = Color(0xFF151515)

    Column(
        modifier = Modifier
            .size(96.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
            .padding(13.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        pattern.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEachIndexed { colIndex, cell ->
                    val blink = busy && ((rowIndex + colIndex) % 3 == 0)
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(if (cell == '1' || blink) on else off)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedNodeControl(
    groupName: String,
    nodeName: String?,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(groupName, style = MaterialTheme.typography.titleLarge)
            Text("⌄", style = MaterialTheme.typography.titleLarge, color = Color(0xFFB0B0B0))
        }
        if (!nodeName.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                nodeName,
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NodesScreen(
    state: DotUiState,
    viewModel: MainViewModel,
    onSettings: () -> Unit,
    onAddSubscription: () -> Unit,
    onClose: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var groupMenuOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        DotHeader(title = "dot.", onSettings = onSettings)
        Spacer(Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("nodes.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.size(10.dp))
                Text(
                    "×",
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Box {
                Row(
                    modifier = Modifier
                        .background(Color(0xFF171717), RoundedCornerShape(8.dp))
                        .clickable { groupMenuOpen = true }
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.selectedSubscription?.name ?: "group",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("⌄", color = Color(0xFF9A9A9A))
                }

                DropdownMenu(
                    expanded = groupMenuOpen,
                    onDismissRequest = { groupMenuOpen = false },
                    containerColor = Color(0xFF171717),
                ) {
                    state.subscriptions.forEach { subscription ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(subscription.name)
                                    if (subscription.id == state.selectedSubscriptionId) Text("✓")
                                }
                            },
                            onClick = {
                                viewModel.selectSubscription(subscription.id)
                                groupMenuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("+ add group") },
                        onClick = {
                            groupMenuOpen = false
                            onAddSubscription()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val selectedGroup = state.selectedSubscription
        if (selectedGroup == null) {
            EmptyState(
                title = "no groups yet",
                hint = "open settings and add a subscription.",
                action = "ADD SUBSCRIPTION",
                onAction = onAddSubscription,
            )
        } else if (selectedGroup.profiles.isEmpty()) {
            EmptyState(
                title = "no nodes in ${selectedGroup.name}",
                hint = if (state.loadingSubscriptionId == selectedGroup.id) {
                    "updating subscription…"
                } else {
                    "update the subscription in settings."
                },
                action = "OPEN SETTINGS",
                onAction = onSettings,
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(selectedGroup.profiles, key = { it.id }) { profile ->
                    NodeRow(
                        profile = profile,
                        selected = profile.id == selectedGroup.selectedProfileId,
                        onSelect = { viewModel.selectProfile(profile.id) },
                        onConnect = {
                            viewModel.selectProfile(profile.id)
                            onConnect()
                        },
                        onTest = { viewModel.testNode(profile.rawUri) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    profile: VlessProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onTest: suspend () -> Result<Long>,
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }
    var latency by remember(profile.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(if (selected) Color(0xFF141414) else Color.Black)
            .border(
                width = 1.dp,
                color = if (selected) Color(0xFF303030) else Color(0xFF171717),
                shape = RoundedCornerShape(2.dp),
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).border(
                1.dp,
                if (selected) Color.White else Color(0xFF666666),
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).background(Color.White, CircleShape))
        }

        Spacer(Modifier.size(12.dp))

        Column(Modifier.weight(1f)) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                "${profile.host}:${profile.port} · ${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()}",
                color = Color(0xFF707070),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            latency?.let { value ->
                Spacer(Modifier.height(3.dp))
                Text(value, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelMedium)
            }
        }

        Box {
            Text(
                text = "⋮",
                modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color(0xFFB0B0B0),
                style = MaterialTheme.typography.titleLarge,
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Color(0xFF171717),
            ) {
                DropdownMenuItem(
                    text = { Text("select") },
                    onClick = {
                        menuOpen = false
                        onSelect()
                    },
                )
                DropdownMenuItem(
                    text = { Text("connect") },
                    onClick = {
                        menuOpen = false
                        onConnect()
                    },
                )
                DropdownMenuItem(
                    text = { Text("url test · Cloudflare") },
                    onClick = {
                        menuOpen = false
                        latency = "testing http://cp.cloudflare.com/ …"
                        scope.launch {
                            latency = onTest().fold(
                                onSuccess = { "${it} ms · cp.cloudflare.com" },
                                onFailure = { it.message ?: "url test failed" },
                            )
                        }
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            "${profile.security.name.lowercase()} / ${profile.transport.name.lowercase()}",
                            color = Color(0xFF707070),
                        )
                    },
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
    onBack: () -> Unit,
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

    BackHandler {
        if (editorOpen) editorOpen = false else onBack()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        SettingsHeader(onBack = onBack)
        Spacer(Modifier.height(24.dp))

        if (editorOpen) {
            Text(
                if (editingId == null) "add subscription" else "edit subscription",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(18.dp))

            FieldLabel("name")
            DotTextField(
                value = draftName,
                onValueChange = { draftName = it },
                placeholder = "vpn1",
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("url")
            DotTextField(
                value = draftUrl,
                onValueChange = { draftUrl = it },
                placeholder = "https://.../sub/user/...",
                keyboardType = KeyboardType.Uri,
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    viewModel.saveSubscription(editingId, draftName, draftUrl)
                    editorOpen = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("SAVE", style = MaterialTheme.typography.labelLarge)
            }

            TextButton(
                onClick = { editorOpen = false },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("cancel", color = Color(0xFF777777))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("subscriptions", style = MaterialTheme.typography.titleLarge)
                Text(
                    "+",
                    modifier = Modifier.clickable { openNewEditor() }.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.headlineLarge,
                )
            }

            Spacer(Modifier.height(10.dp))

            if (state.subscriptions.isEmpty()) {
                Text("no subscriptions yet", color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("tap + to create vpn1, vpn2, …", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
            } else {
                state.subscriptions.forEach { subscription ->
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

            Spacer(Modifier.height(22.dp))
            Text("appearance", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            ThemePicker(
                selected = state.themeMode,
                onSelect = viewModel::setTheme,
            )
            Spacer(Modifier.height(16.dp))
            LauncherIconPicker()

            Spacer(Modifier.height(22.dp))
            Text("connection", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            SettingLine(
                "status",
                when (state.vpnState) {
                    VpnConnectionState.CONNECTED -> "connected"
                    VpnConnectionState.CONNECTING -> "connecting"
                    VpnConnectionState.DISCONNECTING -> "disconnecting"
                    VpnConnectionState.ERROR -> "error"
                    VpnConnectionState.DISCONNECTED -> "offline"
                },
            )
            SettingLine("protocol", "VLESS")
            SettingLine("traffic", "realtime")

            Spacer(Modifier.height(18.dp))
            Text("advanced", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            SettingLine("core", "libXray")
            SettingLine("type", "monospace / courier-like")

            Spacer(Modifier.height(18.dp))
            Text("about", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAbout)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("dot.", color = Color(0xFFB0B0B0))
                Text("v${BuildConfig.VERSION_NAME.removeSuffix("-debug")}  ›", color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.weight(1f))

            state.message?.let {
                Text(it, color = Color(0xFF777777), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text("about.", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(Modifier.height(42.dp))
        Text("dot.", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "minimal VLESS client for Android",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

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
        Text(
            "built around Xray-core · no accounts · no analytics",
            color = Color(0xFF666666),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "dot. / ${BuildConfig.VERSION_NAME.removeSuffix("-debug")}",
            color = Color(0xFF444444),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AboutValue(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = Color(0xFF8A8A8A))
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AboutLink(name: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
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
        modifier = Modifier
            .fillMaxWidth()
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
            Text(
                redactedUrl,
                color = Color(0xFF666666),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(subscription.profiles.size)
                    append(" nodes")
                    if (subscription.lastUpdatedEpochMs != null) {
                        append(" · ")
                        append(updatedAgo(subscription.lastUpdatedEpochMs))
                    }
                },
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp, color = Color.White)
        } else {
            Text("↻", modifier = Modifier.clickable(onClick = onRefresh).padding(8.dp), color = Color(0xFFB0B0B0))
        }

        Box {
            Text(
                "⋮",
                modifier = Modifier.clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFFB0B0B0),
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Color(0xFF171717),
            ) {
                DropdownMenuItem(
                    text = { Text("edit") },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("update") },
                    onClick = {
                        menuOpen = false
                        onRefresh()
                    },
                )
                DropdownMenuItem(
                    text = { Text("delete", color = Color(0xFFFF3B30)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    hint: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color(0xFF888888), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(hint, color = Color(0xFF555555), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(18.dp))
        Text(
            action,
            modifier = Modifier
                .border(1.dp, Color(0xFF303030), RoundedCornerShape(2.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DotHeader(title: String, trailing: String? = null, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            trailing?.let {
                Text("v$it", style = MaterialTheme.typography.labelMedium, color = Color(0xFF5E5E5E))
                Spacer(Modifier.size(12.dp))
            }
            Text(
                "⚙",
                modifier = Modifier.clickable(onClick = onSettings).padding(6.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            modifier = Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text("settings.", style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF777777),
        modifier = Modifier.padding(bottom = 6.dp),
    )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DotThemeMode.entries.forEach { theme ->
            val active = theme == selected
            Text(
                text = theme.label,
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(4.dp),
                    )
                    .background(
                        if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(4.dp),
                    )
                    .clickable { onSelect(theme) }
                    .padding(vertical = 11.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LauncherIcon.entries.forEach { icon ->
                val active = icon == selected
                Text(
                    text = icon.label,
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .background(
                            if (active) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(4.dp),
                        )
                        .clickable {
                            LauncherIconManager.apply(context, icon)
                            selected = icon
                        }
                        .padding(vertical = 11.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text("launcher may refresh the icon with a short delay", color = Color(0xFF555555), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SettingLine(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = Color(0xFFB0B0B0))
        Text(value, color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
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


private fun formatRate(bytesPerSecond: Long): String {
    val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 -> String.format("%.1f MB/s", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format("%.0f KB/s", value / 1024.0)
        else -> "${value.toLong()} B/s"
    }
}
