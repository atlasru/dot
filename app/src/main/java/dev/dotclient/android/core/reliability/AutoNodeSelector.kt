package dev.dotclient.android.core.reliability

import dev.dotclient.android.core.model.ProxyNode

object AutoNodeSelector {
    fun selectVerified(
        profiles: List<ProxyNode>,
        results: Map<String, dev.dotclient.android.core.diagnostics.PingResult>,
        settings: dev.dotclient.android.core.diagnostics.PingSettings,
        excluded: Set<String> = emptySet(),
        maxLatencyMs: Long = 0,
    ): ProxyNode? = profiles.asSequence().filter { it.id !in excluded }
        .filter { results[it.id]?.verified(settings) == true }
        .filter { maxLatencyMs == 0L || (results[it.id]?.latencyMs ?: Long.MAX_VALUE) <= maxLatencyMs }
        .minWithOrNull(compareBy<ProxyNode> { results[it.id]?.latencyMs ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })

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
