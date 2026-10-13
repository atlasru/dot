package dev.dotclient.android.core.subscription

import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.parser.SubscriptionDecoder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionStoreIdentityTest {
    private fun jsonTree(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { jsonTree(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { jsonTree(value.get(it)) }
        else -> value
    }

    private class Disk(var raw: String? = null) {
        var failWrites = false
        var writes = 0
        fun open() = SubscriptionStore({ raw }, { value ->
            if (failWrites) false else { raw = value; writes++; true }
        })
    }

    @Test fun creationReloadCustomEditAndRegenerationAreIndependent() {
        val disk = Disk()
        val a = Subscription(name = "A", url = "https://example.test/a")
        val b = Subscription(name = "B", url = "https://example.test/b")
        disk.open().save(listOf(a, b), b.id)
        val loaded = disk.open().load()
        assertEquals(listOf(a.hwid, b.hwid), loaded.subscriptions.map { it.hwid })
        val custom = "ABC123-existing-Value=="
        disk.open().save(listOf(a.copy(hwid = custom), b), b.id)
        assertEquals(custom, disk.open().load().subscriptions[0].hwid)
        assertEquals(b.hwid, disk.open().load().subscriptions[1].hwid)
        val regenerated = SubscriptionIdentity.generate()
        disk.open().save(listOf(a.copy(hwid = regenerated), b), b.id)
        val reloaded = disk.open().load()
        assertEquals(regenerated, reloaded.subscriptions[0].hwid)
        assertNotEquals(custom, reloaded.subscriptions[0].hwid)
        assertEquals(b.hwid, reloaded.subscriptions[1].hwid)
        assertEquals(b.id, reloaded.selectedSubscriptionId)
    }

    @Test fun legacyMigrationPersistsOnceAndPreservesRawStateAndCredentials() {
        val uri = "vless://11111111-1111-4111-8111-111111111111@example.test:443?security=tls&type=ws&path=%2Fprivate%3Ftoken%3Dsecret#name"
        val url = "https://example.test/sub/private-token?auth=secret"
        val item = JSONObject().put("id", "legacy").put("name", "old").put("url", url)
            .put("profiles", JSONArray().put(uri)).put("selectedProfileUri", uri)
            .put("sortMode", "NAME").put("lastUpdatedEpochMs", 1234L).put("future-field", "keep")
        val disk = Disk(JSONObject().put("selectedSubscriptionId", "legacy").put("subscriptions", JSONArray().put(item)).toString())
        val first = disk.open().load()
        assertNull(first.loadError)
        val migrated = first.subscriptions.single()
        assertTrue(SubscriptionIdentity.isValid(migrated.hwid))
        assertEquals(url, migrated.url)
        assertEquals(uri, migrated.profiles.single().rawUri)
        assertEquals(migrated.profiles.single().id, migrated.selectedProfileId)
        assertEquals(NodeSortMode.NAME, migrated.sortMode)
        assertEquals(1234L, migrated.lastUpdatedEpochMs)
        assertEquals("keep", JSONObject(disk.raw!!).getJSONArray("subscriptions").getJSONObject(0).getString("future-field"))
        assertEquals(migrated.hwid, disk.open().load().subscriptions.single().hwid)
        assertEquals(1, disk.writes)
    }

    @Test fun failedMigrationPreservesLegacyDataAndBlocksOverwriteUntilRetry() {
        val legacy = """{"subscriptions":[{"id":"a","name":"a","url":"https://example.test/a","profiles":[]}]}"""
        val disk = Disk(legacy).also { it.failWrites = true }
        val store = disk.open()
        assertNotNull(store.load().loadError)
        assertEquals(legacy, disk.raw)
        assertTrue(runCatching { store.save(emptyList(), null) }.isFailure)
        assertEquals(legacy, disk.raw)
        disk.failWrites = false
        val migrated = store.load().subscriptions.single()
        assertEquals(migrated.hwid, disk.open().load().subscriptions.single().hwid)
        assertEquals(1, disk.writes)
    }

    @Test fun invalidCustomValueDoesNotChangeSavedState() {
        val disk = Disk()
        val subscription = Subscription(name = "A", url = "https://example.test/a")
        disk.open().save(listOf(subscription), subscription.id)
        val before = disk.raw
        assertTrue(runCatching { disk.open().save(listOf(subscription.copy(hwid = "invalid\r\nheader")), subscription.id) }.isFailure)
        assertEquals(before, disk.raw)
    }

    @Test fun v040MixedMigrationPreservesNodesCredentialsSelectionSettingsAndExistingHwid() {
        val vless = "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls&type=ws&path=%2Fprivate#VLESS"
        val hy2 = "hysteria2://user%3Asecret@hy2.example:8443?sni=tls.example&obfs=salamander&obfs-password=mask-secret&packetSize=512-1200#HY2"
        val custom = "ABC123-existing-Value=="
        val a = JSONObject().put("id", "mixed").put("name", "v0.4.0")
            .put("url", "https://provider.example/sub?token=secret")
            .put("profiles", JSONArray().put(vless).put(hy2)).put("selectedProfileUri", hy2)
            .put("sortMode", "DELAY").put("lastUpdatedEpochMs", 1234L)
        val b = JSONObject(a.toString()).put("id", "existing").put("hwid", custom)
        val original = JSONObject().put("selectedSubscriptionId", "mixed")
            .put("subscriptions", JSONArray().put(a).put(b))
            .put("future-field", JSONObject().put("theme", "graphite").put("auto_node", true))
        val disk = Disk(original.toString())
        val loaded = disk.open().load()
        assertNull(loaded.loadError)
        val mixed = loaded.subscriptions[0]
        assertEquals(listOf("VLESS", "HY2"), mixed.profiles.map { it.protocol })
        assertEquals(hy2, mixed.profiles.single { it.id == mixed.selectedProfileId }.rawUri)
        val config = mixed.profiles[1].config as ProxyConfig.Hysteria2
        assertEquals("user:secret", config.auth)
        assertEquals("mask-secret", config.salamanderPassword)
        assertEquals(custom, loaded.subscriptions[1].hwid)
        val migrated = JSONObject(disk.raw!!)
        migrated.getJSONArray("subscriptions").getJSONObject(0).remove("hwid")
        assertEquals(jsonTree(JSONObject(original.toString())), jsonTree(migrated))
        val saved = disk.raw
        val restarted = disk.open().load()
        assertEquals(mixed.hwid, restarted.subscriptions[0].hwid)
        assertEquals(custom, restarted.subscriptions[1].hwid)
        assertEquals(saved, disk.raw)
        assertEquals(1, disk.writes)
    }

    @Test fun mixedGroupsKeepCustomAndRegeneratedIdentityIndependentAfterRestart() {
        val profiles = SubscriptionDecoder.decode("vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls#VLESS\nhy2://auth@hy2.example:443#HY2").profiles
        assertEquals(2, profiles.size)
        val a = Subscription(name = "A", url = "https://provider.example/a", profiles = profiles,
            selectedProfileId = profiles[1].id)
        val b = Subscription(name = "B", url = "https://provider.example/b", profiles = profiles,
            selectedProfileId = profiles[0].id)
        val disk = Disk()
        disk.open().save(listOf(a, b), b.id)
        val custom = "CaseSensitive123=="
        disk.open().save(listOf(a.copy(hwid = custom), b), b.id)
        assertEquals(custom, disk.open().load().subscriptions[0].hwid)
        val regenerated = SubscriptionIdentity.generate()
        disk.open().save(listOf(a.copy(hwid = regenerated), b), b.id)
        val restarted = disk.open().load()
        assertEquals(regenerated, restarted.subscriptions[0].hwid)
        assertEquals(b.hwid, restarted.subscriptions[1].hwid)
        assertEquals(b.id, restarted.selectedSubscriptionId)
        assertEquals(listOf("VLESS", "HY2"), restarted.subscriptions[0].profiles.map { it.protocol })
        assertEquals(profiles[1].rawUri, restarted.subscriptions[0].profiles.single {
            it.id == restarted.subscriptions[0].selectedProfileId
        }.rawUri)
    }

    @Test fun malformedV040NodeBlocksMigrationWithoutDiscardingAnyStoredData() {
        val item = JSONObject().put("id", "a").put("name", "mixed").put("url", "https://provider.example/a")
            .put("profiles", JSONArray().put("vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls").put("hy2://@hy2.example"))
        val legacy = JSONObject().put("subscriptions", JSONArray().put(item)).toString()
        val disk = Disk(legacy)
        val store = disk.open()
        assertNotNull(store.load().loadError)
        assertEquals(legacy, disk.raw)
        assertEquals(0, disk.writes)
        assertTrue(runCatching { store.save(emptyList(), null) }.isFailure)
        assertEquals(legacy, disk.raw)
    }
}
