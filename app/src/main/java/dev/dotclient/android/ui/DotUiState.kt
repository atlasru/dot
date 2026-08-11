package dev.dotclient.android.ui

import dev.dotclient.android.core.model.VlessProfile

data class DotUiState(
    val subscriptionUrl: String = "",
    val loading: Boolean = false,
    val profiles: List<VlessProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val message: String? = null,
) {
    val selectedProfile: VlessProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId }
}
