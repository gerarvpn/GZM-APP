package com.gzmnet.app.gzm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GzmUpdateBuildTest {
    @Test
    fun `saved profile is enough to start without a live panel status request`() {
        assertEquals(true, canStartFromLocalConfig(hasToken = true, hasConfig = true, assetsReady = true))
        assertEquals(false, canStartFromLocalConfig(hasToken = true, hasConfig = false, assetsReady = true))
        assertEquals(false, canStartFromLocalConfig(hasToken = true, hasConfig = true, assetsReady = false))
        assertEquals(false, canStartFromLocalConfig(hasToken = false, hasConfig = true, assetsReady = true))
    }

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
    fun `reports useful categories for update connectivity failures`() {
        assertEquals("No se resolvió el servidor de actualización (DNS/red móvil).", gzmUpdateFailureSummary(java.net.UnknownHostException("host")))
        assertEquals("La red tardó demasiado al consultar GitHub.", gzmUpdateFailureSummary(java.net.SocketTimeoutException("timeout")))
        assertEquals(true, gzmUpdateFailureSummary(java.io.IOException("offline")).contains("IOException"))
    }

    @Test
    fun `only binds update requests to non VPN networks that have internet`() {
        assertEquals(false, canUseGzmPhysicalNetwork(isVpn = true, hasInternet = true))
        assertEquals(false, canUseGzmPhysicalNetwork(isVpn = false, hasInternet = false))
        assertEquals(true, canUseGzmPhysicalNetwork(isVpn = false, hasInternet = true))
    }

    @Test
    fun `prefers validated internet and then wifi ethernet over cellular`() {
        assertEquals(105, gzmNetworkPreferenceScore(isValidated = true, isWifiOrEthernet = false, isCellular = true))
        assertEquals(110, gzmNetworkPreferenceScore(isValidated = true, isWifiOrEthernet = true, isCellular = false))
        assertEquals(5, gzmNetworkPreferenceScore(isValidated = false, isWifiOrEthernet = false, isCellular = true))
    }

    @Test
    fun `accepts only zip based apk header`() {
        assertEquals(true, isGzmApkZipHeader(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertEquals(false, isGzmApkZipHeader("<html".toByteArray()))
        assertEquals(false, isGzmApkZipHeader(byteArrayOf(0x50, 0x4b)))
    }

    @Test
    fun `restored installer is offered only when it is newer than installed build`() {
        assertEquals(true, isGzmUpdateBuildNewer(downloadedBuild = 1035, installedBuild = 1034))
        assertEquals(false, isGzmUpdateBuildNewer(downloadedBuild = 1034, installedBuild = 1034))
        assertEquals(false, isGzmUpdateBuildNewer(downloadedBuild = 1033, installedBuild = 1034))
    }

}
