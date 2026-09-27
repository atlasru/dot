package dev.dotclient.android.core.parser

import dev.dotclient.android.core.model.ProxyConfig
import dev.dotclient.android.core.subscription.SecretRedactor
import dev.dotclient.android.core.subscription.SubscriptionDiffer
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class Hysteria2UriParserTest {
    @Test fun aliasIpv6UnicodeAndMask() {
        val node = Hysteria2UriParser.parse("hy2://a%40b%3Ac@[::1]:8443?obfs=salamander&obfs-password=secret&packetSize=512-1200&alpn=h3,h2#%D0%A2%D0%B5%D1%81%D1%82").getOrThrow()
        assertEquals("Тест", node.name)
        assertEquals("::1", node.host)
        val config = node.config as ProxyConfig.Hysteria2
        assertEquals("a@b:c", config.auth)
        assertEquals("512-1200", config.packetSize)
    }

    @Test fun mixedSubscriptionAndRefresh() {
        val vless = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=tls#vless"
        val hy2 = "hysteria2://old@example.com:443?sni=one#hy2"
        val encoded = Base64.getEncoder().encodeToString("$vless\n$hy2\nunsupported://node".toByteArray())
        val decoded = SubscriptionDecoder.decode(encoded)
        assertEquals(SubscriptionDecoder.DecodeResult.Format.BASE64, decoded.format)
        assertEquals(2, decoded.profiles.size)
        val edited = Hysteria2UriParser.parse("hy2://new@example.com:443?sni=two#hy2").getOrThrow()
        val diff = SubscriptionDiffer.calculate(decoded.profiles, listOf(decoded.profiles[0], edited))
        assertEquals(1, diff.edited.size)
        assertEquals(edited.id, diff.replacementFor(decoded.profiles[1].id)?.id)
    }

    @Test fun rejectsInvalidAndRedactsSecrets() {
        assertTrue(Hysteria2UriParser.parse("hy2://@example.com").isFailure)
        assertTrue(Hysteria2UriParser.parse("hy2://pass@example.com?obfs=salamander").isFailure)
        assertTrue(Hysteria2UriParser.parse("hy2://pass@example.com?obfs=salamander&obfs-password=x&packetSize=3000").isFailure)
        val redacted = SecretRedactor.raw("hy2://secret@example.com?obfs-password=maskSecret", "")
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("maskSecret"))
        val node = Hysteria2UriParser.parse("hy2://secret@example.com?obfs=salamander&obfs-password=maskSecret").getOrThrow()
        assertEquals("*** and ***", node.redact("secret and maskSecret"))
    }
}
