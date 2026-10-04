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
    val loadError: String? = null,
)

class SubscriptionStore internal constructor(
    private val readState: () -> String?,
    private val writeState: (String) -> Boolean,
) {
    constructor(context: Context) : this(
        { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_STATE, null) },
        { raw ->
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previous = preferences.getString(KEY_STATE, null)
            if (preferences.edit().putString(KEY_STATE, raw).commit()) true else {
                // commit() also updates the memory cache; restore it after a disk failure.
                preferences.edit().putString(KEY_STATE, previous).commit()
                false
            }
        },
    )
    private var loadFailed = false

    fun load(): StoredSubscriptions = synchronized(LOCK) {
        val raw = readState() ?: return@synchronized StoredSubscriptions()
        runCatching {
            val root = JSONObject(raw)
            val groupsJson = root.optJSONArray("subscriptions") ?: JSONArray()
            var migrated = false
            for (index in 0 until groupsJson.length()) {
                val item = groupsJson.getJSONObject(index)
                if (!item.has("hwid") || item.isNull("hwid")) {
                    item.put("hwid", SubscriptionIdentity.generate())
                    migrated = true
                }
                require(SubscriptionIdentity.isValid(item.getString("hwid"))) { SubscriptionIdentity.VALIDATION_ERROR }
            }
            // Patch the original JSON so migration preserves URLs, credentials and unknown fields.
            if (migrated) check(writeState(root.toString())) { "Could not persist subscription identity migration" }
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
                            hwid = item.getString("hwid"),
                        )
                    )
                }
            }
            val selectedSubscriptionId = root
                .optString("selectedSubscriptionId")
                .takeIf { selected -> groups.any { it.id == selected } }
                ?: groups.firstOrNull()?.id
            loadFailed = false
            StoredSubscriptions(groups, selectedSubscriptionId)
        }.getOrElse {
            loadFailed = true
            StoredSubscriptions(loadError = "Could not load or save subscriptions. Original data is preserved; retry after checking storage.")
        }
    }

    fun save(subscriptions: List<Subscription>, selectedSubscriptionId: String?) = synchronized(LOCK) {
        check(!loadFailed) { "Subscription state could not be loaded; refusing to overwrite it" }
        val groups = JSONArray()
        subscriptions.forEach { subscription ->
            require(SubscriptionIdentity.isValid(subscription.hwid)) { SubscriptionIdentity.VALIDATION_ERROR }
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
                    .put("hwid", subscription.hwid)
            )
        }

        val root = JSONObject()
            .put("selectedSubscriptionId", selectedSubscriptionId.orEmpty())
            .put("subscriptions", groups)

        check(writeState(root.toString())) { "Could not save subscriptions" }
    }

    private companion object {
        const val PREFS_NAME = "dot.subscriptions"
        const val KEY_STATE = "state"
        val LOCK = Any()
    }
}
