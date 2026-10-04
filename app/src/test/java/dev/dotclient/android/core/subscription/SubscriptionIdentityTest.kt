package dev.dotclient.android.core.subscription

import dev.dotclient.android.core.model.Subscription
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionIdentityTest {
    @Test fun generationHasCompatibleFormatAndIndependentValues() {
        val values = (1..1000).map { SubscriptionIdentity.generate() }
        assertEquals(1000, values.toSet().size)
        values.forEach { assertTrue(it.matches(Regex("dot-[0-9a-f]{32}"))); assertTrue(SubscriptionIdentity.isValid(it)) }
        val a = Subscription(name = "a", url = "https://example.test/a")
        val b = Subscription(name = "b", url = "https://example.test/b")
        assertNotEquals(a.hwid, b.hwid)
        assertEquals(a.hwid, a.copy(name = "renamed").hwid)
        assertFalse(a.toString().contains(a.hwid))
    }

    @Test fun validationMatchesSharedCrossPlatformFixturesExactly() {
        val source = javaClass.classLoader!!.getResourceAsStream("subscription-hwid.json")!!.bufferedReader().use { it.readText() }
        val cases = JSONObject(source).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            assertEquals("fixture $index", case.getBoolean("valid"), SubscriptionIdentity.isValid(case.getString("value")))
        }
    }
}
