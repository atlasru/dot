package dev.dotclient.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DotNavigationTest {
    @Test
    fun `back returns to the parent screen and leaves home to the system`() {
        assertEquals(Screen.SETTINGS, Screen.ABOUT.backDestination)
        assertEquals(Screen.SETTINGS, Screen.SPLIT_TUNNEL.backDestination)
        assertEquals(Screen.HOME, Screen.NODES.backDestination)
        assertEquals(Screen.HOME, Screen.SETTINGS.backDestination)
        assertNull(Screen.HOME.backDestination)
    }
}
