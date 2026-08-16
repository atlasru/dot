package dev.dotclient.android.core.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test
    fun subscriptionUrlHidesUserTokenAndQuery() {
        val redacted = SecretRedactor.url("https://vpn.example/sub/user/super-secret-token?format=vless")

        assertTrue(redacted.endsWith("/sub/user/••••••••"))
        assertFalse(redacted.contains("super-secret-token"))
        assertFalse(redacted.contains("format=vless"))
    }

    @Test
    fun rawErrorHidesSubscriptionAndVlessCredentials() {
        val subscriptionUrl = "https://vpn.example/sub/user/super-secret-token?format=vless"
        val raw = "request $subscriptionUrl failed for vless://user-uuid@node.example:443#France"

        val redacted = SecretRedactor.raw(raw, subscriptionUrl)

        assertFalse(redacted.contains("super-secret-token"))
        assertFalse(redacted.contains("user-uuid"))
        assertTrue(redacted.contains("/sub/user/••••••••"))
        assertTrue(redacted.contains("vless://***@"))
    }
}
