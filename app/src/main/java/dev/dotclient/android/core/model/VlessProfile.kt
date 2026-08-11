package dev.dotclient.android.core.model

import java.util.UUID

data class VlessProfile(
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
) {
    enum class Security { NONE, TLS, REALITY }
    enum class Transport { TCP, WS, GRPC, XHTTP, HTTPUPGRADE, UNKNOWN }
}
