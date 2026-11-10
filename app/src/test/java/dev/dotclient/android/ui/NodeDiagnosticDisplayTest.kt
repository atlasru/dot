package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.PingResult
import dev.dotclient.android.core.diagnostics.PingSettings
import dev.dotclient.android.core.diagnostics.PingStatus
import dev.dotclient.android.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeDiagnosticDisplayTest {
    @Test
    fun showsOnlyBadgesByDefaultAndDetailsOnlyForErrorsWhenEnabled() {
        val state = DotUiState(pingResults = mapOf(
            "ok" to result(PingStatus.OK, latency = 124),
            "fail" to result(PingStatus.FAIL, "Proxy authentication rejected"),
            "unavailable" to result(PingStatus.UNAVAILABLE, "HTTP request timed out"),
        ))

        for ((id, badge) in mapOf("ok" to "124 ms", "fail" to "FAIL", "unavailable" to "UNAVAILABLE")) {
            assertEquals(badge, state.nodeDiagnosticDisplay(id).badge)
            assertNull(state.nodeDiagnosticDisplay(id).detail)
        }

        val enabled = state.copy(settings = AppSettings(showDetailedTestErrors = true))
        assertNull(enabled.nodeDiagnosticDisplay("ok").detail)
        assertEquals("Proxy authentication rejected", enabled.nodeDiagnosticDisplay("fail").detail)
        assertEquals("HTTP request timed out", enabled.nodeDiagnosticDisplay("unavailable").detail)
        assertEquals("UNAVAILABLE", enabled.nodeDiagnosticDisplay("unavailable").badge)
    }

    @Test
    fun cancellationSupersedesAnOlderFailureWithoutCountingItOrClearingTheCache() {
        val cancelled = DiagnosticBatch("group", setOf("node"), phase = DiagnosticBatchPhase.CANCELLED)
        val oldFailure = result(PingStatus.FAIL, "Proxy authentication rejected")
        val state = DotUiState(
            settings = AppSettings(showDetailedTestErrors = true),
            selectedSubscriptionId = "group",
            diagnosticBatch = cancelled,
            pingResults = mapOf("node" to oldFailure),
        )

        assertEquals("CANCELLED", state.nodeDiagnosticDisplay("node").badge)
        assertNull(state.nodeDiagnosticDisplay("node").detail)
        assertEquals("--", state.nodeDiagnosticDisplay("untested").badge)
        assertEquals(0, cancelled.counts.checked)
        assertEquals(oldFailure, state.pingResults["node"])

        val retested = state.copy(
            diagnosticBatch = cancelled.supersedeUnchecked("node"),
            pingResults = mapOf("node" to result(PingStatus.OK, latency = 27)),
        )
        assertEquals("27 ms", retested.nodeDiagnosticDisplay("node").badge)
        assertEquals(0, retested.diagnosticBatch?.counts?.checked)
    }

    private fun result(status: PingStatus, reason: String? = null, latency: Long? = null) = PingResult(
        settings = PingSettings(), latencyMs = latency, testedAtMs = 1,
        status = status, error = reason, samples = listOfNotNull(latency),
    )
}
