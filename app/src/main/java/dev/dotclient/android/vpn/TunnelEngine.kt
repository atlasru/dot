package dev.dotclient.android.vpn

import dev.dotclient.android.core.model.ProxyNode
import kotlinx.coroutines.flow.StateFlow

interface TunnelEngine {
    val state: StateFlow<EngineState>
    suspend fun start(profile: ProxyNode, tunFd: Int): Result<Unit>
    suspend fun stop()
}

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Running : EngineState
    data class Failed(val message: String) : EngineState
}
