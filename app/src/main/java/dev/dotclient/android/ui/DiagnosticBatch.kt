package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.PingResult

enum class DiagnosticBatchPhase {
    RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED,
}

data class DiagnosticBatch(
    val groupId: String,
    val nodeIds: Set<String>,
    val results: Map<String, PingResult> = emptyMap(),
    val phase: DiagnosticBatchPhase = DiagnosticBatchPhase.RUNNING,
) {
    val running get() = phase == DiagnosticBatchPhase.RUNNING || phase == DiagnosticBatchPhase.CANCELLING

    internal fun record(id: String, result: PingResult): DiagnosticBatch =
        if (running && id in nodeIds && id !in results) copy(results = results + (id to result)) else this
}

internal val PingResult.unavailable: Boolean
    get() = error?.let {
        it.startsWith("HEAD unsupported") || it.startsWith("ICMP unavailable") ||
            it.startsWith("TCP does not test Hysteria2")
    } == true
