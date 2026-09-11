package dev.dotclient.android.vpn

import android.net.VpnService
import dev.dotclient.android.core.splittunnel.SplitTunnelConfig
import dev.dotclient.android.core.splittunnel.SplitTunnelMode

internal fun VpnService.Builder.applySplitTunnel(config: SplitTunnelConfig): VpnService.Builder {
    val packages = config.normalizedPackages

    when (config.mode) {
        SplitTunnelMode.ALL_APPS -> Unit

        SplitTunnelMode.EXCLUDE_SELECTED -> {
            packages.forEach { packageName ->
                runCatching { addDisallowedApplication(packageName) }
            }
        }

        SplitTunnelMode.INCLUDE_ONLY_SELECTED -> {
            require(packages.isNotEmpty()) {
                "Select at least one app before enabling only-selected split tunneling"
            }

            var appliedPackages = 0
            packages.forEach { packageName ->
                runCatching { addAllowedApplication(packageName) }
                    .onSuccess { appliedPackages += 1 }
            }
            require(appliedPackages > 0) {
                "None of the selected split-tunneling apps are currently installed"
            }
        }
    }

    return this
}
