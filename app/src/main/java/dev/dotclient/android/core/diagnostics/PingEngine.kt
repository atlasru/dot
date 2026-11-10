package dev.dotclient.android.core.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.vpn.ActiveProxySession
import dev.dotclient.android.vpn.VpnRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PingEngine(private val context: Context) {
    suspend fun test(
        nodes: List<ProxyNode>,
        settings: PingSettings,
        onResult: suspend (ProxyNode, PingResult) -> Unit = { _, _ -> },
    ): Map<String, PingResult> {
        settings.validate()

        val results = linkedMapOf<String, PingResult>()
        suspend fun report(node: ProxyNode, result: PingResult) {
            currentCoroutineContext().ensureActive()
            if (results.putIfAbsent(node.id, result) == null) onResult(node, result)
        }

        for (batch in nodes.distinctBy { it.id }.chunked(settings.parallelism)) {
            val valid = batch.filter { !settings.method.proxy || it.rawUri.length <= 16_384 }
            for (node in batch - valid.toSet()) {
                report(node, failedProbe(settings, "Node URI exceeds diagnostic limit"))
            }
            if (valid.isEmpty()) continue
            val byId = valid.associateBy(ProxyNode::id)
            val requests = valid.map { node ->
                JSONObject().put("id", node.id).apply {
                    if (settings.method.proxy) put("uri", node.rawUri)
                    else put("host", node.host).put("port", node.port)
                        .put("hysteria2", node.config is ProxyConfig.Hysteria2)
                }
            }
            val failure = testBatch(requests, settings) { id, result, _ -> report(byId.getValue(id), result) }
            if (failure != null) {
                for (node in valid.filterNot { results.containsKey(it.id) }) {
                    report(node, failedProbe(settings, failure))
                }
            }
        }
        return results
    }

    internal suspend fun testConnection(settings: PingSettings): ConnectionProbeResult =
        probeConnection(settings, { VpnRuntime.state.value }, ::testActiveProxy)

    private suspend fun testActiveProxy(session: ActiveProxySession, settings: PingSettings): PingResult {
        val request = JSONObject().put("id", "connection").put("outbound", JSONObject(session.outboundJson))
        var result: PingResult? = null
        val failure = testBatch(listOf(request), settings, session) { _, measured, proxySetupFailed ->
            if (proxySetupFailed) throw ProbeFailure(measured.error ?: "Could not prepare the active proxy")
            result = measured
        }
        if (failure != null) throw ProbeFailure(failure)
        return result ?: throw ProbeFailure("Diagnostics service returned no connection result")
    }

    private suspend fun testBatch(
        requests: List<JSONObject>,
        settings: PingSettings,
        session: ActiveProxySession? = null,
        onResult: suspend (String, PingResult, Boolean) -> Unit,
    ): String? = withContext(Dispatchers.Main) {
        val id = nextRequest.incrementAndGet()
        val nodeIds = requests.mapTo(hashSetOf()) { it.getString("id") }
        val responses = Channel<Message>(requests.size + 1, onUndeliveredElement = Message::recycle)
        var remote: Messenger? = null
        val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.arg1 == id) {
                val response = Message.obtain(message)
                if (responses.trySend(response).isFailure) response.recycle()
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val messenger = Messenger(binder)
                remote = messenger
                val message = Message.obtain(null, PingService.START, id, 0)
                message.replyTo = reply
                message.data = Bundle().apply {
                    putString("settings", settings.json().toString())
                    putParcelable("underlay", if (session == null) VpnRuntime.underlyingNetwork else session.underlay)
                    putString("active_proxy_dns", session?.dns)
                    putString("nodes", JSONArray(requests).toString())
                }
                runCatching { messenger.send(message) }.onFailure { responses.close() }
            }

            override fun onServiceDisconnected(name: ComponentName?) { responses.close() }
            override fun onBindingDied(name: ComponentName?) { responses.close() }
            override fun onNullBinding(name: ComponentName?) { responses.close() }
        }
        val bound = context.bindService(Intent(context, PingService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            return@withContext "Diagnostics service unavailable"
        }

        try {
            val budget = (settings.timeoutSeconds * settings.attempts + 15) * 1000L
            var completed = 0
            while (completed <= requests.size) {
                val response = withTimeoutOrNull(budget) { responses.receiveCatching() }
                if (response == null) {
                    Log.w("DotDiagnostics", "Diagnostics batch exceeded its deadline")
                    return@withContext "Diagnostics batch timed out"
                }
                val message = response.getOrNull() ?: return@withContext "Diagnostics service stopped"
                try {
                    when (message.what) {
                        PingService.RESULT -> {
                            val node = message.data.getString("node")
                            val payload = message.data.getString("result")
                            if (node != null && node in nodeIds && payload != null) {
                                onResult(node, PingResult.from(JSONObject(payload)), message.arg2 == PingService.PROXY_SETUP_FAILED)
                                completed++
                            }
                        }
                        PingService.FINISHED -> return@withContext if (completed == requests.size) null
                            else "Diagnostics service returned incomplete results"
                        PingService.FAILED -> return@withContext "Diagnostics service could not run the test"
                    }
                } finally {
                    message.recycle()
                }
            }
            "Diagnostics service returned duplicate results"
        } finally {
            runCatching { remote?.send(Message.obtain(null, PingService.CANCEL, id, 0)) }
            context.unbindService(connection)
            responses.cancel()
        }
    }

    private companion object { val nextRequest = AtomicInteger() }
}

