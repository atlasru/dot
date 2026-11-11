package dev.dotclient.android.ui

import dev.dotclient.android.vpn.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeOrbMotionTest {
    @Test
    fun onlyAnActiveConnectionAttemptSpinsTheOrb() {
        assertEquals(
            listOf(VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING),
            VpnConnectionState.entries.filter { it.spinsHomeOrb() },
        )
    }
}
