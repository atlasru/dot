package dev.dotclient.android.ui

import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.VlessProfile

data class DotUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
    val loadingSubscriptionId: String? = null,
    val requestingVpnPermission: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val message: String? = null,
) {
    val selectedSubscription: Subscription?
        get() = subscriptions.firstOrNull { it.id == selectedSubscriptionId }

    val profiles: List<VlessProfile>
        get() = selectedSubscription?.profiles.orEmpty()

    val selectedProfileId: String?
        get() = selectedSubscription?.selectedProfileId

    val selectedProfile: VlessProfile?
        get() = selectedSubscription?.profiles?.firstOrNull { it.id == selectedSubscription?.selectedProfileId }

    val loading: Boolean
        get() = loadingSubscriptionId != null
}