internal fun failedProbe(settings: PingSettings, error: String): PingResult =
    failedProbe(settings, ProbeFailure(error))

internal fun failedProbe(settings: PingSettings, failure: ProbeFailure): PingResult =
    PingResult(settings, null, System.currentTimeMillis(), failure.status, failure.message, emptyList())

internal suspend fun measure(
    settings: PingSettings,
    attempt: suspend () -> Long,
): PingResult {
    val samples = mutableListOf<Long>()
    var failure: ProbeFailure? = null
    repeat(settings.attempts) {
        currentCoroutineContext().ensureActive()
        try {
            val latency = attempt()
            if (latency < 0) throw ProbeFailure("Invalid measured latency")
            samples += latency
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            val current = failed as? ProbeFailure ?: ProbeFailure("Diagnostics failed")
            if (failure?.status != PingStatus.FAIL || current.status == PingStatus.FAIL) failure = current
        }
    }
    currentCoroutineContext().ensureActive()
    val latency = if (samples.size == settings.attempts) aggregate(samples, settings.aggregation) else null
    return PingResult(settings, latency, System.currentTimeMillis(),
        if (latency != null) PingStatus.OK else failure?.status ?: PingStatus.UNAVAILABLE,
        failure?.message, samples)
}

internal fun loopbackProxy(port: Int): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

internal suspend fun httpAttempt(settings: PingSettings, proxy: Proxy): Long {
    val phase = AtomicReference(HttpProbePhase.HTTP)
    return withTimeoutOrNull(settings.timeoutSeconds * 1000L) { executeHttp(settings, proxy, phase) }
        ?: throw ProbeFailure(phase.get().timeout)
}

private suspend fun executeHttp(settings: PingSettings, proxy: Proxy, phase: AtomicReference<HttpProbePhase>): Long =
    suspendCancellableCoroutine { continuation ->
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun dnsStart(call: Call, domainName: String) { phase.set(HttpProbePhase.DNS) }
                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    phase.set(HttpProbePhase.CONNECT)
                }
                override fun secureConnectStart(call: Call) { phase.set(HttpProbePhase.TLS) }
                override fun requestHeadersStart(call: Call) { phase.set(HttpProbePhase.HTTP) }
            })
            .build()
        val method = if (settings.method == PingMethod.PROXY_HEAD) "HEAD" else "GET"
        val request = Request.Builder().url(settings.url).method(method, null).header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        val start = System.nanoTime()
        fun cleanup() {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(httpFailure(e, phase.get(), proxy.type() == Proxy.Type.HTTP))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (settings.method == PingMethod.PROXY_HEAD && it.code == 405) {
                            throw ProbeFailure("HEAD unsupported by test server (405); use GET")
                        }
                        if (!it.isSuccessful) throw ProbeFailure("Test URL returned HTTP ${it.code}")
                        if (settings.method == PingMethod.PROXY_GET) {
                            val source = it.body.source()
                            var size = 0L
                            val buffer = okio.Buffer()
                            while (true) {
                                val read = source.read(buffer, 8192)
                                if (read < 0) break
                                size += read
                                buffer.clear()
                                if (size > 1_048_576) throw ProbeFailure("Test response exceeds 1 MiB; use a small URL")
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(elapsedMs(start))
                } catch (e: Exception) {
                    val error = httpFailure(e, HttpProbePhase.HTTP, proxy.type() == Proxy.Type.HTTP)
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    cleanup()
                }
            }
        })
    }

internal fun elapsedMs(start: Long): Long = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
