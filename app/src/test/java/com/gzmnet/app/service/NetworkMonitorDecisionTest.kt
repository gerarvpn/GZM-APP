package com.gzmnet.app.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkMonitorDecisionTest {
    @Test
    fun `never choose VPN as physical upstream and require internet`() {
        assertFalse(shouldAcceptUnderlyingNetwork(isVpn = true, hasInternet = true))
        assertFalse(shouldAcceptUnderlyingNetwork(isVpn = false, hasInternet = false))
        assertTrue(shouldAcceptUnderlyingNetwork(isVpn = false, hasInternet = true))
    }

    @Test
    fun `loss of stale network cannot clear the current upstream`() {
        assertFalse(shouldClearLostUpstream(lostIsCurrent = false))
        assertTrue(shouldClearLostUpstream(lostIsCurrent = true))
    }

    @Test
    fun `handover only reloads current running core`() {
        assertTrue(shouldRunNetworkHandover(isCurrentMonitor = true, coreRunning = true))
        assertFalse(shouldRunNetworkHandover(isCurrentMonitor = false, coreRunning = true))
        assertFalse(shouldRunNetworkHandover(isCurrentMonitor = true, coreRunning = false))
    }
}
