package dev.dotclient.android.vpn

import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import libXray.DialerController
import libXray.LibXray
import java.util.concurrent.atomic.AtomicBoolean

/** libXray appends controllers to process-wide lists. Keep a single pair for all sessions. */
internal object VpnSocketControllers {
    private class Route(val service: VpnService, val network: Network) {
        val loggedSocketFailure = AtomicBoolean()

        fun protectSocket(fd: Long, bind: Boolean): Boolean {
            val socketFd = fd.toInt()
            val protected = runCatching {
                check(service.protect(socketFd))
                if (bind) {
                    // fromFd duplicates the descriptor, leaving ownership with Xray.
                    ParcelFileDescriptor.fromFd(socketFd).use { network.bindSocket(it.fileDescriptor) }
                }
            }.isSuccess
            if (!protected && loggedSocketFailure.compareAndSet(false, true)) {
                Log.w("DotVpnService", "Could not protect or bind an Xray socket")
            }
            return protected
        }
    }

    @Volatile private var route: Route? = null
    private var registered = false
    private val dialer = DialerController { fd -> route?.protectSocket(fd, bind = true) ?: false }
    private val listener = DialerController { fd -> route?.protectSocket(fd, bind = false) ?: false }

    // Called only on the VPN worker, which owns the process-wide Xray and DNS state.
    fun activate(service: VpnService, network: Network): DialerController {
        if (!registered) {
            LibXray.registerDialerController(dialer)
            LibXray.registerListenerController(listener)
            registered = true
        }
        route = Route(service, network)
        return dialer
    }

    fun clear() {
        route = null
    }
}
