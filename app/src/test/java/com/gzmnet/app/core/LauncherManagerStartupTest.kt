package com.gzmnet.app.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LauncherManagerStartupTest {
    @Test
    fun `cached GZM VPN starts immediately only with selected local server`() {
        assertTrue(canStartCachedGzmVpn(hasSelectedServer = true))
        assertFalse(canStartCachedGzmVpn(hasSelectedServer = false))
    }
}
