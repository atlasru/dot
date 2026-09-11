package dev.dotclient.android.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dotclient.android.core.splittunnel.InstalledAppRepository
import dev.dotclient.android.core.splittunnel.SplitTunnelConfig
import dev.dotclient.android.core.splittunnel.SplitTunnelMode

private val SplitRed = Color(0xFFFF2D2D)

@Composable
fun SplitTunnelScreen(
    config: SplitTunnelConfig,
    vpnConnected: Boolean,
    onChange: (SplitTunnelConfig) -> Unit,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val apps = remember(context.applicationContext) {
        InstalledAppRepository(context.applicationContext).loadLaunchableApps()
    }
    var appliedConfig by remember { mutableStateOf(config) }
    var query by remember { mutableStateOf("") }

    BackHandler(onBack = onBack)

    LaunchedEffect(vpnConnected) {
        if (vpnConnected) appliedConfig = config
    }

    val filteredApps = remember(apps, query) {
        val needle = query.trim()
        if (needle.isEmpty()) apps
        else apps.filter {
            it.label.contains(needle, ignoreCase = true) ||
                it.packageName.contains(needle, ignoreCase = true)
        }
    }
    val changedWhileConnected = vpnConnected && config != appliedConfig

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                Modifier.clickable(onClick = onBack).padding(end = 14.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineLarge,
            )
            Text("split tunneling.", style = MaterialTheme.typography.headlineLarge)
        }

        Spacer(Modifier.height(20.dp))
        Text("routing", color = Color(0xFF777777), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        SplitModeRow(
            title = "all apps",
            description = "route every app through dot.",
            selected = config.mode == SplitTunnelMode.ALL_APPS,
        ) { onChange(config.copy(mode = SplitTunnelMode.ALL_APPS)) }
        SplitModeRow(
            title = "exclude selected apps",
            description = "selected apps use the normal connection",
            selected = config.mode == SplitTunnelMode.EXCLUDE_SELECTED,
        ) { onChange(config.copy(mode = SplitTunnelMode.EXCLUDE_SELECTED)) }
        SplitModeRow(
            title = "only selected apps",
            description = "only selected apps use the VPN",
            selected = config.mode == SplitTunnelMode.INCLUDE_ONLY_SELECTED,
        ) { onChange(config.copy(mode = SplitTunnelMode.INCLUDE_ONLY_SELECTED)) }

        if (config.mode != SplitTunnelMode.ALL_APPS) {
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("apps", style = MaterialTheme.typography.titleLarge)
                    Text(
                        splitSummary(config),
                        color = Color(0xFF666666),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (config.packages.isNotEmpty()) {
                    Text(
                        "CLEAR",
                        Modifier.clickable { onChange(config.copy(packages = emptySet())) }
                            .border(1.dp, Color(0xFF303030), RoundedCornerShape(2.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        color = Color(0xFFAAAAAA),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("search apps") },
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF555555),
                    unfocusedBorderColor = Color(0xFF292929),
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = RoundedCornerShape(3.dp),
            )

            if (config.mode == SplitTunnelMode.INCLUDE_ONLY_SELECTED && config.packages.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "select at least one app before connecting",
                    color = SplitRed,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val selected = app.packageName in config.packages
                    AppRuleRow(
                        label = app.label,
                        packageName = app.packageName,
                        selected = selected,
                    ) {
                        val packages = if (selected) config.packages - app.packageName
                        else config.packages + app.packageName
                        onChange(config.copy(packages = packages))
                    }
                }
            }
        } else {
            Spacer(Modifier.height(18.dp))
            Text(
                "All installed apps are routed through the active VPN tunnel.",
                color = Color(0xFF666666),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.weight(1f))
        }

        if (changedWhileConnected) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Color(0xFF121212), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF303030), RoundedCornerShape(4.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("reconnect required", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "the current tunnel keeps its previous app rules until reconnect",
                        color = Color(0xFF666666),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    "RECONNECT",
                    Modifier.clickable(onClick = onReconnect).padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun SplitModeRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp)
                .border(1.dp, if (selected) Color.White else Color(0xFF555555), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).background(SplitRed, CircleShape))
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = Color(0xFF666666), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AppRuleRow(
    label: String,
    packageName: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Color(0xFF111111) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp)
                .border(1.dp, if (selected) SplitRed else Color(0xFF3A3A3A), RoundedCornerShape(2.dp))
                .background(if (selected) SplitRed else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                packageName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF555555),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun splitSummary(config: SplitTunnelConfig): String = when (config.mode) {
    SplitTunnelMode.ALL_APPS -> "all apps use VPN"
    SplitTunnelMode.EXCLUDE_SELECTED -> "${config.normalizedPackages.size} apps bypass VPN"
    SplitTunnelMode.INCLUDE_ONLY_SELECTED -> "VPN for ${config.normalizedPackages.size} apps"
}
