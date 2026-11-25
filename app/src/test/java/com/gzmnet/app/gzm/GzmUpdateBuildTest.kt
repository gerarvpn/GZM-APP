package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GzmUpdateBuildTest {
    @Test
    fun `parses numbered GZM release builds without using the visible app version`() {
        assertEquals(1007, parseGzmReleaseBuild("GZMNET build 1007"))
    }

    @Test
    fun `does not mistake unrelated digits for a release build number`() {
        assertNull(parseGzmReleaseBuild("GZMNET preview 7"))
    }
}
