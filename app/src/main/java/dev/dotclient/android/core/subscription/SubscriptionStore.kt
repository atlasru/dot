package dev.dotclient.android.core.subscription

import android.content.Context
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.parser.VlessUriParser
import org.json.JSONArray
import org.json.JSONObject

data class StoredSubscriptions(
    val subscriptions: List<Subscription> = emptyList(),
    val selectedSubscriptionId: String? = null,
)

class SubscriptionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): StoredSubscriptions {
        val raw = preferences.getString(KEY_STATE, null) ?: return StoredSubscriptions()
        return runCatching {
            val root = JSONObject(raw)
            val groupsJson = root.optJSONArray("subscriptions") ?: JSONArray()
            val groups = buildList {
                for (index in 0 until groupsJson.length()) {
                    val item = groupsJson.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name").ifBlank { "vpn${index + 1}" }
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val rawProfiles = item.optJSONArray("profiles") ?: JSONArray()
                    val profiles = buildList {
                        for (profileIndex in 0 until rawProfiles.length()) {
                            val uri = rawProfiles.optString(profileIndex)
                            VlessUriParser.parse(uri).getOrNull()?.let(::add)
                        }
                    }
                    val selectedRawUri = item.optString("selectedProfileUri").takeIf { it.isNotBlank() }
                    val selectedProfileId = profiles
                        .firstOrNull { it.rawUri == selectedRawUri }
                        ?.id
                        ?: profiles.firstOrNull()?.id
                    val sortMode = runCatching {
                        NodeSortMode.valueOf(item.optString("sortMode", NodeSortMode.ORIGIN.name))
                    }.getOrDefault(NodeSortMode.ORIGIN)
                    add(
                        Subscription(
                            id = id,
                            name = name,
                            url = url,
                            profiles = profiles,
                            selectedProfileId = selectedProfileId,
                            lastUpdatedEpochMs = item.optLong("lastUpdatedEpochMs").takeIf { it > 0L },
                            sortMode = sortMode,
                        )
                    )
                }
            }
            val selectedSubscriptionId = root
                .optString("selectedSubscriptionId")
                .takeIf { selected -> groups.any { it.id == selected } }
                ?: groups.firstOrNull()?.id
            StoredSubscriptions(groups, selectedSubscriptionId)
        }.getOrElse {
            StoredSubscriptions()
        }
    }

    fun save(subscriptions: List<Subscription>, selectedSubscriptionId: String?) {
        val groups = JSONArray()
        subscriptions.forEach { subscription ->
            val profiles = JSONArray()
            subscription.profiles.forEach { profiles.put(it.rawUri) }
            val selectedRawUri = subscription.profiles
                .firstOrNull { it.id == subscription.selectedProfileId }
                ?.rawUri
                .orEmpty()
            groups.put(
                JSONObject()
                    .put("id", subscription.id)
                    .put("name", subscription.name)
                    .put("url", subscription.url)
                    .put("profiles", profiles)
                    .put("selectedProfileUri", selectedRawUri)
                    .put("lastUpdatedEpochMs", subscription.lastUpdatedEpochMs ?: 0L)
                    .put("sortMode", subscription.sortMode.name)
            )
        }

        val root = JSONObject()
            .put("selectedSubscriptionId", selectedSubscriptionId.orEmpty())
            .put("subscriptions", groups)

        preferences.edit().putString(KEY_STATE, root.toString()).apply()
    }

    private companion object {
        const val PREFS_NAME = "dot.subscriptions"
        const val KEY_STATE = "state"
    }
}
