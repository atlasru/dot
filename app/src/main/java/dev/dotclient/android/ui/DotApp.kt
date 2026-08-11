package dev.dotclient.android.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.dotclient.android.core.model.VlessProfile

private enum class Tab { HOME, NODES, SETTINGS }

@Composable
fun DotApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.HOME) }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            DotBottomBar(tab = tab, onTab = { tab = it })
        },
    ) { padding ->
        when (tab) {
            Tab.HOME -> HomeScreen(state, viewModel, Modifier.padding(padding))
            Tab.NODES -> NodesScreen(state, viewModel, Modifier.padding(padding))
            Tab.SETTINGS -> SettingsScreen(state, viewModel, Modifier.padding(padding))
        }
    }
}

@Composable
private fun HomeScreen(state: DotUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("dot.", style = MaterialTheme.typography.headlineLarge)
            Text("v0.0.1", style = MaterialTheme.typography.labelMedium, color = Color(0xFF747474))
        }

        Spacer(Modifier.weight(0.7f))

        Box(
            modifier = Modifier
                .size(92.dp)
                .border(1.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(14.dp).background(Color.White, CircleShape))
        }

        Spacer(Modifier.height(24.dp))
        Text("offline", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            state.selectedProfile?.name ?: "no node selected",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF8A8A8A),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = viewModel::connect,
            enabled = !state.loading,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("CONNECT", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(12.dp))
        Text("↓ 0 B                      ↑ 0 B", color = Color(0xFF686868), style = MaterialTheme.typography.labelMedium)

        Spacer(Modifier.weight(1f))

        state.message?.let {
            Text(it, color = Color(0xFF9A9A9A), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NodesScreen(state: DotUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Text("nodes.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        if (state.profiles.isEmpty()) {
            Text("no nodes yet", color = Color(0xFF777777))
            Spacer(Modifier.height(8.dp))
            Text("add a subscription in settings.", color = Color(0xFF777777), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    NodeRow(profile, profile.id == state.selectedProfileId) {
                        viewModel.selectProfile(profile.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeRow(profile: VlessProfile, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(1.dp, if (selected) Color.White else Color(0xFF242424), RoundedCornerShape(2.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).background(if (selected) Color.White else Color(0xFF3B3B3B), CircleShape)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${profile.security.name.lowercase()} · ${profile.transport.name.lowercase()} · ${profile.host}:${profile.port}",
                color = Color(0xFF777777),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsScreen(state: DotUiState, viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Text("settings.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))
        Text("SUBSCRIPTION", style = MaterialTheme.typography.labelMedium, color = Color(0xFF777777))
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.subscriptionUrl,
            onValueChange = viewModel::setSubscriptionUrl,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://.../sub/user/...") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = viewModel::fetchSubscription,
            enabled = !state.loading,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            shape = RoundedCornerShape(2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = Color.Black)
            } else {
                Text("UPDATE", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingLine("theme", "AMOLED")
        SettingLine("type", "system monospace / courier-like")
        SettingLine("protocol", "VLESS")
        SettingLine("core", "libXray · next milestone")
        SettingLine("telemetry", "OFF")

        Spacer(Modifier.weight(1f))
        if (state.subscriptionUrl.isNotBlank()) {
            Text("stored/displayed safely:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF555555))
            Text(viewModel.redactedSubscriptionUrl(), style = MaterialTheme.typography.labelMedium, color = Color(0xFF777777))
        }
    }
}

@Composable
private fun SettingLine(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, color = Color(0xFFB0B0B0))
        Text(value, color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DotBottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .border(1.dp, Color(0xFF191919))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { item ->
            Text(
                text = item.name.lowercase(),
                modifier = Modifier.clickable { onTab(item) }.padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (item == tab) Color.White else Color(0xFF555555),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
