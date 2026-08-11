package dev.dotclient.android.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.dotclient.android.MainActivity
import dev.dotclient.android.core.subscription.SubscriptionStore

class DotQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()

        when (VpnRuntime.state.value.state) {
            VpnConnectionState.CONNECTED,
            VpnConnectionState.CONNECTING,
            VpnConnectionState.DISCONNECTING -> {
                startService(
                    Intent(this, DotVpnService::class.java)
                        .setAction(DotVpnService.ACTION_DISCONNECT),
                )
                setTileState(Tile.STATE_INACTIVE, "dot.", "disconnecting")
            }

            VpnConnectionState.DISCONNECTED,
            VpnConnectionState.ERROR -> connectSelectedNode()
        }
    }

    private fun connectSelectedNode() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            openApp()
            return
        }

        val stored = SubscriptionStore(this).load()
        val group = stored.subscriptions.firstOrNull { it.id == stored.selectedSubscriptionId }
            ?: stored.subscriptions.firstOrNull()
        val profile = group?.profiles?.firstOrNull { it.id == group.selectedProfileId }
            ?: group?.profiles?.firstOrNull()

        if (profile == null) {
            openApp()
            return
        }

        val intent = Intent(this, DotVpnService::class.java)
            .setAction(DotVpnService.ACTION_CONNECT)
            .putExtra(DotVpnService.EXTRA_VLESS_URI, profile.rawUri)
            .putExtra(DotVpnService.EXTRA_NODE_NAME, profile.name)
        ContextCompat.startForegroundService(this, intent)
        setTileState(Tile.STATE_ACTIVE, "dot.", profile.name)
    }

    private fun refreshTile() {
        val runtime = VpnRuntime.state.value
        val active = runtime.state == VpnConnectionState.CONNECTED ||
            runtime.state == VpnConnectionState.CONNECTING ||
            runtime.state == VpnConnectionState.DISCONNECTING

        setTileState(
            if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE,
            "dot.",
            when (runtime.state) {
                VpnConnectionState.CONNECTED -> runtime.nodeName ?: "connected"
                VpnConnectionState.CONNECTING -> "connecting"
                VpnConnectionState.DISCONNECTING -> "disconnecting"
                VpnConnectionState.ERROR -> "error"
                VpnConnectionState.DISCONNECTED -> "offline"
            },
        )
    }

    private fun setTileState(state: Int, label: String, subtitle: String) {
        qsTile?.let { tile ->
            tile.state = state
            tile.label = label
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = subtitle
            }
            tile.contentDescription = "$label · $subtitle"
            tile.updateTile()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
