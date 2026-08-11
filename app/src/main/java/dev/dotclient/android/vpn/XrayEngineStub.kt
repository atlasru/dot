package dev.dotclient.android.vpn

import dev.dotclient.android.core.model.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Integration seam for libXray.
 *
 * Milestone 0.0.1 intentionally does not establish a TUN interface until the native AAR is wired in.
 * This prevents a half-configured VPN from blackholing the device's traffic.
 */
class XrayEngineStub : TunnelEngine {
    private val mutableState = MutableStateFlow<EngineState>(EngineState.Stopped)
    override val state: StateFlow<EngineState> = mutableState

    override suspend fun start(profile: VlessProfile, tunFd: Int): Result<Unit> {
        mutableState.value = EngineState.Failed("libXray is not bundled in this milestone")
        return Result.failure(IllegalStateException("libXray is not bundled in this milestone"))
    }

    override suspend fun stop() {
        mutableState.value = EngineState.Stopped
    }
}
