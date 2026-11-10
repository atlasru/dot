package dev.dotclient.android.core.diagnostics

import dev.dotclient.android.vpn.ActiveProxySession
import dev.dotclient.android.vpn.VpnConnectionState
import dev.dotclient.android.vpn.VpnRuntimeSnapshot
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectionProbeTest {
    @Test
    fun `connected session uses the active outbound whether or not dot is included in the VPN`() = runBlocking {
        val session = session()
        for (appInTunnel in listOf(true, false)) {
            val runtime = connected(session, appInTunnel)
            val result = probeConnection(PingSettings(), { runtime }) { active, settings ->
                assertSame(session, active)
                assertEquals(PingMethod.PROXY_GET, settings.method)
                assertEquals("https://cp.cloudflare.com/generate_204", settings.url)
                measure(settings) { 42 }
            }
            assertEquals(ConnectionProbeResult.Success(42), result)
        }
    }

    @Test
    fun `custom HTTPS URL is used through the explicit proxy even when node diagnostics uses TCP`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.enqueue(MockResponse().setResponseCode(503))
            val url = "https://check.example.test:8443/status?mode=probe"
            val settings = PingSettings(method = PingMethod.TCP, url = url, attempts = 1)
            val runtime = connected(session(), appInTunnel = false)
            val result = probeConnection(settings, { runtime }) { _, proxySettings ->
                assertEquals(url, proxySettings.url)
                assertEquals(PingMethod.PROXY_GET, proxySettings.method)
                measure(proxySettings) { httpAttempt(proxySettings, loopbackProxy(proxy.port)) }
            }
            assertEquals(ConnectionProbeFailure.REQUEST_FAILED, (result as ConnectionProbeResult.Failure).reason)
            assertEquals("CONNECT check.example.test:8443 HTTP/1.1", proxy.takeRequest(1, TimeUnit.SECONDS)?.requestLine)
        }
    }

    @Test
    fun `a failed explicit proxy cannot fall back to an available origin`() = runBlocking {
        MockWebServer().use { origin ->
            MockWebServer().use { proxy ->
                origin.enqueue(MockResponse().setResponseCode(204))
                proxy.enqueue(MockResponse().setResponseCode(503))
                val settings = PingSettings(url = origin.url("/probe").toString(), attempts = 1)
                val result = measure(settings) { httpAttempt(settings, loopbackProxy(proxy.port)) }
                assertEquals(PingStatus.UNAVAILABLE, result.status)
                assertEquals(0, origin.requestCount)
                assertEquals("GET ${settings.url} HTTP/1.1", proxy.takeRequest(1, TimeUnit.SECONDS)?.requestLine)
            }
        }
    }

    @Test
    fun `missing or unavailable active proxy and disconnected VPN report separate reasons`() = runBlocking {
        val inactive = probeConnection(PingSettings(), { VpnRuntimeSnapshot() }) { _, _ -> error("must not probe") }
        assertEquals(ConnectionProbeResult.Failure(ConnectionProbeFailure.NO_ACTIVE_TUNNEL), inactive)

        val missing = probeConnection(PingSettings(), { connected(null) }) { _, _ -> error("must not probe") }
        assertEquals(ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE), missing)

        val runtime = connected(session())
        val failed = probeConnection(PingSettings(), { runtime }) { _, _ ->
            throw ProbeFailure("Active proxy listener could not start")
        }
        assertEquals(ConnectionProbeResult.Failure(ConnectionProbeFailure.PROXY_UNAVAILABLE,
            "Active proxy listener could not start"), failed)
    }

    @Test
    fun `invalid endpoint and a replaced session cannot produce a success`() = runBlocking {
        var runtime = connected(session())
        val invalid = probeConnection(PingSettings(url = "http://check.example.test/"), { runtime }) { _, _ -> error("must not probe") }
        assertEquals(ConnectionProbeResult.Failure(ConnectionProbeFailure.INVALID_ENDPOINT), invalid)

        val stale = probeConnection(PingSettings(), { runtime }) { _, settings ->
            runtime = connected(session())
            measure(settings) { 25 }
        }
        assertEquals(ConnectionProbeResult.Failure(ConnectionProbeFailure.SESSION_CHANGED), stale)
    }

    @Test
    fun `connection diagnostics copies only a supported outbound into a fail closed proxy`() {
        val direct = JSONObject().put("tag", "direct").put("protocol", "freedom")
        assertNull(DiagnosticConfig.selectedProxyOutbound(JSONObject().put("outbounds", JSONArray().put(direct))))

        for (protocol in listOf("vless", "hysteria")) {
            val selected = JSONObject().put("tag", "proxy").put("protocol", protocol)
                .put("streamSettings", JSONObject().put("security", "tls"))
            val active = JSONObject().put("outbounds", JSONArray().put(selected).put(direct))
            val outbound = checkNotNull(DiagnosticConfig.selectedProxyOutbound(active))
            val diagnostic = DiagnosticConfig.batch(listOf(outbound), listOf(18080))
            val outbounds = diagnostic.getJSONArray("outbounds")
            assertEquals(2, outbounds.length())
            assertEquals("blackhole", outbounds.getJSONObject(0).getString("protocol"))
            assertEquals(protocol, outbounds.getJSONObject(1).getString("protocol"))
            assertEquals("tls", outbounds.getJSONObject(1).getJSONObject("streamSettings").getString("security"))
            assertEquals("proxy", selected.getString("tag"))
            val route = diagnostic.getJSONObject("routing").getJSONArray("rules").getJSONObject(0)
            assertEquals("diagnostic-0", route.getJSONArray("inboundTag").getString(0))
            assertEquals("diagnostic-0", route.getString("outboundTag"))
        }
    }

    private fun session() = ActiveProxySession("""{"tag":"proxy","protocol":"vless"}""", "1.1.1.1", null)

    private fun connected(session: ActiveProxySession?, appInTunnel: Boolean = true) = VpnRuntimeSnapshot(
        state = VpnConnectionState.CONNECTED,
        nodeId = "active-node",
        appInTunnel = appInTunnel,
        proxySession = session,
    )
}
