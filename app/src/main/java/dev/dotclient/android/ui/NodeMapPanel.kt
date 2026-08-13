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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dotclient.android.core.geo.NodeGeoLocation
import dev.dotclient.android.core.geo.NodeGeoResolver
import dev.dotclient.android.core.geo.NodeGeoSource
import dev.dotclient.android.core.model.VlessProfile
import java.util.Locale
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val NodeMapRed = Color(0xFFFF2D2D)

private data class CountryCluster(
    val code: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val profiles: List<VlessProfile>,
    val cities: List<String>,
    val fallbackOnly: Boolean,
)

@Composable
fun NodeMapPanel(
    state: DotUiState,
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val group = state.selectedSubscription ?: return
    val resolver = remember(context.applicationContext) { NodeGeoResolver(context.applicationContext) }
    val profileKey = remember(group.profiles) {
        group.profiles.joinToString("|") { "${it.id}:${it.host}:${it.name}" }.hashCode()
    }

    var locations by remember(group.id, profileKey) { mutableStateOf<Map<String, NodeGeoLocation>>(emptyMap()) }
    var completed by remember(group.id, profileKey) { mutableIntStateOf(0) }
    var selectedCountry by remember(group.id, profileKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(group.id, profileKey) {
        locations = emptyMap()
        completed = 0
        selectedCountry = null
        val semaphore = Semaphore(4)
        coroutineScope {
            group.profiles.forEach { profile ->
                launch {
                    val location = semaphore.withPermit { resolver.resolve(profile) }
                    if (location != null) locations = locations + (profile.id to location)
                    completed += 1
                }
            }
        }
    }

    val profilesById = remember(group.profiles) { group.profiles.associateBy(VlessProfile::id) }
    val clusters = remember(locations, profilesById) {
        locations.values
            .mapNotNull { location -> profilesById[location.profileId]?.let { it to location } }
            .groupBy { (_, location) -> location.countryCode }
            .map { (countryCode, entries) ->
                val exactEntries = entries.filter { it.second.source == NodeGeoSource.GEO_IP }
                val anchors = exactEntries.ifEmpty { entries }
                CountryCluster(
                    code = countryCode,
                    name = entries.first().second.countryName,
                    latitude = anchors.map { it.second.latitude }.average(),
                    longitude = anchors.map { it.second.longitude }.average(),
                    profiles = entries.map { it.first }.sortedBy(VlessProfile::name),
                    cities = exactEntries.mapNotNull { it.second.city }.distinct().sorted(),
                    fallbackOnly = exactEntries.isEmpty(),
                )
            }
            .sortedBy(CountryCluster::name)
    }

    val selectedCluster = clusters.firstOrNull { it.code == selectedCountry }
    val missing = (completed - locations.size).coerceAtLeast(0)

    Column(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF252525), RoundedCornerShape(4.dp)),
        ) {
            NodeMapView(
                markers = clusters.map { cluster ->
                    NodeMapMarker(
                        countryCode = cluster.code,
                        latitude = cluster.latitude,
                        longitude = cluster.longitude,
                        nodeCount = cluster.profiles.size,
                        active = cluster.profiles.any { it.name == state.runningNodeName },
                    )
                },
                onMarkerClick = { selectedCountry = it },
                modifier = Modifier.fillMaxSize(),
            )
            Text(
                when {
                    completed < group.profiles.size -> "LOCATING $completed/${group.profiles.size}"
                    missing > 0 -> "${locations.size}/${group.profiles.size} LOCATED"
                    else -> "${locations.size} NODES · ${clusters.size} COUNTRIES"
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color(0xE6000000), RoundedCornerShape(2.dp))
                    .border(1.dp, Color(0xFF262626), RoundedCornerShape(2.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                color = Color(0xFFAAAAAA),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(8.dp))
        if (selectedCluster != null) {
            CountrySheet(selectedCluster, state, viewModel, onToggleVpn)
        } else {
            Text(
                when {
                    clusters.isEmpty() && completed == group.profiles.size -> "no node locations available"
                    missing > 0 && completed == group.profiles.size -> "$missing node(s) could not be located"
                    else -> "tap a country marker to show nodes"
                },
                color = Color(0xFF666666),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun CountrySheet(
    cluster: CountryCluster,
    state: DotUiState,
    viewModel: MainViewModel,
    onToggleVpn: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF282828), RoundedCornerShape(3.dp))
            .background(Color(0xFF0D0D0D), RoundedCornerShape(3.dp))
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${countryFlag(cluster.code)} ${cluster.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    when {
                        cluster.cities.isNotEmpty() -> cluster.cities.joinToString(" · ")
                        cluster.fallbackOnly -> "country location · name fallback"
                        else -> "${cluster.profiles.size} nodes"
                    },
                    color = Color(0xFF626262),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("${cluster.profiles.size}", color = Color(0xFF777777), style = MaterialTheme.typography.labelMedium)
        }

        Spacer(Modifier.height(7.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 170.dp)) {
            items(cluster.profiles, key = VlessProfile::id) { profile ->
                val running = state.vpnConnected && profile.name == state.runningNodeName
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !running) {
                            if (state.vpnConnected) viewModel.switchProfile(profile.id)
                            else {
                                viewModel.selectProfile(profile.id)
                                onToggleVpn()
                            }
                        }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(if (running) NodeMapRed else Color(0xFF555555), RoundedCornerShape(1.dp))
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        profile.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val latency = state.nodeLatenciesMs[profile.id]
                    Text(
                        when {
                            profile.id in state.testingNodeIds -> "··"
                            latency != null -> "$latency ms"
                            running -> "LIVE"
                            else -> "CONNECT"
                        },
                        color = if (running) NodeMapRed else Color(0xFF777777),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private fun countryFlag(code: String): String {
    val upper = code.uppercase(Locale.ROOT)
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return ""
    val first = Character.toChars(0x1F1E6 + upper[0].code - 'A'.code)
    val second = Character.toChars(0x1F1E6 + upper[1].code - 'A'.code)
    return String(first) + String(second)
}
