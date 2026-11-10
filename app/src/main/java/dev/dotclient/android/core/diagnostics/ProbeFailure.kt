package dev.dotclient.android.core.diagnostics

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Only controlled reasons may cross the diagnostics process boundary or be stored. */
internal class ProbeFailure(
    override val message: String,
    val status: PingStatus = PingStatus.UNAVAILABLE,
) : IOException(message)

internal enum class HttpProbePhase(val timeout: String) {
    DNS("DNS resolution timed out"),
    CONNECT("Connection timed out"),
    TLS("TLS handshake timed out"),
    HTTP("HTTP request timed out"),
}

internal fun httpFailure(error: Throwable, phase: HttpProbePhase, throughHttpProxy: Boolean): ProbeFailure {
    if (error is ProbeFailure) return error
    val causes = generateSequence(error) { it.cause }.take(6).toList()
    if (causes.any { it is InterruptedIOException }) return ProbeFailure(phase.timeout)
    if (causes.any { it is UnknownHostException }) return ProbeFailure("DNS resolution failed")

    if (throughHttpProxy && phase == HttpProbePhase.CONNECT) {
        // OkHttp emits this only for a proxy authentication challenge during HTTPS CONNECT.
        if (causes.any { it.message == "Failed to authenticate with proxy" }) {
            return ProbeFailure("HTTP proxy rejected authentication (407)", PingStatus.FAIL)
        }
        val status = causes.firstNotNullOfOrNull { connectStatus.matchEntire(it.message.orEmpty())?.groupValues?.get(1) }
        if (status != null) return ProbeFailure("Proxy CONNECT returned HTTP $status")
    }

    return ProbeFailure(when {
        causes.any { it is NoRouteToHostException } -> "No route to server"
        causes.any { it is ConnectException } -> "Connection failed"
        phase == HttpProbePhase.TLS -> "TLS handshake failed"
        causes.any { it is SSLException } -> "TLS connection failed"
        throughHttpProxy && phase == HttpProbePhase.CONNECT -> "Proxy connection failed"
        else -> "HTTP request failed"
    })
}

private val connectStatus = Regex("Unexpected response code for CONNECT: ([1-5][0-9]{2})")

internal fun coreStartFailure(message: String): ProbeFailure {
    // The pinned Xray config builder emits this stage before it starts the listeners.
    if (message.split(" > ").any { it.removePrefix("infra/conf: ").matches(invalidDiagnosticOutbound) }) {
        return ProbeFailure("Xray could not build the proxy outbound configuration", PingStatus.FAIL)
    }
    return if (message.contains("address already in use", ignoreCase = true)) {
        ProbeFailure("Diagnostics listener address already in use")
    } else {
        ProbeFailure("Diagnostics core could not start")
    }
}

private val invalidDiagnosticOutbound = Regex("failed to build outbound config with tag diagnostic-[0-7]")

internal fun conversionFailure(message: String): ProbeFailure {
    val invalid = message == "unsupported share format" || message == "no valid outbounds" ||
        message == "no valid outbound found" || message.startsWith("no valid outbound found: ") ||
        message.startsWith("unsupported link: ")
    return if (invalid) ProbeFailure("Invalid or unsupported proxy configuration", PingStatus.FAIL)
    else ProbeFailure("Diagnostics could not prepare the proxy outbound")
}
