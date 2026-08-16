package dev.dotclient.android.ui

import dev.dotclient.android.core.subscription.SubscriptionDiff

sealed interface SubscriptionUpdateResult {
    val subscriptionName: String

    data class Success(
        override val subscriptionName: String,
        val totalNodes: Int,
        val diff: SubscriptionDiff,
    ) : SubscriptionUpdateResult

    data class Error(
        override val subscriptionName: String,
        val userMessage: String,
        val rawError: String,
    ) : SubscriptionUpdateResult
}
