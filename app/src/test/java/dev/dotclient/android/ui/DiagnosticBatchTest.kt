package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.PingMethod
import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingSettings
import dev.dotclient.android.core.diagnostics.PingStatus
import dev.dotclient.android.core.model.NodeSortMode
import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.model.Subscription
import dev.dotclient.android.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticBatchTest {
    @Test
    fun recordsCompletedNodesOnceAndKeepsPartialResultsAfterCancel() {
        val batch = DiagnosticBatch("group", setOf("one", "two"))
        val measured = result(25)
        val partial = batch.record("one", measured).record("one", result(99)).record("other", measured)
        val cancelling = partial.copy(phase = DiagnosticBatchPhase.CANCELLING)
        val cancelled = cancelling.copy(phase = DiagnosticBatchPhase.CANCELLED)

        assertEquals(mapOf("one" to measured), cancelled.results)
        assertEquals(2, cancelled.nodeIds.size)
        assertEquals(DiagnosticBatchCounts(1, 0, 0), cancelled.counts)
        assertEquals(1, cancelled.counts.checked)
        assertEquals(cancelling, cancelling.record("two", measured))
        assertFalse(cancelled.running)
        assertEquals(cancelled, cancelled.record("two", measured))
    }

    @Test
    fun countsEachOutcomeAndExcludesPendingNodes() {
        val batch = DiagnosticBatch("group", setOf("ok", "fail", "unavailable-1", "unavailable-2", "pending"))
            .record("ok", result(20))
            .record("fail", result(null, "Proxy authentication rejected", status = PingStatus.FAIL))
            .record("unavailable-1", result(null, "HTTP request timed out"))
            .record("unavailable-2", result(null, "DNS resolution failed"))
        assertEquals(DiagnosticBatchCounts(ok = 1, failed = 1, unavailable = 2), batch.counts)
        assertEquals(4, batch.counts.checked)
    }

    @Test
    fun delaySortUsesPartialMeasurementsAndSeparatesUnavailableFromUnmeasured() {
        val missing = node("A unmeasured")
        val unavailable = node("B unavailable")
        val measured = node("C measured")
        val settings = PingSettings(method = PingMethod.ICMP)
        val unsupported = result(null, "ICMP unavailable on this device", settings)
        val profiles = listOf(missing, unavailable, measured)
        val state = DotUiState(
            settings = AppSettings(ping = settings),
            subscriptions = listOf(Subscription(id = "group", name = "group", url = "", profiles = profiles, sortMode = NodeSortMode.DELAY)),
            selectedSubscriptionId = "group",
            pingResults = mapOf(unavailable.id to unsupported, measured.id to result(12, settings = settings)),
            diagnosticBatch = DiagnosticBatch("group", profiles.map { it.id }.toSet()),
        )

        assertEquals(PingStatus.UNAVAILABLE, unsupported.status)
        assertEquals(listOf(measured, unavailable, missing), state.sortedProfiles)
    }

    private fun result(
        latency: Long?,
        error: String? = null,
        settings: PingSettings = PingSettings(),
        status: PingStatus = if (latency != null) PingStatus.OK else PingStatus.UNAVAILABLE,
    ) = PingResult(settings = settings, latencyMs = latency, testedAtMs = 1,
        status = status, error = error, samples = listOfNotNull(latency))

    private fun node(name: String) = ProxyNode(
        id = name, name = name, host = "example.com", port = 443,
        userId = "test", rawUri = "vless://test@example.com:443",
    )
}
