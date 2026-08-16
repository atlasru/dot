package dev.dotclient.android.core.model

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
    fun delayPlacesSuccessfulNodesFirstThenFailedThenUntested() {
        val slow = profile("slow")
        val failed = profile("failed")
        val fast = profile("fast")
        val untested = profile("untested")
        val profiles = listOf(slow, failed, fast, untested)

        val sorted = NodeSorter.sort(
            profiles = profiles,
            mode = NodeSortMode.DELAY,
            latenciesMs = mapOf(slow.id to 140L, fast.id to 28L),
            failedIds = setOf(failed.id),
        )

        assertEquals(listOf("fast", "slow", "failed", "untested"), sorted.map { it.name })
    }

    @Test
    fun delayWithoutAnyResultsKeepsOriginOrder() {
        val profiles = listOf(profile("z"), profile("a"), profile("m"))

        assertEquals(profiles, NodeSorter.sort(profiles, NodeSortMode.DELAY, emptyMap()))
    }

    private fun profile(name: String) = VlessProfile(
        id = "$name-id",
        name = name,
        host = "$name.example",
        port = 443,
        userId = "$name-user",
        rawUri = "vless://$name-user@$name.example:443#$name",
    )
}
