package dev.dotclient.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Hysteria2XrayConfigTest {
    @Test
    fun hysteria2UsesDefaultOutboundWithoutInvalidRoutingRule() {
        val config = Hysteria2XrayConfig.fromUri("hy2://pass@example.com:443?sni=example.com&alpn=h3")
        assertFalse(config.has("routing"))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("proxy", outbound.getString("tag"))
        assertEquals("hysteria", outbound.getString("protocol"))
        assertEquals(2, outbound.getJSONObject("settings").getInt("version"))
        assertEquals("pass", outbound.getJSONObject("streamSettings")
            .getJSONObject("hysteriaSettings").getString("auth"))
    }

    @Test
    fun salamanderPacketSizeRangeUsesXrayObjectShape() {
        val config = Hysteria2XrayConfig.fromUri(
            "hy2://secret@example.com:8443?obfs=salamander&obfs-password=mask&packetSize=512-1200"
        )
        val bounds = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("finalmask")
            .getJSONArray("udp").getJSONObject(0).getJSONObject("settings")
            .getJSONObject("packetSize")
        assertEquals(512, bounds.getInt("from"))
        assertEquals(1200, bounds.getInt("to"))
    }

    @Test
    fun singlePacketSizeUsesEqualBounds() {
        val config = Hysteria2XrayConfig.fromUri(
            "hysteria2://secret@example.com?obfs=salamander&obfs-password=mask&packetSize=900"
        )
        val bounds = config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("finalmask")
            .getJSONArray("udp").getJSONObject(0).getJSONObject("settings")
            .getJSONObject("packetSize")
        assertEquals(900, bounds.getInt("from"))
        assertEquals(900, bounds.getInt("to"))
    }
}
