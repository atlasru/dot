package dev.dotclient.android.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.subscription.SecretRedactor
import dev.dotclient.android.core.subscription.SubscriptionClient
import dev.dotclient.android.core.subscription.SubscriptionStore
import dev.dotclient.android.vpn.DotVpnService
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnRuntime
import dev.dotclient.android.ui.theme.DotThemeMode
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
                    )
                }
            }
        }
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
                            profiles = if (subscription.url == cleanUrl) subscription.profiles else emptyList(),
                            selectedProfileId = if (subscription.url == cleanUrl) subscription.selectedProfileId else null,
                        )
                    } else {
                        subscription
                    }
                },
                selectedSubscriptionId = existingId,
                message = null,
            )
        }
        persist()
        refreshSubscription(existingId)
    }

    fun refreshSubscription(id: String) {
        val subscription = state.value.subscriptions.firstOrNull { it.id == id } ?: return
        if (state.value.loadingSubscriptionId != null) return

        viewModelScope.launch {
            mutableState.update { it.copy(loadingSubscriptionId = id, message = null) }

            subscriptionClient.fetch(subscription.url)
                .onSuccess { decoded ->
                    mutableState.update { old ->
                        val updated = old.subscriptions.map { group ->
                            if (group.id != id) return@map group

                            val oldSelectedRaw = group.profiles
                                .firstOrNull { it.id == group.selectedProfileId }
                                ?.rawUri
                            val selectedId = decoded.profiles
                                .firstOrNull { it.rawUri == oldSelectedRaw }
                                ?.id
                                ?: decoded.profiles.firstOrNull()?.id

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
                            message = "${decoded.profiles.size} node(s) · ${decoded.format.name.lowercase()}",
                        )
                    }
                    persist()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            loadingSubscriptionId = null,
                            message = error.message ?: "subscription request failed",
                        )
                    }
                }
        }
    }

    fun deleteSubscription(id: String) {
        mutableState.update { old ->
            val remaining = old.subscriptions.filterNot { it.id == id }
            old.copy(
                subscriptions = remaining,
                selectedSubscriptionId = if (old.selectedSubscriptionId == id) {
                    remaining.firstOrNull()?.id
                } else {
                    old.selectedSubscriptionId
                },
                message = null,
            )
        }
        persist()
    }

    fun selectSubscription(id: String) {
        if (state.value.subscriptions.none { it.id == id }) return
        mutableState.update { it.copy(selectedSubscriptionId = id, message = null) }
        persist()
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

    fun redactedSubscriptionUrl(url: String): String = SecretRedactor.url(url)

    private fun persist() {
        val current = state.value
        subscriptionStore.save(current.subscriptions, current.selectedSubscriptionId)
    }
}
