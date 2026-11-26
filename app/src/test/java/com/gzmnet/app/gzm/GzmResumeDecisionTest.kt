package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GzmResumeDecisionTest {
    private fun allowed(
        requested: Boolean = true,
        visible: Boolean = true,
        starting: Boolean = false,
        token: Boolean = true,
        config: Boolean = true,
        assets: Boolean = true,
        permission: Boolean = true,
    ) = shouldResumeGzmVpn(requested, visible, starting, token, config, assets, permission)

    @Test
    fun `resume from cached profile only after a previously active session and live permission`() {
        assertTrue(allowed())
        assertFalse(allowed(requested = false))
        assertFalse(allowed(permission = false))
    }

    @Test
    fun `do not auto-resume while hidden or already starting`() {
        assertFalse(allowed(visible = false))
        assertFalse(allowed(starting = true))
    }

    @Test
    fun `do not auto-resume without the saved account profile or core assets`() {
        assertFalse(allowed(token = false))
        assertFalse(allowed(config = false))
        assertFalse(allowed(assets = false))
    }
}
