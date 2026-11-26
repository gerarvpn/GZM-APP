package com.gzmnet.app.gzm

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
}
