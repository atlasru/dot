package dev.dotclient.android.core.diagnostics

import android.content.*
import android.os.*
import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.model.ProxyNode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PingEngine(private val context: Context) {
    suspend fun test(nodes: List<ProxyNode>, settings: PingSettings, activeTunnel: Boolean = false): Map<String,PingResult> {
        settings.validate()
        if (settings.method.proxy() && !activeTunnel) {
            require(nodes.all { it.rawUri.length <= 16384 }) { "Node URI exceeds diagnostic limit" }
            val results=mutableMapOf<String,PingResult>()
            for(batch in nodes.chunked(settings.parallelism)) results.putAll(isolated(batch,settings))
            return results
        }
        val permits=Semaphore(settings.parallelism)
        return coroutineScope { nodes.map { node -> async { permits.withPermit { node.id to measure(settings,activeTunnel) {
            if (settings.method.proxy()) httpAttempt(settings,null)
            else directAttempt(node,settings)
        } } } }.awaitAll().toMap() }
    }
    private suspend fun isolated(nodes: List<ProxyNode>, settings: PingSettings): Map<String,PingResult> = withContext(Dispatchers.Main) {
        val result=CompletableDeferred<Map<String,PingResult>>()
        var remote: Messenger?=null
        val reply=Messenger(Handler(Looper.getMainLooper()) { msg ->
            if (msg.what==2) {
                runCatching { val json=JSONObject(msg.data.getString("results")!!); json.keys().asSequence().associateWith { PingResult.from(json.getJSONObject(it)) } }
                    .onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
            } else if (msg.what==3) result.completeExceptionally(IOException("Diagnostics core failed; verify node configuration"))
            true
        })
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote=Messenger(binder)
                val message=Message.obtain(null,1)
                message.replyTo=reply
                message.data=Bundle().apply {
                    putString("settings",settings.json().toString())
                    putString("nodes",JSONArray(nodes.map { JSONObject().put("id",it.id).put("uri",it.rawUri) }).toString())
                }
                runCatching { remote!!.send(message) }.onFailure { result.completeExceptionally(it) }
            }
            override fun onServiceDisconnected(name: ComponentName?) { result.completeExceptionally(IOException("Diagnostics process stopped")) }
            override fun onNullBinding(name: ComponentName?) { result.completeExceptionally(IOException("Diagnostics unavailable")) }
        }
        val bound=context.bindService(Intent(context,PingService::class.java),connection,Context.BIND_AUTO_CREATE)
        check(bound) { "Diagnostics service unavailable" }
        try { result.await() } finally {
            runCatching { remote?.send(Message.obtain(null,4)) }
            context.unbindService(connection)
        }
    }
}

internal suspend fun measure(settings: PingSettings, active: Boolean = false, attempt: suspend () -> Long): PingResult {
    val samples=mutableListOf<Long>(); var error: String?=null
    repeat(settings.attempts) {
        currentCoroutineContext().ensureActive()
        try { samples+=attempt() } catch (e: CancellationException) { throw e } catch(e: Exception) { error=e.message ?: "Diagnostics failed" }
    }
    val success=samples.size==settings.attempts
    return PingResult(settings,if(success) aggregate(samples,settings.aggregation) else null,System.currentTimeMillis(),success,error,samples,active)
}

internal suspend fun httpAttempt(settings: PingSettings, port: Int?): Long = suspendCancellableCoroutine { continuation ->
    val proxy=if(port==null) Proxy.NO_PROXY else Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",port))
    val client=OkHttpClient.Builder().proxy(proxy).followRedirects(false).followSslRedirects(false)
        .callTimeout(settings.timeoutSeconds.toLong(),TimeUnit.SECONDS).connectTimeout(settings.timeoutSeconds.toLong(),TimeUnit.SECONDS)
        .readTimeout(settings.timeoutSeconds.toLong(),TimeUnit.SECONDS).build()
    val request=Request.Builder().url(settings.url).method(if(settings.method==PingMethod.PROXY_HEAD) "HEAD" else "GET",null).header("Cache-Control","no-cache").build()
    val call=client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    val start=System.nanoTime()
    fun cleanup() { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    call.enqueue(object: Callback {
        override fun onFailure(call: Call, e: IOException) { cleanup(); if(continuation.isActive) continuation.resumeWithException(IOException("HTTP request failed or timed out (DNS, TLS or proxy)")) }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if(settings.method==PingMethod.PROXY_HEAD && it.code==405) throw IOException("HEAD unsupported by test server (405); use GET; proxy health unknown")
                    if(!it.isSuccessful) throw IOException("Test URL returned HTTP ${it.code}")
                    if(settings.method==PingMethod.PROXY_GET) {
                        val source=it.body.source(); var size=0L; val buffer=okio.Buffer()
                        while(true) { val read=source.read(buffer,8192); if(read<0) break; size+=read; buffer.clear(); if(size>1_048_576) throw IOException("Test response exceeds 1 MiB; use a small URL") }
                    }
                }
                if(continuation.isActive) continuation.resume(((System.nanoTime()-start)/1_000_000).coerceAtLeast(1))
            } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) } finally { cleanup() }
        }
    })
}

private suspend fun directAttempt(node: ProxyNode, settings: PingSettings): Long = withContext(Dispatchers.IO) {
    if(settings.method==PingMethod.TCP && node.config is ProxyConfig.Hysteria2) throw IOException("TCP does not test Hysteria2 UDP/QUIC; use Via Proxy")
    val address=runInterruptible { InetAddress.getByName(node.host) }
    if(settings.method==PingMethod.TCP) {
        Socket().use { socket ->
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                try { val start=System.nanoTime(); socket.connect(InetSocketAddress(address,node.port),settings.timeoutSeconds*1000); if(continuation.isActive) continuation.resume(((System.nanoTime()-start)/1_000_000).coerceAtLeast(1)) }
                catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(IOException("TCP connect failed or timed out")) }
            }
        }
    } else {
        val binary=if(address is Inet6Address) "/system/bin/ping6" else "/system/bin/ping"
        val process=try { ProcessBuilder(binary,"-n","-c","1","-W",settings.timeoutSeconds.toString(),address.hostAddress!!).redirectErrorStream(true).apply { environment()["LC_ALL"]="C" }.start() } catch(e: Exception) { throw IOException("ICMP unavailable on this device; use Via Proxy") }
        try {
            runInterruptible {
                if(!process.waitFor((settings.timeoutSeconds+1).toLong(),TimeUnit.SECONDS)) throw IOException("ICMP timed out")
                val output=process.inputStream.bufferedReader().readText()
                if(process.exitValue()!=0) throw IOException("ICMP blocked, unavailable or timed out; proxy health unknown")
                Regex("time[=<]([0-9.]+)").find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()?.coerceAtLeast(1) ?: throw IOException("ICMP round trip time unavailable")
            }
        } finally { process.destroyForcibly() }
    }
}
