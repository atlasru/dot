package dev.dotclient.android.core.subscription

import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.model.NodeSortMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionStoreIdentityTest {
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
}
