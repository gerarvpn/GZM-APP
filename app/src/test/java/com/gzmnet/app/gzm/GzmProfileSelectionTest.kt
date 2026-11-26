package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GzmProfileSelectionTest {
    @Test
    fun `account notice includes panel message and warns within three days of renewal`() {
        val today = java.time.LocalDate.of(2026, 11, 25)
        assertEquals("Vence pronto\nTu pago o renovación vence en 2 días.", gzmAccountNotice("Vence pronto", "2026-11-27", today))
        assertEquals("Tu pago o renovación vence hoy.", gzmAccountNotice(null, "25/11/2026", today))
        assertEquals("Tu mensaje\nTu pago o renovación vence hoy.", gzmAccountNotice("Tu mensaje", "25/11/2026", today))
        assertEquals(null, gzmAccountNotice("Activo", "2026-12-01", today))
        assertEquals(null, gzmAccountNotice(null, "2026-12-01", today))
        assertEquals("Aviso personalizado", gzmAccountNotice("Aviso personalizado", "2026-12-01", today))
    }

    @Test
    fun `server display separates name from server account status`() {
        assertEquals("GZMNET VPN", gzmServerDisplayName("GZMNET VPN"))
        assertEquals("GZMNET VPN", gzmServerDisplayName(" "))
        assertEquals("Activo", gzmServerStatusLabel("active"))
        assertEquals("Inactivo", gzmServerStatusLabel("disabled"))
        assertEquals("Sin verificar", gzmServerStatusLabel(null))
    }

    @Test
    fun `saved config with a missing sync timestamp does not trigger a download on boot`() {
        assertEquals(false, isGzmConfigRefreshDue(lastSyncAt = 0L, now = 1_000_000L, intervalHours = 12, hasSavedConfig = true))
        assertEquals(true, isGzmConfigRefreshDue(lastSyncAt = 0L, now = 1_000_000L, intervalHours = 12, hasSavedConfig = false))
    }

    @Test
    fun `refresh saved config only when its configured interval has elapsed`() {
        val last = 1_000_000L
        val interval = 12 * 60 * 60 * 1000L
        assertEquals(false, isGzmConfigRefreshDue(last, last + interval - 1, 12, true))
        assertEquals(true, isGzmConfigRefreshDue(last, last + interval, 12, true))
    }

    @Test
    fun `rebuild a missing selected profile from the encrypted local snapshot`() {
        assertEquals(true, shouldRestoreGzmProfile(hasUsableSelectedProfile = false, hasEncryptedConfigSnapshot = true))
        assertEquals(false, shouldRestoreGzmProfile(hasUsableSelectedProfile = true, hasEncryptedConfigSnapshot = true))
        assertEquals(false, shouldRestoreGzmProfile(hasUsableSelectedProfile = false, hasEncryptedConfigSnapshot = false))
    }

    @Test
    fun `keeps selected profile only when it belongs to imported group and is usable`() {
        val selected = selectUsableGzmProfile("second", listOf("first", "second")) { it != "first" }

        assertEquals("second", selected)
    }

    @Test
    fun `falls back to first usable imported profile when current profile is stale or corrupt`() {
        val selected = selectUsableGzmProfile("outside-group", listOf("broken", "good")) { it == "good" }

        assertEquals("good", selected)
    }

    @Test
    fun `does not select any profile when none can be decoded`() {
        val selected = selectUsableGzmProfile(null, listOf("broken-one", "broken-two")) { false }

        assertNull(selected)
    }
}
