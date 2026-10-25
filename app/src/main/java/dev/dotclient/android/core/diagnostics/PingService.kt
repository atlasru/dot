package dev.dotclient.android.core.diagnostics

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket

/** A separate process owns one temporary core, with bounded HTTP outbounds per batch. */
class PingService: Service() {
    companion object { private val coreGate=Mutex() }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var running: Job?=null
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        if(message.what==4) running?.cancel()
        if(message.what==1 && running?.isActive!=true) {
            val data=message.data; val reply=message.replyTo
            running=scope.launch { coreGate.withLock {
                try {
                    val settings=PingSettings.from(JSONObject(data.getString("settings")!!))
                    val nodes=JSONArray(data.getString("nodes")!!)
                    val results=JSONObject()
                    for(batch in (0 until nodes.length()).toList().chunked(settings.parallelism)) {
                        ensureActive()
                        val inbounds=JSONArray(); val outbounds=JSONArray(); val rules=JSONArray()
                        val ports=batch.map { ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress()).use { it.localPort } }
                        val valid=mutableListOf<Pair<Int,Int>>()
                        batch.forEachIndexed { index, nodeIndex ->
                            val node=nodes.getJSONObject(nodeIndex)
                            try {
                                val config=DiagnosticConfig.convertProfile(node.getString("uri"))
                                val all=config.getJSONArray("outbounds")
                                val outbound=(0 until all.length()).map { all.getJSONObject(it) }.first { it.optString("protocol") in listOf("vless","hysteria") }
                                val tag="diag-$index"; outbound.put("tag",tag); outbounds.put(outbound)
                                inbounds.put(JSONObject().put("tag",tag).put("listen","127.0.0.1").put("port",ports[index]).put("protocol","http").put("settings",JSONObject()))
                                rules.put(JSONObject().put("type","field").put("inboundTag",JSONArray().put(tag)).put("outboundTag",tag))
                                valid+=nodeIndex to ports[index]
                            } catch(e: Exception) { results.put(node.getString("id"),measure(settings) { error("Invalid proxy configuration") }.json()) }
                        }
                        if(valid.isEmpty()) continue
                        val config=JSONObject().put("log",JSONObject().put("loglevel","none")).put("inbounds",inbounds).put("outbounds",outbounds).put("routing",JSONObject().put("rules",rules))
                        try {
                            val response=invoke("runXrayFromJson",JSONObject().put("configJSON",config.toString()))
                            check(response.optBoolean("success")) { "Diagnostics core rejected configuration" }
                            coroutineScope { valid.map { (nodeIndex,port) -> async {
                                val result=measure(settings) { httpAttempt(settings,port) }
                                nodes.getJSONObject(nodeIndex).getString("id") to result
                            } }.awaitAll() }.forEach { (id,result) -> results.put(id,result.json()) }
                        } finally { invoke("stopXray",JSONObject()) }
                    }
                    reply.send(Message.obtain(null,2).apply { this.data=Bundle().apply { putString("results",results.toString()) } })
                } catch(e: CancellationException) { throw e } catch(e: Exception) { runCatching { reply.send(Message.obtain(null,3)) } }
            } }
        }
        true
    })
    override fun onBind(intent: Intent?) = messenger.binder
    override fun onUnbind(intent: Intent?): Boolean { running?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun invoke(method: String, payload: JSONObject) = JSONObject(LibXray.invoke(JSONObject().put("apiVersion",1).put("method",method).put("payload",payload).toString()))
}
