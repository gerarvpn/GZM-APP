package com.gzmnet.app.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreVpnStartDecisionTest {
    @Test
    fun `defer descriptor close while Xray is opening it on another thread`() {
        assertTrue(shouldDeferTunnelClose(coreStartInProgress = true, onCoreStartThread = false))
        assertFalse(shouldDeferTunnelClose(coreStartInProgress = true, onCoreStartThread = true))
        assertFalse(shouldDeferTunnelClose(coreStartInProgress = false, onCoreStartThread = false))
    }

    @Test
    fun `reuse only when the core and its VPN interface are both established`() {
        assertTrue(shouldReuseEstablishedVpn(coreRunning = true, interfaceReady = true))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = false, interfaceReady = true))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = true, interfaceReady = false))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = false, interfaceReady = false))
    }
}
