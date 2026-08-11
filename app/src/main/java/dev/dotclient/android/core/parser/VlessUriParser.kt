package dev.dotclient.android.core.parser

import dev.dotclient.android.core.model.VlessProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

object VlessUriParser {
    fun parse(raw: String): Result<VlessProfile> = runCatching {
        require(raw.startsWith("vless://", ignoreCase = true)) { "Not a VLESS URI" }

        val uri = URI(raw.trim())
        val userInfo = uri.rawUserInfo?.decode().orEmpty()
        require(userInfo.isNotBlank()) { "VLESS UUID is missing" }
        require(runCatching { UUID.fromString(userInfo) }.isSuccess) { "Invalid VLESS UUID" }

        val host = uri.host ?: parseBracketedOrPlainHost(uri.rawAuthority)
        require(!host.isNullOrBlank()) { "VLESS host is missing" }

        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery)

        val security = when (query["security"]?.lowercase()) {
            "reality" -> VlessProfile.Security.REALITY
            "tls" -> VlessProfile.Security.TLS
            else -> VlessProfile.Security.NONE
        }

        val transport = when ((query["type"] ?: "tcp").lowercase()) {
            "tcp", "raw" -> VlessProfile.Transport.TCP
            "ws" -> VlessProfile.Transport.WS
            "grpc" -> VlessProfile.Transport.GRPC
            "xhttp", "splithttp" -> VlessProfile.Transport.XHTTP
            "httpupgrade" -> VlessProfile.Transport.HTTPUPGRADE
            else -> VlessProfile.Transport.UNKNOWN
        }

        VlessProfile(
            name = uri.rawFragment?.decode()?.ifBlank { null } ?: "$host:$port",
            host = host,
            port = port,
            userId = userInfo,
            encryption = query["encryption"],
            flow = query["flow"],
            security = security,
            transport = transport,
            sni = query["sni"] ?: query["serverName"],
            fingerprint = query["fp"] ?: query["fingerprint"],
            publicKey = query["pbk"] ?: query["publicKey"],
            shortId = query["sid"] ?: query["shortId"],
            path = query["path"],
            hostHeader = query["host"],
            serviceName = query["serviceName"],
            rawUri = raw.trim(),
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index < 0) {
                    pair.decode().takeIf { it.isNotBlank() }?.let { it to "" }
                } else {
                    val key = pair.substring(0, index).decode()
                    val value = pair.substring(index + 1).decode()
                    key to value
                }
            }
            .toMap()
    }

    private fun parseBracketedOrPlainHost(authority: String?): String? {
        if (authority.isNullOrBlank()) return null
        val withoutUser = authority.substringAfter('@', authority)
        return if (withoutUser.startsWith("[")) {
            withoutUser.substringAfter('[').substringBefore(']')
        } else {
            withoutUser.substringBefore(':')
        }
    }

    private fun String.decode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
}
