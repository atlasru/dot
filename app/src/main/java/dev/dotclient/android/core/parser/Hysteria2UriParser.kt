package dev.dotclient.android.core.parser

import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.model.ProxyNode
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object Hysteria2UriParser {
    fun parse(raw: String): Result<ProxyNode> = runCatching {
        val value = raw.trim()
        require(value.startsWith("hy2://", true) || value.startsWith("hysteria2://", true)) {
            "Not a Hysteria2 URI"
        }
        val uri = URI(value)
        val authority = uri.rawAuthority ?: error("Hysteria2 host is missing")
        val hostPort = authority.substringAfterLast('@')
        val host = (uri.host ?: if (hostPort.startsWith('[')) hostPort.substringAfter('[').substringBefore(']')
            else hostPort.substringBefore(':')).trim('[', ']')
        require(host.isNotBlank()) { "Hysteria2 host is missing" }
        val explicitPort = if (hostPort.startsWith('[')) hostPort.substringAfter(']', "").removePrefix(":")
            else hostPort.substringAfter(':', "")
        require(explicitPort.isEmpty() || explicitPort.toIntOrNull() != null) { "Invalid Hysteria2 port" }
        val port = if (uri.port == -1) 443 else uri.port
        require(port in 1..65535) { "Invalid Hysteria2 port" }
        val auth = uri.rawUserInfo?.decode().orEmpty()
        require(auth.isNotEmpty()) { "Hysteria2 authentication is missing" }
        val query = uri.rawQuery?.split('&')?.associate { part ->
            part.substringBefore('=').decode() to part.substringAfter('=', "").decode()
        }.orEmpty()
        fun option(vararg keys: String) = keys.firstNotNullOfOrNull { query[it]?.takeIf(String::isNotEmpty) }
        val insecure = when (option("insecure", "allowInsecure")?.lowercase()) {
            null, "0", "false" -> false
            "1", "true" -> true
            else -> error("Invalid Hysteria2 insecure flag")
        }
        val mask = option("obfs", "finalmask", "mask")
        require(mask == null || mask.equals("salamander", true)) { "Unsupported Hysteria2 mask" }
        val password = option("obfs-password", "obfsPassword", "salamanderPassword")
        val packetSize = option("packetSize", "packet_size")
        require(mask == null || password != null) { "Salamander password is missing" }
        require(mask != null || (password == null && packetSize == null)) {
            "Salamander settings require obfs=salamander"
        }
        packetSize?.let { size ->
            val bounds = size.split('-').map(String::toIntOrNull)
            require(bounds.size in 1..2 && bounds.all { it != null && it in 1..2048 } &&
                (bounds.size == 1 || bounds[0]!! <= bounds[1]!!)) { "Invalid Salamander packet size" }
        }
        val congestion = option("congestion")
        require(congestion == null || congestion in setOf("bbr", "reno", "brutal", "force-brutal")) {
            "Unsupported Hysteria2 congestion"
        }
        val sni = option("sni", "serverName", "peer")
        val alpn = option("alpn")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        ProxyNode(
            name = uri.rawFragment?.decode()?.ifBlank { null } ?: "$host:$port",
            host = host, port = port, userId = "", security = ProxyNode.Security.TLS,
            rawUri = value,
            config = ProxyConfig.Hysteria2(auth, sni, insecure, alpn, congestion,
                option("up", "upmbps"), option("down", "downmbps"), password, packetSize),
        )
    }

    private fun String.decode(): String = URLDecoder.decode(replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
