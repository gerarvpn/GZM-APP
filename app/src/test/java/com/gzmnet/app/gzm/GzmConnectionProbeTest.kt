package com.gzmnet.app.gzm

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
}
