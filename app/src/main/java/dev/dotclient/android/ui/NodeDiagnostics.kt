package dev.dotclient.android.ui

import android.content.Context
import android.util.Log
import dev.dotclient.android.core.diagnostics.ConnectionProbeFailure
import dev.dotclient.android.core.diagnostics.ConnectionProbeResult
import dev.dotclient.android.core.diagnostics.PingEngine
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class NodeDiagnostics(
    context: Context,
    private val state: MutableStateFlow<DotUiState>,
    private val repository: SettingsRepository,
) {
    private val engine = PingEngine(context)
    private var job: Job? = null
    private var testingConnection = false

    fun cancel() {
        val running = job ?: return
        state.update { old ->
            val batch = old.diagnosticBatch
            if (batch?.phase == DiagnosticBatchPhase.RUNNING) {
                old.copy(diagnosticBatch = batch.copy(phase = DiagnosticBatchPhase.CANCELLING))
            } else old
        }
        running.cancel()
    }

    fun cancelConnection() {
        if (testingConnection) job?.cancel()
    }

    suspend fun runConnection() {
        val current = state.value
        if (job != null || current.savingSettings) return
        job = currentCoroutineContext()[Job]
        testingConnection = true
        state.update {
            it.copy(diagnosticsRunning = true, connectionTestRunning = true, connectionTestResult = null)
        }
        try {
            val result = engine.testConnection(current.settings.ping)
            currentCoroutineContext().ensureActive()
            state.update { it.copy(connectionTestResult = result) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            state.update {
                it.copy(connectionTestResult = ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE))
            }
        } finally {
            testingConnection = false
            job = null
            state.update { it.copy(diagnosticsRunning = false, connectionTestRunning = false) }
        }
    }

    suspend fun run(nodes: List<ProxyNode>, groupId: String? = null): Boolean {
        val current = state.value
        if (nodes.isEmpty() || job != null || current.loading || current.savingSettings) return false
        val settings = current.settings.ping
        val ids = nodes.mapTo(hashSetOf()) { it.id }
        val batch = groupId?.let { DiagnosticBatch(it, ids) }
        job = currentCoroutineContext()[Job]
        state.update {
            it.copy(
                diagnosticsRunning = true,
                testingNodeIds = ids,
                diagnosticBatch = batch ?: it.diagnosticBatch,
            )
        }

        var phase = DiagnosticBatchPhase.FAILED
        var completed = 0
        try {
            engine.test(nodes, settings) { node, result ->
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    state.update { old ->
                        val pending = old.testingNodeIds - node.id
                        if (old.settings.ping != settings || old.subscriptions.none { group ->
                                group.profiles.any { it.id == node.id }
                            }
                        ) {
                            return@update old.copy(testingNodeIds = pending)
                        }
                        val nextBatch = if (batch == null) {
                            old.diagnosticBatch?.supersedeUnchecked(node.id)
                        } else old.diagnosticBatch?.record(node.id, result)
                        old.copy(
                            pingResults = old.pingResults + (node.id to result),
                            testingNodeIds = pending,
                            diagnosticBatch = nextBatch,
                        )
                    }
                    completed++
                }
            }
            phase = DiagnosticBatchPhase.COMPLETED
        } catch (cancelled: CancellationException) {
            phase = DiagnosticBatchPhase.CANCELLED
            throw cancelled
        } catch (_: Exception) {
            if (batch == null) state.update { it.copy(message = "Diagnostics unavailable") }
        } finally {
            try {
                if (completed > 0) {
                    val results = state.value.pingResults
                    withContext(NonCancellable + Dispatchers.IO) { repository.saveResults(results) }
                }
            } catch (_: CancellationException) {
                phase = DiagnosticBatchPhase.CANCELLED
            } catch (_: Exception) {
                phase = DiagnosticBatchPhase.FAILED
                Log.w("dot.diagnostics", "Could not save diagnostics")
            } finally {
                job = null
                state.update { old ->
                    val nextBatch = if (batch == null) old.diagnosticBatch else old.diagnosticBatch?.copy(phase = phase)
                    old.copy(
                        diagnosticsRunning = false,
                        testingNodeIds = emptySet(),
                        diagnosticBatch = nextBatch,
                    )
                }
                Log.i("dot.diagnostics", "${settings.method.key} $phase $completed/${ids.size}")
            }
        }
        return phase == DiagnosticBatchPhase.COMPLETED
    }
}
