package com.v2ray.ang.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmPanelOfflineDecisionTest {
    @Test
    fun `one transient panel request failure does not mark the app offline`() {
        assertFalse(isPanelOfflineAfterFailures(0))
        assertFalse(isPanelOfflineAfterFailures(1))
    }

    @Test
    fun `repeated panel request failures are shown as offline`() {
        assertTrue(isPanelOfflineAfterFailures(2))
        assertTrue(isPanelOfflineAfterFailures(5))
    }

    @Test
    fun `connected screen forces ping without waiting for normal interval`() {
        assertTrue(shouldSendGzmPing(force = true, hasPending = false, pendingAgeMs = 0L, sinceLastRequestMs = 1L))
    }

    @Test
    fun `recent pending ping is not duplicated unless forced`() {
        assertFalse(shouldSendGzmPing(force = false, hasPending = true, pendingAgeMs = 5_000L, sinceLastRequestMs = 20_000L))
        assertTrue(shouldSendGzmPing(force = true, hasPending = true, pendingAgeMs = 5_000L, sinceLastRequestMs = 20_000L))
    }

    @Test
    fun `lost ping can retry after timeout and idle ping uses ten second interval`() {
        assertTrue(shouldSendGzmPing(force = false, hasPending = true, pendingAgeMs = 25_000L, sinceLastRequestMs = 25_000L))
        assertFalse(shouldSendGzmPing(force = false, hasPending = false, pendingAgeMs = 0L, sinceLastRequestMs = 9_999L))
        assertTrue(shouldSendGzmPing(force = false, hasPending = false, pendingAgeMs = 0L, sinceLastRequestMs = 10_000L))
    }
}
