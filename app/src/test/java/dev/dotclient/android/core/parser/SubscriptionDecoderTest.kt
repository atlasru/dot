package dev.dotclient.android.core.parser

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionDecoderTest {
    private val line = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&type=tcp#node"

    @Test
    fun decodesPlaintext() {
        val result = SubscriptionDecoder.decode(line)
        assertEquals(1, result.profiles.size)
        assertEquals(SubscriptionDecoder.DecodeResult.Format.PLAINTEXT, result.format)
    }

    @Test
    fun decodesBase64() {
        val encoded = Base64.getEncoder().encodeToString(line.toByteArray())
        val result = SubscriptionDecoder.decode(encoded)
        assertEquals(1, result.profiles.size)
        assertEquals(SubscriptionDecoder.DecodeResult.Format.BASE64, result.format)
    }

    @Test
    fun rejectsUnknownBody() {
        val result = SubscriptionDecoder.decode("hello world")
        assertTrue(result.profiles.isEmpty())
        assertEquals(SubscriptionDecoder.DecodeResult.Format.UNSUPPORTED, result.format)
    }
}
