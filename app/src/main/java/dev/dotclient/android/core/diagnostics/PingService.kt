package dev.dotclient.android.core.diagnostics

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** Owns the diagnostic core and its network binding in a process separate from the VPN. */
class PingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests = mutableMapOf<Int, Job>()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.arg1
        when (message.what) {
            CANCEL -> requests.remove(id)?.cancel()
            START -> {
                val data = Bundle(message.data)
                val reply = message.replyTo
                requests[id] = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            coreGate.withLock { runBatch(data, id, reply) }
                        }
                        reply.send(Message.obtain(null, FINISHED, id, 0))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Log.w("DotDiagnostics", "Diagnostics service failed to complete a batch")
                        runCatching { reply.send(Message.obtain(null, FAILED, id, 0)) }
                    } finally {
                        requests.remove(id)
                    }
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent?) = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        requests.values.forEach { it.cancel() }
        requests.clear()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION") // The typed Parcelable overload requires API 33.
    private suspend fun runBatch(data: Bundle, id: Int, reply: Messenger) {
        val settings = PingSettings.from(JSONObject(data.getString("settings")!!))
        val payload = JSONArray(data.getString("nodes")!!)
        require(payload.length() in 1..settings.parallelism)
        fun report(nodeId: String, result: PingResult) {
            val response = Message.obtain(null, RESULT, id, 0)
            response.data = Bundle().apply {
                putString("node", nodeId)
                putString("result", result.json().toString())
            }
            reply.send(response)
        }

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.diagnosticNetwork(data.getParcelable<Network>("underlay"))
        if (network == null) {
            for (index in 0 until payload.length()) {
                report(payload.getJSONObject(index).getString("id"), failedProbe(settings, "No underlying network for diagnostics"))
            }
            return
        }
        val link = connectivity.getLinkProperties(network)
        if (!settings.method.proxy) {
            coroutineScope {
                for (index in 0 until payload.length()) {
                    val item = payload.getJSONObject(index)
                    launch {
                        val result = directProbe(item.getString("host"), item.getInt("port"),
                            item.getBoolean("hysteria2"), settings, network, link?.interfaceName)
                        currentCoroutineContext().ensureActive()
                        report(item.getString("id"), result)
                    }
                }
            }
            return
        }

        val proxies = buildList {
            for (index in 0 until payload.length()) {
                val item = payload.getJSONObject(index)
                val nodeId = item.getString("id")
                val outbound = runCatching { DiagnosticConfig.proxyOutbound(item.getString("uri")) }.getOrNull()
                if (outbound == null) report(nodeId, failedProbe(settings, "Invalid proxy configuration"))
                else add(ProbeNode(nodeId, outbound))
            }
        }
        if (proxies.isEmpty()) return

        val dns = link?.dnsServers?.firstOrNull()?.hostAddress
        if (dns == null) {
            proxies.forEach { report(it.id, failedProbe(settings, "Underlying network has no DNS resolver")) }
            return
        }

        outboundNetwork.set(network)
        try {
            registerController()
            LibXray.setDNS(controller, if (':' in dns) "[$dns]:53" else "$dns:53")
            testProxyBatch(proxies, settings, ::report)
        } finally {
            LibXray.resetDNS()
            outboundNetwork.set(null)
        }
    }

    private suspend fun testProxyBatch(
        nodes: List<ProbeNode>,
        settings: PingSettings,
        report: (String, PingResult) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        val ports = allocateLoopbackPorts(nodes.size)
        val config = DiagnosticConfig.batch(nodes.map { it.outbound }, ports)
        try {
            val started = invoke("runXrayFromJson", JSONObject().put("configJSON", config.toString()))
            if (started.optBoolean("success")) {
                coroutineScope {
                    nodes.zip(ports).forEach { (node, port) ->
                        launch {
                            val result = measure(settings) { httpAttempt(settings, loopbackProxy(port)) }
                            ensureActive()
                            report(node.id, result)
                        }
                    }
                }
                return
            }
        } finally {
            invoke("stopXray", JSONObject())
        }

        Log.w("DotDiagnostics", "Diagnostics core rejected a configuration for ${nodes.size} node(s)")
        if (nodes.size == 1) {
            report(nodes.single().id, failedProbe(settings, "Diagnostics core rejected proxy configuration"))
            return
        }
        val split = nodes.size / 2
        testProxyBatch(nodes.take(split), settings, report)
        testProxyBatch(nodes.drop(split), settings, report)
    }

    private data class ProbeNode(val id: String, val outbound: JSONObject)

    private fun invoke(method: String, payload: JSONObject): JSONObject = JSONObject(
        LibXray.invoke(JSONObject().put("apiVersion", 1).put("method", method).put("payload", payload).toString()),
    )

    companion object {
        const val START = 1
        const val RESULT = 2
        const val FAILED = 3
        const val CANCEL = 4
        const val FINISHED = 5

        private val coreGate = Mutex()
        private val outboundNetwork = AtomicReference<Network?>()
        private var controllerRegistered = false
        private val controller = object : DialerController {
            override fun protectFd(fd: Long): Boolean {
                val network = outboundNetwork.get() ?: return false
                return runCatching {
                    ParcelFileDescriptor.fromFd(fd.toInt()).use { network.bindSocket(it.fileDescriptor) }
                }.isSuccess
            }
        }

        private fun registerController() {
            if (controllerRegistered) return
            LibXray.registerDialerController(controller)
            controllerRegistered = true
        }
    }
}

@Suppress("DEPRECATION")
private fun ConnectivityManager.diagnosticNetwork(preferred: Network?): Network? {
    fun Network.capabilities() = getNetworkCapabilities(this)?.takeIf {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    if (preferred?.capabilities() != null) return preferred
    activeNetwork?.let { if (it.capabilities() != null) return it }
    return allNetworks.mapNotNull { network -> network.capabilities()?.let { network to it } }
        .maxByOrNull { (_, capabilities) ->
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0
        }?.first
}

internal fun allocateLoopbackPorts(count: Int): List<Int> {
    val sockets = mutableListOf<ServerSocket>()
    try {
        repeat(count) { sockets += ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }
        return sockets.map { it.localPort }
    } finally {
        sockets.forEach { runCatching { it.close() } }
    }
}
