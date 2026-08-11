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
    val downloadBytesPerSecond: Long = 0L,
    val uploadBytesPerSecond: Long = 0L,
    val sessionDownloadBytes: Long = 0L,
    val sessionUploadBytes: Long = 0L,
)

object VpnRuntime {
    private val mutableState = MutableStateFlow(VpnRuntimeSnapshot())
    val state: StateFlow<VpnRuntimeSnapshot> = mutableState.asStateFlow()

    fun update(
        state: VpnConnectionState,
        nodeName: String? = mutableState.value.nodeName,
        message: String? = null,
        downloadBytesPerSecond: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.downloadBytesPerSecond else 0L,
        uploadBytesPerSecond: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.uploadBytesPerSecond else 0L,
        sessionDownloadBytes: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.sessionDownloadBytes else 0L,
        sessionUploadBytes: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.sessionUploadBytes else 0L,
    ) {
        mutableState.value = VpnRuntimeSnapshot(
            state = state,
            nodeName = nodeName,
            message = message,
            downloadBytesPerSecond = downloadBytesPerSecond,
            uploadBytesPerSecond = uploadBytesPerSecond,
            sessionDownloadBytes = sessionDownloadBytes,
            sessionUploadBytes = sessionUploadBytes,
        )
    }

    fun updateTraffic(
        downloadBytesPerSecond: Long,
        uploadBytesPerSecond: Long,
        sessionDownloadBytes: Long,
        sessionUploadBytes: Long,
    ) {
        val current = mutableState.value
        if (current.state != VpnConnectionState.CONNECTED) return
        mutableState.value = current.copy(
            downloadBytesPerSecond = downloadBytesPerSecond.coerceAtLeast(0L),
            uploadBytesPerSecond = uploadBytesPerSecond.coerceAtLeast(0L),
            sessionDownloadBytes = sessionDownloadBytes.coerceAtLeast(0L),
            sessionUploadBytes = sessionUploadBytes.coerceAtLeast(0L),
        )
    }
}
