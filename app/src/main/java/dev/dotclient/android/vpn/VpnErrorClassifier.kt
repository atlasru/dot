package dev.dotclient.android.vpn

enum class VpnFailureCategory(val label: String) {
    NO_NETWORK("no network"),
    DNS("DNS failed"),
    TIMEOUT("connection timed out"),
    REFUSED("connection refused"),
    TLS("TLS handshake failed"),
    REALITY("REALITY handshake failed"),
    TUN("VPN interface failed"),
    CONFIG("invalid VPN configuration"),
    XRAY("Xray failed to start"),
    UNKNOWN("VPN connection failed"),
}

data class VpnFailure(
    val category: VpnFailureCategory,
    val userMessage: String,
    val detail: String,
)

object VpnErrorClassifier {
    fun classify(error: Throwable): VpnFailure {
        val raw = buildString {
            append(error.message.orEmpty())
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 3) {
                if (!cause.message.isNullOrBlank()) append(" · ").append(cause.message)
                cause = cause.cause
                depth++
            }
        }.ifBlank { error.javaClass.simpleName }
        val normalized = raw.lowercase()

        val category = when {
            "network is unreachable" in normalized || "no network" in normalized || "offline" in normalized -> VpnFailureCategory.NO_NETWORK
            "unknownhost" in normalized || "dns" in normalized || "resolve" in normalized -> VpnFailureCategory.DNS
            "timeout" in normalized || "timed out" in normalized || "deadline exceeded" in normalized -> VpnFailureCategory.TIMEOUT
            "refused" in normalized -> VpnFailureCategory.REFUSED
            "reality" in normalized -> VpnFailureCategory.REALITY
            "tls" in normalized || "certificate" in normalized || "handshake" in normalized -> VpnFailureCategory.TLS
            "tun" in normalized || "establish" in normalized -> VpnFailureCategory.TUN
            "config" in normalized || "invalid" in normalized || "convertsharelinks" in normalized -> VpnFailureCategory.CONFIG
            "xray" in normalized || "runxray" in normalized -> VpnFailureCategory.XRAY
            else -> VpnFailureCategory.UNKNOWN
        }

        return VpnFailure(
            category = category,
            userMessage = category.label,
            detail = raw.take(500),
        )
    }
}
