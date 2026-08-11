package dev.dotclient.android.vpn

import android.net.VpnService

/**
 * VpnService boundary for dot.
 *
 * The native engine hookup lands in the next milestone. We deliberately do not call Builder.establish()
 * yet because a TUN without a packet engine would route user traffic into a dead end.
 */
class DotVpnService : VpnService()
