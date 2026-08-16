package dev.dotclient.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.VlessProfile
import dev.dotclient.android.core.parser.SubscriptionDecoder
import dev.dotclient.android.core.subscription.SecretRedactor
import dev.dotclient.android.core.subscription.SubscriptionClient
import dev.dotclient.android.core.subscription.SubscriptionContentException
import dev.dotclient.android.core.subscription.SubscriptionDiffer
import dev.dotclient.android.core.subscription.SubscriptionStore
import dev.dotclient.android.core.subscription.SubscriptionUpdateErrorFormatter
import dev.dotclient.android.ui.theme.DotThemeMode
import dev.dotclient.android.vpn.DotVpnService
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val subscriptionClient = SubscriptionClient()
    private val subscriptionStore = SubscriptionStore(application)
    private val uiPreferences = application.getSharedPreferences("dot_ui", Application.MODE_PRIVATE)
    private val nodeLatencyTester = NodeLatencyTester(application)

    private val mutableState = MutableStateFlow(loadInitialState())
    val state: StateFlow<DotUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            VpnRuntime.state.collect { runtime ->
                mutableState.update {
                    it.copy(
                        requestingVpnPermission = false,
                        vpnPermissionGranted = runtime.state == VpnConnectionState.CONNECTING ||
                            runtime.state == VpnConnectionState.CONNECTED ||
                            runtime.state == VpnConnectionState.DISCONNECTING,
                        vpnState = runtime.state,
                        message = runtime.message,
                        downloadBytesPerSecond = runtime.downloadBytesPerSecond,
                        uploadBytesPerSecond = runtime.uploadBytesPerSecond,
                        sessionDownloadBytes = runtime.sessionDownloadBytes,
                        sessionUploadBytes = runtime.sessionUploadBytes,
                        runningNodeName = runtime.nodeName,
                        connectionTestRunning = if (runtime.state == VpnConnectionState.CONNECTED) it.connectionTestRunning else false,
                        connectionTestLatencyMs = if (runtime.state == VpnConnectionState.CONNECTED) it.connectionTestLatencyMs else null,
                        connectionTestError = if (runtime.state == VpnConnectionState.CONNECTED) it.connectionTestError else null,
                    )
                }
            }
        }

        state.value.selectedSubscription
            ?.takeIf { it.sortMode == NodeSortMode.DELAY && it.profiles.isNotEmpty() }
            ?.let { ensureDelaySortData(it.id) }
    }

    private fun loadInitialState(): DotUiState {
        val stored = subscriptionStore.load()
        return DotUiState(
            subscriptions = stored.subscriptions,
            selectedSubscriptionId = stored.selectedSubscriptionId,
            themeMode = DotThemeMode.fromStorage(uiPreferences.getString("theme", null)),
        )
    }

    fun saveSubscription(existingId: String?, name: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            mutableState.update { it.copy(message = "subscription url is required") }
            return
        }

        val current = state.value
        val fallbackName = "vpn${current.subscriptions.size + 1}"
        val cleanName = name.trim().ifBlank { fallbackName }

        if (existingId == null) {
            val subscription = Subscription(name = cleanName, url = cleanUrl)
            mutableState.update {
                it.copy(
                    subscriptions = it.subscriptions + subscription,
                    selectedSubscriptionId = subscription.id,
                    message = null,
                    subscriptionUpdateResult = null,
                )
            }
            persist()
            refreshSubscription(subscription.id)
            return
        }

        mutableState.update { old ->
            old.copy(
                subscriptions = old.subscriptions.map { subscription ->
                    if (subscription.id == existingId) {
                        subscription.copy(
                            name = cleanName,
                            url = cleanUrl,
                        )
                    } else {
                        subscription
                    }
                },
                selectedSubscriptionId = existingId,
                message = null,
                subscriptionUpdateResult = null,
            )
        }
        persist()
        refreshSubscription(existingId)
    }

    fun refreshSubscription(id: String) {
        val subscription = state.value.subscriptions.firstOrNull { it.id == id } ?: return
        if (state.value.loadingSubscriptionId != null) return
        if (subscription.profiles.any { it.id in state.value.testingNodeIds }) {
            mutableState.update { it.copy(message = "wait for URL test to finish") }
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loadingSubscriptionId = id,
                    message = null,
                    subscriptionUpdateResult = null,
                )
            }

            val decoded = subscriptionClient.fetch(subscription.url).getOrElse { error ->
                showSubscriptionUpdateError(subscription, error)
                return@launch
            }

            if (decoded.profiles.isEmpty()) {
                val message = when (decoded.format) {
                    SubscriptionDecoder.DecodeResult.Format.EMPTY -> "The subscription is empty."
                    SubscriptionDecoder.DecodeResult.Format.UNSUPPORTED -> "The server responded, but dot. could not parse the subscription."
                    else -> "The subscription contains no supported VLESS nodes."
                }
                showSubscriptionUpdateError(subscription, SubscriptionContentException(message))
                return@launch
            }

            val latestGroup = state.value.subscriptions.firstOrNull { it.id == id } ?: return@launch
            val diff = SubscriptionDiffer.calculate(latestGroup.profiles, decoded.profiles)
            val oldSelected = latestGroup.profiles.firstOrNull { it.id == latestGroup.selectedProfileId }
            val selectedId = oldSelected?.let { selected ->
                decoded.profiles.firstOrNull { it.rawUri == selected.rawUri }?.id
                    ?: diff.replacementFor(selected.id)?.id
            } ?: decoded.profiles.firstOrNull()?.id

            mutableState.update { old ->
                val oldProfileIds = latestGroup.profiles.mapTo(hashSetOf()) { it.id }
                val transferredLatencies = old.nodeLatenciesMs
                    .filterKeys { it !in oldProfileIds }
                    .toMutableMap()
                val transferredFailures = old.nodeLatencyFailedIds
                    .filterNotTo(mutableSetOf()) { it in oldProfileIds }

                diff.unchanged.forEach { match ->
                    old.nodeLatenciesMs[match.before.id]?.let { transferredLatencies[match.after.id] = it }
                    if (match.before.id in old.nodeLatencyFailedIds) transferredFailures += match.after.id
                }
                diff.edited.forEach { edit ->
                    old.nodeLatenciesMs[edit.before.id]?.let { transferredLatencies[edit.after.id] = it }
                    if (edit.before.id in old.nodeLatencyFailedIds) transferredFailures += edit.after.id
                }

                val updated = old.subscriptions.map { group ->
                    if (group.id != id) return@map group
                    group.copy(
                        profiles = decoded.profiles,
                        selectedProfileId = selectedId,
                        lastUpdatedEpochMs = System.currentTimeMillis(),
                    )
                }

                old.copy(
                    subscriptions = updated,
                    selectedSubscriptionId = id,
                    loadingSubscriptionId = null,
                    nodeLatenciesMs = transferredLatencies,
                    nodeLatencyFailedIds = transferredFailures,
                    testingNodeIds = old.testingNodeIds - oldProfileIds,
                    message = null,
                    subscriptionUpdateResult = SubscriptionUpdateResult.Success(
                        subscriptionName = latestGroup.name,
                        totalNodes = decoded.profiles.size,
                        diff = diff,
                    ),
                )
            }
            persist()
        }
    }

    private fun showSubscriptionUpdateError(subscription: Subscription, error: Throwable) {
        val formatted = SubscriptionUpdateErrorFormatter.format(error, subscription.url)
        mutableState.update {
            it.copy(
                loadingSubscriptionId = null,
                message = null,
                subscriptionUpdateResult = SubscriptionUpdateResult.Error(
                    subscriptionName = subscription.name,
                    userMessage = formatted.userMessage,
                    rawError = formatted.rawError,
                ),
            )
        }
    }

    fun dismissSubscriptionUpdateResult() {
        mutableState.update { it.copy(subscriptionUpdateResult = null) }
    }

    fun deleteSubscription(id: String) {
        mutableState.update { old ->
            val removedProfileIds = old.subscriptions
                .firstOrNull { it.id == id }
                ?.profiles
                .orEmpty()
                .mapTo(hashSetOf()) { it.id }
            val remaining = old.subscriptions.filterNot { it.id == id }
            old.copy(
                subscriptions = remaining,
                selectedSubscriptionId = if (old.selectedSubscriptionId == id) {
                    remaining.firstOrNull()?.id
                } else {
                    old.selectedSubscriptionId
                },
                nodeLatenciesMs = old.nodeLatenciesMs.filterKeys { it !in removedProfileIds },
                nodeLatencyFailedIds = old.nodeLatencyFailedIds - removedProfileIds,
                testingNodeIds = old.testingNodeIds - removedProfileIds,
                pendingDelaySortSubscriptionId = old.pendingDelaySortSubscriptionId.takeUnless { it == id },
                message = null,
                subscriptionUpdateResult = null,
            )
        }
        persist()
    }

    fun selectSubscription(id: String) {
        if (state.value.subscriptions.none { it.id == id }) return
        mutableState.update { it.copy(selectedSubscriptionId = id, message = null) }
        persist()
        ensureDelaySortData(id)
    }

    fun selectProfile(id: String) {
        val selectedGroupId = state.value.selectedSubscriptionId ?: return
        mutableState.update { old ->
            old.copy(
                subscriptions = old.subscriptions.map { group ->
                    if (group.id == selectedGroupId && group.profiles.any { it.id == id }) {
                        group.copy(selectedProfileId = id)
                    } else {
                        group
                    }
                },
                message = null,
            )
        }
        persist()
    }

    fun setNodeSortMode(mode: NodeSortMode) {
        val group = state.value.selectedSubscription ?: return
        if (group.profiles.isEmpty()) return

        if (mode == NodeSortMode.DELAY) {
            val tested = group.profiles.all { profile ->
                profile.id in state.value.nodeLatenciesMs || profile.id in state.value.nodeLatencyFailedIds
            }
            if (!tested) {
                if (state.value.testingNodeIds.isNotEmpty()) return
                viewModelScope.launch { runAllNodeTests(group.id, activateDelaySort = true) }
                return
            }
        }

        updateSubscriptionSortMode(group.id, mode)
    }

    private fun updateSubscriptionSortMode(subscriptionId: String, mode: NodeSortMode) {
        mutableState.update { old ->
            old.copy(
                subscriptions = old.subscriptions.map { group ->
                    if (group.id == subscriptionId) group.copy(sortMode = mode) else group
                },
                pendingDelaySortSubscriptionId = old.pendingDelaySortSubscriptionId.takeUnless { it == subscriptionId },
            )
        }
        persist()
    }

    private fun ensureDelaySortData(subscriptionId: String) {
        val group = state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        if (group.sortMode != NodeSortMode.DELAY || group.profiles.isEmpty()) return
        val tested = group.profiles.all { profile ->
            profile.id in state.value.nodeLatenciesMs || profile.id in state.value.nodeLatencyFailedIds
        }
        if (tested || state.value.testingNodeIds.isNotEmpty()) return
        viewModelScope.launch { runAllNodeTests(subscriptionId, activateDelaySort = false) }
    }

    fun requestVpnPermission(): Boolean {
        if (state.value.selectedProfile == null) {
            mutableState.update { it.copy(message = "select a node first") }
            return false
        }

        mutableState.update {
            it.copy(
                requestingVpnPermission = true,
                message = "requesting Android VPN permission…",
            )
        }
        return true
    }

    fun onVpnPermissionGranted() {
        val profile = state.value.selectedProfile
        if (profile == null) {
            mutableState.update { it.copy(requestingVpnPermission = false, message = "select a node first") }
            return
        }

        mutableState.update {
            it.copy(
                requestingVpnPermission = false,
                vpnPermissionGranted = true,
                vpnState = VpnConnectionState.CONNECTING,
                message = "starting VLESS tunnel…",
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }

        val application = getApplication<Application>()
        val intent = Intent(application, DotVpnService::class.java)
            .setAction(DotVpnService.ACTION_CONNECT)
            .putExtra(DotVpnService.EXTRA_VLESS_URI, profile.rawUri)
            .putExtra(DotVpnService.EXTRA_NODE_NAME, profile.name)
        ContextCompat.startForegroundService(application, intent)
    }

    fun disconnect() {
        mutableState.update {
            it.copy(
                vpnState = VpnConnectionState.DISCONNECTING,
                message = "disconnecting…",
                connectionTestRunning = false,
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }
        val application = getApplication<Application>()
        val intent = Intent(application, DotVpnService::class.java)
            .setAction(DotVpnService.ACTION_DISCONNECT)
        application.startService(intent)
    }

    fun onVpnPermissionDenied() {
        mutableState.update {
            it.copy(
                requestingVpnPermission = false,
                vpnPermissionGranted = false,
                vpnState = VpnConnectionState.DISCONNECTED,
                message = "VPN permission was not granted",
            )
        }
    }

    fun setTheme(theme: DotThemeMode) {
        mutableState.update { it.copy(themeMode = theme) }
        uiPreferences.edit().putString("theme", theme.name).apply()
    }

    fun testNode(profile: VlessProfile) {
        if (state.value.testingNodeIds.contains(profile.id)) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    testingNodeIds = it.testingNodeIds + profile.id,
                    nodeLatencyFailedIds = it.nodeLatencyFailedIds - profile.id,
                )
            }
            nodeLatencyTester.test(profile.rawUri)
                .onSuccess { latency ->
                    mutableState.update { current ->
                        current.copy(
                            nodeLatenciesMs = current.nodeLatenciesMs + (profile.id to latency),
                            nodeLatencyFailedIds = current.nodeLatencyFailedIds - profile.id,
                            testingNodeIds = current.testingNodeIds - profile.id,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update { current ->
                        current.copy(
                            nodeLatenciesMs = current.nodeLatenciesMs - profile.id,
                            nodeLatencyFailedIds = current.nodeLatencyFailedIds + profile.id,
                            testingNodeIds = current.testingNodeIds - profile.id,
                            message = error.message ?: "url test failed",
                        )
                    }
                }
        }
    }

    fun testAllNodes() {
        val group = state.value.selectedSubscription ?: return
        if (group.profiles.isEmpty() || state.value.testingNodeIds.isNotEmpty()) return
        viewModelScope.launch { runAllNodeTests(group.id, activateDelaySort = false) }
    }

    private suspend fun runAllNodeTests(subscriptionId: String, activateDelaySort: Boolean) {
        val group = state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        val profiles = group.profiles
        if (profiles.isEmpty() || state.value.testingNodeIds.isNotEmpty()) return
        val profileIds = profiles.mapTo(hashSetOf()) { it.id }
        val delaySortPending = activateDelaySort || group.sortMode == NodeSortMode.DELAY

        mutableState.update {
            it.copy(
                testingNodeIds = it.testingNodeIds + profileIds,
                nodeLatenciesMs = it.nodeLatenciesMs.filterKeys { id -> id !in profileIds },
                nodeLatencyFailedIds = it.nodeLatencyFailedIds - profileIds,
                pendingDelaySortSubscriptionId = if (delaySortPending) subscriptionId else it.pendingDelaySortSubscriptionId,
                message = null,
            )
        }

        profiles.forEach { profile ->
            nodeLatencyTester.test(profile.rawUri)
                .onSuccess { latency ->
                    mutableState.update { current ->
                        current.copy(
                            nodeLatenciesMs = current.nodeLatenciesMs + (profile.id to latency),
                            nodeLatencyFailedIds = current.nodeLatencyFailedIds - profile.id,
                            testingNodeIds = current.testingNodeIds - profile.id,
                        )
                    }
                }
                .onFailure {
                    mutableState.update { current ->
                        current.copy(
                            nodeLatenciesMs = current.nodeLatenciesMs - profile.id,
                            nodeLatencyFailedIds = current.nodeLatencyFailedIds + profile.id,
                            testingNodeIds = current.testingNodeIds - profile.id,
                        )
                    }
                }
        }

        mutableState.update { current ->
            current.copy(
                subscriptions = if (activateDelaySort) {
                    current.subscriptions.map { item ->
                        if (item.id == subscriptionId) item.copy(sortMode = NodeSortMode.DELAY) else item
                    }
                } else {
                    current.subscriptions
                },
                pendingDelaySortSubscriptionId = current.pendingDelaySortSubscriptionId.takeUnless { it == subscriptionId },
                message = "URL test complete · cp.cloudflare.com",
            )
        }
        if (activateDelaySort) persist()
    }

    fun testConnection() {
        val current = state.value
        if (!current.vpnConnected || current.connectionTestRunning) return

        val runningProfile = current.subscriptions
            .asSequence()
            .flatMap { it.profiles.asSequence() }
            .firstOrNull { it.name == current.runningNodeName }
            ?: current.selectedProfile

        if (runningProfile == null) {
            mutableState.update { it.copy(connectionTestError = "active node is unavailable") }
            return
        }

        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    connectionTestRunning = true,
                    connectionTestLatencyMs = null,
                    connectionTestError = null,
                )
            }

            nodeLatencyTester.test(runningProfile.rawUri)
                .onSuccess { latency ->
                    mutableState.update {
                        it.copy(
                            connectionTestRunning = false,
                            connectionTestLatencyMs = latency,
                            connectionTestError = null,
                            nodeLatenciesMs = it.nodeLatenciesMs + (runningProfile.id to latency),
                            nodeLatencyFailedIds = it.nodeLatencyFailedIds - runningProfile.id,
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            connectionTestRunning = false,
                            connectionTestLatencyMs = null,
                            connectionTestError = error.message ?: "connection test failed",
                        )
                    }
                }
        }
    }

    fun switchProfile(id: String) {
        val group = state.value.selectedSubscription ?: return
        val profile = group.profiles.firstOrNull { it.id == id } ?: return
        selectProfile(id)
        if (!state.value.vpnConnected) return

        mutableState.update {
            it.copy(
                vpnState = VpnConnectionState.CONNECTING,
                message = "switching to ${profile.name}…",
                connectionTestRunning = false,
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }
        val application = getApplication<Application>()
        val intent = Intent(application, DotVpnService::class.java)
            .setAction(DotVpnService.ACTION_CONNECT)
            .putExtra(DotVpnService.EXTRA_VLESS_URI, profile.rawUri)
            .putExtra(DotVpnService.EXTRA_NODE_NAME, profile.name)
        ContextCompat.startForegroundService(application, intent)
    }

    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)

    private fun persist() {
        val current = state.value
        subscriptionStore.save(current.subscriptions, current.selectedSubscriptionId)
    }
}
