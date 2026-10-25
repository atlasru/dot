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
        assertFalse(good.verified(settings.copy(url="https://example.com/")))
        val tcp=measure(settings.copy(method=PingMethod.TCP)) { 1L }
        assertFalse(tcp.verified(tcp.settings))
        assertEquals(good,PingResult.from(good.json()))
    }
    @Test fun `GET and HEAD go through specified HTTP proxy and reject bad status`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            val settings=PingSettings(url="http://destination.invalid/generate_204",attempts=1)
            proxy.enqueue(MockResponse().setResponseCode(204))
            assertTrue(httpAttempt(settings,proxy.port)>=1)
            assertEquals("GET",proxy.takeRequest().method)
            proxy.enqueue(MockResponse().setResponseCode(204))
            assertTrue(httpAttempt(settings.copy(method=PingMethod.PROXY_HEAD),proxy.port)>=1)
            assertEquals("HEAD",proxy.takeRequest().method)
            proxy.enqueue(MockResponse().setResponseCode(405))
            val result=measure(settings.copy(method=PingMethod.PROXY_HEAD)) { httpAttempt(settings.copy(method=PingMethod.PROXY_HEAD),proxy.port) }
            assertFalse(result.success)
            assertTrue(result.error!!.contains("HEAD unsupported"))
            proxy.enqueue(MockResponse().setResponseCode(503))
            assertFalse(measure(settings) { httpAttempt(settings,proxy.port) }.success)
        }
    }
    @Test fun `HTTP timeout and cancellation free request resources`() = runBlocking {
        MockWebServer().use { proxy ->
            proxy.start()
            val settings=PingSettings(url="http://destination.invalid/",timeoutSeconds=3,attempts=1)
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val timed=measure(settings) { httpAttempt(settings,proxy.port) }
            assertFalse(timed.success)
            proxy.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job=async { httpAttempt(settings,proxy.port) }
            delay(100)
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }
}
