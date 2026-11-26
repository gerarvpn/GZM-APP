package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmConnectionProbeTest {
    @Test
    fun `closed daemon pipe and EOF are treated as stale connection tests, not server failures`() {
        assertTrue(isGzmCorePipeClosed("io: read/write on closed pipe"))
        assertTrue(isGzmCorePipeClosed("EOF"))
        assertFalse(isGzmCorePipeClosed("connection timed out"))
        assertFalse(shouldTryAlternateGzmPing("EOF", coreRunning = true))
        assertFalse(shouldTryAlternateGzmPing("timeout", coreRunning = false))
        assertTrue(shouldTryAlternateGzmPing("timeout", coreRunning = true))
    }

    @Test
    fun `failed ping retries back off instead of probing every ten seconds`() {
        assertEquals(60_000L, gzmPingRetryDelayMs(failureCount = 1, pipeClosed = true))
        assertEquals(30_000L, gzmPingRetryDelayMs(failureCount = 1, pipeClosed = false))
        assertEquals(120_000L, gzmPingRetryDelayMs(failureCount = 3, pipeClosed = false))
    }

    @Test
    fun `an in-flight ping is not replaced by forced screen resume refresh`() {
        assertFalse(shouldSendGzmPing(force = true, hasPending = true, pendingAgeMs = 1_000, sinceLastRequestMs = 1_000))
        assertTrue(shouldSendGzmPing(force = true, hasPending = false, pendingAgeMs = 0, sinceLastRequestMs = 1_000))
    }

    @Test
    fun `negative ping result identifies an unresponsive server`() {
        assertTrue(isGzmConnectionProbeFailure(-1L))
        assertFalse(isGzmConnectionProbeFailure(0L))
        assertFalse(isGzmConnectionProbeFailure(128L))
    }
}
