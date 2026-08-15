package dev.dotclient.android.core.subscription

import dev.dotclient.android.core.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDifferTest {
    @Test
    fun identicalProfilesAreUnchanged() {
        val old = profile(name = "France 1")
        val fresh = old.copy(id = "fresh-id")

        val diff = SubscriptionDiffer.calculate(listOf(old), listOf(fresh))

        assertFalse(diff.hasChanges)
        assertEquals(1, diff.unchanged.size)
        assertSame(fresh, diff.replacementFor(old.id))
    }

    @Test
    fun addedAndDeletedProfilesAreDetected() {
        val deleted = profile(name = "Germany", host = "de.example")
        val added = profile(name = "Finland", host = "fi.example")

        val diff = SubscriptionDiffer.calculate(listOf(deleted), listOf(added))

        assertEquals(listOf(added), diff.added)
        assertEquals(listOf(deleted), diff.deleted)
        assertTrue(diff.edited.isEmpty())
    }

    @Test
    fun sameIdentityWithChangedConfigurationIsEdited() {
        val old = profile(name = "France 1", sni = "old.example")
        val fresh = profile(name = "France 2", sni = "new.example")

        val diff = SubscriptionDiffer.calculate(listOf(old), listOf(fresh))

        assertEquals(1, diff.edited.size)
        assertTrue("name" in diff.edited.single().changedFields)
        assertTrue("SNI" in diff.edited.single().changedFields)
        assertEquals(fresh.id, diff.replacementFor(old.id)?.id)
    }

    @Test
    fun duplicateIdentityMatchesExactUriBeforeEditPairing() {
        val exact = profile(name = "tcp", rawUri = "vless://same@node.example:443?type=tcp#tcp")
        val oldOther = profile(name = "ws", rawUri = "vless://same@node.example:443?type=ws#ws")
        val freshExact = exact.copy(id = "exact-new")
        val freshOther = oldOther.copy(id = "ws-new", name = "ws renamed", rawUri = "vless://same@node.example:443?type=ws#ws-renamed")

        val diff = SubscriptionDiffer.calculate(listOf(exact, oldOther), listOf(freshOther, freshExact))

        assertEquals("exact-new", diff.replacementFor(exact.id)?.id)
        assertEquals("ws-new", diff.replacementFor(oldOther.id)?.id)
        assertEquals(1, diff.edited.size)
    }

    private fun profile(
        name: String,
        host: String = "node.example",
        sni: String? = "node.example",
        rawUri: String = "vless://same@$host:443?security=reality#$name",
    ) = VlessProfile(
        id = "$name-id",
        name = name,
        host = host,
        port = 443,
        userId = "same",
        security = VlessProfile.Security.REALITY,
        sni = sni,
        rawUri = rawUri,
    )
}
