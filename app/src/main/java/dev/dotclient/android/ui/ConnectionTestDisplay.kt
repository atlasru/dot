package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.ConnectionProbeFailure
import dev.dotclient.android.core.diagnostics.ConnectionProbeResult

internal data class ConnectionTestDisplay(
    val label: String? = null,
    val advice: String? = null,
    val detail: String? = null,
    val failed: Boolean = false,
)

internal fun DotUiState.connectionTestDisplay(): ConnectionTestDisplay {
    if (connectionTestRunning) return ConnectionTestDisplay(label = "TESTING…")
    return when (val result = connectionTestResult) {
        null -> ConnectionTestDisplay()
        is ConnectionProbeResult.Success -> ConnectionTestDisplay(label = "${result.latencyMs} ms via proxy")
        is ConnectionProbeResult.Failure -> {
            val (label, advice) = when (result.reason) {
                ConnectionProbeFailure.NO_ACTIVE_TUNNEL -> "VPN INACTIVE" to "Connect to a VPN node and retry."
                ConnectionProbeFailure.PROXY_UNAVAILABLE -> "PROXY UNAVAILABLE" to "Reconnect the VPN and retry."
                ConnectionProbeFailure.REQUEST_FAILED -> "FAILED" to
                    "Check the Test URL in Settings or try another VPN node."
                ConnectionProbeFailure.INVALID_ENDPOINT -> "INVALID TEST URL" to
                    "Set a valid HTTPS Test URL in Settings under Ping & Diagnostics."
                ConnectionProbeFailure.SESSION_CHANGED -> "VPN CHANGED" to "Wait for the VPN to connect, then retry."
            }
            ConnectionTestDisplay(label, advice, result.detail.takeIf { settings.showDetailedTestErrors }, failed = true)
        }
    }
}
