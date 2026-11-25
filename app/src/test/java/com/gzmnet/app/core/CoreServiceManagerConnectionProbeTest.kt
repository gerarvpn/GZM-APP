package com.gzmnet.app.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreServiceManagerConnectionProbeTest {
    @Test
    fun `successful outbound probe requires a nonnegative measured delay`() {
        assertTrue(isGzmOutboundProbeSuccessful(0L))
        assertTrue(isGzmOutboundProbeSuccessful(128L))
        assertFalse(isGzmOutboundProbeSuccessful(-1L))
    }
}
