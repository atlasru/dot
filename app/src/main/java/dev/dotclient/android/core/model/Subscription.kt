package dev.dotclient.android.core.model

data class Subscription(
    val name: String,
    val url: String,
    val lastUpdatedEpochMs: Long? = null,
)
