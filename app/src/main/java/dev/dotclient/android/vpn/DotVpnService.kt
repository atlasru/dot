package dev.dotclient.android.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.dotclient.android.MainActivity
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class DotVpnService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor()
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
                    VpnRuntime.update(VpnConnectionState.ERROR, message = "missing VLESS profile")
                    stopSelf()
                } else {
                    startForeground(NOTIFICATION_ID, notification("connecting", nodeName))
                    worker.execute { connect(rawUri, nodeName) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdownCore()
        worker.shutdownNow()
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
            VpnRuntime.update(VpnConnectionState.CONNECTING, nodeName, "starting libXray…")

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

                VpnRuntime.update(VpnConnectionState.CONNECTED, nodeName, "connected")
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(NOTIFICATION_ID, notification("connected", nodeName))
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                shutdownCore()
                VpnRuntime.update(VpnConnectionState.ERROR, nodeName, message)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

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
        val env = config.optJSONObject("env") ?: JSONObject()
        env.put("xray.tun.fd", tunFd.toString())
        config.put("env", env)

        val tunInbound = JSONObject()
            .put("tag", "dot-tun")
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "dot0")
                    .put("mtu", MTU),
            )

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
        if (!response.optBoolean("success")) {
            error(response.optString("error", "runXrayFromJson failed"))
        }
    }

    private fun disconnect() {
        worker.execute {
            VpnRuntime.update(VpnConnectionState.DISCONNECTING, runningNodeName, "disconnecting…")
            shutdownCore()
            VpnRuntime.update(VpnConnectionState.DISCONNECTED, null, "disconnected")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun shutdownCore() {
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

    private fun notification(status: String, nodeName: String?): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = Intent(this, DotVpnService::class.java).setAction(ACTION_DISCONNECT)
        val disconnectPending = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("dot. · $status")
            .setContentText(nodeName ?: "VLESS")
            .setContentIntent(pendingIntent)
            .setOngoing(status == "connected" || status == "connecting")
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", disconnectPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "dot. VPN",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
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
    }
}
