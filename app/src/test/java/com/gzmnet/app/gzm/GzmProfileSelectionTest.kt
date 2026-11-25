package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GzmProfileSelectionTest {
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
