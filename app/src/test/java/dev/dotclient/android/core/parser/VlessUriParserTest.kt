package dev.dotclient.android.core.parser

import dev.dotclient.android.core.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlessUriParserTest {
    @Test
    fun parsesRealityVision() {
        val uri = "vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none&security=reality&flow=xtls-rprx-vision&fp=chrome&pbk=PUBLIC&sid=abcd&type=tcp&sni=example.org#Paris%2001"
        val result = VlessUriParser.parse(uri).getOrThrow()

        assertEquals("Paris 01", result.name)
        assertEquals("example.com", result.host)
        assertEquals(443, result.port)
        assertEquals(VlessProfile.Security.REALITY, result.security)
        assertEquals(VlessProfile.Transport.TCP, result.transport)
        assertEquals("xtls-rprx-vision", result.flow)
        assertEquals("chrome", result.fingerprint)
        assertEquals("PUBLIC", result.publicKey)
        assertEquals("abcd", result.shortId)
    }

    @Test
    fun rejectsNonVless() {
        assertTrue(VlessUriParser.parse("https://example.com").isFailure)
    }
}
