package dev.dotclient.android.vpn

internal data class UnderlayCapabilities(val validated: Boolean, val cellular: Boolean)

/** Best-matching callbacks were introduced in API 31. Older Android reports every match. */
internal fun <Network> selectLegacyUnderlay(
    networks: Map<Network, UnderlayCapabilities>,
    active: Network?,
    previous: Network?,
): Network? = networks.entries.maxWithOrNull(
    compareBy<Map.Entry<Network, UnderlayCapabilities>> { it.key == active }
        .thenBy { it.value.validated }
        .thenBy { !it.value.cellular }
        .thenBy { it.key == previous },
)?.key

internal enum class UnderlayAction { KEEP, WAIT, RECONNECT, DISCONNECT }

internal fun <Network> underlayAction(
    current: Network?,
    running: Network?,
    waiting: Boolean,
    reconnectOnNetwork: Boolean,
): UnderlayAction = when {
    current == null -> if (reconnectOnNetwork) UnderlayAction.WAIT else UnderlayAction.DISCONNECT
    running != null && running != current -> UnderlayAction.RECONNECT
    waiting -> UnderlayAction.RECONNECT
    else -> UnderlayAction.KEEP
}
