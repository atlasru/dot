package dev.dotclient.android.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    WAITING_FOR_NETWORK,
    RECONNECTING,
    DISCONNECTING,
    ERROR,
}

data class VpnRuntimeSnapshot(
    val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val nodeName: String? = null,
    val message: String? = null,
    val failureCategory: VpnFailureCategory? = null,
    val failureDetail: String? = null,
    val reconnectAttempt: Int = 0,
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
        failure: VpnFailure? = null,
        reconnectAttempt: Int = if (state == VpnConnectionState.RECONNECTING) mutableState.value.reconnectAttempt else 0,
        downloadBytesPerSecond: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.downloadBytesPerSecond else 0L,
        uploadBytesPerSecond: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.uploadBytesPerSecond else 0L,
        sessionDownloadBytes: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.sessionDownloadBytes else 0L,
        sessionUploadBytes: Long = if (state == VpnConnectionState.CONNECTED) mutableState.value.sessionUploadBytes else 0L,
    ) {
        mutableState.value = VpnRuntimeSnapshot(
            state = state,
            nodeName = nodeName,
            message = message,
            failureCategory = failure?.category,
            failureDetail = failure?.detail,
            reconnectAttempt = reconnectAttempt,
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
