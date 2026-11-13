package dev.dotclient.android.ui

import dev.dotclient.android.core.diagnostics.ConnectionProbeFailure
import dev.dotclient.android.core.diagnostics.ConnectionProbeResult
import dev.dotclient.android.core.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestDisplayTest {
    @Test
    fun successReportsProxyLatencyAndTestingDoesNotReuseIt() {
        val state = DotUiState(connectionTestResult = ConnectionProbeResult.Success(124))

        assertEquals(ConnectionTestDisplay(label = "124 ms via proxy"), state.connectionTestDisplay())
        assertEquals(ConnectionTestDisplay(label = "TESTING…"), state.copy(connectionTestRunning = true).connectionTestDisplay())
    }

    @Test
    fun proxyFailureShowsRecoveryAdviceAndHidesTechnicalDetailsByDefault() {
        val state = DotUiState(connectionTestResult = ConnectionProbeResult.Failure(
            ConnectionProbeFailure.PROXY_UNAVAILABLE,
            "Connection timed out",
        ))

        val normal = state.connectionTestDisplay()
        assertTrue(normal.failed)
        assertEquals("PROXY UNAVAILABLE", normal.label)
        assertEquals("Reconnect the VPN and retry.", normal.advice)
        assertNull(normal.detail)

        val detailed = state.copy(settings = AppSettings(showDetailedTestErrors = true)).connectionTestDisplay()
        assertEquals(normal.copy(detail = "Connection timed out"), detailed)
    }

    @Test
    fun inactiveVpnAndInvalidEndpointSuggestDistinctNextSteps() {
        val inactive = DotUiState(connectionTestResult = ConnectionProbeResult.Failure(ConnectionProbeFailure.NO_ACTIVE_TUNNEL))
            .connectionTestDisplay()
        val invalidUrl = DotUiState(connectionTestResult = ConnectionProbeResult.Failure(ConnectionProbeFailure.INVALID_ENDPOINT))
            .connectionTestDisplay()

        assertEquals("VPN INACTIVE", inactive.label)
        assertEquals("Connect to a VPN node and retry.", inactive.advice)
        assertEquals("INVALID TEST URL", invalidUrl.label)
        assertEquals("Set a valid HTTPS Test URL in Settings under Ping & Diagnostics.", invalidUrl.advice)
        assertFalse(DotUiState().connectionTestDisplay().failed)
    }
}
