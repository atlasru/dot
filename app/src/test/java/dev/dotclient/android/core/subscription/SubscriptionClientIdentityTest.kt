package dev.dotclient.android.core.subscription

import dev.dotclient.android.BuildConfig
import dev.dotclient.android.core.model.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.Base64

class SubscriptionClientIdentityTest {
    private val link = "vless://11111111-1111-4111-8111-111111111111@example.test:443?security=tls#node"
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun server() = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
    private fun http() = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("X-Provider-Custom", "preserved").build()) }
        .build()
    private fun sub(url: String, hwid: String) = Subscription(name = "test", url = url, hwid = hwid)

    @Test fun builderPreservesExactIdentityAndExistingHeaders() {
        val client = SubscriptionClient(http(), osVersion = { "16" })
        val request = client.request(sub("https://example.test/private", "ABC123-existing-Value=="))
        assertEquals("ABC123-existing-Value==", request.header("x-hwid"))
        assertEquals("Android", request.header("x-device-os"))
        assertEquals("16", request.header("x-ver-os"))
        assertEquals("dot Android", request.header("x-device-model"))
        assertEquals("*/*", request.header("Accept"))
        assertEquals("dot/${BuildConfig.VERSION_NAME} (Android)", request.header("User-Agent"))
        assertTrue(runCatching { client.request(sub("https://example.test/", "bad\r\nvalue")) }.isFailure)
    }

    @Test fun initialFetchRetriesAndBackgroundRequestsUseTheirOwnPersistedIdentity() = runBlocking {
        server().use { server ->
            val http = http()
            val client = SubscriptionClient(http, osVersion = { "16" })
            val a = sub(server.url("/a").toString(), "AAAAAAAAAA-first")
            val b = sub(server.url("/b").toString(), "BBBBBBBBBB-second")
            var raw: String? = null
            val store = SubscriptionStore({ raw }, { raw = it; true })
            store.save(listOf(a, b), b.id)
            val saved = store.load().subscriptions
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(client.fetch(saved[0]).isFailure)
            assertEquals(a.hwid, server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("x-hwid"))
            repeat(2) { server.enqueue(MockResponse().setBody(link)) }
            withContext(Dispatchers.Default) {
                val first = async { client.fetch(saved[0]).getOrThrow() }
                val second = async { client.fetch(saved[1]).getOrThrow() }
                assertEquals(1, first.await().profiles.size)
                assertEquals(1, second.await().profiles.size)
            }
            val requests = (1..2).map { server.takeRequest(5, TimeUnit.SECONDS)!! }.associateBy { it.path }
            assertEquals(a.hwid, requests.getValue("/a").getHeader("x-hwid"))
            assertEquals(b.hwid, requests.getValue("/b").getHeader("x-hwid"))
            assertEquals("preserved", requests.getValue("/a").getHeader("X-Provider-Custom"))
            server.enqueue(MockResponse().setBody("unrelated"))
            http.newCall(Request.Builder().url(server.url("/update-check")).build()).execute().close()
            assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("x-hwid"))
        }
    }

    @Test fun sameOriginRedirectKeepsIdentityAndDifferentOriginStripsAllMetadata() = runBlocking {
        server().use { first -> server().use { second ->
            val client = SubscriptionClient(http(), osVersion = { "16" })
            val a = sub(first.url("/start").toString(), "ABC1234567890")
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/same-origin"))
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/cdn")))
            second.enqueue(MockResponse().setBody(link))
            client.fetch(a).getOrThrow()
            repeat(2) { assertEquals(a.hwid, first.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("x-hwid")) }
            val redirect = second.takeRequest(5, TimeUnit.SECONDS)!!
            listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model").forEach { assertNull(redirect.getHeader(it)) }
        } }
    }

    @Test fun vlessHy2AndMixedDownloadsKeepImportIdentityAcrossRedirectsAndReload() = runBlocking {
        val hy2 = "hy2://auth-secret@hy2.example:8443?sni=tls.example&obfs=salamander&obfs-password=mask-secret#HY2"
        val variants = listOf(
            link to listOf("VLESS"),
            hy2 to listOf("HY2"),
            Base64.getEncoder().encodeToString("$link\n$hy2".toByteArray()) to listOf("VLESS", "HY2"),
        )
        server().use { origin -> server().use { cdn ->
            val client = SubscriptionClient(http(), osVersion = { "16" })
            var raw: String? = null
            val store = SubscriptionStore({ raw }, { raw = it; true })
            var subscriptions = emptyList<Subscription>()
            variants.forEachIndexed { index, (body, protocols) ->
                val subscription = sub(origin.url("/import/$index").toString(), "CUSTOM123456789-$index")
                subscriptions = subscriptions + subscription
                store.save(subscriptions, subscription.id)
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/same/$index"))
                origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", cdn.url("/body/$index")))
                cdn.enqueue(MockResponse().setBody(body))
                val decoded = client.fetch(subscription).getOrThrow()
                assertEquals(protocols, decoded.profiles.map { it.protocol })
                repeat(2) {
                    val request = origin.takeRequest(5, TimeUnit.SECONDS)!!
                    assertEquals(subscription.hwid, request.getHeader("x-hwid"))
                    assertEquals("Android", request.getHeader("x-device-os"))
                    assertEquals("16", request.getHeader("x-ver-os"))
                    assertEquals("dot Android", request.getHeader("x-device-model"))
                }
                val redirected = cdn.takeRequest(5, TimeUnit.SECONDS)!!
                listOf("x-hwid", "x-device-os", "x-ver-os", "x-device-model").forEach {
                    assertNull(redirected.getHeader(it))
                }
                assertEquals("preserved", redirected.getHeader("X-Provider-Custom"))
                subscriptions = subscriptions.map { if (it.id == subscription.id) it.copy(profiles = decoded.profiles) else it }
                store.save(subscriptions, subscription.id)
                val restarted = store.load().subscriptions.single { it.id == subscription.id }
                assertEquals(subscription.hwid, restarted.hwid)
                assertEquals(protocols, restarted.profiles.map { it.protocol })
            }
        } }
    }
}
