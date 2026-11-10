package dev.dotclient.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnUnderlayTest {
    private val wifi = UnderlayCapabilities(validated = true, cellular = false)
    private val mobile = UnderlayCapabilities(validated = true, cellular = true)

    @Test
    fun legacySelectionUsesThePhysicalDefaultWhenAvailable() {
        val networks = mapOf("wifi" to wifi, "mobile" to mobile)
        assertEquals("mobile", selectLegacyUnderlay(networks, active = "mobile", previous = "wifi"))
        assertEquals("wifi", selectLegacyUnderlay(networks, active = "vpn", previous = "mobile"))
    }

    @Test
    fun legacySelectionFallsBackToValidatedMobileWhenWifiHasNoInternet() {
        val networks = mapOf("wifi" to wifi.copy(validated = false), "mobile" to mobile)
        assertEquals("mobile", selectLegacyUnderlay(networks, active = "vpn", previous = "wifi"))
        assertNull(selectLegacyUnderlay(emptyMap(), active = "vpn", previous = "wifi"))
    }

    @Test
    fun seamlessHandoffRestartsSessionsBoundToTheOldNetwork() {
        assertEquals(UnderlayAction.RECONNECT, underlayAction("mobile", "wifi", false, true))
        assertEquals(UnderlayAction.RECONNECT, underlayAction("wifi", "mobile", false, false))
        assertEquals(UnderlayAction.KEEP, underlayAction("wifi", "wifi", false, true))
    }

    @Test
    fun outageUsesRecoveryPreferenceAndWaitingConnectionResumes() {
        assertEquals(UnderlayAction.WAIT, underlayAction(null, "wifi", false, true))
        assertEquals(UnderlayAction.DISCONNECT, underlayAction(null, "wifi", false, false))
        assertEquals(UnderlayAction.RECONNECT, underlayAction("mobile", "wifi", true, true))
        assertEquals(UnderlayAction.RECONNECT, underlayAction("wifi", null, true, false))
        assertEquals(UnderlayAction.KEEP, underlayAction("wifi", null, false, true))
    }
}
