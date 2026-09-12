package dev.dotclient.android.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnErrorClassifierTest {
    @Test
    fun `classifies reality errors before generic handshake errors`() {
        val failure = VpnErrorClassifier.classify(IllegalStateException("REALITY handshake rejected"))
        assertEquals(VpnFailureCategory.REALITY, failure.category)
    }

    @Test
    fun `classifies timeouts`() {
        val failure = VpnErrorClassifier.classify(IllegalStateException("dial tcp: i/o timeout"))
        assertEquals(VpnFailureCategory.TIMEOUT, failure.category)
    }

    @Test
    fun `classifies tun failures`() {
        val failure = VpnErrorClassifier.classify(IllegalStateException("Android failed to establish TUN"))
        assertEquals(VpnFailureCategory.TUN, failure.category)
    }

    @Test
    fun `falls back to unknown`() {
        val failure = VpnErrorClassifier.classify(IllegalStateException("something unexpected"))
        assertEquals(VpnFailureCategory.UNKNOWN, failure.category)
    }
}
