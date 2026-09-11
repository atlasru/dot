package dev.dotclient.android.core.splittunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelConfigTest {
    @Test
    fun normalizesPackages() {
        val config = SplitTunnelConfig(
            mode = SplitTunnelMode.EXCLUDE_SELECTED,
            packages = setOf(" org.telegram.messenger ", "", "com.android.chrome"),
        )

        assertEquals(
            setOf("com.android.chrome", "org.telegram.messenger"),
            config.normalizedPackages,
        )
        assertTrue(config.isActive)
    }

    @Test
    fun allAppsIsNeverActive() {
        val config = SplitTunnelConfig(
            mode = SplitTunnelMode.ALL_APPS,
            packages = setOf("org.telegram.messenger"),
        )

        assertFalse(config.isActive)
    }

    @Test
    fun emptySelectionIsNotActive() {
        val config = SplitTunnelConfig(mode = SplitTunnelMode.INCLUDE_ONLY_SELECTED)

        assertFalse(config.isActive)
    }
}
