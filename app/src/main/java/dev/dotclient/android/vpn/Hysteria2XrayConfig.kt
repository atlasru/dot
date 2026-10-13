package dev.dotclient.android.vpn

import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.parser.Hysteria2UriParser
import org.json.JSONArray
import org.json.JSONObject

/** Client outbound shared by the VPN service and the Xray-backed URL test. */
object Hysteria2XrayConfig {
    fun fromUri(rawUri: String): JSONObject {
        val node = Hysteria2UriParser.parse(rawUri).getOrThrow()
        val hy2 = node.config as ProxyConfig.Hysteria2
        val tls = JSONObject()
        hy2.sni?.let { tls.put("serverName", it) }
        if (hy2.insecure) tls.put("allowInsecure", true)
        if (hy2.alpn.isNotEmpty()) tls.put("alpn", JSONArray(hy2.alpn))
        val settings = JSONObject().put("version", 2).put("address", node.host).put("port", node.port)
        val hysteria = JSONObject().put("version", 2).put("auth", hy2.auth)
        hy2.up?.let { hysteria.put("up", it) }
        hy2.down?.let { hysteria.put("down", it) }
        val stream = JSONObject().put("method", "hysteria").put("security", "tls")
            .put("tlsSettings", tls).put("hysteriaSettings", hysteria)
        val finalmask = JSONObject()
        hy2.congestion?.let {
            finalmask.put("quicParams", JSONObject().put("congestion", it))
        }
        hy2.salamanderPassword?.let { password ->
            val maskSettings = JSONObject().put("password", password)
            hy2.packetSize?.let { maskSettings.put("packetSize", it) }
            finalmask.put("udp", JSONArray().put(JSONObject()
                .put("type", "salamander").put("settings", maskSettings)))
        }
        if (finalmask.length() > 0) stream.put("finalmask", finalmask)
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "hysteria")
            .put("settings", settings).put("streamSettings", stream)
        return JSONObject().put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject()
                .put("tag", "direct").put("protocol", "freedom")))
            .put("routing", JSONObject().put("rules", JSONArray().put(JSONObject()
                .put("type", "field").put("outboundTag", "proxy"))))
    }
}
