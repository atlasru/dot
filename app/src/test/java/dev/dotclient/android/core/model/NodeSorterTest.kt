package dev.dotclient.android.core.model

import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingSettings
import dev.dotclient.android.core.diagnostics.PingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSorterTest {
    @Test
    fun originPreservesProviderOrder() {
        val profiles = listOf(profile("France #10"), profile("France #2"), profile("France #1"))

        assertEquals(profiles, NodeSorter.sort(profiles, NodeSortMode.ORIGIN, emptyMap()))
    }

    @Test
    fun nameUsesNaturalNumericOrder() {
        val profiles = listOf(profile("France #10"), profile("France #2"), profile("France #1"))

        val sorted = NodeSorter.sort(profiles, NodeSortMode.NAME, emptyMap())

        assertEquals(listOf("France #1", "France #2", "France #10"), sorted.map { it.name })
    }

    @Test
    fun delayRanksSuccessThenUnavailableThenFailureThenUntestedAndKeepsProviderOrderWithinGroups() {
        val slow = profile("slow")
        val fast = profile("fast")
        val firstUnavailable = profile("Z unavailable")
        val secondUnavailable = profile("A unavailable")
        val firstFailed = profile("Z failed")
        val secondFailed = profile("A failed")
        val firstUntested = profile("Z untested")
        val secondUntested = profile("A untested")
        val profiles = listOf(firstUntested, firstFailed, slow, firstUnavailable,
            secondFailed, secondUntested, secondUnavailable, fast)

        val sorted = NodeSorter.sort(
            profiles = profiles,
            mode = NodeSortMode.DELAY,
            results = mapOf(
                slow.id to result(PingStatus.OK, 140),
                fast.id to result(PingStatus.OK, 28),
                secondUnavailable.id to result(PingStatus.UNAVAILABLE),
                firstUnavailable.id to result(PingStatus.UNAVAILABLE),
                secondFailed.id to result(PingStatus.FAIL),
                firstFailed.id to result(PingStatus.FAIL),
            ),
        )

        assertEquals(
            listOf(fast, slow, firstUnavailable, secondUnavailable, firstFailed, secondFailed, firstUntested, secondUntested),
            sorted,
        )
    }

    @Test
    fun delayWithoutAnyResultsKeepsOriginOrder() {
        val profiles = listOf(profile("z"), profile("a"), profile("m"))

        assertEquals(profiles, NodeSorter.sort(profiles, NodeSortMode.DELAY, emptyMap()))
    }

    private fun result(status: PingStatus, latency: Long? = null) = PingResult(
        settings = PingSettings(), latencyMs = latency, testedAtMs = 1,
        status = status, error = when (status) {
            PingStatus.OK -> null
            PingStatus.UNAVAILABLE -> "HTTP request timed out"
            PingStatus.FAIL -> "Proxy authentication rejected"
        }, samples = listOfNotNull(latency),
    )

    private fun profile(name: String) = ProxyNode(
        id = "$name-id",
        name = name,
        host = "$name.example",
        port = 443,
        userId = "$name-user",
        rawUri = "vless://$name-user@$name.example:443#$name",
    )
}
