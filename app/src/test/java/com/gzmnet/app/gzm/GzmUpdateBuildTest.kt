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

    @Test
    fun `rejects empty or short downloads and permits unknown content length only after bytes arrive`() {
        assertEquals(false, isGzmUpdateDownloadComplete(0, -1))
        assertEquals(true, isGzmUpdateDownloadComplete(2048, -1))
        assertEquals(false, isGzmUpdateDownloadComplete(2047, 2048))
        assertEquals(true, isGzmUpdateDownloadComplete(2048, 2048))
    }

    @Test
    fun `delete completed update cache only after Android confirms package replacement`() {
        assertEquals(true, shouldCleanGzmUpdateCacheAfter("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(false, shouldCleanGzmUpdateCacheAfter("android.intent.action.BOOT_COMPLETED"))
        assertEquals(false, shouldCleanGzmUpdateCacheAfter(null))
    }

    @Test
    fun `accepts only zip based apk header`() {
        assertEquals(true, isGzmApkZipHeader(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertEquals(false, isGzmApkZipHeader("<html".toByteArray()))
        assertEquals(false, isGzmApkZipHeader(byteArrayOf(0x50, 0x4b)))
    }
}
