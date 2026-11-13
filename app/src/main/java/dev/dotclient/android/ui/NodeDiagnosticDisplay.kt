package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingStatus

internal data class NodeDiagnosticDisplay(
    val badge: String,
    val detail: String? = null,
    val result: PingResult? = null,
)

internal fun DotUiState.nodeDiagnosticDisplay(id: String, untestedLabel: String = "--"): NodeDiagnosticDisplay {
    if (id in testingNodeIds) return NodeDiagnosticDisplay("··")
    val unchecked = uncheckedDiagnosticLabel(selectedSubscriptionId, id)
    if (unchecked != null) return NodeDiagnosticDisplay(unchecked)

    val result = pingResults[id] ?: return NodeDiagnosticDisplay(untestedLabel)
    val badge = when (result.status) {
        PingStatus.OK -> "${result.latencyMs} ms"
        PingStatus.FAIL -> "FAIL"
        PingStatus.UNAVAILABLE -> "UNAVAILABLE"
    }
    val detail = if (settings.showDetailedTestErrors && result.status != PingStatus.OK) {
        result.error?.trim()?.takeIf { it.isNotEmpty() && !it.equals(badge, ignoreCase = true) }
    } else null
    return NodeDiagnosticDisplay(badge, detail, result)
}
