package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreVpnStartDecisionTest {
    @Test
    fun `reuse only when the core and its VPN interface are both established`() {
        assertTrue(shouldReuseEstablishedVpn(coreRunning = true, interfaceReady = true))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = false, interfaceReady = true))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = true, interfaceReady = false))
        assertFalse(shouldReuseEstablishedVpn(coreRunning = false, interfaceReady = false))
    }
}
