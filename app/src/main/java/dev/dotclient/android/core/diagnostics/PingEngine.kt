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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PingEngine(private val context: Context) {
    suspend fun test(
        nodes: List<ProxyNode>,
        settings: PingSettings,
        activeTunnel: Boolean = false,
        onResult: suspend (ProxyNode, PingResult) -> Unit = { _, _ -> },
    ): Map<String, PingResult> {
        settings.validate()
        if (activeTunnel) {
            require(nodes.size == 1 && settings.method.proxy) { "An active tunnel probe requires one node and HTTP" }
            val node = nodes.single()
            val result = measure(settings, active = true) { httpAttempt(settings, Proxy.NO_PROXY) }
            onResult(node, result)
            return mapOf(node.id to result)
        }

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
            val failure = testBatch(valid, settings, ::report)
            if (failure != null) {
                for (node in valid.filterNot { results.containsKey(it.id) }) {
                    report(node, failedProbe(settings, failure))
                }
            }
        }
        return results
    }

    private suspend fun testBatch(
        nodes: List<ProxyNode>,
        settings: PingSettings,
        onResult: suspend (ProxyNode, PingResult) -> Unit,
    ): String? = withContext(Dispatchers.Main) {
        val id = nextRequest.incrementAndGet()
        val byId = nodes.associateBy { it.id }
        val responses = Channel<Message>(nodes.size + 1, onUndeliveredElement = Message::recycle)
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
                    putString("nodes", JSONArray(nodes.map { node ->
                        JSONObject().put("id", node.id).apply {
                            if (settings.method.proxy) put("uri", node.rawUri)
                            else put("host", node.host).put("port", node.port)
                                .put("hysteria2", node.config is ProxyConfig.Hysteria2)
                        }
                    }).toString())
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
            while (completed <= nodes.size) {
                val response = withTimeoutOrNull(budget) { responses.receiveCatching() }
                if (response == null) {
                    Log.w("DotDiagnostics", "Diagnostics batch exceeded its deadline")
                    return@withContext "Diagnostics batch timed out"
                }
                val message = response.getOrNull() ?: return@withContext "Diagnostics service stopped"
                try {
                    when (message.what) {
                        PingService.RESULT -> {
                            val node = byId[message.data.getString("node")]
                            val payload = message.data.getString("result")
                            if (node != null && payload != null) {
                                onResult(node, PingResult.from(JSONObject(payload)))
                                completed++
                            }
                        }
                        PingService.FINISHED -> return@withContext if (completed == nodes.size) null
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
    PingResult(settings, null, System.currentTimeMillis(), false, error, emptyList())

internal suspend fun measure(
    settings: PingSettings,
    active: Boolean = false,
    attempt: suspend () -> Long,
): PingResult {
    val samples = mutableListOf<Long>()
    var error: String? = null
    repeat(settings.attempts) {
        currentCoroutineContext().ensureActive()
        try {
            samples += attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: IOException) {
            error = failed.message ?: "Diagnostics failed"
        } catch (_: Exception) {
            error = "Diagnostics failed"
        }
    }
    currentCoroutineContext().ensureActive()
    val success = samples.size == settings.attempts
    return PingResult(settings, if (success) aggregate(samples, settings.aggregation) else null,
        System.currentTimeMillis(), success, error, samples, active)
}

internal fun loopbackProxy(port: Int): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))

internal suspend fun httpAttempt(settings: PingSettings, proxy: Proxy): Long =
    withTimeoutOrNull(settings.timeoutSeconds * 1000L) { executeHttp(settings, proxy) }
        ?: throw IOException("HTTP request timed out")

private suspend fun executeHttp(settings: PingSettings, proxy: Proxy): Long =
    suspendCancellableCoroutine { continuation ->
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(settings.timeoutSeconds.toLong(), TimeUnit.SECONDS)
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
                if (continuation.isActive) continuation.resumeWithException(IOException("HTTP request failed or timed out (DNS, TLS or proxy)"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (settings.method == PingMethod.PROXY_HEAD && it.code == 405) {
                            throw ProbeResponseException("HEAD unsupported by test server (405); use GET; proxy health unknown")
                        }
                        if (!it.isSuccessful) throw ProbeResponseException("Test URL returned HTTP ${it.code}")
                        if (settings.method == PingMethod.PROXY_GET) {
                            val source = it.body.source()
                            var size = 0L
                            val buffer = okio.Buffer()
                            while (true) {
                                val read = source.read(buffer, 8192)
                                if (read < 0) break
                                size += read
                                buffer.clear()
                                if (size > 1_048_576) throw ProbeResponseException("Test response exceeds 1 MiB; use a small URL")
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(elapsedMs(start))
                } catch (e: Exception) {
                    val error = e as? ProbeResponseException ?: IOException("HTTP response failed or timed out")
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    cleanup()
                }
            }
        })
    }

private class ProbeResponseException(message: String) : IOException(message)

internal fun elapsedMs(start: Long): Long = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
