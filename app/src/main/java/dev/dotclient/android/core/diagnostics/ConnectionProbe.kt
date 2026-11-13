package dev.dotclient.android.core.diagnostics

import dev.dotclient.android.vpn.ActiveProxySession
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnRuntimeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface ConnectionProbeResult {
    data class Success(val latencyMs: Long) : ConnectionProbeResult
    data class Failure(val reason: ConnectionProbeFailure, val detail: String? = null) : ConnectionProbeResult
}

enum class ConnectionProbeFailure {
    NO_ACTIVE_TUNNEL,
    PROXY_UNAVAILABLE,
    REQUEST_FAILED,
    INVALID_ENDPOINT,
    SESSION_CHANGED,
}

internal suspend fun probeConnection(
    settings: PingSettings,
    snapshot: () -> VpnRuntimeSnapshot,
    testProxy: suspend (ActiveProxySession, PingSettings) -> PingResult,
): ConnectionProbeResult {
    val runtime = snapshot()
    if (runtime.state != VpnConnectionState.CONNECTED) {
        return ConnectionProbeResult.Failure(ConnectionProbeFailure.NO_ACTIVE_TUNNEL)
    }
    val session = runtime.proxySession ?: return ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE)
    val probeSettings = if (settings.method.proxy) settings else settings.copy(method = PingMethod.PROXY_GET)
    try {
        probeSettings.validate()
    } catch (_: IllegalArgumentException) {
        return ConnectionProbeResult.Failure(ConnectionProbeFailure.INVALID_ENDPOINT)
    }

    val result = try {
        val measured = testProxy(session, probeSettings)
        measured.latencyMs?.let { ConnectionProbeResult.Success(it) }
            ?: ConnectionProbeResult.Failure(ConnectionProbeFailure.REQUEST_FAILED, measured.error)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: ProbeFailure) {
        ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE, failed.message)
    } catch (_: Exception) {
        ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE, "Could not start connection diagnostics")
    }
    currentCoroutineContext().ensureActive()
    val current = snapshot()
    return if (current.state == VpnConnectionState.CONNECTED && current.proxySession === session) result
    else ConnectionProbeResult.Failure(ConnectionProbeFailure.SESSION_CHANGED)
}
