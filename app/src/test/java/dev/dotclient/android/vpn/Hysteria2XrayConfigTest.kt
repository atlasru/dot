package dev.dotclient.android.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Hysteria2XrayConfigTest {
    @Test
    fun udpModelLabelPreservesExistingXrayOutboundJson() {
        val uri = "hysteria2://test-user%3Atest-password@hy2.example:8443?sni=tls.example&insecure=1&alpn=h3,h2&congestion=bbr&up=20&down=100&obfs=salamander&obfs-password=test-mask&packetSize=512-1200"
        // Full pre-fix JSON contract; transport metadata must never become network=udp.
        val expected = JSONObject("""
            {
              "log": {"loglevel": "warning"},
              "outbounds": [
                {
                  "tag": "proxy", "protocol": "hysteria",
                  "settings": {"version": 2, "address": "hy2.example", "port": 8443},
                  "streamSettings": {
                    "method": "hysteria", "security": "tls",
                    "tlsSettings": {"serverName": "tls.example", "allowInsecure": true, "alpn": ["h3", "h2"]},
                    "hysteriaSettings": {"version": 2, "auth": "test-user:test-password", "up": "20", "down": "100"},
                    "finalmask": {
                      "quicParams": {"congestion": "bbr"},
                      "udp": [{"type": "salamander", "settings": {"password": "test-mask", "packetSize": {"from": 512, "to": 1200}}}]
                    }
                  }
                },
                {"tag": "direct", "protocol": "freedom"}
              ]
            }
        """.trimIndent())
        assertEquals(jsonTree(expected), jsonTree(Hysteria2XrayConfig.fromUri(uri)))
    }

    private fun jsonTree(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonTree(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonTree(value.get(it)) }
        else -> value
    }

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
