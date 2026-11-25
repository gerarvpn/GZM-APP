package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmDiagnosticsRedactionTest {
    @Test
    fun `report redacts GZM tokens server links and credential values`() {
        val safe = GzmDiagnostics.redact(
            "GZM.abc_DEF token=secret vless://user:pass@example.com:443 and https://name:pass@example.com/error"
        )
        assertTrue(safe.contains("GZM.[REDACTED]"))
        assertTrue(safe.contains("token=[REDACTED]"))
        assertTrue(safe.contains("[SERVER_CONFIG_REDACTED]"))
        assertTrue(safe.contains("[URL_REDACTED]"))
        assertFalse(safe.contains("abc_DEF"))
        assertFalse(safe.contains("user:pass"))
        assertFalse(safe.contains("name:pass"))
        assertFalse(safe.contains("example.com"))
    }
}
