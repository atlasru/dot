package dev.dotclient.android.core.parser

import dev.dotclient.android.core.model.ProxyNode
import dev.dotclient.android.core.subscription.SubscriptionDiffer
import dev.dotclient.android.core.subscription.nodeIdentity
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TransportRegressionTest {
    @Test fun hysteria2SchemesAndOptionsUseUdpAndTls() {
        val uris = listOf(
            "hysteria2://test-auth@hy2.example:443#HY2",
            "hy2://test-auth@hy2.example:443#HY2",
            "hysteria2://test-auth@hy2.example:8443?sni=tls.example&alpn=h3&insecure=1#HY2",
            "hy2://test-auth@hy2.example:8443?obfs=salamander&obfs-password=test-mask&packetSize=512-1200#HY2",
        )
        uris.forEach { uri ->
            val node = Hysteria2UriParser.parse(uri).getOrThrow()
            assertEquals(ProxyNode.Transport.UDP, node.transport)
            assertEquals(ProxyNode.Security.TLS, node.security)
            assertEquals(uri, node.rawUri)
            // Same model-driven expressions used by DotApp's node row and home card.
            assertEquals("HY2 · tls · udp", "${node.protocol} · ${node.security.name.lowercase()} · ${node.transport.name.lowercase()}")
            assertEquals("tls · udp", "${node.security.name.lowercase()} · ${node.transport.name.lowercase()}")
        }
    }

    @Test fun vlessTransportsAndLabelsRemainUnchanged() {
        val transports = listOf(
            "tcp" to ProxyNode.Transport.TCP,
            "ws" to ProxyNode.Transport.WS,
            "grpc" to ProxyNode.Transport.GRPC,
            "xhttp" to ProxyNode.Transport.XHTTP,
            "httpupgrade" to ProxyNode.Transport.HTTPUPGRADE,
            "raw" to ProxyNode.Transport.TCP,
            "splithttp" to ProxyNode.Transport.XHTTP,
            "unknown" to ProxyNode.Transport.UNKNOWN,
        )
        transports.forEach { (type, expected) ->
            val uri = "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls&type=$type&sni=tls.example&path=%2Ftest&host=cdn.example&serviceName=test-service#VLESS"
            val node = VlessUriParser.parse(uri).getOrThrow()
            assertEquals(expected, node.transport)
            assertEquals(ProxyNode.Security.TLS, node.security)
            assertEquals("tls.example", node.sni)
            assertEquals("/test", node.path)
            assertEquals("cdn.example", node.hostHeader)
            assertEquals("test-service", node.serviceName)
            assertEquals(uri, node.rawUri)
            assertEquals("VLESS · tls · ${expected.name.lowercase()}", "${node.protocol} · ${node.security.name.lowercase()} · ${node.transport.name.lowercase()}")
        }
        assertEquals(ProxyNode.Transport.TCP, VlessUriParser.parse(
            "vless://11111111-1111-4111-8111-111111111111@vless.example:443"
        ).getOrThrow().transport)
    }

    @Test fun udpIsAppendedAndModelDefaultRemainsTcp() {
        assertEquals(listOf("TCP", "WS", "GRPC", "XHTTP", "HTTPUPGRADE", "UNKNOWN", "UDP"),
            ProxyNode.Transport.entries.map { it.name })
        val node = ProxyNode(name = "test", host = "vless.example", port = 443,
            userId = "11111111-1111-4111-8111-111111111111", rawUri = "test")
        assertEquals(ProxyNode.Transport.TCP, node.transport)
    }

    @Test fun existingPlaintextAndBase64SubscriptionsDecodeWithoutDuplicates() {
        val body = "vless://11111111-1111-4111-8111-111111111111@vless.example:443?security=tls&type=ws#VLESS\n" +
            "hysteria2://test-auth@hy2.example:443?sni=tls.example#HY2\n" +
            "hy2://test-auth@alias.example:8443?obfs=salamander&obfs-password=test-mask#alias"
        listOf(body, Base64.getEncoder().encodeToString(body.toByteArray())).forEach { input ->
            val result = SubscriptionDecoder.decode(input)
            assertTrue(result.rejectedLines.isEmpty())
            assertEquals(listOf(ProxyNode.Transport.WS, ProxyNode.Transport.UDP, ProxyNode.Transport.UDP),
                result.profiles.map { it.transport })
            assertEquals(body.lines(), result.profiles.map { it.rawUri })
        }
    }

    @Test fun correctingLegacyTcpDoesNotChangeSubscriptionIdentityOrDiff() {
        val uri = "hy2://test-auth@hy2.example:443?sni=tls.example#HY2"
        val current = Hysteria2UriParser.parse(uri).getOrThrow()
        val legacy = current.copy(id = "legacy-node-id", transport = ProxyNode.Transport.TCP)
        assertEquals(legacy.nodeIdentity(), current.nodeIdentity())
        val diff = SubscriptionDiffer.calculate(listOf(legacy), listOf(current))
        assertFalse(diff.hasChanges)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.deleted.isEmpty())
        assertTrue(diff.edited.isEmpty())
        assertEquals(1, diff.unchanged.size)
        val replacement = diff.replacementFor(legacy.id)!!
        assertEquals(current.id, replacement.id)
        assertEquals(uri, replacement.rawUri)
        assertEquals(ProxyNode.Transport.UDP, replacement.transport)
    }
}
