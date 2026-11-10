package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.ConnectionProbeResult
import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.NodeSorter
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.ui.theme.DotThemeMode
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnFailureCategory

data class DotUiState(
    val settings: dev.dotclient.android.core.settings.AppSettings = dev.dotclient.android.core.settings.AppSettings(),
    val savingSettings: Boolean = false,
    val pingResults: Map<String, PingResult> = emptyMap(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
    val loadingSubscriptionId: String? = null,
    val requestingVpnPermission: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val message: String? = null,
    val vpnFailureCategory: VpnFailureCategory? = null,
    val vpnFailureDetail: String? = null,
    val reconnectAttempt: Int = 0,
    val autoNodeEnabled: Boolean = false,
    val themeMode: DotThemeMode = DotThemeMode.AMOLED,
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
    val runningNodeId: String? = null,
    val runningNodeName: String? = null,
    val diagnosticsRunning: Boolean = false,
    val testingNodeIds: Set<String> = emptySet(),
    val diagnosticBatch: DiagnosticBatch? = null,
    val connectionTestRunning: Boolean = false,
    val connectionTestResult: ConnectionProbeResult? = null,
    val subscriptionUpdateResult: SubscriptionUpdateResult? = null,
) {
    val selectedSubscription: Subscription?
        get() = subscriptions.firstOrNull { it.id == selectedSubscriptionId }

    val profiles: List<ProxyNode>
        get() = selectedSubscription?.profiles.orEmpty()

    val selectedSortMode: NodeSortMode
        get() = selectedSubscription?.sortMode ?: NodeSortMode.ORIGIN

    val sortedProfiles: List<ProxyNode>
        get() {
            val batch = diagnosticBatch?.takeIf { it.groupId == selectedSubscriptionId && !it.running }
            val results = if (selectedSortMode == NodeSortMode.DELAY && batch != null) {
                pingResults.filterKeys { batch.uncheckedLabel(it) == null }
            } else pingResults
            return NodeSorter.sort(profiles, selectedSortMode, results)
        }

    internal fun uncheckedDiagnosticLabel(groupId: String?, id: String): String? =
        diagnosticBatch?.takeIf { it.groupId == groupId }?.uncheckedLabel(id)

    internal fun measuredResult(groupId: String, id: String): PingResult? =
        pingResults[id].takeUnless { uncheckedDiagnosticLabel(groupId, id) != null }

    val selectedProfileId: String?
        get() = selectedSubscription?.selectedProfileId

    val selectedProfile: ProxyNode?
        get() = selectedSubscription?.profiles?.firstOrNull { it.id == selectedSubscription?.selectedProfileId }

    val loading: Boolean
        get() = loadingSubscriptionId != null

    val vpnBusy: Boolean
        get() = vpnState == VpnConnectionState.CONNECTING ||
            vpnState == VpnConnectionState.RECONNECTING ||
            vpnState == VpnConnectionState.WAITING_FOR_NETWORK ||
            vpnState == VpnConnectionState.DISCONNECTING

    val vpnConnected: Boolean
        get() = vpnState == VpnConnectionState.CONNECTED
}
