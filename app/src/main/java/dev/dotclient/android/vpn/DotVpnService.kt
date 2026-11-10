package dev.dotclient.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import dev.dotclient.android.MainActivity
import dev.dotclient.android.R
import dev.dotclient.android.core.parser.Hysteria2UriParser
import dev.dotclient.android.core.parser.VlessUriParser
import dev.dotclient.android.core.settings.AppSettings
import dev.dotclient.android.core.settings.SettingsRepository
import dev.dotclient.android.core.splittunnel.SplitTunnelMode
import dev.dotclient.android.core.splittunnel.SplitTunnelStore
import dev.dotclient.android.ui.LauncherIcon
import dev.dotclient.android.ui.LauncherIconManager
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class DotVpnService : VpnService() {
    private data class Connection(val generation: Long, val startId: Int, val rawUri: String, val name: String?) {
        val nodeId = if (rawUri.startsWith("vless://", true)) {
            VlessUriParser.parse(rawUri).getOrNull()?.id
        } else {
            Hysteria2UriParser.parse(rawUri).getOrNull()?.id
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    @Volatile private var destroyed = false
    private val settingsRepository by lazy { SettingsRepository(this) }
    private val splitTunnelStore by lazy { SplitTunnelStore(this) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }

    // The worker owns the connection, timers, network selection and all native core calls.
    private val legacyUnderlays = linkedMapOf<Network, UnderlayCapabilities>()
    private var underlay: Network? = null
    private var runningUnderlay: Network? = null
    private var lossFuture: ScheduledFuture<*>? = null
    private var trafficFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var desired: Connection? = null
    private var tun: ParcelFileDescriptor? = null
    private var appInTunnel = false
    private var reconnectAttempt = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                worker.execute { updateUnderlay(network) }
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
            val details = UnderlayCapabilities(
                validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
            worker.execute {
                if (!destroyed) {
                    legacyUnderlays[network] = details
                    selectLegacyUnderlay()
                }
            }
        }

        override fun onLost(network: Network) {
            worker.execute {
                if (destroyed) return@execute
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (network == underlay) updateUnderlay(null)
                } else {
                    legacyUnderlays.remove(network)
                    selectLegacyUnderlay()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        worker.execute {
            val active = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(active)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) {
                updateUnderlay(active)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectivityManager.registerBestMatchingNetworkCallback(request, networkCallback, mainHandler)
            } else {
                connectivityManager.registerNetworkCallback(request, networkCallback, mainHandler)
            }
        }.onFailure {
            Log.w(TAG, "Could not monitor the VPN's underlying networks")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> requestDisconnect(startId)
            ACTION_CONNECT -> {
                val rawUri = intent.getStringExtra(EXTRA_VLESS_URI)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME)
                val requestGeneration = generation.incrementAndGet()
                // Start foreground before queued native work, using the known-safe shield icon.
                startForeground(
                    NOTIFICATION_ID,
                    notification("connecting", nodeName, preferSelectedIcon = false),
                )
                if (rawUri.isNullOrBlank()) {
                    worker.execute {
                        if (!isCurrent(requestGeneration)) return@execute
                        clearConnection()
                        publishState(VpnConnectionState.ERROR, message = "missing proxy node")
                        stopService(requestGeneration, startId)
                    }
                } else {
                    worker.execute {
                        if (!isCurrent(requestGeneration)) return@execute
                        val connection = Connection(requestGeneration, startId, rawUri, nodeName)
                        desired = connection
                        reconnectAttempt = 0
                        connect(connection, reconnecting = false)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        worker.execute {
            lossFuture?.cancel(false)
            VpnRuntime.underlyingNetwork = null
            clearConnection()
            if (VpnRuntime.state.value.state !in setOf(VpnConnectionState.ERROR, VpnConnectionState.DISCONNECTED)) {
                publishState(VpnConnectionState.DISCONNECTED, message = "disconnected")
            }
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        requestDisconnect(startId = null)
    }

    private fun selectLegacyUnderlay() {
        updateUnderlay(selectLegacyUnderlay(legacyUnderlays, connectivityManager.activeNetwork, underlay))
    }

    private fun updateUnderlay(network: Network?) {
        if (destroyed || underlay == network) return
        underlay = network
        VpnRuntime.underlyingNetwork = network
        lossFuture?.cancel(false)
        if (network == null) {
            // A replacement may arrive just after onLost. Wait briefly before declaring an outage.
            lossFuture = worker.schedule({ handleUnderlayChange() }, NETWORK_LOSS_GRACE_MS, TimeUnit.MILLISECONDS)
        } else {
            handleUnderlayChange()
        }
    }

    private fun handleUnderlayChange() {
        val connection = desired ?: return
        if (!isCurrent(connection.generation)) return
        val reconnectOnNetwork = runCatching { settingsRepository.load().reconnectOnNetwork }.getOrDefault(true)
        val waiting = VpnRuntime.state.value.state == VpnConnectionState.WAITING_FOR_NETWORK
        when (underlayAction(underlay, runningUnderlay, waiting, reconnectOnNetwork)) {
            UnderlayAction.KEEP -> Unit
            UnderlayAction.WAIT -> {
                Log.i(TAG, "VPN underlay lost")
                waitForNetwork(connection)
            }
            UnderlayAction.RECONNECT -> {
                Log.i(TAG, "VPN underlay changed or recovered")
                connect(connection, reconnecting = true)
            }
            UnderlayAction.DISCONNECT -> disconnect(connection.generation, connection.startId)
        }
    }

    private fun waitForNetwork(connection: Connection) {
        cancelReconnect()
        stopTrafficMeter()
        // Keep the established routes in place while the VPN is waiting for an upstream network.
        if (tun != null) runCatching { setUnderlyingNetworks(emptyArray()) }
        publishState(VpnConnectionState.WAITING_FOR_NETWORK, connection, "waiting for network…")
        notifyStatus("waiting for network", connection)
    }

    private fun connect(connection: Connection, reconnecting: Boolean) {
        if (!isCurrent(connection.generation) || desired !== connection) return
        cancelReconnect()
        val network = underlay ?: run {
            waitForNetwork(connection)
            return
        }

        if (reconnecting) {
            publishState(VpnConnectionState.RECONNECTING, connection, "reconnecting…", reconnectAttempt = reconnectAttempt)
            notifyStatus("reconnecting", connection)
        } else {
            publishState(VpnConnectionState.CONNECTING, connection, "starting libXray…")
        }

        var settings: AppSettings? = null
        try {
            val appliedSettings = settingsRepository.load()
            settings = appliedSettings
            val splitTunnelConfig = splitTunnelStore.load()
            val vpnInterface = Builder()
                .setSession("dot.")
                .setMtu(MTU)
                .addAddress("10.77.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(appliedSettings.dns)
                .setUnderlyingNetworks(arrayOf(network))
                .applySplitTunnel(splitTunnelConfig)
                .establish()
                ?: error("Android failed to establish TUN")

            // Establish the replacement before closing the old descriptor so app routes can hand over.
            shutdownCore()
            tun = vpnInterface
            appInTunnel = when (splitTunnelConfig.mode) {
                SplitTunnelMode.ALL_APPS -> true
                SplitTunnelMode.EXCLUDE_SELECTED -> packageName !in splitTunnelConfig.normalizedPackages
                SplitTunnelMode.INCLUDE_ONLY_SELECTED -> packageName in splitTunnelConfig.normalizedPackages
            }
            val controller = VpnSocketControllers.activate(this, network)
            LibXray.setDNS(controller, "${appliedSettings.dns}:53")
            val config = RuntimeSettings.apply(buildXrayConfig(connection.rawUri, vpnInterface.fd), appliedSettings)
            invokeRunXrayFromJson(config)
            if (!isCurrent(connection.generation)) {
                shutdownCore()
                return
            }

            runningUnderlay = network
            reconnectAttempt = 0
            Log.i(TAG, "TUN and Xray started")
            publishState(VpnConnectionState.CONNECTED, connection, "connected")
            startTrafficMeter(connection)
        } catch (error: Throwable) {
            shutdownCore()
            if (!isCurrent(connection.generation)) return
            val failure = VpnErrorClassifier.classify(error, connection.rawUri)
            Log.w(TAG, "VPN start failed (${failure.category.name})")
            if (failure.category == VpnFailureCategory.CONFIG || settings?.reconnect != true) {
                desired = null
                publishState(VpnConnectionState.ERROR, connection, failure.userMessage, failure)
                stopService(connection.generation, connection.startId)
            } else {
                scheduleReconnect(connection, failure, settings)
            }
        }
    }

    private fun scheduleReconnect(connection: Connection, failure: VpnFailure, settings: AppSettings) {
        reconnectAttempt += 1
        val delayIndex = min(reconnectAttempt - 1, RECONNECT_DELAYS_SECONDS.lastIndex)
        val delaySeconds = (RECONNECT_DELAYS_SECONDS[delayIndex] * settings.retrySeconds)
            .coerceAtMost(settings.maxRetrySeconds.toLong())
        publishState(
            VpnConnectionState.RECONNECTING,
            connection,
            "${failure.userMessage} · retry in ${delaySeconds}s",
            failure,
            reconnectAttempt,
        )
        notifyStatus("reconnecting", connection)
        reconnectFuture = worker.schedule({
            connect(connection, reconnecting = true)
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun startTrafficMeter(connection: Connection) {
        stopTrafficMeter()
        val uid = Process.myUid()
        val baselineRx = uidRxBytes(uid)
        val baselineTx = uidTxBytes(uid)
        var previousRx = baselineRx
        var previousTx = baselineTx
        var previousAt = System.nanoTime()
        notifyStatus("connected", connection)

        trafficFuture = worker.scheduleAtFixedRate({
            if (!isCurrent(connection.generation)) return@scheduleAtFixedRate
            val now = System.nanoTime()
            val rx = uidRxBytes(uid)
            val tx = uidTxBytes(uid)
            val elapsedSeconds = ((now - previousAt).coerceAtLeast(1L) / 1_000_000_000.0).coerceAtLeast(0.001)
            val downRate = ((rx - previousRx).coerceAtLeast(0L) / elapsedSeconds).toLong()
            val upRate = ((tx - previousTx).coerceAtLeast(0L) / elapsedSeconds).toLong()
            val sessionDown = (rx - baselineRx).coerceAtLeast(0L)
            val sessionUp = (tx - baselineTx).coerceAtLeast(0L)
            previousRx = rx
            previousTx = tx
            previousAt = now

            VpnRuntime.updateTraffic(
                downloadBytesPerSecond = downRate,
                uploadBytesPerSecond = upRate,
                sessionDownloadBytes = sessionDown,
                sessionUploadBytes = sessionUp,
            )
            notifyStatus("connected", connection, downRate, upRate)
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun stopTrafficMeter() {
        trafficFuture?.cancel(false)
        trafficFuture = null
    }

    private fun notifyStatus(
        status: String,
        connection: Connection,
        downRate: Long = 0L,
        upRate: Long = 0L,
    ) {
        mainHandler.post {
            if (!isCurrent(connection.generation)) return@post
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(status, connection.name, downRate, upRate, preferSelectedIcon = status == "connected"),
                )
            }
        }
    }

    private fun uidRxBytes(uid: Int): Long = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L } ?: 0L
    private fun uidTxBytes(uid: Int): Long = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0L } ?: 0L

    private fun buildXrayConfig(rawUri: String, tunFd: Int): String {
        if (rawUri.startsWith("hy2://", true) || rawUri.startsWith("hysteria2://", true)) {
            val config = Hysteria2XrayConfig.fromUri(rawUri)
            config.put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
            config.put("inbounds", JSONArray().put(JSONObject().put("tag", "dot-tun")
                .put("protocol", "tun").put("settings", JSONObject().put("name", "dot0").put("mtu", MTU))))
            return config.toString()
        }
        val conversionRequest = JSONObject()
            .put("apiVersion", LIBXRAY_API_VERSION)
            .put("method", "convertShareLinksToXrayJson")
            .put("payload", JSONObject().put("text", rawUri))
        val conversionResponse = JSONObject(LibXray.invoke(conversionRequest.toString()))
        if (!conversionResponse.optBoolean("success")) {
            error(conversionResponse.optString("error", "failed to convert VLESS link"))
        }
        val generatedJson = conversionResponse.optString("data")
        if (generatedJson.isBlank()) error("libXray returned an empty config")
        val config = JSONObject(generatedJson)
        val shareUri = Uri.parse(rawUri)

        config.optJSONArray("outbounds")?.let { outbounds ->
            for (index in 0 until outbounds.length()) {
                val outbound = outbounds.optJSONObject(index) ?: continue
                outbound.remove("sendThrough")
                val stream = outbound.optJSONObject("streamSettings") ?: continue
                if (!stream.optString("security").equals("reality", ignoreCase = true)) continue
                val reality = stream.optJSONObject("realitySettings") ?: JSONObject().also { stream.put("realitySettings", it) }
                SERVER_ONLY_REALITY_KEYS.forEach(reality::remove)
                shareUri.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { reality.put("fingerprint", it) }
                shareUri.getQueryParameter("sni")?.takeIf { it.isNotBlank() }?.let { reality.put("serverName", it) }
                shareUri.getQueryParameter("pbk")?.takeIf { it.isNotBlank() }?.let {
                    reality.put("password", it)
                    reality.put("publicKey", it)
                }
                shareUri.getQueryParameter("sid")?.let { reality.put("shortId", it) }
                shareUri.getQueryParameter("spx")?.takeIf { it.isNotBlank() }?.let { reality.put("spiderX", it) }
                shareUri.getQueryParameter("pqv")?.takeIf { it.isNotBlank() }?.let { reality.put("mldsa65Verify", it) }
            }
        }

        val env = config.optJSONObject("env") ?: JSONObject()
        env.put("xray.tun.fd", tunFd.toString())
        config.put("env", env)
        val tunInbound = JSONObject()
            .put("tag", "dot-tun")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("name", "dot0").put("mtu", MTU))
        config.put("inbounds", JSONArray().put(tunInbound))
        config.put("log", JSONObject().put("loglevel", "warning"))
        return config.toString()
    }

    private fun invokeRunXrayFromJson(config: String) {
        val request = JSONObject()
            .put("apiVersion", LIBXRAY_API_VERSION)
            .put("method", "runXrayFromJson")
            .put("payload", JSONObject().put("configJSON", config))
        val response = JSONObject(LibXray.invoke(request.toString()))
        if (!response.optBoolean("success")) error(response.optString("error", "runXrayFromJson failed"))
    }

    private fun isCurrent(requestGeneration: Long): Boolean =
        !destroyed && requestGeneration == generation.get()

    private fun requestDisconnect(startId: Int?) {
        val requestGeneration = generation.incrementAndGet()
        worker.execute { disconnect(requestGeneration, startId) }
    }

    private fun disconnect(requestGeneration: Long, startId: Int?) {
        if (!isCurrent(requestGeneration)) return
        publishState(VpnConnectionState.DISCONNECTING, desired, "disconnecting…")
        clearConnection()
        if (!isCurrent(requestGeneration)) return
        publishState(VpnConnectionState.DISCONNECTED, message = "disconnected")
        stopService(requestGeneration, startId)
    }

    private fun clearConnection() {
        desired = null
        cancelReconnect()
        reconnectAttempt = 0
        shutdownCore()
    }

    private fun stopService(requestGeneration: Long, startId: Int?) {
        mainHandler.post {
            if (!isCurrent(requestGeneration)) return@post
            if (startId == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun publishState(
        state: VpnConnectionState,
        connection: Connection? = null,
        message: String? = null,
        failure: VpnFailure? = null,
        reconnectAttempt: Int = 0,
    ) {
        VpnRuntime.update(
            state = state,
            nodeName = connection?.name,
            nodeId = connection?.nodeId,
            appInTunnel = appInTunnel,
            message = message,
            failure = failure,
            reconnectAttempt = reconnectAttempt,
        )
        runCatching { DotQuickTileService.requestRefresh(this) }
    }

    private fun shutdownCore() {
        stopTrafficMeter()
        appInTunnel = false
        runningUnderlay = null
        val oldTun = tun ?: return
        tun = null
        runCatching {
            val request = JSONObject()
                .put("apiVersion", LIBXRAY_API_VERSION)
                .put("method", "stopXray")
                .put("payload", JSONObject())
            check(JSONObject(LibXray.invoke(request.toString())).optBoolean("success"))
        }.onFailure {
            Log.w(TAG, "Xray shutdown failed")
        }
        runCatching { LibXray.resetDNS() }
        VpnSocketControllers.clear()
        runCatching { oldTun.close() }
    }

    @Suppress("DEPRECATION")
    private fun notification(
        status: String,
        nodeName: String?,
        downloadBytesPerSecond: Long = 0L,
        uploadBytesPerSecond: Long = 0L,
        preferSelectedIcon: Boolean,
    ): Notification {
        val preferredIcon = if (preferSelectedIcon) notificationIconRes() else SAFE_NOTIFICATION_ICON
        return runCatching {
            buildNotification(status, nodeName, downloadBytesPerSecond, uploadBytesPerSecond, preferredIcon)
        }.getOrElse {
            // A cosmetic icon failure must never be able to take down the VPN service.
            buildNotification(status, nodeName, downloadBytesPerSecond, uploadBytesPerSecond, SAFE_NOTIFICATION_ICON)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(
        status: String,
        nodeName: String?,
        downloadBytesPerSecond: Long,
        uploadBytesPerSecond: Long,
        smallIconRes: Int,
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = Intent(this, DotVpnService::class.java).setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val traffic = "↓ ${formatRate(downloadBytesPerSecond)}   ↑ ${formatRate(uploadBytesPerSecond)}"
        val title = if (status == "connected") nodeName ?: "dot. VPN" else "dot. · $status"

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(if (status == "connected") traffic else (nodeName ?: "VLESS"))
            .setSubText(if (status == "connected") "dot. · connected" else null)
            .setContentIntent(pendingIntent)
            .setOngoing(status == "connected" || status == "connecting" || status == "reconnecting" || status == "waiting for network")
            .setOnlyAlertOnce(true)
            .setSound(null)
            .addAction(0, "Disconnect", disconnectPending)
            .build()
    }

    private fun notificationIconRes(): Int = when (LauncherIconManager.current(this)) {
        LauncherIcon.SHIELD -> R.drawable.ic_notification_dot
        LauncherIcon.RED_DOT -> R.drawable.ic_notification_red_dot
        LauncherIcon.WORDMARK -> R.drawable.ic_notification_wordmark
    }

    private fun formatRate(bytesPerSecond: Long): String {
        val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
        return when {
            value >= 1024.0 * 1024.0 -> String.format("%.1f MB/s", value / (1024.0 * 1024.0))
            value >= 1024.0 -> String.format("%.0f KB/s", value / 1024.0)
            else -> "${value.toLong()} B/s"
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "dot. VPN", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ACTION_CONNECT = "dev.dotclient.android.CONNECT"
        const val ACTION_DISCONNECT = "dev.dotclient.android.DISCONNECT"
        const val EXTRA_VLESS_URI = "vless_uri"
        const val EXTRA_NODE_NAME = "node_name"
        private const val TAG = "DotVpnService"
        private const val LIBXRAY_API_VERSION = 1
        private const val CHANNEL_ID = "dot_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val MTU = 1500
        private const val NETWORK_LOSS_GRACE_MS = 250L
        // libXray and its DNS resolver are process-wide, including across service restarts.
        private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "dot-vpn") }
            .apply { removeOnCancelPolicy = true }
        private val RECONNECT_DELAYS_SECONDS = longArrayOf(1L, 2L, 5L, 10L, 30L)
        private val SAFE_NOTIFICATION_ICON = R.drawable.ic_notification_dot
        private val SERVER_ONLY_REALITY_KEYS = listOf(
            "target", "dest", "type", "xver", "serverNames", "privateKey", "minClientVer", "maxClientVer",
            "maxTimeDiff", "shortIds", "mldsa65Seed", "limitFallbackUpload", "limitFallbackDownload",
        )
    }
}
