package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmStartupTimeoutTest {
    @Test
    fun `connecting remains valid until ninety second startup limit`() {
        assertFalse(hasGzmStartupTimedOut(1_000L, 90_999L))
        assertTrue(hasGzmStartupTimedOut(1_000L, 91_000L))
    }

    @Test
    fun `clock value before attempt start is not treated as timeout`() {
        assertFalse(hasGzmStartupTimedOut(5_000L, 4_000L))
    }
}
