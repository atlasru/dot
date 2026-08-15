package dev.dotclient.android.core.model

import java.util.UUID

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val profiles: List<VlessProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val lastUpdatedEpochMs: Long? = null,
    val sortMode: NodeSortMode = NodeSortMode.ORIGIN,
)
