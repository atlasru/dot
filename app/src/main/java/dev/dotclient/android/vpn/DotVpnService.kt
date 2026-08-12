package dev.dotclient.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Process
import dev.dotclient.android.MainActivity
import dev.dotclient.android.R
import dev.dotclient.android.ui.LauncherIcon
import dev.dotclient.android.ui.LauncherIconManager
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DotVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val trafficWorker = Executors.newSingleThreadScheduledExecutor()
    private var trafficFuture: ScheduledFuture<*>? = null
    private var tun: ParcelFileDescriptor? = null
    private var runningNodeName: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnect()
            ACTION_CONNECT -> {
                val rawUri = intent.getStringExtra(EXTRA_VLESS_URI)
                val nodeName = intent.getStringExtra(EXTRA_NODE_NAME)
                if (rawUri.isNullOrBlank()) {
                    publishState(VpnConnectionState.ERROR, message = "missing VLESS profile")
                    stopSelf()
                } else {
                    // Foreground startup must never depend on a user-selected icon. Android gives a
                    // foreground service only a very small window to post a valid notification, so
                    // always bootstrap with the long-tested shield vector and switch the glyph only
                    // after Xray is actually connected.
                    startForeground(
                        NOTIFICATION_ID,
                        notification("connecting", nodeName, preferSelectedIcon = false),
                    )
                    worker.execute { connect(rawUri, nodeName) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdownCore()
        worker.shutdownNow()
        trafficWorker.shutdownNow()
        super.onDestroy()
    }

    override fun onRevoke() {
        disconnect()
        super.onRevoke()
    }

    private fun connect(rawUri: String, nodeName: String?) {
        synchronized(this) {
            shutdownCore()
            runningNodeName = nodeName
            publishState(VpnConnectionState.CONNECTING, nodeName, "starting libXray…")

            try {
                val vpnInterface = Builder()
                    .setSession("dot.")
                    .setMtu(MTU)
                    .addAddress("10.77.0.2", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .setBlocking(true)
                    .establish()
                    ?: error("Android failed to establish TUN")

                tun = vpnInterface

                val controller = object : DialerController {
                    override fun protectFd(fd: Long): Boolean = protect(fd.toInt())
                }

                LibXray.registerDialerController(controller)
                LibXray.registerListenerController(controller)
                LibXray.setDNS(controller, "1.1.1.1:53")

                val config = buildXrayConfig(rawUri, vpnInterface.fd)
                invokeRunXrayFromJson(config)

                publishState(VpnConnectionState.CONNECTED, nodeName, "connected")
                startTrafficMeter(nodeName)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                shutdownCore()
                publishState(VpnConnectionState.ERROR, nodeName, message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startTrafficMeter(nodeName: String?) {
        trafficFuture?.cancel(true)
        val uid = Process.myUid()
        val baselineRx = uidRxBytes(uid)
        val baselineTx = uidTxBytes(uid)
        var previousRx = baselineRx
        var previousTx = baselineTx
        var previousAt = System.nanoTime()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            notification("connected", nodeName, 0L, 0L, preferSelectedIcon = true),
        )

        trafficFuture = trafficWorker.scheduleAtFixedRate({
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
            manager.notify(
                NOTIFICATION_ID,
                notification("connected", nodeName, downRate, upRate, preferSelectedIcon = true),
            )
        }, 1L, 1L, TimeUnit.SECONDS)
    }

    private fun uidRxBytes(uid: Int): Long = TrafficStats.getUidRxBytes(uid).takeIf { it >= 0L } ?: 0L
    private fun uidTxBytes(uid: Int): Long = TrafficStats.getUidTxBytes(uid).takeIf { it >= 0L } ?: 0L

    private fun buildXrayConfig(rawUri: String, tunFd: Int): String {
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

    private fun disconnect() {
        worker.execute {
            publishState(VpnConnectionState.DISCONNECTING, runningNodeName, "disconnecting…")
            shutdownCore()
            publishState(VpnConnectionState.DISCONNECTED, null, "disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishState(state: VpnConnectionState, nodeName: String? = null, message: String? = null) {
        VpnRuntime.update(state, nodeName, message)
        runCatching { DotQuickTileService.requestRefresh(this) }
    }

    private fun shutdownCore() {
        trafficFuture?.cancel(true)
        trafficFuture = null
        runCatching {
            val request = JSONObject()
                .put("apiVersion", LIBXRAY_API_VERSION)
                .put("method", "stopXray")
                .put("payload", JSONObject())
            LibXray.invoke(request.toString())
        }
        runCatching { LibXray.resetDNS() }
        runCatching { tun?.close() }
        tun = null
        runningNodeName = null
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
            .setOngoing(status == "connected" || status == "connecting")
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
        private const val LIBXRAY_API_VERSION = 1
        private const val CHANNEL_ID = "dot_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val MTU = 1500
        private val SAFE_NOTIFICATION_ICON = R.drawable.ic_notification_dot
        private val SERVER_ONLY_REALITY_KEYS = listOf(
            "target", "dest", "type", "xver", "serverNames", "privateKey", "minClientVer", "maxClientVer",
            "maxTimeDiff", "shortIds", "mldsa65Seed", "limitFallbackUpload", "limitFallbackDownload",
        )
    }
}
