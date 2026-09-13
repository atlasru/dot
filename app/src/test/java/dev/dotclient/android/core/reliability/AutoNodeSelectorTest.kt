package dev.dotclient.android.core.reliability

import dev.dotclient.android.core.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoNodeSelectorTest {
    @Test
    fun `selects lowest successful latency`() {
        val slow = profile("slow")
        val fast = profile("fast")

        val selected = AutoNodeSelector.select(
            profiles = listOf(slow, fast),
            latenciesMs = mapOf(slow.id to 120L, fast.id to 35L),
            failedIds = emptySet(),
            preferredProfileId = slow.id,
        )

        assertEquals(fast.id, selected?.id)
    }

    @Test
    fun `ignores failed nodes even when they have stale latency`() {
        val failed = profile("failed")
        val healthy = profile("healthy")

        val selected = AutoNodeSelector.select(
            profiles = listOf(failed, healthy),
            latenciesMs = mapOf(failed.id to 10L, healthy.id to 80L),
            failedIds = setOf(failed.id),
        )

        assertEquals(healthy.id, selected?.id)
    }

    @Test
    fun `falls back to preferred node when no latency exists`() {
        val first = profile("first")
        val preferred = profile("preferred")

        val selected = AutoNodeSelector.select(
            profiles = listOf(first, preferred),
            latenciesMs = emptyMap(),
            failedIds = emptySet(),
            preferredProfileId = preferred.id,
        )

        assertEquals(preferred.id, selected?.id)
    }

    @Test
    fun `returns null for empty profile list`() {
        assertNull(AutoNodeSelector.select(emptyList(), emptyMap(), emptySet()))
    }

    private fun profile(name: String) = VlessProfile(
        name = name,
        host = "$name.example.com",
        port = 443,
        userId = "00000000-0000-0000-0000-000000000000",
        rawUri = "vless://00000000-0000-0000-0000-000000000000@$name.example.com:443",
    )
}
