package dev.dotclient.android.core.model

import java.util.UUID
import dev.dotclient.android.core.subscription.SubscriptionIdentity

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val profiles: List<ProxyNode> = emptyList(),
    val selectedProfileId: String? = null,
    val lastUpdatedEpochMs: Long? = null,
    val sortMode: NodeSortMode = NodeSortMode.ORIGIN,
    val hwid: String = SubscriptionIdentity.generate(),
) {
    override fun toString(): String = "Subscription(id=$id, name=$name, profiles=${profiles.size}, hwid=[redacted])"
}
