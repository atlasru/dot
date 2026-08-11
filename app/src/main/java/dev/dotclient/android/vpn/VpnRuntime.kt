package dev.dotclient.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

data class VpnRuntimeSnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val nodeName: String? = null,
    val message: String? = null,
)

object VpnRuntime {
    private val mutableState = MutableStateFlow(VpnRuntimeSnapshot())
    val state: StateFlow<VpnRuntimeSnapshot> = mutableState.asStateFlow()

    fun update(
        state: VpnConnectionState,
        nodeName: String? = mutableState.value.nodeName,
        message: String? = null,
    ) {
        mutableState.value = VpnRuntimeSnapshot(state, nodeName, message)
    }
}
