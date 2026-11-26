package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GzmPaymentReminderTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test fun dueDateIsDetectedInIsoAndLegacyDateFormats() {
        assertEquals(0L, gzmDaysUntilPayment("2026-11-26", today))
        assertEquals(1L, gzmDaysUntilPayment("27/11/2026", today))
    }

    @Test fun onlyNotifiesWithinThreeDayWindow() {
        assertEquals("Tu pago o renovación de GZMNET vence hoy.", gzmPaymentReminderText(0))
        assertEquals("Tu pago o renovación de GZMNET vence mañana.", gzmPaymentReminderText(1))
        assertEquals("Tu pago o renovación de GZMNET vence en 3 días.", gzmPaymentReminderText(3))
        assertNull(gzmPaymentReminderText(4))
        assertNull(gzmPaymentReminderText(-1))
    }

    @Test fun invalidDateDoesNotCreateReminder() {
        assertNull(gzmDaysUntilPayment("2026-02-30", today))
        assertNull(gzmDaysUntilPayment(null, today))
    }
}
