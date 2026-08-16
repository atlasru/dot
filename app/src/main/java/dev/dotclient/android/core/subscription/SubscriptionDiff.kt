package dev.dotclient.android.core.subscription

import dev.dotclient.android.core.model.VlessProfile
import java.util.Locale

data class NodeIdentity(
    val userId: String,
    val host: String,
    val port: Int,
)

fun VlessProfile.nodeIdentity(): NodeIdentity = NodeIdentity(
    userId = userId.lowercase(Locale.ROOT),
    host = host.lowercase(Locale.ROOT),
    port = port,
)

data class NodeMatch(
    val before: VlessProfile,
    val after: VlessProfile,
)

data class NodeEdit(
    val before: VlessProfile,
    val after: VlessProfile,
    val changedFields: List<String>,
)

data class SubscriptionDiff(
    val added: List<VlessProfile>,
    val deleted: List<VlessProfile>,
    val edited: List<NodeEdit>,
    val unchanged: List<NodeMatch>,
) {
    val hasChanges: Boolean
        get() = added.isNotEmpty() || deleted.isNotEmpty() || edited.isNotEmpty()

    fun replacementFor(oldProfileId: String): VlessProfile? =
        unchanged.firstOrNull { it.before.id == oldProfileId }?.after
            ?: edited.firstOrNull { it.before.id == oldProfileId }?.after
}

object SubscriptionDiffer {
    fun calculate(oldProfiles: List<VlessProfile>, newProfiles: List<VlessProfile>): SubscriptionDiff {
        val added = mutableListOf<VlessProfile>()
        val deleted = mutableListOf<VlessProfile>()
        val edited = mutableListOf<NodeEdit>()
        val unchanged = mutableListOf<NodeMatch>()

        val oldByIdentity = oldProfiles.groupBy(VlessProfile::nodeIdentity)
        val newByIdentity = newProfiles.groupBy(VlessProfile::nodeIdentity)
        val identities = LinkedHashSet<NodeIdentity>().apply {
            addAll(oldByIdentity.keys)
            addAll(newByIdentity.keys)
        }

        identities.forEach { identity ->
            val oldRemaining = oldByIdentity[identity].orEmpty().toMutableList()
            val newRemaining = newByIdentity[identity].orEmpty().toMutableList()

            val oldIterator = oldRemaining.listIterator()
            while (oldIterator.hasNext()) {
                val oldProfile = oldIterator.next()
                val exactIndex = newRemaining.indexOfFirst { it.rawUri == oldProfile.rawUri }
                if (exactIndex >= 0) {
                    unchanged += NodeMatch(oldProfile, newRemaining.removeAt(exactIndex))
                    oldIterator.remove()
                }
            }

            val pairCount = minOf(oldRemaining.size, newRemaining.size)
            repeat(pairCount) {
                val before = oldRemaining.removeAt(0)
                val after = newRemaining.removeAt(0)
                val changedFields = changedFields(before, after)
                if (changedFields.isEmpty()) unchanged += NodeMatch(before, after)
                else edited += NodeEdit(before, after, changedFields)
            }

            deleted += oldRemaining
            added += newRemaining
        }

        return SubscriptionDiff(
            added = added,
            deleted = deleted,
            edited = edited,
            unchanged = unchanged,
        )
    }

    private fun changedFields(before: VlessProfile, after: VlessProfile): List<String> = buildList {
        if (before.name != after.name) add("name")
        if (!before.host.equals(after.host, ignoreCase = true)) add("host")
        if (before.port != after.port) add("port")
        if (before.security != after.security) add("security")
        if (before.transport != after.transport) add("transport")
        if (before.sni != after.sni) add("SNI")
        if (before.fingerprint != after.fingerprint) add("fingerprint")
        if (before.flow != after.flow) add("flow")
        if (before.publicKey != after.publicKey) add("REALITY key")
        if (before.shortId != after.shortId) add("short ID")
        if (before.path != after.path) add("path")
        if (before.hostHeader != after.hostHeader) add("host header")
        if (before.serviceName != after.serviceName) add("service name")
        if (before.encryption != after.encryption) add("encryption")
    }
}
