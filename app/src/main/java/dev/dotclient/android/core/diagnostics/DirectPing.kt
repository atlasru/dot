package dev.dotclient.android.core.diagnostics

import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun directProbe(
    host: String,
    port: Int,
    hysteria2: Boolean,
    settings: PingSettings,
    network: Network,
    networkInterface: String?,
): PingResult {
    if (settings.method == PingMethod.TCP && hysteria2) {
        return failedProbe(settings, "TCP does not test Hysteria2 UDP/QUIC; use Via Proxy")
    }
    if (settings.method == PingMethod.ICMP && (networkInterface == null || !pingBinary.canExecute())) {
        return failedProbe(settings, "ICMP unavailable on this device or network; use Via Proxy")
    }

    return measure(settings) {
        val timeoutMs = settings.timeoutSeconds * 1000L
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        withTimeoutOrNull(timeoutMs) {
            val addresses = resolve(network, host)
            if (settings.method == PingMethod.TCP) tcpAttempt(network, addresses, port, deadline)
            else icmpAttempt(addresses.first(), checkNotNull(networkInterface), deadline)
        } ?: throw ProbeFailure("${settings.method.label} timed out")
    }
}

private suspend fun resolve(network: Network, host: String): List<InetAddress> {
    val family = if (':' in host) OsConstants.AF_INET6 else OsConstants.AF_INET
    Os.inet_pton(family, host)?.let { return listOf(it) }
    val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, inlineExecutor, signal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        continuation.resume(answer)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        continuation.resumeWithException(ProbeFailure("DNS resolution failed"))
                    }
                },
            )
        }
    } else {
        suspendCancellableCoroutine { continuation ->
            try {
                val work = legacyResolver.submit {
                    try {
                        continuation.resume(network.getAllByName(host).toList())
                    } catch (_: Exception) {
                        continuation.resumeWithException(ProbeFailure("DNS resolution failed"))
                    }
                }
                // Old platform resolvers may finish after cancellation. The fixed pool bounds those calls.
                continuation.invokeOnCancellation {
                    work.cancel(true)
                    legacyResolver.purge()
                }
            } catch (_: RejectedExecutionException) {
                continuation.resumeWithException(ProbeFailure("Server DNS resolver is busy"))
            }
        }
    }
    if (addresses.isEmpty()) throw ProbeFailure("DNS resolution returned no addresses")
    return addresses
}

private suspend fun tcpAttempt(
    network: Network,
    addresses: List<InetAddress>,
    port: Int,
    deadline: Long,
): Long = withContext(Dispatchers.IO) {
    val start = System.nanoTime()
    var failure = ProbeFailure("Connection timed out")
    for ((index, address) in addresses.withIndex()) {
        val timeout = remainingMillis(deadline) / (addresses.size - index)
        if (timeout <= 0) break
        try {
            Socket().use { socket ->
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { runCatching { socket.close() } }
                    try {
                        if (!address.isLoopbackAddress) network.bindSocket(socket)
                        socket.connect(InetSocketAddress(address, port), timeout.toInt())
                        continuation.resume(Unit)
                    } catch (error: IOException) {
                        continuation.resumeWithException(error)
                    }
                }
            }
            return@withContext elapsedMs(start)
        } catch (error: IOException) {
            // Continue to the next DNS address under the original attempt deadline.
            failure = if (error is SocketTimeoutException) ProbeFailure("Connection timed out")
            else ProbeFailure("TCP connection failed")
        }
    }
    throw failure
}

private val pingBinary = File("/system/bin/ping")
private val ping6Binary = File("/system/bin/ping6")
private val echoTime = Regex("time[=<]([0-9.]+)")
private val inlineExecutor = Executor { it.run() }
private val legacyResolver = ThreadPoolExecutor(
    8, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
    { task -> Thread(task, "dot-diagnostic-dns").apply { isDaemon = true } },
).apply { allowCoreThreadTimeOut(true) }

private suspend fun icmpAttempt(address: InetAddress, networkInterface: String, deadline: Long): Long =
    runInterruptible(Dispatchers.IO) {
        val timeout = remainingMillis(deadline)
        if (timeout <= 0) throw ProbeFailure("ICMP timed out")
        val binary = if (address is Inet6Address && ping6Binary.canExecute()) ping6Binary else pingBinary
        val command = mutableListOf(binary.path)
        if (binary == pingBinary && address is Inet6Address) command += "-6"
        command += listOf("-n", "-c", "1", "-W", ((timeout + 999) / 1000).toString())
        if (!address.isLoopbackAddress) command += listOf("-I", networkInterface)
        command += address.hostAddress ?: throw ProbeFailure("DNS resolution returned no addresses")
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
        } catch (_: IOException) {
            throw ProbeFailure("ICMP unavailable on this device; use Via Proxy")
        }
        try {
            if (!process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS)) throw ProbeFailure("ICMP timed out")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() != 0) {
                if (isIcmpUnavailable(output)) throw ProbeFailure("ICMP unavailable on this device or network; use Via Proxy")
                throw ProbeFailure("ICMP blocked or timed out; proxy health unknown")
            }
            echoTime.find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()?.coerceAtLeast(1)
                ?: throw ProbeFailure("ICMP round trip time unavailable")
        } finally {
            process.destroyForcibly()
        }
    }

internal fun isIcmpUnavailable(output: String): Boolean = listOf(
    "permission denied", "operation not permitted", "protocol not supported",
    "address family not supported", "unknown option", "invalid option",
).any { output.contains(it, ignoreCase = true) }

private fun remainingMillis(deadline: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
