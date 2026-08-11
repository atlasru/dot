package dev.dotclient.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dotclient.android.core.subscription.SecretRedactor
import dev.dotclient.android.core.subscription.SubscriptionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val subscriptionClient: SubscriptionClient = SubscriptionClient(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(DotUiState())
    val state: StateFlow<DotUiState> = mutableState.asStateFlow()

    fun setSubscriptionUrl(value: String) {
        mutableState.update { it.copy(subscriptionUrl = value, message = null) }
    }

    fun fetchSubscription() {
        val url = state.value.subscriptionUrl.trim()
        if (url.isBlank()) {
            mutableState.update { it.copy(message = "paste a subscription url first") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, message = null) }
            subscriptionClient.fetch(url)
                .onSuccess { decoded ->
                    val first = decoded.profiles.firstOrNull()?.id
                    mutableState.update {
                        it.copy(
                            loading = false,
                            profiles = decoded.profiles,
                            selectedProfileId = first,
                            message = "${decoded.profiles.size} node(s) · ${decoded.format.name.lowercase()}",
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            loading = false,
                            message = error.message ?: "subscription request failed",
                        )
                    }
                }
        }
    }

    fun selectProfile(id: String) {
        mutableState.update { it.copy(selectedProfileId = id) }
    }

    fun connect() {
        val profile = state.value.selectedProfile
        mutableState.update {
            it.copy(
                message = if (profile == null) {
                    "select a node first"
                } else {
                    "${profile.name}: UI + subscription path works; libXray hookup is next"
                }
            )
        }
    }

    fun redactedSubscriptionUrl(): String = SecretRedactor.url(state.value.subscriptionUrl)
}
