package dev.dotclient.android.core.reliability

import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoNodeSelectorTest {
    private val settings=PingSettings()
    private fun result(ms: Long, success: Boolean=true) = PingResult(settings,ms,System.currentTimeMillis(),success,null,listOf(ms))
    @Test
    fun `selects lowest successful latency`() {
        val slow = profile("slow")
        val fast = profile("fast")

        val selected = AutoNodeSelector.selectVerified(
            profiles = listOf(slow, fast),
            results = mapOf(slow.id to result(120L), fast.id to result(35L)),
            settings = settings,
        )

        assertEquals(fast.id, selected?.id)
    }

    @Test
    fun `ignores failed nodes even when they have stale latency`() {
        val failed = profile("failed")
        val healthy = profile("healthy")

        val selected = AutoNodeSelector.selectVerified(
            profiles = listOf(failed, healthy),
            results = mapOf(failed.id to result(10L,false), healthy.id to result(80L)),
            settings = settings,
        )

        assertEquals(healthy.id, selected?.id)
    }

    @Test
    fun `does not fall back to an untested preferred node`() {
        val first = profile("first")
        val preferred = profile("preferred")

        val selected = AutoNodeSelector.selectVerified(
            profiles = listOf(first, preferred),
            results = emptyMap(),
            settings = settings,
        )

        assertNull(selected)
    }

    @Test
    fun `returns null for empty profile list`() {
        assertNull(AutoNodeSelector.selectVerified(emptyList(), emptyMap(), settings))
    }

    private fun profile(name: String) = ProxyNode(
        name = name,
        host = "$name.example.com",
        port = 443,
        userId = "00000000-0000-0000-0000-000000000000",
        rawUri = "vless://00000000-0000-0000-0000-000000000000@$name.example.com:443",
    )
}
