package dev.dotclient.android.ui

import android.content.Context
import dev.dotclient.android.core.diagnostics.PingEngine
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.settings.SettingsRepository
import dev.dotclient.android.vpn.VpnRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class NodeDiagnostics(context: Context, private val state: MutableStateFlow<DotUiState>, private val repository: SettingsRepository) {
    private val engine=PingEngine(context)
    private var job: Job?=null
    fun cancel() { job?.cancel() }
    suspend fun run(nodes: List<ProxyNode>, connection: Boolean = false) {
        if(nodes.isEmpty() || job?.isActive==true) return
        val current=state.value
        val active=current.vpnConnected
        val settings=current.settings.ping
        if(current.loading || current.vpnBusy || (active && (nodes.size!=1 || nodes.single().id!=VpnRuntime.state.value.nodeId || !settings.method.proxy))) {
            state.update { it.copy(message="Disconnect before diagnosing other nodes or using TCP/ICMP") }; return
        }
        if(active && !VpnRuntime.state.value.appInTunnel) { state.update { it.copy(message="dot is outside the active split tunnel; disconnect for reliable diagnostics") }; return }
        if(active && settings.url!=dev.dotclient.android.core.diagnostics.PingSettings().url) { state.update { it.copy(message="Disconnect to diagnose custom URLs; active-tunnel tests use the default public HTTPS endpoint to avoid direct/LAN routes") }; return }
        val ids=nodes.map {it.id}.toSet()
        job=currentCoroutineContext()[Job]
        state.update { it.copy(testingNodeIds=ids,connectionTestRunning=connection,message=null) }
        try {
            val results=engine.test(nodes,settings,active)
            if(state.value.settings.ping!=settings) return
            state.update { old ->
                val combined=old.pingResults+results
                old.copy(pingResults=combined,nodeLatenciesMs=combined.mapNotNull {(id,r)->r.latencyMs?.let {id to it}}.toMap(),nodeLatencyFailedIds=combined.filterValues {!it.success && it.error?.startsWith("HEAD unsupported")!=true}.keys,
                    connectionTestLatencyMs=if(connection) results.values.firstOrNull()?.latencyMs else old.connectionTestLatencyMs,
                    connectionTestError=if(connection) results.values.firstOrNull()?.error else old.connectionTestError,
                    message=results.values.firstOrNull {!it.success}?.error ?: "Diagnostics complete · ${settings.method.label}")
            }
            withContext(Dispatchers.IO) { repository.saveResults(state.value.pingResults) }
        } catch(e: CancellationException) { throw e } catch(e: Exception) { state.update { it.copy(message="Diagnostics unavailable; verify node configuration and platform support") } }
        finally { state.update { it.copy(testingNodeIds=emptySet(),pendingDelaySortSubscriptionId=null,connectionTestRunning=false) }; job=null }
    }
}
