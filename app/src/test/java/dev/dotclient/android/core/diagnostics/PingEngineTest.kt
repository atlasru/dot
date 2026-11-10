package dev.dotclient.android.core.diagnostics

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PingEngineTest {
    @Test fun `defaults migration roundtrip and validation`() {
        val defaults=PingSettings.from(JSONObject())
        assertEquals(PingMethod.PROXY_GET,defaults.method)
        assertEquals(3,defaults.attempts)
        assertEquals(4,defaults.parallelism)
        assertEquals(Aggregation.MEDIAN,defaults.aggregation)
        assertFalse(defaults.onStart)
        assertEquals(defaults,PingSettings.from(defaults.json()))
        assertTrue(runCatching { defaults.copy(url="https://user:secret@example.com").validate() }.isFailure)
        assertTrue(runCatching { defaults.copy(url="http://example.com/").validate() }.isFailure)
        assertTrue(runCatching { defaults.copy(parallelism=100).validate() }.isFailure)
    }
    @Test fun `aggregation uses real samples without subtracting overhead`() {
        assertEquals(20L,aggregate(listOf(200,10,20),Aggregation.MEDIAN))
        assertEquals(15L,aggregate(listOf(10,20),Aggregation.MEDIAN))
        assertEquals(76L,aggregate(listOf(200,10,20),Aggregation.AVERAGE))
        assertEquals(10L,aggregate(listOf(200,10,20),Aggregation.MINIMUM))
        assertEquals(20L,aggregate(listOf(200,10,20),Aggregation.LAST))
        assertNull(aggregate(emptyList(),Aggregation.MEDIAN))
    }
    @Test fun `all attempts must succeed and TCP never confirms proxy health`() = runBlocking {
        val settings=PingSettings(attempts=2)
        var call=0
        val partial=measure(settings) { if(call++==0) 10L else error("timeout") }
        assertFalse(partial.success)
        assertNull(partial.latencyMs)
        assertEquals(listOf(10L),partial.samples)
        val good=measure(settings) { 50L }
        assertTrue(good.verified(settings))
        assertFalse(good.copy(activeTunnel = true).verified(settings))
        assertFalse(good.verified(settings.copy(url="https://example.com/")))
        val tcp=measure(settings.copy(method=PingMethod.TCP)) { 1L }
        assertFalse(tcp.verified(tcp.settings))
        assertEquals(good,PingResult.from(good.json()))
    }
    @Test fun `GET and HEAD go through specified HTTP proxy and reject bad status`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            val settings=PingSettings(url="http://destination.invalid/generate_204",attempts=1)
            val route = loopbackProxy(proxy.port)
            proxy.enqueue(MockResponse().setResponseCode(204))
            assertTrue(httpAttempt(settings,route)>=1)
            val request = proxy.takeRequest()
            assertEquals("GET",request.method)
            assertEquals("GET http://destination.invalid/generate_204 HTTP/1.1", request.requestLine)
            proxy.enqueue(MockResponse().setResponseCode(204))
            assertTrue(httpAttempt(settings.copy(method=PingMethod.PROXY_HEAD),route)>=1)
            assertEquals("HEAD",proxy.takeRequest().method)
            proxy.enqueue(MockResponse().setResponseCode(405))
            val result=measure(settings.copy(method=PingMethod.PROXY_HEAD)) { httpAttempt(settings.copy(method=PingMethod.PROXY_HEAD),route) }
            assertFalse(result.success)
            assertTrue(result.error!!.contains("HEAD unsupported"))
            proxy.enqueue(MockResponse().setResponseCode(503))
            assertFalse(measure(settings) { httpAttempt(settings,route) }.success)
        }
    }
    @Test fun `HTTP timeout and cancellation free request resources`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            val settings=PingSettings(url="http://destination.invalid/",timeoutSeconds=3,attempts=1)
            val route = loopbackProxy(proxy.port)
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val timed=measure(settings) { httpAttempt(settings,route) }
            assertFalse(timed.success)
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job=async { httpAttempt(settings,route) }
            delay(100)
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun `default HTTPS request requires a CONNECT through the proxy`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            proxy.enqueue(MockResponse().setResponseCode(503))
            val settings = PingSettings(attempts = 1)
            val result = measure(settings) { httpAttempt(settings, loopbackProxy(proxy.port)) }
            assertFalse(result.success)
            val request = proxy.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            assertEquals("CONNECT cp.cloudflare.com:443 HTTP/1.1", request.requestLine)
        }
    }

    @Test fun `proxy batch maps each listener to its node with a blocked default`() {
        val ports = allocateLoopbackPorts(8)
        assertEquals(8, ports.toSet().size)
        val nodes = listOf(JSONObject().put("protocol", "vless"), JSONObject().put("protocol", "hysteria"))
        val config = DiagnosticConfig.batch(nodes, ports.take(2))
        val outbounds = config.getJSONArray("outbounds")
        assertEquals("blackhole", outbounds.getJSONObject(0).getString("protocol"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val inbounds = config.getJSONArray("inbounds")
        nodes.indices.forEach { index ->
            val inbound = inbounds.getJSONObject(index)
            val outbound = outbounds.getJSONObject(index + 1)
            val rule = rules.getJSONObject(index)
            assertEquals(ports[index], inbound.getInt("port"))
            assertEquals(inbound.getString("tag"), rule.getJSONArray("inboundTag").getString(0))
            assertEquals(outbound.getString("tag"), rule.getString("outboundTag"))
            assertEquals(nodes[index].getString("protocol"), outbound.getString("protocol"))
        }
    }

    @Test fun `ICMP permissions and timeouts are distinguished`() {
        assertTrue(isIcmpUnavailable("ping: socket: Operation not permitted"))
        assertTrue(isIcmpUnavailable("ping: unknown option -- 6"))
        assertFalse(isIcmpUnavailable("1 packets transmitted, 0 received"))
    }
}
