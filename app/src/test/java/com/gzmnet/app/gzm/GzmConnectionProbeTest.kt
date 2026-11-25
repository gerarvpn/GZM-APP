package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmConnectionProbeTest {
    @Test
    fun `negative ping result identifies an unresponsive server`() {
        assertTrue(isGzmConnectionProbeFailure(-1L))
        assertFalse(isGzmConnectionProbeFailure(0L))
        assertFalse(isGzmConnectionProbeFailure(128L))
    }
}
