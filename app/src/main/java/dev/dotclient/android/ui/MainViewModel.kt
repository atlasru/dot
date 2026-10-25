package dev.dotclient.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.parser.SubscriptionDecoder
import dev.dotclient.android.core.subscription.SecretRedactor
import dev.dotclient.android.core.subscription.SubscriptionClient
import dev.dotclient.android.core.subscription.SubscriptionIdentity
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val subscriptionClient = SubscriptionClient()
    private val subscriptionStore = SubscriptionStore(application)
    private val uiPreferences = application.getSharedPreferences("dot_ui", Application.MODE_PRIVATE)
    private val settingsRepository = dev.dotclient.android.core.settings.SettingsRepository(application)
    private val settingsMutex = Mutex()

    private val mutableState = MutableStateFlow(loadInitialState())
    val state: StateFlow<DotUiState> = mutableState.asStateFlow()

    private val diagnostics = NodeDiagnostics(application,mutableState,settingsRepository)

    init {
        viewModelScope.launch {
            VpnRuntime.state.collect { runtime ->
                if (runtime.state != VpnConnectionState.DISCONNECTED && runtime.state != VpnConnectionState.ERROR && runtime.state != VpnConnectionState.CONNECTED) diagnostics.cancel()
                mutableState.update {
                    it.copy(
                        requestingVpnPermission = false,
                        vpnPermissionGranted = runtime.state == VpnConnectionState.CONNECTING ||
                            runtime.state == VpnConnectionState.CONNECTED ||
                            runtime.state == VpnConnectionState.WAITING_FOR_NETWORK ||
                            runtime.state == VpnConnectionState.RECONNECTING ||
                            runtime.state == VpnConnectionState.DISCONNECTING,
                        vpnState = runtime.state,
                        message = runtime.message,
                        vpnFailureCategory = runtime.failureCategory,
                        vpnFailureDetail = runtime.failureDetail,
                        reconnectAttempt = runtime.reconnectAttempt,
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

                if (runtime.state == VpnConnectionState.DISCONNECTED && state.value.autoNodeEnabled) {
                    state.value.selectedSubscription?.let { ensureAutoNodeData(it.id) }
                }
            }
        }

        viewModelScope.launch {
            val initial=state.value.settings
            if(initial.autoConnect && android.net.VpnService.prepare(application)==null && !state.value.vpnConnected && requestVpnPermission()) onVpnPermissionGranted()
            if(initial.refreshOnStart) refreshAllSubscriptions().join()
            if(initial.ping.onStart) runScheduledDiagnostics()
            var lastPing=System.nanoTime(); var lastRefresh=System.nanoTime()
            while(true) {
                kotlinx.coroutines.delay(15_000)
                val settings=state.value.settings
                if(settings.ping.repeatMinutes>0 && System.nanoTime()-lastPing>=settings.ping.repeatMinutes*60_000_000_000L) { runScheduledDiagnostics(); lastPing=System.nanoTime() }
                if(settings.refreshHours>0 && System.nanoTime()-lastRefresh>=settings.refreshHours*3_600_000_000_000L) { refreshAllSubscriptions(); lastRefresh=System.nanoTime() }
            }
        }
    }

    private fun loadInitialState(): DotUiState {
        val stored = subscriptionStore.load()
        val settings=runCatching { settingsRepository.load() }.getOrDefault(dev.dotclient.android.core.settings.AppSettings())
        val results=settingsRepository.results().filterValues { it.settings==settings.ping }
        return DotUiState(
            settings=settings, pingResults=results,
            nodeLatenciesMs=results.mapNotNull { (id,r)->r.latencyMs?.let {id to it} }.toMap(),
            nodeLatencyFailedIds=results.filterValues { !it.success && it.error?.startsWith("HEAD unsupported")!=true }.keys,
            subscriptions = stored.subscriptions,
            selectedSubscriptionId = stored.selectedSubscriptionId,
            message = stored.loadError,
            autoNodeEnabled = uiPreferences.getBoolean("auto_node", false),
            themeMode = DotThemeMode.fromStorage(uiPreferences.getString("theme", null)),
        )
    }

    fun saveSubscription(existingId: String?, name: String, url: String, hwid: String): Boolean {
        if(state.value.testingNodeIds.isNotEmpty()) { mutableState.update { it.copy(message="Wait for diagnostics before editing subscriptions") }; return false }
        if (!SubscriptionIdentity.isValid(hwid)) {
            mutableState.update { it.copy(message = SubscriptionIdentity.VALIDATION_ERROR) }
            return false
        }
        if (state.value.loadingSubscriptionId != null) {
            mutableState.update { it.copy(message = "wait for subscription refresh to finish") }
            return false
        }
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            mutableState.update { it.copy(message = "subscription url is required") }
            return false
        }

        val current = state.value
        val fallbackName = "vpn${current.subscriptions.size + 1}"
        val cleanName = name.trim().ifBlank { fallbackName }

        val subscription = if (existingId == null) {
            Subscription(name = cleanName, url = cleanUrl, hwid = hwid)
        } else {
            current.subscriptions.firstOrNull { it.id == existingId }
                ?.copy(name = cleanName, url = cleanUrl, hwid = hwid) ?: return false
        }
        val subscriptions = if (existingId == null) current.subscriptions + subscription
        else current.subscriptions.map { if (it.id == existingId) subscription else it }
        runCatching { subscriptionStore.save(subscriptions, subscription.id) }.getOrElse {
            mutableState.update { it.copy(message = "could not save subscription; check storage and retry") }
            return false
        }
        mutableState.update { it.copy(subscriptions = subscriptions, selectedSubscriptionId = subscription.id, message = null, subscriptionUpdateResult = null) }
        refreshSubscription(subscription.id)
        return true
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

            val decoded = subscriptionClient.fetch(subscription, state.value.settings.subscriptionTimeoutSeconds, state.value.settings.subscriptionUserAgent).getOrElse { error ->
                showSubscriptionUpdateError(subscription, error)
                return@launch
            }

            if (decoded.profiles.isEmpty()) {
                val message = when (decoded.format) {
                    SubscriptionDecoder.DecodeResult.Format.EMPTY -> "The subscription is empty."
                    SubscriptionDecoder.DecodeResult.Format.UNSUPPORTED -> "The server responded, but dot. could not parse the subscription."
                    else -> "The subscription contains no supported nodes."
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
                val currentIds = decoded.profiles.mapTo(hashSetOf()) { it.id }
                val results=old.pingResults.filterKeys { it !in oldProfileIds || it in currentIds }
                val transferredLatencies=results.mapNotNull { (id,r)->r.latencyMs?.let { id to it } }.toMap()
                val transferredFailures=results.filterValues { !it.success && it.error?.startsWith("HEAD unsupported")!=true }.keys

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
                    pingResults = results,
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
            settingsMutex.withLock {
                val base=state.value.settings
                val validIds=state.value.subscriptions.flatMap { it.profiles }.map {it.id}.toSet()
                val updated=base.remapExclusions(diff,validIds)
                if(updated!=base) {
                    runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { settingsRepository.update(base,updated) } }
                        .onSuccess { saved -> mutableState.update { it.copy(settings=saved) } }
                        .onFailure { mutableState.update { it.copy(message="Could not persist AUTO exclusions; retry saving settings") } }
                }
            }
            if (state.value.autoNodeEnabled && state.value.selectedSubscriptionId == id) ensureAutoNodeData(id)
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
        if(state.value.subscriptions.firstOrNull { it.id==id }?.profiles?.any { it.id in state.value.testingNodeIds }==true) { mutableState.update { it.copy(message="Cancel diagnostics before deleting this subscription") }; return }
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
                pingResults = old.pingResults.filterKeys { it !in removedProfileIds },
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
        if (state.value.autoNodeEnabled) ensureAutoNodeData(id)
    }

    fun selectProfile(id: String) {
        if (state.value.autoNodeEnabled) {
            uiPreferences.edit().putBoolean("auto_node", false).apply()
            mutableState.update { it.copy(autoNodeEnabled = false) }
        }
        selectProfileInternal(id)
    }

    private fun selectProfileInternal(id: String) {
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

    fun setAutoNodeEnabled(enabled: Boolean) {
        if(enabled && (state.value.vpnConnected || state.value.vpnBusy)) { mutableState.update { it.copy(message="Disconnect before AUTO selection") }; return }
        if(enabled && !state.value.settings.ping.method.proxy) { mutableState.update { it.copy(message="AUTO requires Via Proxy GET/HEAD diagnostics") }; return }
        uiPreferences.edit().putBoolean("auto_node", enabled).apply()
        mutableState.update { it.copy(autoNodeEnabled = enabled, message = null) }
        if (!enabled) return

        val group = state.value.selectedSubscription ?: return
        val selected = resolveAutoProfile(group)
        val hasFreshResult = selected != null && selected.id in state.value.nodeLatenciesMs
        if (hasFreshResult) {
            selectProfileInternal(selected!!.id)
            mutableState.update { it.copy(message = "AUTO · ${selected.name}") }
            return
        }

        if (state.value.vpnConnected || state.value.vpnBusy) {
            mutableState.update { it.copy(message = "AUTO enabled · node test will refresh after disconnect") }
            return
        }
        ensureAutoNodeData(group.id)
    }

    private fun ensureAutoNodeData(subscriptionId: String) {
        val group = state.value.subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        if (group.profiles.isEmpty() || state.value.testingNodeIds.isNotEmpty()) return
        if (state.value.vpnConnected || state.value.vpnBusy) return
        val tested = group.profiles.all { profile -> state.value.pingResults[profile.id]?.let { result -> result.settings == state.value.settings.ping && System.currentTimeMillis()-result.testedAtMs in 0..899_999 } == true }
        if (tested) {
            resolveAutoProfile(group)?.let { selected ->
                selectProfileInternal(selected.id)
                mutableState.update { it.copy(message = "AUTO · ${selected.name}") }
            } ?: mutableState.update { it.copy(message="AUTO has no eligible verified proxy; run GET/HEAD diagnostics or choose a node manually") }
            return
        }
        viewModelScope.launch { runAllNodeTests(subscriptionId, activateDelaySort = false, selectAutoAfter = true) }
    }

    private fun resolveAutoProfile(group: Subscription? = state.value.selectedSubscription): ProxyNode? {
        val target = group ?: return null
        val current=state.value
        return dev.dotclient.android.core.reliability.AutoNodeSelector.selectVerified(target.profiles,current.pingResults,current.settings.ping,current.settings.autoExcluded,current.settings.autoMaxLatencyMs)
    }

    private fun resolvedConnectionProfile(): ProxyNode? {
        val current = state.value
        val group = current.selectedSubscription ?: return null
        val profile = if (current.autoNodeEnabled) resolveAutoProfile(group) else current.selectedProfile
        if (profile != null && profile.id != group.selectedProfileId) selectProfileInternal(profile.id)
        return profile
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
        if (state.value.testingNodeIds.isNotEmpty()) {
            mutableState.update { it.copy(message = "AUTO · testing nodes…") }
            return false
        }

        val profile = resolvedConnectionProfile()
        if (profile == null) {
            mutableState.update { it.copy(message = if(it.autoNodeEnabled) "AUTO has no eligible verified proxy; run GET/HEAD diagnostics or choose a node manually" else "select a node first") }
            return false
        }

        mutableState.update {
            it.copy(
                requestingVpnPermission = true,
                message = if (it.autoNodeEnabled) "AUTO · ${profile.name}" else "requesting Android VPN permission…",
            )
        }
        return true
    }

    fun onVpnPermissionGranted() {
        if(state.value.testingNodeIds.isNotEmpty()) { mutableState.update { it.copy(message="Wait for diagnostics to finish") }; return }
        val profile = resolvedConnectionProfile()
        if (profile == null) {
            mutableState.update { it.copy(requestingVpnPermission = false, message = if(it.autoNodeEnabled) "AUTO has no eligible verified proxy; run GET/HEAD diagnostics or choose a node manually" else "select a node first") }
            return
        }

        mutableState.update {
            it.copy(
                requestingVpnPermission = false,
                vpnPermissionGranted = true,
                vpnState = VpnConnectionState.CONNECTING,
                message = if (it.autoNodeEnabled) "AUTO · connecting ${profile.name}…" else "starting ${profile.protocol} tunnel…",
                vpnFailureCategory = null,
                vpnFailureDetail = null,
                reconnectAttempt = 0,
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }
        startVpn(profile)
    }

    fun reconnectCurrentProfile() {
        if(state.value.testingNodeIds.isNotEmpty()) return
        val profile = resolvedConnectionProfile() ?: return
        mutableState.update {
            it.copy(
                vpnState = VpnConnectionState.CONNECTING,
                message = if (it.autoNodeEnabled) "AUTO · reconnecting ${profile.name}…" else "reconnecting ${profile.name}…",
                vpnFailureCategory = null,
                vpnFailureDetail = null,
                reconnectAttempt = 0,
                connectionTestRunning = false,
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }
        startVpn(profile)
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

    fun testNode(profile: ProxyNode) { if(state.value.testingNodeIds.isEmpty()) viewModelScope.launch { diagnostics.run(listOf(profile)) } }
    fun testAllNodes() { state.value.selectedSubscription?.let { group -> if(state.value.testingNodeIds.isEmpty()) viewModelScope.launch { runAllNodeTests(group.id,false) } } }
    fun cancelDiagnostics() { diagnostics.cancel() }
    private suspend fun runAllNodeTests(subscriptionId: String, activateDelaySort: Boolean, selectAutoAfter: Boolean = false) {
        val group=state.value.subscriptions.firstOrNull { it.id==subscriptionId } ?: return
        diagnostics.run(group.profiles)
        if(activateDelaySort) updateSubscriptionSortMode(subscriptionId,NodeSortMode.DELAY)
        if(selectAutoAfter && state.value.autoNodeEnabled && state.value.selectedSubscriptionId==subscriptionId) {
            resolveAutoProfile()?.let { selectProfileInternal(it.id); mutableState.update { old -> old.copy(message="AUTO · ${it.name}") } }
                ?: mutableState.update { it.copy(message="AUTO has no eligible verified proxy; check exclusions/threshold or choose a node manually") }
        }
    }
    fun testConnection() {
        val node=state.value.subscriptions.flatMap {it.profiles}.firstOrNull { it.id==VpnRuntime.state.value.nodeId } ?: return
        if(state.value.vpnConnected && state.value.testingNodeIds.isEmpty()) viewModelScope.launch { diagnostics.run(listOf(node),true) }
    }
    private fun runScheduledDiagnostics() {
        if(state.value.vpnConnected || state.value.vpnBusy || state.value.testingNodeIds.isNotEmpty()) return
        if(state.value.settings.ping.selectedOnly) state.value.selectedProfile?.let(::testNode) else testAllNodes()
    }
    fun updateSettings(settings: dev.dotclient.android.core.settings.AppSettings, base: dev.dotclient.android.core.settings.AppSettings? = null) {
        if(state.value.testingNodeIds.isNotEmpty()) { mutableState.update { it.copy(message="Cancel diagnostics before changing settings") }; return }
        viewModelScope.launch { settingsMutex.withLock {
            try {
                val saved=withContext(kotlinx.coroutines.Dispatchers.IO) { settingsRepository.update(base,settings) }
                mutableState.update { old ->
                    val results=old.pingResults.filterValues { it.settings==saved.ping }
                    old.copy(settings=saved,pingResults=results,nodeLatenciesMs=results.mapNotNull { (id,r)->r.latencyMs?.let {id to it} }.toMap(),nodeLatencyFailedIds=results.filterValues {!it.success && it.error?.startsWith("HEAD unsupported")!=true}.keys,message=if(old.vpnConnected) "Saved · DNS/routing/log changes apply after reconnect" else "Settings saved")
                }
            } catch(e: Exception) { mutableState.update { it.copy(message=e.message ?: "Could not save settings") } }
        } }
    }
    fun clearDiagnostics() { if(state.value.testingNodeIds.isNotEmpty()) return; settingsRepository.saveResults(emptyMap()); mutableState.update { it.copy(pingResults=emptyMap(),nodeLatenciesMs=emptyMap(),nodeLatencyFailedIds=emptySet()) } }
    fun refreshAllSubscriptions() = viewModelScope.launch {
        for(group in state.value.subscriptions.toList()) {
            state.first { it.loadingSubscriptionId==null && it.testingNodeIds.isEmpty() }
            refreshSubscription(group.id)
            state.first { it.loadingSubscriptionId==null }
        }
    }

    fun switchProfile(id: String) {
        if(state.value.testingNodeIds.isNotEmpty()) return
        val group = state.value.selectedSubscription ?: return
        val profile = group.profiles.firstOrNull { it.id == id } ?: return
        selectProfile(id)
        if (!state.value.vpnConnected) return

        mutableState.update {
            it.copy(
                vpnState = VpnConnectionState.CONNECTING,
                message = "switching to ${profile.name}…",
                vpnFailureCategory = null,
                vpnFailureDetail = null,
                connectionTestRunning = false,
                connectionTestLatencyMs = null,
                connectionTestError = null,
            )
        }
        startVpn(profile)
    }

    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)

    private fun startVpn(profile: ProxyNode) {
        if(state.value.testingNodeIds.isNotEmpty()) { mutableState.update { it.copy(message="Wait for diagnostics to finish") }; return }
        val application = getApplication<Application>()
        val intent = Intent(application, DotVpnService::class.java)
            .setAction(DotVpnService.ACTION_CONNECT)
            .putExtra(DotVpnService.EXTRA_VLESS_URI, profile.rawUri)
            .putExtra(DotVpnService.EXTRA_NODE_NAME, profile.name)
        ContextCompat.startForegroundService(application, intent)
    }

    private fun persist() {
        val current = state.value
        runCatching { subscriptionStore.save(current.subscriptions, current.selectedSubscriptionId) }
            .onFailure { error -> mutableState.update { it.copy(message = error.message) } }
    }
}
