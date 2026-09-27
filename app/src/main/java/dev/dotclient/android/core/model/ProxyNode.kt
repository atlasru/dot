package dev.dotclient.android.core.model

import java.util.UUID

data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val userId: String,
    val encryption: String? = null,
    val flow: String? = null,
    val security: Security = Security.NONE,
    val transport: Transport = Transport.TCP,
    val sni: String? = null,
    val fingerprint: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val path: String? = null,
    val hostHeader: String? = null,
    val serviceName: String? = null,
    val rawUri: String,
    val config: ProxyConfig = ProxyConfig.Vless,
) {
    enum class Security { NONE, TLS, REALITY }
    enum class Transport { TCP, WS, GRPC, XHTTP, HTTPUPGRADE, UNKNOWN }
    val protocol: String get() = when (config) {
        ProxyConfig.Vless -> "VLESS"
        is ProxyConfig.Hysteria2 -> "HY2"
    }
    override fun toString(): String = "ProxyNode(id=$id, name=$name, host=$host, port=$port, protocol=$protocol)"
}

sealed class ProxyConfig {
    data object Vless : ProxyConfig()
    data class Hysteria2(
        val auth: String,
        val sni: String?,
        val insecure: Boolean,
        val alpn: List<String>,
        val congestion: String?,
        val up: String?,
        val down: String?,
        val salamanderPassword: String?,
        val packetSize: String?,
    ) : ProxyConfig() {
        override fun toString(): String = "Hysteria2(config redacted)"
    }
}
