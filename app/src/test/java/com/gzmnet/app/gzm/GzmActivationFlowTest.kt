package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmActivationFlowTest {
    @Test
    fun `connect button stays available for token while first server list prepares`() {
        assertTrue(shouldEnableGzmConnectAction(hasToken = true, canConnect = false, running = false, starting = false))
        assertFalse(shouldEnableGzmConnectAction(hasToken = false, canConnect = false, running = false, starting = false))
        assertFalse(shouldEnableGzmConnectAction(hasToken = true, canConnect = false, running = false, starting = true))
        assertTrue(shouldEnableGzmConnectAction(hasToken = false, canConnect = false, running = true, starting = false))
    }

    @Test
    fun `deferred connection starts only after permission profile assets and visible activity are ready`() {
        assertTrue(shouldStartDeferredGzmConnect(true, true, true, true, true, false))
        assertFalse(shouldStartDeferredGzmConnect(false, true, true, true, true, false))
        assertFalse(shouldStartDeferredGzmConnect(true, false, true, true, true, false))
        assertFalse(shouldStartDeferredGzmConnect(true, true, false, true, true, false))
        assertFalse(shouldStartDeferredGzmConnect(true, true, true, false, true, false))
        assertFalse(shouldStartDeferredGzmConnect(true, true, true, true, false, false))
        assertFalse(shouldStartDeferredGzmConnect(true, true, true, true, true, true))
    }

    @Test
    fun `mobile auto-connect works even when wifi is also present`() {
        assertTrue(shouldAutoConnectOnGzmNetwork(wifiEnabled = false, mobileEnabled = true, wifiAvailable = true, mobileAvailable = true))
        assertTrue(shouldAutoConnectOnGzmNetwork(wifiEnabled = false, mobileEnabled = true, wifiAvailable = false, mobileAvailable = true))
        assertFalse(shouldAutoConnectOnGzmNetwork(wifiEnabled = false, mobileEnabled = true, wifiAvailable = true, mobileAvailable = false))
        assertFalse(shouldAutoConnectOnGzmNetwork(wifiEnabled = false, mobileEnabled = false, wifiAvailable = true, mobileAvailable = true))
    }

    @Test
    fun `cached profile can only be reused for the same token account`() {
        val saved = GzmToken("https://panel.example/", "account-a")
        assertTrue(sameGzmTokenAccount(saved, GzmToken("https://panel.example", "account-a")))
        assertFalse(sameGzmTokenAccount(saved, GzmToken("https://panel.example", "account-b")))
        assertFalse(sameGzmTokenAccount(saved, GzmToken("https://other.example", "account-a")))
        assertFalse(sameGzmTokenAccount(null, GzmToken("https://panel.example", "account-a")))
    }

    @Test
    fun `daemon state query can distinguish running from stopped`() {
        assertTrue(isGzmDaemonReportedRunning(android.app.Activity.RESULT_OK))
        assertTrue(isGzmDaemonReportedStopped(android.app.Activity.RESULT_FIRST_USER))
        assertFalse(isGzmDaemonReportedRunning(android.app.Activity.RESULT_FIRST_USER))
        assertFalse(isGzmDaemonReportedStopped(android.app.Activity.RESULT_CANCELED))
    }

}
