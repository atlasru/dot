package dev.dotclient.android.ui

import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.NodeSorter
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.VlessProfile
import dev.dotclient.android.ui.theme.DotThemeMode
import dev.dotclient.android.vpn.VpnConnectionState

data class DotUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
    val loadingSubscriptionId: String? = null,
    val requestingVpnPermission: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val message: String? = null,
    val themeMode: DotThemeMode = DotThemeMode.AMOLED,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val runningNodeName: String? = null,
    val nodeLatenciesMs: Map<String, Long> = emptyMap(),
    val nodeLatencyFailedIds: Set<String> = emptySet(),
    val testingNodeIds: Set<String> = emptySet(),
    val pendingDelaySortSubscriptionId: String? = null,
    val connectionTestRunning: Boolean = false,
    val connectionTestLatencyMs: Long? = null,
    val connectionTestError: String? = null,
    val subscriptionUpdateResult: SubscriptionUpdateResult? = null,
) {
    val selectedSubscription: Subscription?
        get() = subscriptions.firstOrNull { it.id == selectedSubscriptionId }

    val profiles: List<VlessProfile>
        get() = selectedSubscription?.profiles.orEmpty()

    val selectedSortMode: NodeSortMode
        get() = selectedSubscription?.sortMode ?: NodeSortMode.ORIGIN

    val sortedProfiles: List<VlessProfile>
        get() = NodeSorter.sort(profiles, selectedSortMode, nodeLatenciesMs, nodeLatencyFailedIds)

    val selectedProfileId: String?
        get() = selectedSubscription?.selectedProfileId

    val selectedProfile: VlessProfile?
        get() = selectedSubscription?.profiles?.firstOrNull { it.id == selectedSubscription?.selectedProfileId }

    val loading: Boolean
        get() = loadingSubscriptionId != null

    val vpnBusy: Boolean
        get() = vpnState == VpnConnectionState.CONNECTING || vpnState == VpnConnectionState.DISCONNECTING

    val vpnConnected: Boolean
        get() = vpnState == VpnConnectionState.CONNECTED
}
