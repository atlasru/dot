package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingStatus

enum class DiagnosticBatchPhase {
    RUNNING, CANCELLING, COMPLETED, CANCELLED, FAILED,
}

data class DiagnosticBatch(
    val groupId: String,
    val nodeIds: Set<String>,
    val results: Map<String, PingResult> = emptyMap(),
    val phase: DiagnosticBatchPhase = DiagnosticBatchPhase.RUNNING,
    private val retestedNodeIds: Set<String> = emptySet(),
) {
    val running get() = phase == DiagnosticBatchPhase.RUNNING || phase == DiagnosticBatchPhase.CANCELLING
    internal val counts: DiagnosticBatchCounts
        get() {
            val byStatus = results.values.groupingBy(PingResult::status).eachCount()
            return DiagnosticBatchCounts(
                ok = byStatus[PingStatus.OK] ?: 0,
                failed = byStatus[PingStatus.FAIL] ?: 0,
                unavailable = byStatus[PingStatus.UNAVAILABLE] ?: 0,
            )
        }

    internal fun record(id: String, result: PingResult): DiagnosticBatch =
        if (phase == DiagnosticBatchPhase.RUNNING && id in nodeIds && id !in results) {
            copy(results = results + (id to result))
        } else this

    internal fun uncheckedLabel(id: String): String? = when {
        running || id !in nodeIds || id in results || id in retestedNodeIds -> null
        phase == DiagnosticBatchPhase.CANCELLED -> "CANCELLED"
        else -> "SKIPPED"
    }

    internal fun supersedeUnchecked(id: String): DiagnosticBatch =
        if (uncheckedLabel(id) == null) this else copy(retestedNodeIds = retestedNodeIds + id)
}

internal data class DiagnosticBatchCounts(val ok: Int, val failed: Int, val unavailable: Int) {
    val checked get() = ok + failed + unavailable
}
