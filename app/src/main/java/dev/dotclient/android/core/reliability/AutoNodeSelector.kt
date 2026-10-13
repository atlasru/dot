package dev.dotclient.android.core.reliability

import dev.dotclient.android.core.model.ProxyNode

object AutoNodeSelector {
    fun select(
        profiles: List<ProxyNode>,
        latenciesMs: Map<String, Long>,
        failedIds: Set<String>,
        preferredProfileId: String? = null,
    ): ProxyNode? {
        if (profiles.isEmpty()) return null

        val successful = profiles
            .asSequence()
            .filterNot { it.id in failedIds }
            .mapNotNull { profile -> latenciesMs[profile.id]?.let { latency -> profile to latency } }
            .minWithOrNull(compareBy<Pair<ProxyNode, Long>> { it.second }.thenBy { it.first.name.lowercase() })
            ?.first

        if (successful != null) return successful

        return profiles.firstOrNull { it.id == preferredProfileId }
            ?: profiles.firstOrNull { it.id !in failedIds }
            ?: profiles.first()
    }
}
